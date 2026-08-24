package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.session.OpenCharacterFactory
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface
// Aliased: this view model's own `togglePane` is the *persisting* gesture, the imported one is
// the pure rule it applies. Same name in two layers is right; shadowing it silently is not.
import com.hashtagchow.magehand.ui.panes.togglePane as nextPaneSet
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryLayoutPlan
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.toInventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.CustomizeSection
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerCustomizeState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerOverridePlan
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toConnectionState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toCustomizeState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toTrackerUiState
import com.hashtagchow.magehand.ui.webview.SheetSession
import com.hashtagchow.magehand.ui.webview.SheetSessionFactory
import java.time.ZoneId
import javax.inject.Inject

/**
 * @param session `null` until the encrypted token store has been read; the Sheet
 *   tab shows a spinner rather than an un-authenticated page in the meantime.
 * @param tracker the Tracker tab's whole rendered state (04 §3).
 * @param customize the customize bottom sheet's state (04 §5 + §6). Built from the
 *   *unhidden* board, so hidden rows can be brought back.
 * @param inventory FR-8's tab (docs/design/10-inventory.md). Built from the character's own
 *   `inventory` flow, **not** from the tracker board: the two answer different questions and
 *   the tracker's hide/pin override layer has no business reaching the inventory — see
 *   [CharacterHomeViewModel.adjustItemQuantity].
 */
data class CharacterHomeUiState(
    val creatureId: String = "",
    val characterName: String? = null,
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val session: SheetSession? = null,
    val tracker: TrackerUiState = TrackerUiState(),
    val customize: TrackerCustomizeState = TrackerCustomizeState(),
    val inventory: InventoryUiState = InventoryUiState(),
) {
    /** The accent seeds the whole character-home subtree, both tabs (04 §Theming). */
    val accentColor: String? get() = tracker.accentColor
}

/**
 * One-shot tracker feedback (04 §3): the 5 s UNDO snackbar after every mutation, and the
 * error snackbar + shake after a rollback.
 *
 * An event stream rather than a field on [CharacterHomeUiState], because both are *things
 * that happened* — two identical spends in a row must produce two snackbars, and a state
 * field equal to its predecessor would produce one.
 *
 * One type, two **streams**: [CharacterHomeViewModel.writeEvents] and
 * [CharacterHomeViewModel.failureEvents] carry the two arms separately, because they need
 * opposite back-pressure. See those declarations for why one stream could not.
 */
sealed interface TrackerEvent {
    /** A write the server accepted. Carries the entry so the snackbar can offer UNDO. */
    data class Wrote(val write: TrackerWrite) : TrackerEvent

    /** A write that was rolled back. [TrackerWriteFailure.propertyId] is the row to shake. */
    data class Failed(val failure: TrackerWriteFailure) : TrackerEvent
}

/**
 * Screens 3 + 4 (docs/design/04-screens-ux.md).
 *
 * ### Session lifecycle
 *
 * One [OpenCharacter] per opened character: created in `init`, closed in [onCleared].
 * That is the whole of "create on enter, close on exit" — because this ViewModel is
 * scoped to the `CharacterHome` nav entry, popping back to the character list clears it,
 * which stops the `singleCharacter` subscription. Nothing else has to remember to.
 *
 * ### Writes (WP7)
 *
 * The tracker mutates the server now, but only through [OpenCharacter]'s named intents —
 * `spend`, `restore`, `changeHitPoints`, `adjustItem`, `toggle`, `rest`. This class holds
 * no `WriteQueue`, names no DDP method and builds no method parameters; it maps a tapped
 * `propertyId` back to its board row and hands that over. Everything the queue guarantees
 * (LIVE-only, rate limiting, coalescing, optimistic rollback, the undo stack) is therefore
 * unbypassable from here. `WritePostureTest` pins that.
 *
 * The customize methods further down still write `tracker_prefs` / `theme_prefs` only —
 * local Room rows, never the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CharacterHomeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    characterListRepository: CharacterListRepository,
    sheetSessionFactory: SheetSessionFactory,
    appSettingsStore: AppSettingsStore,
    private val selectedRollStore: SelectedRollStore,
    private val equippableOverrideStore: EquippableOverrideStore,
    private val inventoryLayoutStore: InventoryLayoutStore,
    private val paneLayoutStore: PaneLayoutStore,
    private val openCharacterFactory: OpenCharacterFactory,
    private val connectionManager: DdpConnectionManager,
) : ViewModel() {

    /** Type-safe nav routes store each component under its property name. */
    private val creatureId: String = requireNotNull(savedStateHandle["creatureId"]) {
        "CharacterHome route is missing creatureId"
    }

    private val open = MutableStateFlow<OpenCharacter?>(null)

    @Volatile
    private var cleared = false

    private val _writeEvents = MutableSharedFlow<TrackerEvent.Wrote>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _failureEvents = MutableSharedFlow<TrackerEvent.Failed>(extraBufferCapacity = 32)

    /**
     * Confirmations — 04 §3's UNDO snackbar. **Conflated**: one pending at most, newest wins.
     *
     * ### Why two streams and not one
     *
     * These two used to share one 16-deep `MutableSharedFlow` with a suspending overflow
     * policy, collected by one `when` in the screen — and `SnackbarHostState.showSnackbar`
     * suspends for the life of each snackbar. So a burst of taps filled the buffer with
     * confirmations, and the *emitter* of a [failureEvents] then parked waiting for room,
     * behind up to sixteen four-second snackbars. Over a minute in which the one event the
     * player needs — "that write did not stick" — could not reach the screen at all, on
     * precisely the press-and-hold that produces rate-limit refusals in the first place.
     *
     * Two streams because the two need *opposite* back-pressure, which one flow cannot have.
     * And deliberately **not** one flow merged from two: `merge` puts a 64-deep channel in
     * front of the collector, which drains this lane instantly and defeats the conflation
     * entirely — the confirmations then queue in the merge instead of the SharedFlow and the
     * screen still shows twenty snackbars over eighty seconds. Separate streams, separately
     * collected, is what makes both policies real.
     *
     * ### Dropping receipts is honest
     *
     * A confirmation is a receipt with an UNDO on it, and an UNDO offered twelve seconds late
     * is for a write the player stopped thinking about. Nothing else is lost: the writes
     * themselves are untouched, the queue's undo stack is untouched, and every dropped receipt
     * is still a row in the history sheet with its own UNDO ([OpenCharacter.writeHistory] is a
     * `StateFlow` and keeps 100 of them).
     */
    val writeEvents: Flow<TrackerEvent.Wrote> = _writeEvents.asSharedFlow()

    /**
     * Rollbacks and refusals — 04 §3's error snackbar and the row shake. **Never dropped, and
     * never queued behind a confirmation.**
     *
     * Its own buffer and its own collector, which is the whole point: a failure means a tap the
     * player believes landed did not, and the number on screen has already snapped back without
     * explanation — the snackbar is the only thing that says why.
     */
    val failureEvents: Flow<TrackerEvent.Failed> = _failureEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            val opened = openCharacterFactory.open(creatureId)
            // The screen can be popped while `open` is still suspended on the socket;
            // an OpenCharacter nobody holds would keep its subscription forever.
            if (cleared) opened?.close() else open.value = opened
        }

        // A *new* history id is exactly "a fresh mutation reached the server": an undo
        // submits with the undo bookkeeping suppressed, so it marks an existing entry
        // undone rather than adding one. That is what stops UNDO offering to undo itself.
        viewModelScope.launch {
            var lastSeen = 0L
            open.filterNotNull()
                .flatMapLatest { it.writeHistory }
                .collect { history ->
                    val newest = history.firstOrNull() ?: return@collect
                    if (newest.id > lastSeen) {
                        lastSeen = newest.id
                        // `tryEmit`, not `emit`: the lane is DROP_OLDEST, so this always
                        // succeeds and this collector can never be parked by a slow screen —
                        // which is what let a backlog build behind it.
                        _writeEvents.tryEmit(TrackerEvent.Wrote(newest))
                    }
                }
        }

        viewModelScope.launch {
            open.filterNotNull()
                .flatMapLatest { it.writeFailures }
                .collect { _failureEvents.emit(TrackerEvent.Failed(it)) }
        }
    }

    /**
     * FR-7's remembered dropdown selection, for the *opened* character.
     *
     * Read through the store directly rather than through [OpenCharacter], for the same reason
     * FR-6's switch is: it is a **preference**, not sheet state, and `AppSettingsStore` set the
     * pattern one feature ago — a `:core:data` interface over plain types, injected into the
     * view model that renders it. Widening `OpenCharacter` would have put a DataStore read
     * behind the seam whose whole job is to be the app's *write* surface onto a character (see
     * `WritePostureTest`), which is a bigger claim than this feature earns.
     *
     * The key needs the account id, and the only place that knows it is the opened character —
     * hence [rollKey] rather than a field. Before the open completes there is no key and
     * nothing to read, which is exactly the state where the board is empty anyway.
     */
    private fun selectedRollId(character: OpenCharacter): Flow<String?> =
        selectedRollStore.selectedRollId(rollKey(character))

    /** Account-scoped, matching every other per-character store. */
    private fun rollKey(character: OpenCharacter): String =
        SelectedRollStore.serverKey(character.accountId, character.creatureId)

    /**
     * FR-10's per-item equippability overrides (11 decision 2), for the *opened* character.
     *
     * Read through the store rather than through [OpenCharacter] for [selectedRollId]'s reason,
     * whole: it is a preference about how this app renders a sheet, not sheet state, and
     * widening the app's one *write* surface onto a character to carry a display setting is a
     * bigger claim than this feature earns.
     */
    private fun equippableOverrideKey(character: OpenCharacter): String =
        EquippableOverrideStore.serverKey(character.accountId, character.creatureId)

    /**
     * FR-14's per-character inventory arrangement (12 decision 5), for the *opened* character.
     *
     * Read through the store rather than through [OpenCharacter] for [selectedRollId]'s reason,
     * whole: it is a preference about how this app draws a sheet, not sheet state.
     */
    private fun inventoryLayoutKey(character: OpenCharacter): String =
        InventoryLayoutStore.serverKey(character.accountId, character.creatureId)

    /**
     * FR-17's key for the *opened* character (14 decision 8).
     *
     * A function of the open character rather than of [creatureId] alone, for
     * [inventoryLayoutKey]'s reason: the same creature reached from two accounts is two rows
     * everywhere else in this app, and account-scoping the key is what makes sign-out's reap a
     * prefix match. It therefore cannot be computed until the character is open, which is why
     * [panes] is a `flatMapLatest` rather than a plain store read.
     */
    private fun paneLayoutKey(character: OpenCharacter): String =
        PaneLayoutStore.serverKey(character.accountId, character.creatureId)

    /**
     * FR-17's chosen panes for this character (14 decision 8).
     *
     * A separate `StateFlow` from [uiState] rather than a field on it, deliberately: this is a
     * preference about *chrome*, read only by the composable that decides which chrome to draw,
     * and folding it into the state that every tracker row and every inventory section recomposes
     * against would make a pane toggle invalidate the whole screen. It is also the state decision
     * 10 needs to survive a gate crossing untouched, and keeping it out of the character's ui
     * state keeps it out of every rebuild of that state — including the ones a DDP sync causes.
     *
     * The empty set is *"no preference"*, not *"no panes"*; `resolvePanes` turns it into decision
     * 8's Tracker-only default, which is also what renders for the frame before the character
     * opens. See `PaneLayoutStore`.
     */
    val panes: StateFlow<Set<PaneSurface>> = open.flatMapLatest { character ->
        if (character == null) flowOf(emptySet()) else paneLayoutStore.panes(paneLayoutKey(character))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

    /**
     * Decision 6's picker gesture, persisted.
     *
     * [current] is what is on screen — the resolved list, not the stored set — because
     * `togglePane`'s minimum-of-one has to count visible panes; see its KDoc. A gesture the rule
     * refuses returns the input unchanged and is not written, matching [mutateInventoryLayout]'s
     * no-op contract.
     */
    fun togglePane(current: Set<PaneSurface>, surface: PaneSurface) {
        val key = paneLayoutKey(open.value ?: return)
        val next = nextPaneSet(current, surface)
        if (next == current) return
        viewModelScope.launch { paneLayoutStore.setPanes(key, next) }
    }

    /**
     * FR-6 applies to **every** character, not only local ones (09 decision 9: "for ALL
     * characters"). It is read here rather than in the composable so the gate is on the state
     * the screen renders, which is what `TrackerUiStateTest` can pin.
     */
    private val showToggles: Flow<Boolean> = appSettingsStore.showToggles

    private val trackerState: Flow<TrackerUiState> = open.flatMapLatest { character ->
        if (character == null) {
            flowOf(TrackerUiState(creatureId = creatureId))
        } else {
            combine(
                combine(
                    character.board,
                    character.connectionState,
                    character.lastSyncedAt,
                    character.isShowingSnapshot,
                    character.accentColor,
                ) { board, connection, syncedAt, showingSnapshot, accent ->
                    Read(board, connection, syncedAt, showingSnapshot, accent)
                },
                character.canWrite,
                character.canUndo,
                character.writeHistory,
                combine(showToggles, selectedRollId(character)) { toggles, rollId ->
                    Prefs(showToggles = toggles, selectedRollId = rollId)
                },
            ) { read, canWrite, canUndo, history, prefs ->
                toTrackerUiState(
                    creatureId = creatureId,
                    board = read.board,
                    connection = read.connection,
                    lastSyncedAt = read.syncedAt,
                    isShowingSnapshot = read.showingSnapshot,
                    accentColor = read.accent,
                    canWrite = canWrite,
                    canUndo = canUndo,
                    history = history,
                    zone = zone,
                    showToggles = prefs.showToggles,
                    selectedRollId = prefs.selectedRollId,
                )
            }
        }
    }

    /**
     * FR-8's tab state (docs/design/10-inventory.md).
     *
     * Its own `combine` rather than a field folded into [trackerState], because the two are
     * built from different sources — `character.inventory` and `character.board` — and folding
     * them would make every wallet stepper re-derive the whole tracker and every pip tap
     * re-derive the whole inventory. Five flows, exactly at the typed arity.
     */
    private val inventoryState: Flow<InventoryUiState> = open.flatMapLatest { character ->
        if (character == null) {
            flowOf(InventoryUiState(creatureId = creatureId))
        } else {
            combine(
                character.inventory,
                character.connectionState,
                character.isShowingSnapshot,
                character.canWrite,
                inventoryPrefs(character),
            ) { board, connection, showingSnapshot, canWrite, prefs ->
                toInventoryUiState(
                    creatureId = creatureId,
                    board = board,
                    connection = connection,
                    isShowingSnapshot = showingSnapshot,
                    canWrite = canWrite,
                    equippableOverrides = prefs.equippableOverrides,
                    layout = prefs.layout,
                )
            }
        }
    }

    /**
     * The inventory tab's two DataStore-backed preferences, paired.
     *
     * The same arity fix as [Prefs] and for the same reason: `combine` tops out at five typed
     * flows before it degenerates into an `Array<Any?>` with unchecked casts, and FR-14's layout
     * made this tab's need six. The grouping means something rather than being an arbitrary split
     * to fit — both are stored choices about how to *draw* this character's inventory, neither is
     * sheet data, and they are reaped by the same two paths.
     */
    private fun inventoryPrefs(character: OpenCharacter): Flow<InventoryPrefs> = combine(
        equippableOverrideStore.overrides(equippableOverrideKey(character)),
        inventoryLayoutStore.layout(inventoryLayoutKey(character)),
    ) { overrides, layout -> InventoryPrefs(overrides, layout) }

    private val customizeState: Flow<TrackerCustomizeState> = open.flatMapLatest { character ->
        if (character == null) {
            flowOf(TrackerCustomizeState())
        } else {
            combine(
                character.boardIgnoringHidden,
                character.overrides,
                character.accentColor,
                showToggles,
            ) { board, overrides, accent, toggles ->
                toCustomizeState(board, overrides, accent, toggles)
            }
        }
    }

    val uiState: StateFlow<CharacterHomeUiState> = combine(
        characterListRepository.state,
        sheetSessionFactory.sessions { SheetSessionFactory.characterPath(creatureId) },
        trackerState,
        customizeState,
        inventoryState,
    ) { listState, session, tracker, customize, inventory ->
        CharacterHomeUiState(
            creatureId = creatureId,
            // The name comes from the list the user just came from. The
            // `singleCharacter` sub could supply it, but the list is already correct
            // and is on screen before the sub goes ready.
            characterName = listState.characters.firstOrNull { it.creatureId == creatureId }?.name,
            connection = tracker.status.tone.toConnectionState(),
            session = session,
            tracker = tracker,
            customize = customize,
            inventory = inventory,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        CharacterHomeUiState(creatureId = creatureId),
    )

    // --- tracker writes (04 §3) ---------------------------------------------------

    /** A filled pip was tapped. */
    fun spend(propertyId: String, amount: Int = 1) = withRow(propertyId) { character, row ->
        character.spend(row, amount)
    }

    /** An empty pip was tapped. */
    fun restore(propertyId: String, amount: Int = 1) = withRow(propertyId) { character, row ->
        character.restore(row, amount)
    }

    /** The HP steppers (and the number pad's Damage / Heal). Negative damages. */
    fun changeHitPoints(delta: Int) {
        open.value?.changeHitPoints(delta)
    }

    /** The number pad's third option: set HP to an absolute value. */
    fun setHitPoints(value: Int) {
        open.value?.setHitPoints(value)
    }

    /** A consumable's − / + stepper. */
    fun adjustItem(propertyId: String, delta: Int) = withRow(propertyId) { character, row ->
        character.adjustItem(row, delta)
    }

    // --- inventory tab (docs/design/10-inventory.md) --------------------------------

    /**
     * The row's one-tap equip control (10 decision 4).
     *
     * Resolved against the **inventory** board rather than the tracker's, so `currentlyEquipped`
     * is read from the same list the player is looking at. That argument is what lets
     * `:core:data` build a correct inverse for undo; passing the composable's idea of the
     * current state straight through would let one stale frame file an undo entry that puts
     * the item back the way it already was.
     *
     * The server reparents the item and the undo does not restore the folder — stated in full
     * on `WriteOp.Equip`, and invisible here because 10 decision 2 renders sections by state
     * and never renders the tree.
     */
    fun setEquipped(propertyId: String, equipped: Boolean) {
        val character = open.value ?: return
        val item = character.inventory.value.allItems
            .firstOrNull { it.propertyId == propertyId } ?: return
        character.setEquipped(
            propertyId = propertyId,
            equipped = equipped,
            currentlyEquipped = item.equipped,
            targetName = item.name,
        )
    }

    /**
     * The detail sheet's quantity stepper.
     *
     * ### Why this does not reuse `withRow`
     *
     * Because `withRow` searches `board.allItems`, and that list is **override-filtered**:
     * `TrackerEngine.order` drops anything the player hid from the tracker. An item hidden
     * there is still an item they own, so it is still on the inventory tab — and resolving
     * through the tracker would have made its stepper a control that silently did nothing,
     * on exactly the rows a player is most likely to have forgotten about. The inventory
     * board carries no hide layer, so it is the honest place to look.
     *
     * The [TrackedResource] is built here rather than carried on the state because
     * `adjustItem` needs one and `:core:model` is shared: an inventory item and a tracked
     * item are the same property seen twice, and `value == total` is what a
     * `TrackerKind.ITEM` row means (see [TrackedResource.total]).
     */
    fun adjustItemQuantity(propertyId: String, delta: Int) {
        val character = open.value ?: return
        val item = character.inventory.value.allItems
            .firstOrNull { it.propertyId == propertyId } ?: return
        character.adjustItem(
            TrackedResource(
                propertyId = item.propertyId,
                kind = TrackerKind.ITEM,
                name = item.name,
                value = item.quantity,
                total = item.quantity,
            ),
            delta,
        )
    }

    /**
     * The detail sheet's "Can be equipped" switch (11 decision 2).
     *
     * **Local only** — it writes the same DataStore file the accent, the FR-6 switch and the
     * FR-7 selection use, and never the sheet. That is the whole reason this feature needed no
     * `WriteOp`, no undo entry and no rollback path: nothing about the server changes, so there
     * is nothing for a snackbar to offer to take back.
     *
     * Unvalidated against the board, like [selectRoll] and for the same reason: the id came
     * *from* the rendered row, and an id that has since stopped naming an item is already
     * handled where it is read (an override nothing matches is simply never applied — see
     * `EquippableOverrideStore.overrides`). A guard here would only turn a recoverable stale
     * override into a dropped tap.
     */
    fun setEquippableOverride(propertyId: String, canEquip: Boolean) {
        val key = equippableOverrideKey(open.value ?: return)
        viewModelScope.launch { equippableOverrideStore.setOverridden(key, propertyId, canEquip) }
    }

    /**
     * A wallet stepper (10 decision 5).
     *
     * Re-resolved from the live wallet so the intent receives the row's real
     * `propertyId` — including the `null` that means "this sheet has no such coin yet", which
     * is what turns the first `+` into an insert rather than an adjust. Passing a row captured
     * at composition would send an id that a sync has since filled in.
     */
    fun adjustCoins(coin: CoinKind, delta: Int) {
        val character = open.value ?: return
        character.adjustCoins(character.inventory.value.wallet.row(coin), delta)
    }

    /**
     * The add-item flow's one call (10 decision 6), for both the catalog and the custom form.
     *
     * Not undoable — see `OpenCharacter.addItem`. The screen says so before the tap rather
     * than leaving the player to notice the missing UNDO on the snackbar.
     */
    fun addItem(spec: NewItemSpec) {
        open.value?.addItem(spec)
    }

    /**
     * Delete an item, after the detail sheet's destructive confirm (FR-9, 12 decision 7).
     *
     * **Undoable on a DiceCloud character**, because the server's only deletion is a
     * soft-remove and `restore` is a real inverse — so the snackbar's UNDO works and the
     * history entry offers it. `OpenCharacter.removeItem` carries the whole argument.
     *
     * Unvalidated against the board here, unlike [setEquipped]: `setEquipped` has to look the
     * item up because it needs the item's *current state* to build a correct inverse, and a
     * delete needs nothing but the id. The lookup that would have gone here happens one layer
     * down instead — `DefaultOpenCharacter.removeItem` resolves the id against the inventory
     * board for the row's name and, in the same read, refuses a coin. Doing it twice would only
     * mean two chances for the two copies to disagree.
     */
    fun removeItem(propertyId: String) {
        open.value?.removeItem(propertyId)
    }

    /**
     * Move an item into a container or back to the carried root (12 decision 8).
     *
     * @param containerId `null` for the carried root. The screen speaks in containers and the
     *   carried root; `:core:data` turns that into a `parentRef` against the live sheet — see
     *   `InventoryMoveTarget` for why the picker is not handed one.
     */
    fun moveItem(propertyId: String, containerId: String?) {
        val target = containerId
            ?.let { InventoryMoveTarget.Container(it) }
            ?: InventoryMoveTarget.Carried
        open.value?.moveItem(propertyId, target)
    }

    /** A condition chip (or the concentration banner's ✕, which is the same write). */
    fun toggleCondition(propertyId: String) {
        val character = open.value ?: return
        val toggle = character.board.value.activeToggles.firstOrNull { it.propertyId == propertyId }
            ?: return
        character.toggle(toggle)
    }

    /**
     * Short or long rest. The confirm dialog (04 §3) is the screen's job and has already
     * been shown by the time this runs — a rest is not undoable, so the dialog *is* the
     * safety mechanism.
     */
    fun rest(kind: RestKind) {
        open.value?.rest(kind)
    }

    fun undoLastWrite() {
        val character = open.value ?: return
        viewModelScope.launch { character.undoLastWrite() }
    }

    /**
     * The connection sheet's "Try reconnecting".
     *
     * Deliberately the *existing* `DdpConnectionManager.restart()` — the same call the
     * token-refresh path already makes — and nothing else. There is no second reconnect
     * path in this app and this feature does not add one: the backoff loop inside
     * `DdpClient` owns retrying, and this only asks it to stop waiting out the current
     * delay. That is also why the sheet says so in as many words: a button that looked
     * like the only thing keeping the socket alive would be a lie.
     *
     * Not offered while `SIGNED_OUT` — see [com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionStatus.canRetry].
     */
    fun reconnect() {
        connectionManager.restart()
    }

    /**
     * Looks the tapped row up on the current board.
     *
     * The UI hands back a `propertyId` and nothing else, so a stale row (one the server has
     * since removed, or a burst that outran a re-sync) resolves to nothing and is dropped
     * rather than written blind. The board is already overlay-adjusted, so the clamps in
     * `OpenCharacter` see the value the user is looking at, not the last server value.
     */
    private inline fun withRow(propertyId: String, act: (OpenCharacter, TrackedResource) -> Unit) {
        val character = open.value ?: return
        val board = character.board.value
        val row = (board.slots + board.resources + board.allItems + listOfNotNull(board.hp))
            .firstOrNull { it.propertyId == propertyId } ?: return
        act(character, row)
    }

    // --- customize sheet actions (04 §5, §6) — all local, all functional in WP6 ----

    fun setRowHidden(propertyId: String, hidden: Boolean) = mutateOverride { current ->
        listOf(TrackerOverridePlan.setHidden(current, propertyId, hidden))
    }

    fun setRowPinned(propertyId: String, pinned: Boolean) = mutateOverride { current ->
        listOf(TrackerOverridePlan.setPinned(current, propertyId, pinned))
    }

    /** Moves a row one place within its section. `delta` is −1 (up) or +1 (down). */
    fun moveRow(section: CustomizeSection, propertyId: String, delta: Int) = mutateOverride { current ->
        val order = uiState.value.customize.sections
            .firstOrNull { it.section == section }
            ?.rows
            ?.map { it.propertyId }
            .orEmpty()
        TrackerOverridePlan.reorder(current, order, propertyId, delta)
    }

    /** Drops every local override for this character — the sheet's "Reset" affordance. */
    fun resetCustomizations() {
        val character = open.value ?: return
        viewModelScope.launch {
            uiState.value.customize.let { state ->
                (state.sections.flatMap { it.rows } + state.hidden).forEach {
                    character.clearOverride(it.propertyId)
                }
                state.items.filter { it.pinned }.forEach { character.clearOverride(it.propertyId) }
            }
        }
    }

    // --- inventory customize sheet (12 decision 3) — all local, all DataStore -------

    /**
     * Moves one inventory section one place. `delta` is −1 (up) or +1 (down).
     *
     * Every gesture writes the **whole** arrangement, because the value is an order — see
     * `InventoryLayoutStore.setLayout`. The plan is handed both the arrangement on screen and the
     * one on disk: the second is what stops a gesture made while a container is missing from the
     * board (a cold open, a snapshot) from quietly forgetting where that container sat.
     */
    fun moveInventorySection(key: String, delta: Int) = mutateInventoryLayout { resolved, stored ->
        InventoryLayoutPlan.move(resolved, stored, key, delta)
    }

    /** Folds an inventory section into Gear, or brings it back (12 decision 3). */
    fun setInventorySectionHidden(key: String, hidden: Boolean) =
        mutateInventoryLayout { resolved, stored ->
            InventoryLayoutPlan.setHidden(resolved, stored, key, hidden)
        }

    /**
     * Shuts an inventory section, or opens it (FR-16, 13 decision 3).
     *
     * The same write path as the two customize-sheet gestures above, and that is decision 3's
     * mechanism rather than an economy: collapse is *"a preference over whatever exists"* exactly
     * as the order and the folds are, so it rides in the same key and is cleared by the same
     * Reset. The gesture happens on the tab (decision 6 keeps the sheet out of it); only the
     * storage is shared.
     *
     * The **Wallet cannot reach this** — [InventoryTab] wires its chevron to a `rememberSaveable`
     * — and `InventoryLayoutPlan.setCollapsed` refuses the key regardless, which is what keeps
     * decision 3's exception true rather than merely intended.
     */
    fun setInventorySectionCollapsed(key: String, collapsed: Boolean) =
        mutateInventoryLayout { resolved, stored ->
            InventoryLayoutPlan.setCollapsed(resolved, stored, key, collapsed)
        }

    /**
     * The sheet's Reset: forget this character's arrangement, so 12 decision 1's default draws.
     *
     * A key deletion rather than a write of the default order, which is decision 5's own wording
     * and is the meaningfully different one: a stored copy of the default would freeze *today's*
     * default into that character, so a later release that changed decision 1 would change the
     * order for every new character and for nobody who had ever pressed Reset.
     */
    fun resetInventoryLayout() {
        val key = inventoryLayoutKey(open.value ?: return)
        viewModelScope.launch { inventoryLayoutStore.clearForCharacter(key) }
    }

    /**
     * Applies a plan and persists it, unless the plan says the gesture was a no-op.
     *
     * An empty result means "nothing to do" — a bounce off the top of the list, or a hide the
     * guardrail refuses — and is deliberately not written: see `InventoryLayoutPlan.move`.
     */
    private inline fun mutateInventoryLayout(
        crossinline plan: (
            resolved: List<InventoryLayoutEntry>,
            stored: List<InventoryLayoutEntry>,
        ) -> List<InventoryLayoutEntry>,
    ) {
        val key = inventoryLayoutKey(open.value ?: return)
        viewModelScope.launch {
            val resolved = uiState.value.inventory.customize.resolved
            val stored = inventoryLayoutStore.layout(key).first()
            val next = plan(resolved, stored)
            if (next.isNotEmpty()) inventoryLayoutStore.setLayout(key, next)
        }
    }

    /**
     * FR-7: the Rolls dropdown was used.
     *
     * Local only — it writes the same DataStore the accent and the FR-6 switch use, never the
     * sheet. Unvalidated against the board on purpose: the id came *from* the rendered option
     * list, and an id that has since stopped naming a roll is already handled where it is read
     * (`RollPickerState.selected` resolves to null and the section shows its placeholder), so
     * a guard here would only turn a recoverable stale selection into a dropped tap.
     */
    fun selectRoll(rollId: String) {
        val key = rollKey(open.value ?: return)
        viewModelScope.launch { selectedRollStore.setSelectedRollId(key, rollId) }
    }

    fun setAccentColor(hex: String?) {
        val character = open.value ?: return
        viewModelScope.launch { character.setAccentColor(hex) }
    }

    /** 06 step 2: re-serialize the mirror into the snapshot cache when the app backgrounds. */
    fun captureSnapshot() {
        val character = open.value ?: return
        viewModelScope.launch { character.captureSnapshot() }
    }

    private fun mutateOverride(plan: (List<TrackerOverride>) -> List<TrackerOverride>) {
        val character = open.value ?: return
        viewModelScope.launch {
            val rows = plan(character.overrides.value)
            if (rows.isNotEmpty()) character.setOverrides(rows)
        }
    }

    override fun onCleared() {
        cleared = true
        val character = open.value ?: return
        // `viewModelScope` is already cancelled by the time this runs, so closing has to
        // happen somewhere else. The scope dies with the (short) close call.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { character.close() }
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * The five read signals, bundled.
     *
     * `combine` tops out at five flows before it degenerates into an `Array<Any?>` with
     * unchecked casts; the tracker now needs eight. Nesting one typed `combine` inside
     * another keeps every element's type, which is worth a five-field private class.
     */
    private data class Read(
        val board: TrackerBoard,
        val connection: ConnectionState,
        val syncedAt: Long?,
        val showingSnapshot: Boolean,
        val accent: String?,
    )

    /**
     * The two *preference* signals, bundled for the same arity reason as [Read].
     *
     * FR-7's selection made the tracker's `combine` nine flows wide, one past the typed
     * five-arity overload nesting bought. Pairing the two DataStore-backed reads is the
     * cheapest honest fix — they are the same kind of signal (a stored choice, not sheet
     * data), so the grouping means something rather than being an arbitrary split to fit.
     */
    private data class Prefs(val showToggles: Boolean, val selectedRollId: String?)

    /** The inventory tab's stored preferences. See [inventoryPrefs]. */
    private data class InventoryPrefs(
        val equippableOverrides: Set<String>,
        val layout: List<InventoryLayoutEntry>,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
