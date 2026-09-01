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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.feed.ActivityFeedRepository
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.session.OpenCharacterFactory
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.core.data.settings.PaneLayoutEntry
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConcentrationPrompt
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.FeedEntry
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.QuestEntry
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.ui.panes.movePane
import com.hashtagchow.magehand.ui.panes.nextStoredPanes
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryLayoutPlan
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState
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
    /**
     * FR-26's Actions surface (docs/design/16-actions-and-feed.md). Built from the character's
     * own `actions` flow, like [inventory] and for the same reason — it answers a different
     * question from the tracker board and shares none of its override layer.
     */
    val actions: ActionsUiState = ActionsUiState(),
    /**
     * FR-32's quest log (docs/design/18-table-pack.md decisions 13–16).
     *
     * The domain list, carried through unrendered — unlike [tracker], [inventory] and [actions],
     * each of which has a `…UiState` between the board and the screen. There is nothing for such a
     * layer to do here: the log is read-only (decision 15), has no filter, no collapse and no
     * per-row state the screen owns, and its **ordering is already settled** by `QuestEngine`
     * (open above closed, sheet order within each). A pass-through type whose every field mapped
     * one-to-one would be indirection rather than abstraction — `SpellListHeader` is carried
     * through `ActionsUiState` the same way and for the same reason.
     *
     * Empty is the discovery gate decision 14 asks for: the top-bar entry appears only when this
     * is not.
     */
    val quests: List<QuestEntry> = emptyList(),
) {
    /** The accent seeds the whole character-home subtree, both tabs (04 §Theming). */
    val accentColor: String? get() = tracker.accentColor

    /**
     * FR-26 decision 1's discovery gate: does this character have an Actions surface at all?
     *
     * A derived `Boolean` rather than the screen reaching into `actions.sections`, so the chrome
     * decision reads one stable value. That matters for recomposition: `serverHomeTabs` and
     * `serverPaneSurfaces` are `remember(hasActions)`-keyed in the screen, and keying them on a
     * list would rebuild the tab row on every board emission.
     *
     * **False while loading**, which is the honest default — see `serverPaneSurfaces`' KDoc, and
     * `resolveTab` for what keeps that from bouncing a restored Actions selection.
     */
    val hasActions: Boolean get() = actions.sections.isNotEmpty()

    /**
     * FR-32 decision 14's gate: *"present only when ≥1 quest note exists"*.
     *
     * A named derivation rather than `quests.isNotEmpty()` at the call site, matching
     * [hasActions] one line up: the app bar reads one boolean, and the rule has a name a test can
     * assert on.
     */
    val hasQuests: Boolean get() = quests.isNotEmpty()
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
    /**
     * FR-25's feed, injected here for FR-28 decision 6's best-effort `doAction` error look —
     * see [watchForUseError]. The repository is a `@Singleton` handing out cold flows, so a
     * second consumer beside `DmViewViewModel` costs no subscription and no extra mirror.
     */
    private val activityFeedRepository: ActivityFeedRepository,
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

    /**
     * FR-31's concentration prompts (docs/design/18-table-pack.md decisions 9–12).
     *
     * A **pass-through** of `OpenCharacter.concentrationPrompts` rather than a third
     * `MutableSharedFlow` fed by a collector, which is what the two streams above are. The
     * difference is where the back-pressure question lives: those two exist because two kinds of
     * snackbar contend for one `SnackbarHostState` that suspends its emitter for the life of each
     * one. This does not go to the snackbar host at all — it goes to a banner the screen swaps
     * into a slot — so nothing suspends, nothing queues, and a buffer in front of it would only be
     * a place for a stale check to wait.
     *
     * `flatMapLatest` over [open] because the source belongs to the character: closing one and
     * opening another must not carry a prompt across, and the old flow simply ends.
     *
     * The **pin** is one layer down and is worth repeating here, because this is where a future
     * "why not just watch `board.hp`?" would land: the prompt fires only for damage **this client
     * wrote**. Another client hitting a concentrating character emits nothing. See
     * `DefaultOpenCharacter.promptConcentration` for the observer-storm measurement behind that.
     */
    val concentrationPrompts: Flow<ConcentrationPrompt> = open.flatMapLatest { character ->
        character?.concentrationPrompts ?: flowOf()
    }

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
     * The empty list is *"no preference"*, not *"no panes"*; `resolvePaneLayout` turns it into
     * decision 8's Tracker-only default in FR-27's default order, which is also what renders for
     * the frame before the character opens. See `PaneLayoutStore`.
     */
    val panes: StateFlow<List<PaneLayoutEntry>> = open.flatMapLatest { character ->
        if (character == null) {
            flowOf(emptyList())
        } else {
            paneLayoutStore.panes(paneLayoutKey(character))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * Decision 6's picker gesture, persisted.
     *
     * [resolved] is what is on screen — `resolvePaneLayout`'s answer, not the raw stored list —
     * because `togglePane`'s minimum-of-one has to count visible panes; see its KDoc. What gets
     * *persisted* is `nextStoredPanes`'s edit woven back against [panes]' current value, not
     * [resolved] itself — see its KDoc for why writing the resolved arrangement directly silently
     * erased a filtered-out preference. A gesture the rule refuses returns the empty list and is
     * not written, matching [mutateInventoryLayout]'s no-op contract.
     */
    fun togglePane(resolved: List<PaneLayoutEntry>, surface: PaneSurface) {
        writePaneLayout(nextStoredPanes(resolved, panes.value, surface))
    }

    /**
     * FR-27 decision 2's reorder gesture, persisted.
     *
     * [togglePane]'s shape exactly, down to the empty-for-a-no-op contract, and written as a twin
     * rather than folded into one `mutatePaneLayout(plan)` for `InventoryLayoutPlan.setCollapsed`'s
     * reason: they are two different player intents reached from two different controls, and a
     * reader at either call site should see which one they are looking at.
     */
    fun movePane(resolved: List<PaneLayoutEntry>, surface: PaneSurface, delta: Int) {
        writePaneLayout(movePane(resolved, panes.value, surface, delta))
    }

    /**
     * FR-27 decision 3's Reset: **delete the key**, which restores the default order and the
     * default pane set together because both live in it.
     *
     * `clearForCharacter` rather than `setPanes(key, emptyList())` — they reach the same state,
     * and the named one says which of the two intents this is. See `PaneLayoutStore`.
     */
    fun resetPaneLayout() {
        val key = paneLayoutKey(open.value ?: return)
        viewModelScope.launch { paneLayoutStore.clearForCharacter(key) }
    }

    /** The one write path for both pane gestures. Empty is the plans' no-op signal. */
    private fun writePaneLayout(next: List<PaneLayoutEntry>) {
        if (next.isEmpty()) return
        val key = paneLayoutKey(open.value ?: return)
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
                    sort = prefs.sort,
                )
            }
        }
    }

    /**
     * FR-26's Actions surface state (docs/design/16-actions-and-feed.md decisions 3–6).
     *
     * ### It used to be one source; FR-28 makes it four
     *
     * This was *"the narrowest of the four state flows … a `map` over one flow"*, because 16
     * decision 7's read-only surface needed no `canWrite` and no write state. 17 gives it exactly
     * one gesture and with it three more inputs, each of which is the *live* half of a decision
     * that would otherwise be made against a stale number:
     *
     * - `board.slots` — decision 3's picker, from the tracker's own rows so the picker and the
     *   pips cannot disagree about how many slots are left;
     * - `usesInFlight` — decision 5's single-flight, mirrored so the button looks the way the
     *   latch in `:core:data` already behaves;
     * - `canWrite` — so an offline Use dims rather than being swallowed, 04's standing rule.
     *
     * Four is inside `combine`'s typed arity, so no `Prefs`-style pairing is needed here.
     *
     * The query and the collapse set are **not** here, and neither is which row is open. They are
     * `ActionsScreen`'s own `rememberSaveable` state, applied on top via `ActionsUiState.withView`
     * — see that function for why the split is where it is.
     */
    private val actionsState: Flow<ActionsUiState> = open.flatMapLatest { character ->
        if (character == null) {
            flowOf(ActionsUiState(creatureId = creatureId))
        } else {
            combine(
                character.actions,
                character.board,
                character.usesInFlight,
                character.canWrite,
            ) { board, tracker, inFlight, canWrite ->
                toActionsUiState(
                    creatureId = creatureId,
                    board = board,
                    spellSlots = tracker.slots,
                    usesInFlight = inFlight,
                    canWrite = canWrite,
                )
            }
        }
    }

    /**
     * The inventory tab's DataStore-backed preferences, bundled.
     *
     * The same arity fix as [Prefs] and for the same reason: `combine` tops out at five typed
     * flows before it degenerates into an `Array<Any?>` with unchecked casts, and FR-14's layout
     * made this tab's need six. The grouping means something rather than being an arbitrary split
     * to fit — all of them are stored choices about how to *draw* this character's inventory, none
     * is sheet data, and they are reaped by the same two paths.
     *
     * FR-35's sort joins the bundle rather than claiming a slot in [inventoryState]'s own five,
     * and it belongs here on its own terms: it is a third read off the same two stores, keyed by
     * the same character key, cleared by the same Reset.
     */
    private fun inventoryPrefs(character: OpenCharacter): Flow<InventoryPrefs> = combine(
        equippableOverrideStore.overrides(equippableOverrideKey(character)),
        inventoryLayoutStore.layout(inventoryLayoutKey(character)),
        inventoryLayoutStore.sort(inventoryLayoutKey(character)),
    ) { overrides, layout, sort -> InventoryPrefs(overrides, layout, sort) }

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

    /**
     * [customizeState] and [actionsState], paired — the same arity fix as [Prefs] and
     * [InventoryPrefs], for the same reason.
     *
     * `combine` tops out at five typed flows before it degenerates into an `Array<Any?>` with
     * unchecked casts, and [uiState] below was already at exactly five. FR-26 needed a sixth
     * input, so two of them are pre-merged here rather than the whole call being demoted to the
     * untyped overload — where a mis-ordered argument becomes a `ClassCastException` at runtime
     * instead of a compile error.
     *
     * These two are the right pair to merge because neither is on the other's hot path: the
     * customize sheet's state changes when the sheet is open, the actions list's when the sheet
     * arrives, and nothing a player does moves both at once.
     */
    /**
     * Three of the five things [uiState] combines, grouped because `combine` tops out at five
     * typed flows before it degenerates into an `Array<Any?>` with unchecked casts.
     *
     * FR-32's quests join this bundle rather than claiming the sixth slot, and they belong here on
     * their own terms as well as arithmetically: like the customize sheet's state and the actions
     * board, they are a *derivation of the same sheet* the tracker is built from, arriving on the
     * same emission. Grouping is the same fix `Prefs` already applies one bundle over.
     */
    private data class SheetAndActions(
        val customize: TrackerCustomizeState,
        val actions: ActionsUiState,
        val quests: List<QuestEntry>,
    )

    /** FR-32's log, straight off the character. See [CharacterHomeUiState.quests]. */
    private val questsState: Flow<List<QuestEntry>> = open.flatMapLatest { character ->
        character?.quests ?: flowOf(emptyList())
    }

    private val customizeAndActions: Flow<SheetAndActions> =
        combine(customizeState, actionsState, questsState) { customize, actions, quests ->
            SheetAndActions(customize, actions, quests)
        }

    val uiState: StateFlow<CharacterHomeUiState> = combine(
        characterListRepository.state,
        sheetSessionFactory.sessions { SheetSessionFactory.characterPath(creatureId) },
        trackerState,
        customizeAndActions,
        inventoryState,
    ) { listState, session, tracker, sheetAndActions, inventory ->
        CharacterHomeUiState(
            creatureId = creatureId,
            // The name comes from the list the user just came from. The
            // `singleCharacter` sub could supply it, but the list is already correct
            // and is on screen before the sub goes ready.
            characterName = listState.characters.firstOrNull { it.creatureId == creatureId }?.name,
            connection = tracker.status.tone.toConnectionState(),
            session = session,
            tracker = tracker,
            customize = sheetAndActions.customize,
            inventory = inventory,
            actions = sheetAndActions.actions,
            quests = sheetAndActions.quests,
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

    /**
     * FR-22 direct entry on a **slot or resource** row (15 decisions 5–7).
     *
     * ### Why this composes spend/restore instead of setting the row
     *
     * Decision 6: *"Slots/resources likewise delta-shaped via the existing spend/restore
     * intents, one op."* The two intents already carry the clamps this row needs — floor 0,
     * ceiling `total` — and they carry them *inside* `:core:data`, against the board the write
     * layer trusts, which is where every other clamp in this app lives. A `setValue` path for
     * these rows would have been a second opinion about the same two bounds, and the history
     * entry would have read "Set" where the player will look for "Spent" or "Restored".
     *
     * One op, never two: the delta is signed, so exactly one branch fires. A target equal to the
     * row's current value is a zero delta and writes nothing — unlike the item and coin paths,
     * where an absolute is sent regardless because the *board* may have drifted. It cannot drift
     * here in a way this call could fix: `spend`/`restore` are increments, and an increment
     * computed from a stale value is wrong rather than corrective.
     *
     * The row is re-resolved through [withRow] so the clamp sees the board the player is looking
     * at, exactly as the steppers do.
     */
    fun setResourceValue(propertyId: String, value: Int) = withRow(propertyId) { character, row ->
        val delta = value.coerceIn(0, row.total) - row.value
        when {
            delta < 0 -> character.spend(row, -delta)
            delta > 0 -> character.restore(row, delta)
        }
    }

    /**
     * FR-22 direct entry on an **item quantity** — the tracker's consumable rows (15 decisions
     * 5–7).
     *
     * Absolute-shaped, so it goes through `OpenCharacter.adjustItem`'s `ExactQuantity` overload
     * and the latch's barrier. See that KDoc for why a delta computed here would be wrong
     * whenever a press-and-hold is still settling — which is precisely when a player reaches for
     * the number pad.
     *
     * Floor 0, no ceiling (decision 7: an item has no maximum).
     */
    fun setItemQuantity(propertyId: String, value: Int) = withRow(propertyId) { character, row ->
        character.adjustItem(row, ExactQuantity(value.coerceAtLeast(0)))
    }

    /**
     * FR-23 (15 decisions 19–21): a death-save pip was tapped.
     *
     * Straight through, with no board lookup — unlike every other tracker write here. There is
     * no id to re-resolve: the intent names no property, and the implementation reads the pair
     * off its own board inside the write. A guard here would only turn a tap that raced a
     * re-sync into a dropped tap, where one layer down it is a write against whatever the pair
     * currently is.
     */
    fun setDeathSaves(successes: Int, failures: Int) {
        open.value?.setDeathSaves(successes, failures)
    }

    // --- FR-28: Use (docs/design/17-use-action.md) ----------------------------------

    /**
     * The Actions surface's one gesture (17 decisions 3, 4 and 6).
     *
     * ### It takes a [UseTarget], not a property id
     *
     * That is the third of decision 2's gates and the only one visible from here. A
     * `useAction(propertyId: String)` on this class would be a seam an unprepared spell could be
     * pushed through by any caller that had an id — and every caller has an id. A `UseTarget` can
     * only be obtained from `SpellEntry.useTarget` / `ActionEntry.useTarget`, which return `null`
     * for a row that fails the gate, so there is no value of the parameter type for a row the app
     * has refused. `:core:data` re-resolves against the live board anyway (gate two), because a
     * target built three frames ago is a claim about a sheet that may have moved.
     *
     * ### The dialog has already been shown
     *
     * Decision 4 requires a confirm before **every** use, free ones included, and this call
     * assumes it — [rest]'s contract, and `removeItem`'s. The reason is stronger than either: a
     * use has no inverse *and* posts to the party feed and any configured Discord webhook (probe
     * U4), so the dialog is the only place the player can decline.
     *
     * @param slotId decision 3's upcast choice, from the picker. `null` for an action, a cantrip
     *   or a ritual cast.
     */
    fun use(target: UseTarget, slotId: String? = null, ritual: Boolean = false) {
        val character = open.value ?: return
        val dispatched = when (target) {
            is UseTarget.Action -> character.useAction(target.propertyId)
            is UseTarget.Spell -> character.castSpell(
                spellId = target.propertyId,
                slotId = slotId,
                ritual = ritual,
            )
        }
        // M3/M4 [architect ruling]: `dispatched` is `false` exactly when `:core:data`'s gate 1
        // or its single-flight latch dropped the tap — the player confirmed and nothing went
        // out. That used to return silently; now it surfaces through the existing failure lane
        // (M3) instead of a bespoke one, and starts no settle-window watch (M4) — there is no
        // call in flight for [watchForUseError] to watch for.
        if (!dispatched) {
            _failureEvents.tryEmit(
                TrackerEvent.Failed(
                    TrackerWriteFailure(
                        id = USE_ERROR_IDS.incrementAndGet(),
                        kind = if (target is UseTarget.Spell) {
                            TrackerWriteKind.CAST_SPELL
                        } else {
                            TrackerWriteKind.USE_ACTION
                        },
                        propertyId = null,
                        targetName = target.name,
                        reason = null,
                        refusedOffline = false,
                        rateLimited = false,
                        dropped = true,
                    ),
                ),
            )
            return
        }
        watchForUseError(target)
    }

    /**
     * Decision 6's second half — the best-effort look at the feed after a `doAction`.
     *
     * ### Why this exists, and why it is honestly labelled best-effort
     *
     * `doCastSpell` throws an atomic `Meteor.Error` and needs nothing here: the refusal rides
     * `writeFailures` to the existing snackbar with the server's `reason` on it. **`doAction`
     * returns `null` always** (probe U1) — for a success and for every refusal alike — so there
     * is no error frame to catch and no way to distinguish "used it" from "the server declined
     * silently". What the server *does* do is append a `creatureLogs` entry named "Error" with
     * the refusal's text in it, which the FR-25 feed is already carrying for this creature.
     *
     * So this reads that feed for a window after the tap and surfaces such an entry as a
     * snackbar. It is a **look**, not a check, and three things about it are wrong on purpose:
     *
     * - it can miss (the log arriving after the window closes);
     * - it can fire for an error somebody *else* caused on the same creature inside the window
     *   — attribution is creature-level only, there is no actor field anywhere in the data
     *   (16 decision 11);
     * - it proves nothing about a use that produced no log at all.
     *
     * The real gate is the client-side one (decision 1). This is the difference between showing
     * the player a sentence the server wrote and showing them nothing, and decision 6 asks for it
     * on exactly those terms. Making it *look* authoritative would be the error — hence a plain
     * snackbar carrying the server's own words, and no claim in the UI that a use "succeeded".
     *
     * Entries older than the tap are ignored by timestamp, so an "Error" already sitting in the
     * feed from ten minutes ago cannot be reported as this tap's.
     */
    private fun watchForUseError(target: UseTarget) {
        if (target !is UseTarget.Action) return
        val since = System.currentTimeMillis()
        viewModelScope.launch {
            withTimeoutOrNull(USE_ERROR_WINDOW_MILLIS) {
                activityFeedRepository.feed(setOf(creatureId))
                    .mapNotNull { entries -> entries.firstOrNull { it.isUseErrorSince(since) } }
                    .first()
            }?.let { entry ->
                _failureEvents.emit(
                    TrackerEvent.Failed(
                        TrackerWriteFailure(
                            id = USE_ERROR_IDS.incrementAndGet(),
                            kind = TrackerWriteKind.USE_ACTION,
                            // No row shakes. The write was not rolled back — nothing optimistic
                            // was applied to roll back — and shaking a row would claim the sheet
                            // had snapped back, which it did not.
                            propertyId = null,
                            targetName = target.name,
                            reason = entry.errorText(),
                            refusedOffline = false,
                            rateLimited = false,
                        ),
                    ),
                )
            }
        }
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
     * FR-22 direct entry on the **inventory** side of an item quantity — the list row and the
     * detail sheet (15 decision 5).
     *
     * Resolved against the inventory board rather than the tracker's, for [adjustItemQuantity]'s
     * reason in full: the tracker board is override-filtered, so an item the player hid from the
     * tracker would silently have no direct entry on the one tab that still lists it.
     *
     * The [TrackedResource] is built here for the same reason [adjustItemQuantity] builds one —
     * `value == total` is what a `TrackerKind.ITEM` row means — and its `value` is the item's
     * quantity **as the board has it**, which is what the op's inverse (and therefore the UNDO)
     * will restore.
     */
    fun setInventoryItemQuantity(propertyId: String, value: Int) {
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
            ExactQuantity(value.coerceAtLeast(0)),
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
     * FR-22 direct entry on a wallet row (15 decisions 5–7).
     *
     * Re-resolved from the live wallet for [adjustCoins]' reason, whole — including the `null`
     * `propertyId` that means "this sheet has no such coin yet", which is what turns a typed
     * number on an empty row into an insert carrying the whole count rather than an adjust
     * against nothing.
     *
     * Floor 0, no ceiling (decision 7: coins have no maximum).
     */
    fun setCoins(coin: CoinKind, value: Int) {
        val character = open.value ?: return
        character.adjustCoins(
            character.inventory.value.wallet.row(coin),
            ExactQuantity(value.coerceAtLeast(0)),
        )
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

    /**
     * A condition chip (or the concentration banner's ✕, which is the same write).
     *
     * M5 [architect ruling]: a [propertyId] the board no longer carries — the FR-31 prompt's
     * source toggled off, or was removed from the sheet, before the player tapped Drop — used to
     * return here silently. That is the same "confirmed and nothing went out" shape `use` (FR-28,
     * M3) already has a lane for, so a stale id now surfaces through it rather than growing a
     * second one: [TrackerWriteFailure.dropped] `true`, which `CharacterHomeScreen` already
     * renders as the honest "couldn't do that" snackbar regardless of which write dropped. No row
     * shakes ([propertyId] on the failure is `null`) for [TrackerEvent.Failed]'s own reason —
     * nothing optimistic was applied for a lookup miss, so there is nothing to roll back.
     */
    fun toggleCondition(propertyId: String) {
        val character = open.value ?: return
        val toggle = character.board.value.activeToggles.firstOrNull { it.propertyId == propertyId }
        if (toggle == null) {
            _failureEvents.tryEmit(
                TrackerEvent.Failed(
                    TrackerWriteFailure(
                        id = USE_ERROR_IDS.incrementAndGet(),
                        kind = TrackerWriteKind.TOGGLE,
                        propertyId = null,
                        targetName = propertyId,
                        reason = null,
                        refusedOffline = false,
                        rateLimited = false,
                        dropped = true,
                    ),
                ),
            )
            return
        }
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
        // FR-30: hit dice join the lookup, because decision 18 writes them through these same
        // `spend`/`restore` intents — the row a tap names has to be findable or the tap is
        // dropped. See `TrackerBoard.hitDice` for why they are their own list to begin with.
        val row = (board.slots + board.resources + board.hitDice + board.allItems + listOfNotNull(board.hp))
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
     * FR-35 decision 3's criterion radio, persisted.
     *
     * ### Why this is not a `mutateInventoryLayout`
     *
     * Because the sort is not an *order*, and that is the whole reason the three layout gestures
     * need a plan at all: an arrangement is a list, a list cannot be edited one element at a time
     * without the caller and the store agreeing on the other elements, and the gap between them is
     * where a cold open silently forgets four containers (see `InventoryLayoutPlan`'s KDoc). A
     * criterion is one value of a four-value enum. There is nothing to weave, nothing to lose, and
     * no state on screen the write has to be computed against — so it is a plain read-modify-write
     * of the pair, and writing it as a twin of the layout gestures would have implied a hazard it
     * does not have.
     *
     * The direction is read back from the store rather than from `uiState`, so a criterion tap
     * lands on the direction that is *stored* rather than on the one a stale frame is showing.
     */
    fun setInventorySortCriterion(criterion: InventorySortCriterion) =
        mutateInventorySort { it.copy(criterion = criterion) }

    /**
     * FR-35 decision 3's ascending/descending toggle, persisted.
     *
     * A gesture the sheet cannot even offer under [InventorySortCriterion.DEFAULT] — the control
     * is disabled there (decision 6) — and this deliberately does **not** re-check that. The
     * direction is a standing preference that survives a trip through sheet order (see
     * [InventorySort.direction]), so refusing to record one while the criterion happens to be
     * Default would throw away a choice the player would have to make again. There is no state
     * this can reach that renders differently, because sheet order ignores the direction.
     */
    fun setInventorySortDirection(direction: InventorySortDirection) =
        mutateInventorySort { it.copy(direction = direction) }

    /**
     * Reads the stored pair, applies [edit], writes it back — unless nothing changed.
     *
     * The no-write-on-a-no-op rule the three layout gestures keep, reached differently: they
     * signal it with an empty list because their value *is* a list, and this compares the two
     * values because its value is a pair of enums. Same contract, same reason — a radio tapped on
     * the option it is already on should not wake DataStore, and every write here re-emits a flow
     * the whole inventory tab is built from.
     */
    private inline fun mutateInventorySort(crossinline edit: (InventorySort) -> InventorySort) {
        val key = inventoryLayoutKey(open.value ?: return)
        viewModelScope.launch {
            val current = inventoryLayoutStore.sort(key).first()
            val next = edit(current)
            if (next != current) inventoryLayoutStore.setSort(key, next)
        }
    }

    /**
     * The sheet's Reset: forget this character's arrangement, so 12 decision 1's default draws.
     *
     * A key deletion rather than a write of the default order, which is decision 5's own wording
     * and is the meaningfully different one: a stored copy of the default would freeze *today's*
     * default into that character, so a later release that changed decision 1 would change the
     * order for every new character and for nobody who had ever pressed Reset.
     *
     * FR-35's *"Reset restores Default + ascending"* rides the same call: `clearForCharacter`
     * drops all three of this character's keys. One Reset, one meaning — after it the tab is what
     * a character who has never customized sees.
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

    /**
     * Is this feed entry a refusal the server logged *for this tap* (17 decision 6)?
     *
     * Two conditions, and both are needed.
     *
     * **Named "Error".** `doAction`'s refusals arrive as an ordinary `creatureLogs` document whose
     * `content[].name` is the word — the only thing distinguishing it from the entry a successful
     * use also writes. Prefix-matched case-insensitively; see [LOG_ERROR_NAME].
     *
     * **Newer than the tap.** Without this every use would report the last error the creature
     * ever logged, because the feed is a 20-entry window that keeps old rows. `dateMillis` is
     * `null` on a document the server sent no `date` for, and such an entry is **excluded**: it
     * cannot be shown to be this tap's, and 16 decision 11's rule for the same field is that an
     * absent date does not get to claim the top of the feed. Not claiming to be our error is the
     * same rule one step further.
     */
    private fun FeedEntry.isUseErrorSince(since: Long): Boolean {
        val at = dateMillis ?: return false
        if (at < since) return false
        return lines.any { it.name?.trim()?.startsWith(LOG_ERROR_NAME, ignoreCase = true) == true }
    }

    /**
     * The server's own words out of a logged refusal — the `value` of the "Error" line.
     *
     * `null` when the line carried a heading and no text, which routes the snackbar to its
     * generic sentence rather than printing "Not saved: ". Paraphrasing the absence would be this
     * app inventing a reason, which is the one thing decision 6 is careful not to do.
     */
    private fun FeedEntry.errorText(): String? = lines
        .firstOrNull { it.name?.trim()?.startsWith(LOG_ERROR_NAME, ignoreCase = true) == true }
        ?.value
        ?.takeIf { it.isNotBlank() }

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
        val sort: InventorySort,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * How long [watchForUseError] watches the feed after a `doAction` (17 decision 6).
         *
         * Three seconds, and the number is a trade rather than a measurement. The `creatureLogs`
         * document is written by the same server pass that ran the action, so it rides the same
         * fast path the resource fields do (~0.1-0.35 s, probe U3) plus whatever the mirror takes
         * to deliver it. Long enough to catch that comfortably; short enough that an error the
         * player caused with a *later* tap cannot still be attributed to this one, which matters
         * because the feed carries no actor and this window is the only attribution there is.
         */
        const val USE_ERROR_WINDOW_MILLIS = 3_000L

        /**
         * The `content[].name` DiceCloud gives a refusal it logged rather than threw.
         *
         * Matched case-insensitively and as a prefix, because the observed shapes are "Error" and
         * "Error:" and pinning the exact spelling of a server's log heading is the kind of
         * assertion that breaks silently on their next release. A false positive here costs one
         * snackbar the player can dismiss; a false negative costs the only signal `doAction` ever
         * gives (probe U1: it returns null for every refusal).
         */
        const val LOG_ERROR_NAME = "error"

        /**
         * Distinct ids, so two refusals in a row produce two snackbars rather than one.
         *
         * [A3] Starts at a billion, not 0: `OpenCharacter.kt`'s own `FAILURE_IDS` is a
         * SEPARATE `AtomicLong(0)` counting real write failures, and [TrackerWriteFailure.id]
         * is a Compose key two different counters both starting at 0 would happily collide on
         * — a real failure and a dropped use minted the same frame could compare equal and
         * fail to animate twice. Disjoint ranges cost nothing and remove the coincidence.
         */
        val USE_ERROR_IDS = java.util.concurrent.atomic.AtomicLong(1_000_000_000L)
    }
}
