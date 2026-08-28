package com.hashtagchow.magehand.ui.screens.local

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacter
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacterFactory
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneLayoutEntry
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.ui.panes.movePane
import com.hashtagchow.magehand.ui.panes.nextStoredPanes
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.ui.screens.characterhome.TrackerEvent
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryLayoutPlan
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.toInventoryUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.CustomizeSection
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerCustomizeState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerOverridePlan
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toCustomizeState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toTrackerUiState
import java.time.ZoneId
import javax.inject.Inject

/**
 * @param reference 09 decision 6's read-only strip. `null` until the character has loaded (or
 *   after it has been deleted underneath this screen), which is the same condition that makes
 *   the tracker's board empty — so the two go absent together rather than the strip surviving
 *   as a header over nothing.
 * @param tracker the same [TrackerUiState] the DiceCloud tracker renders (09 decision 5), with
 *   `hasConnection = false` (decision 8).
 * @param inventory the same [InventoryUiState] the DiceCloud inventory tab renders
 *   (docs/design/10-inventory.md decision 10). The board differs — no containers, no
 *   attunement, a wallet backed by four columns rather than tagged items — but the *type* does
 *   not, so the tab is reused rather than forked exactly as the tracker was.
 */
data class LocalCharacterHomeUiState(
    val characterId: String = "",
    val characterName: String? = null,
    val reference: LocalReferenceState? = null,
    val tracker: TrackerUiState = TrackerUiState(hasConnection = false),
    val customize: TrackerCustomizeState = TrackerCustomizeState(reorderOnly = true),
    val inventory: InventoryUiState = InventoryUiState(),
    /**
     * FR-29's Actions surface (docs/design/18-table-pack.md decisions 1–4).
     *
     * The same [ActionsUiState] the DiceCloud screen renders, from a board built by
     * `LocalActionBoard` instead of `ActionEngine` — the third surface this screen reuses rather
     * than forks, on 09 decision 5's terms exactly. What differs is inside the state and not
     * around it: no spells, no spell lists, no upcast picker, and `usesAreUndoable = true`.
     */
    val actions: ActionsUiState = ActionsUiState(),
) {
    /**
     * FR-29 decision 3's discovery gate: does this character have an Actions surface at all?
     *
     * `CharacterHomeUiState.hasActions`'s twin, down to the recomposition argument: the chrome
     * keys `remember(hasActions)` on this, and keying it on a list would rebuild the tab row on
     * every Room emission. **False while loading**, which is the honest default — a tab that
     * appeared a beat after the screen would be worse than one that appears with the data.
     */
    val hasActions: Boolean get() = actions.sections.isNotEmpty()
}

/**
 * A local character's home (docs/design/09-local-characters.md decisions 5–8).
 *
 * ### Why this is a second view model and not a flag on `CharacterHomeViewModel`
 *
 * 09 decision 5's "reused, not forked" is a claim about **the tracker**, and it holds exactly:
 * this class builds the same [TrackerUiState] with the same `toTrackerUiState`, from a
 * `LocalOpenCharacter` that implements the same [OpenCharacter] interface, and the screen
 * renders the same `TrackerTab`. Not one line of the tracker is duplicated.
 *
 * What differs is the *chrome around it*, and that is where a shared view model would have
 * cost more than it saved. `CharacterHomeViewModel` constructor-injects four things a local
 * character has no use for — `OpenCharacterFactory`, `CharacterListRepository`,
 * `SheetSessionFactory`, `DdpConnectionManager` — and Hilt resolves constructor dependencies
 * whether or not a code path uses them, so "one view model, `if (local)`" means building a
 * DDP-backed object graph for a character that has no account. Worse, the Sheet tab's
 * `SheetSession` would exist as a nullable field on a screen 09 decision 8 says must never
 * instantiate a WebView, and "never" would then be a runtime `if` rather than a type.
 *
 * So: shared tracker, separate host. The absence of a `session` field on
 * [LocalCharacterHomeUiState] is what makes "no Sheet tab, no WebView" structural rather than
 * conditional — there is nothing for a tab to render, so no tab can be added by accident.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalCharacterHomeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: LocalCharacterRepository,
    appSettingsStore: AppSettingsStore,
    private val selectedRollStore: SelectedRollStore,
    private val equippableOverrideStore: EquippableOverrideStore,
    private val inventoryLayoutStore: InventoryLayoutStore,
    private val paneLayoutStore: PaneLayoutStore,
    private val factory: LocalOpenCharacterFactory,
) : ViewModel() {

    private val characterId: String = requireNotNull(savedStateHandle["characterId"]) {
        "LocalCharacterHome route is missing characterId"
    }

    private val open = MutableStateFlow<LocalOpenCharacter?>(null)

    /**
     * Guards [cleared] and the *publish* of [open] together, so the two can never interleave.
     *
     * A `@Volatile` flag was enough to make each read see the latest write and not enough to
     * make the pair atomic: `factory.open` suspends on a Room read, so "is this view model
     * cleared?" and "publish the character" are two steps with the whole main-thread schedule
     * between them. `onCleared` landing in that gap saw `cleared = true` written *after* the
     * check and `open.value` still null before the publish — so neither side closed the
     * character, and a `LocalOpenCharacter` nobody holds kept its private scope and two Room
     * collectors alive for the life of the process.
     *
     * Rare and cheap to close, which is the whole argument for closing it: the window is one
     * disk read wide and the loser is a screen the user has already left, so nothing will ever
     * report it. A plain lock rather than a compare-and-set because there are two fields, and
     * `onCleared` is not a suspending context — this is uncontended in every real run.
     */
    private val lifecycle = Any()

    /** Guarded by [lifecycle]. */
    private var cleared = false

    private val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 16)

    /** The same undo snackbar the DiceCloud tracker shows. Nothing is replayed on re-subscribe. */
    val events: SharedFlow<TrackerEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            // Closed *outside* the lock, because `close` suspends — and because a character
            // nobody adopted has nothing racing it any more.
            adoptOrOrphan(factory.open(characterId))?.close()
        }

        // A new history id is a fresh mutation. Undo marks an existing entry undone rather
        // than adding one, which is what stops UNDO offering to undo itself.
        viewModelScope.launch {
            var lastSeen = 0L
            open.filterNotNull()
                .flatMapLatest { it.writeHistory }
                .collect { history ->
                    val newest = history.firstOrNull() ?: return@collect
                    if (newest.id > lastSeen) {
                        lastSeen = newest.id
                        _events.emit(TrackerEvent.Wrote(newest))
                    }
                }
        }
        // `LocalOpenCharacter.writeFailures` never emits (a Room write against a row this
        // instance owns has no failure the player can act on), so there is deliberately no
        // failure collector and no shake here.
    }

    private val character = repository.observe(characterId)

    /**
     * FR-7's key for this character.
     *
     * A **local** key, which is the whole reason `SelectedRollStore` is not a Room table: every
     * per-character table in this app is keyed by `(accountId, creatureId)`, and 09 decision 1
     * forbids the sentinel account a local character would need to have a row in one. Keyed by
     * the character instead, the same store serves both kinds — and its own namespace is what
     * keeps sign-out's per-account reap from reaching this.
     */
    private val rollKey: String = SelectedRollStore.localKey(characterId)

    /**
     * FR-10's key for this character (11 decision 2), in the same local namespace and for the
     * same reason as [rollKey]: keyed by the character, so sign-out's per-account prefix sweep
     * provably cannot reach it (09 decision 10).
     *
     * A local character's items carry no tags, so `LocalInventoryBoard` reports every one of
     * them equippable and the detail sheet's override switch never renders — see there. The key
     * is wired up anyway rather than left for a later wave, because the *deletion* path has to
     * reap it either way, and a store that only half exists is how an orphan key gets written
     * the first time someone adds local tags.
     */
    private val equippableOverrideKey: String = EquippableOverrideStore.localKey(characterId)

    /**
     * FR-14's key for this character (12 decision 6), in the same local namespace and for the
     * same reason as [rollKey].
     *
     * 12 decision 6: a local character gets the **same** customize surface, over a smaller set of
     * sections — no containers, and every local item is equippable so Weapons and Armor stay
     * empty until FR-10b. Nothing about the mechanism differs, which is the decision: the sheet,
     * the plan and the store are the DiceCloud ones, reached with a local key.
     */
    private val inventoryLayoutKey: String = InventoryLayoutStore.localKey(characterId)

    /**
     * FR-17's key for this character (14 decision 8), in the same local namespace and for the
     * same reason as [rollKey].
     *
     * Available before [open] resolves, unlike the DiceCloud screen's — a local character's key
     * is its own id, so the panes can be read the moment the screen exists rather than one
     * `flatMapLatest` after the character loads.
     */
    private val paneLayoutKey: String = PaneLayoutStore.localKey(characterId)

    /**
     * FR-17's chosen panes for this character (14 decision 8).
     *
     * A separate `StateFlow` from [uiState] rather than a field on it, and deliberately: this is
     * a preference about *chrome*, read only by the composable that decides which chrome to draw,
     * and folding it into the state every tracker row recomposes against would make a pane toggle
     * invalidate the whole screen. It is also the state decision 10 needs to survive a gate
     * crossing untouched — keeping it out of the character's ui state keeps it out of every
     * rebuild of that state.
     *
     * The empty list is *"no preference"*, not *"no panes"*; `resolvePaneLayout` turns it into
     * decision 8's Tracker-only default in FR-27's default order. See `PaneLayoutStore`.
     */
    val panes: StateFlow<List<PaneLayoutEntry>> = paneLayoutStore.panes(paneLayoutKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * Decision 6's picker gesture, persisted — the DiceCloud view model's, unchanged, over a
     * smaller set of surfaces (FR-27 decision 4: *"local characters get the same mechanism over
     * their smaller set"*).
     *
     * [resolved] is what is on screen — `resolvePaneLayout`'s answer, not the raw stored list —
     * because `togglePane`'s minimum-of-one has to count visible panes; see its KDoc. What gets
     * *persisted* is `nextStoredPanes`'s edit woven back against [panes]' current value, not
     * [resolved] itself — see its KDoc for why writing the resolved arrangement directly silently
     * erased a filtered-out preference. A gesture the rule refuses returns the empty list and is
     * not written, matching `mutateInventoryLayout`'s no-op contract.
     */
    fun togglePane(resolved: List<PaneLayoutEntry>, surface: PaneSurface) {
        writePaneLayout(nextStoredPanes(resolved, panes.value, surface))
    }

    /** FR-27 decision 2's reorder gesture. `CharacterHomeViewModel.movePane`'s twin. */
    fun movePane(resolved: List<PaneLayoutEntry>, surface: PaneSurface, delta: Int) {
        writePaneLayout(movePane(resolved, panes.value, surface, delta))
    }

    /**
     * FR-27 decision 3's Reset: delete the key, restoring the default order and the default pane
     * set together because both live in it. Also the local-delete reap's write — see
     * `LocalCharacterRepository.delete`, which reaches the same method (09 decision 10).
     */
    fun resetPaneLayout() {
        viewModelScope.launch { paneLayoutStore.clearForCharacter(paneLayoutKey) }
    }

    /** The one write path for both pane gestures. Empty is the plans' no-op signal. */
    private fun writePaneLayout(next: List<PaneLayoutEntry>) {
        if (next.isEmpty()) return
        viewModelScope.launch { paneLayoutStore.setPanes(paneLayoutKey, next) }
    }

    /**
     * The two *preference* signals, paired.
     *
     * `combine` tops out at five typed flows before it degenerates into an `Array<Any?>` with
     * unchecked casts, and FR-7's selection made the tracker's need six. Grouping the two
     * DataStore-backed reads is the cheapest honest fix — they are the same kind of signal (a
     * stored choice, not character data) — and it is the same grouping, for the same reason,
     * as `CharacterHomeViewModel.Prefs`.
     */
    private data class Prefs(val showToggles: Boolean, val selectedRollId: String?)

    private val prefs: Flow<Prefs> =
        combine(appSettingsStore.showToggles, selectedRollStore.selectedRollId(rollKey)) { toggles, rollId ->
            Prefs(showToggles = toggles, selectedRollId = rollId)
        }

    private val trackerState: Flow<TrackerUiState> = open.flatMapLatest { local ->
        if (local == null) {
            flowOf(TrackerUiState(creatureId = characterId, hasConnection = false))
        } else {
            combine(
                local.board,
                local.canWrite,
                local.canUndo,
                local.writeHistory,
                prefs,
            ) { board, canWrite, canUndo, history, prefs ->
                toTrackerUiState(
                    creatureId = characterId,
                    board = board,
                    // Not read from `local.connectionState`. That flow is a constant `LIVE`
                    // and this is the value the *status derivation* needs to produce a
                    // healthy, quiet state; `hasConnection = false` below is what actually
                    // guarantees nothing about it renders. Passing the constant through
                    // would make the suppression depend on it staying constant.
                    connection = ConnectionState.LIVE,
                    lastSyncedAt = null,
                    isShowingSnapshot = false,
                    accentColor = null,
                    canWrite = canWrite,
                    canUndo = canUndo,
                    history = history,
                    zone = ZoneId.systemDefault(),
                    // FR-6 applies to local characters too, and costs nothing: a local board
                    // carries no toggles at all (09 decision 4). Passing it anyway is what
                    // keeps the two trackers one rule rather than two.
                    showToggles = prefs.showToggles,
                    // 09 decision 8, pinned — see TrackerUiState.hasConnection.
                    hasConnection = false,
                    // FR-7 works identically for a local character: six ability checks from
                    // the stored scores, and a selection remembered under this character's own
                    // key. See `SelectedRollStore` for why that needs no account.
                    selectedRollId = prefs.selectedRollId,
                )
            }
        }
    }

    /**
     * FR-8's tab for a local character (10 decision 10).
     *
     * `connection = LIVE` and `isShowingSnapshot = false` are not a pretence: the board comes
     * off Room synchronously, so there is no cold-open gap for a spinner to fill and
     * `isLoading` is correctly false the moment the screen composes. A character that has not
     * loaded yet renders `InventoryBoard.EMPTY`, which is the same four zero coin rows a real
     * empty character has — and briefly showing the truth about nothing is better than a
     * spinner that never had anything to wait for.
     */
    private val inventoryState: Flow<InventoryUiState> = open.flatMapLatest { local ->
        if (local == null) {
            flowOf(InventoryUiState(creatureId = characterId))
        } else {
            combine(
                local.inventory,
                local.canWrite,
                equippableOverrideStore.overrides(equippableOverrideKey),
                inventoryLayoutStore.layout(inventoryLayoutKey),
            ) { board, canWrite, overrides, layout ->
                toInventoryUiState(
                    creatureId = characterId,
                    board = board,
                    connection = ConnectionState.LIVE,
                    isShowingSnapshot = false,
                    canWrite = canWrite,
                    equippableOverrides = overrides,
                    layout = layout,
                    // 12 decisions 7 and 8, stamped onto every row: delete offers no undo here,
                    // and move is not offered at all. See `InventoryRowState.isLocal`.
                    isLocal = true,
                )
            }
        }
    }

    private val customizeState: Flow<TrackerCustomizeState> = open.flatMapLatest { local ->
        if (local == null) {
            flowOf(TrackerCustomizeState(reorderOnly = true))
        } else {
            combine(
                local.boardIgnoringHidden,
                local.overrides,
                appSettingsStore.showToggles,
            ) { board, overrides, showToggles ->
                toCustomizeState(
                    board = board,
                    overrides = overrides,
                    accentColor = null,
                    showToggles = showToggles,
                    // 09 decision 8's "ONE mechanism": sortIndex, and nothing else.
                    reorderOnly = true,
                )
            }
        }
    }

    /**
     * FR-29's Actions surface (18 decisions 1–4).
     *
     * `combine` of two flows rather than four, because a local action board needs nothing the
     * server's does: no spell slots (there are no local spells to upcast), and no in-flight set
     * (`LocalOpenCharacter.usesInFlight` is constant empty — the mutex is the guard, see there).
     *
     * `usesAreUndoable = true` is the one argument this state passes that the DiceCloud screen
     * passes false for, and it deletes a sentence rather than adding one: decision 4's *"NO
     * no-undo line (undo exists; saying otherwise would lie)"*. See
     * [ActionsUiState.usesAreUndoable].
     */
    private val actionsState: Flow<ActionsUiState> = open.flatMapLatest { local ->
        if (local == null) {
            flowOf(ActionsUiState(creatureId = characterId, usesAreUndoable = true))
        } else {
            combine(local.actions, local.canWrite) { board, canWrite ->
                toActionsUiState(
                    creatureId = characterId,
                    board = board,
                    canWrite = canWrite,
                    usesAreUndoable = true,
                )
            }
        }
    }

    val uiState: StateFlow<LocalCharacterHomeUiState> = combine(
        character,
        trackerState,
        customizeState,
        inventoryState,
        actionsState,
    ) { character, tracker, customize, inventory, actions ->
        LocalCharacterHomeUiState(
            characterId = characterId,
            characterName = character?.name,
            reference = LocalReferenceState.from(character),
            tracker = tracker,
            customize = customize,
            inventory = inventory,
            actions = actions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        LocalCharacterHomeUiState(characterId = characterId),
    )

    // --- tracker writes: the same OpenCharacter intents, nothing else ---------------

    fun spend(propertyId: String, amount: Int = 1) = withRow(propertyId) { character, row ->
        character.spend(row, amount)
    }

    fun restore(propertyId: String, amount: Int = 1) = withRow(propertyId) { character, row ->
        character.restore(row, amount)
    }

    fun changeHitPoints(delta: Int) {
        open.value?.changeHitPoints(delta)
    }

    fun setHitPoints(value: Int) {
        open.value?.setHitPoints(value)
    }

    fun adjustItem(propertyId: String, delta: Int) = withRow(propertyId) { character, row ->
        character.adjustItem(row, delta)
    }

    /**
     * FR-22 direct entry on a slot or resource row (15 decisions 5–7).
     *
     * The DiceCloud path's arithmetic, verbatim and deliberately so: decision 6 makes these rows
     * delta-shaped through `spend`/`restore`, and both implementations of those intents already
     * carry the same clamps. A local-only "just set the column" shortcut would have been the one
     * place in this feature where the two kinds of character behaved differently for no reason a
     * player could see.
     */
    fun setResourceValue(propertyId: String, value: Int) = withRow(propertyId) { character, row ->
        val delta = value.coerceIn(0, row.total) - row.value
        when {
            delta < 0 -> character.spend(row, -delta)
            delta > 0 -> character.restore(row, delta)
        }
    }

    /** FR-22 direct entry on a consumable's quantity. Floor 0, no ceiling (decision 7). */
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

    /** 09 decision 7. The confirm dialog is the screen's job and has already been answered. */
    fun rest(kind: RestKind) {
        open.value?.rest(kind)
    }

    /**
     * FR-29 decision 4: a local action was used, after the confirm dialog.
     *
     * `CharacterHomeViewModel.use`'s twin, and deliberately the thinner of the two. There is no
     * slot to pass (a local character has no spells), no ritual box, and no error watch: the
     * server path spends a second or two afterwards reading the activity feed for a `doAction`
     * failure it can report, because `doAction` returns null for every outcome (probe U1). Here
     * the write is a Room transaction that either commits or does not, and it is journalled with
     * an inverse — so the receipt is the undo snackbar the tracker already shows, and there is
     * nothing to watch for.
     *
     * Takes the [UseTarget] rather than a property id, matching the interface's shape: a target
     * cannot be constructed for a row the app has decided is unusable, so the gate is the
     * parameter type. `LocalOpenCharacter.useAction` re-checks against the committed rows anyway
     * — 17 decision 6's two gates, both of them still here.
     */
    fun use(target: UseTarget, slotId: String?, ritual: Boolean) {
        val character = open.value ?: return
        when (target) {
            is UseTarget.Action -> character.useAction(target.propertyId)
            // Unreachable: `LocalActionBoard` emits no spells, so no `ActionRow.Spell` exists to
            // open a detail sheet on and no `UseTarget.Spell` can be built. Branch named rather
            // than swept into an `else`, matching this screen's posture towards `PaneSurface.SHEET`
            // — a total `when` is how a future local spell model becomes a compile error here
            // instead of a silently dropped tap.
            is UseTarget.Spell -> Unit
        }
    }

    // --- inventory tab: the same four intents, against the same interface ------------

    /**
     * 10 decision 4's one-tap equip. Locally a plain flag — there are no folders to move
     * between — so the undo is complete rather than partial. See `LocalOpenCharacter.setEquipped`.
     *
     * Resolved against the inventory board for the same reason the DiceCloud path is: the
     * current state has to come from the list the player is looking at.
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
     * Resolved against the inventory board rather than through [withRow], matching the
     * DiceCloud path — and here the reason is the same one in miniature: `LocalTrackerBoard`
     * applies the hide override too, so a hidden item row would resolve to nothing.
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
     * FR-22 direct entry on the inventory side of an item quantity — the list row and the detail
     * sheet (15 decision 5).
     *
     * Resolved against the inventory board for [adjustItemQuantity]'s reason, whole.
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
     * A wallet stepper. Locally the four denominations are integer columns, so
     * `WalletRow.propertyId` is never null and there is no insert branch — see
     * `LocalOpenCharacter.adjustCoins`. The screen cannot tell, which is the point.
     */
    fun adjustCoins(coin: CoinKind, delta: Int) {
        val character = open.value ?: return
        character.adjustCoins(character.inventory.value.wallet.row(coin), delta)
    }

    /** FR-22 direct entry on a wallet row. Floor 0, no ceiling (15 decision 7). */
    fun setCoins(coin: CoinKind, value: Int) {
        val character = open.value ?: return
        character.adjustCoins(
            character.inventory.value.wallet.row(coin),
            ExactQuantity(value.coerceAtLeast(0)),
        )
    }

    /**
     * The detail sheet's "Can be equipped" switch (11 decision 2).
     *
     * Reachable only in principle today — the switch renders on rows the board calls
     * non-equippable, and a local board calls none of them that — and wired anyway so the two
     * screens keep the one contract. The DiceCloud view model's own `setEquippableOverride`
     * argues the rest.
     */
    fun setEquippableOverride(propertyId: String, canEquip: Boolean) {
        viewModelScope.launch {
            equippableOverrideStore.setOverridden(equippableOverrideKey, propertyId, canEquip)
        }
    }

    /** The add-item flow. Not undoable here either, and deliberately so — see the impl. */
    fun addItem(spec: NewItemSpec) {
        open.value?.addItem(spec)
    }

    /**
     * Delete an item row, after the detail sheet's destructive confirm (FR-9, 12 decision 7).
     *
     * **Not undoable here, unlike the server path**, and the dialog has already said so: the
     * copy is chosen by `InventoryRowState.deleteWarningRes`, which turns on the same
     * `isLocal` flag this screen stamps onto every row. `LocalOpenCharacter.removeItem` gives
     * the reason — a Room row that is deleted is gone, and buying an undo would mean a
     * tombstone table.
     */
    fun removeItem(propertyId: String) {
        open.value?.removeItem(propertyId)
    }

    /**
     * A no-op, kept only so the inventory tab can be wired identically on both screens.
     *
     * 12 decision 8: local characters have no containers, so the detail sheet omits the Move
     * control entirely (`InventoryRowState.showsMoveControl` is false whenever `isLocal`) and
     * nothing can reach this. `LocalOpenCharacter.moveItem` refuses it a second time. Wiring
     * the callback to `null` instead would mean the two screens constructed
     * `InventoryActions` differently, which is the drift that makes one of them quietly lose a
     * control nobody notices for a release.
     */
    fun moveItem(propertyId: String, containerId: String?) {
        val target = containerId
            ?.let { InventoryMoveTarget.Container(it) }
            ?: InventoryMoveTarget.Carried
        open.value?.moveItem(propertyId, target)
    }

    fun undoLastWrite() {
        val character = open.value ?: return
        viewModelScope.launch { character.undoLastWrite() }
    }

    /**
     * FR-7: the Rolls dropdown was used. Local, like every write on this screen.
     *
     * Not routed through [LocalOpenCharacter]: the key is the character id, which this class
     * already has from the route, so the selection is readable and writable before — and
     * after — a character is open. The DiceCloud view model reads the same store the same way;
     * see its `selectedRollId`.
     */
    fun selectRoll(rollId: String) {
        viewModelScope.launch { selectedRollStore.setSelectedRollId(rollKey, rollId) }
    }

    // --- inventory customize sheet (12 decisions 3 and 6) ---------------------------
    //
    // Byte for byte the DiceCloud view model's three methods, against the same plan and the same
    // store with a local key. 12 decision 6 asks for the same surface, and this is what "same"
    // looks like from here — note in particular that Reset is offered, unlike the *tracker*
    // customize sheet's, which a local character does not get (09 decision 8's reorder-only mode
    // exists because `sortIndex` IS the tracker's order). An inventory arrangement is one stored
    // key with a documented default either way, so resetting means the same thing for both kinds.

    /** Moves one inventory section one place. `delta` is −1 (up) or +1 (down). */
    fun moveInventorySection(key: String, delta: Int) = mutateInventoryLayout { resolved, stored ->
        InventoryLayoutPlan.move(resolved, stored, key, delta)
    }

    /** Folds an inventory section into Gear, or brings it back. */
    fun setInventorySectionHidden(key: String, hidden: Boolean) =
        mutateInventoryLayout { resolved, stored ->
            InventoryLayoutPlan.setHidden(resolved, stored, key, hidden)
        }

    /**
     * Shuts an inventory section, or opens it (FR-16, 13 decision 3).
     *
     * The DiceCloud screen's method, verbatim, over this character's own layout key — which is
     * the whole of 12 decision 6's "same customize surface" claim extended to FR-16: a local
     * character's inventory collapses and remembers it exactly as a server one does, because
     * both go through the same plan and the same store.
     */
    fun setInventorySectionCollapsed(key: String, collapsed: Boolean) =
        mutateInventoryLayout { resolved, stored ->
            InventoryLayoutPlan.setCollapsed(resolved, stored, key, collapsed)
        }

    /** The sheet's Reset — a key deletion, so the default is never frozen into a character. */
    fun resetInventoryLayout() {
        viewModelScope.launch { inventoryLayoutStore.clearForCharacter(inventoryLayoutKey) }
    }

    private inline fun mutateInventoryLayout(
        crossinline plan: (
            resolved: List<InventoryLayoutEntry>,
            stored: List<InventoryLayoutEntry>,
        ) -> List<InventoryLayoutEntry>,
    ) {
        viewModelScope.launch {
            val resolved = uiState.value.inventory.customize.resolved
            val stored = inventoryLayoutStore.layout(inventoryLayoutKey).first()
            val next = plan(resolved, stored)
            if (next.isNotEmpty()) inventoryLayoutStore.setLayout(inventoryLayoutKey, next)
        }
    }

    /** The tracker customize sheet's ▲/▼. The only mutation *that* sheet offers here. */
    fun moveRow(section: CustomizeSection, propertyId: String, delta: Int) {
        val character = open.value ?: return
        viewModelScope.launch {
            val order = uiState.value.customize.sections
                .firstOrNull { it.section == section }
                ?.rows
                ?.map { it.propertyId }
                .orEmpty()
            val rows: List<TrackerOverride> =
                TrackerOverridePlan.reorder(character.overrides.value, order, propertyId, delta)
            if (rows.isNotEmpty()) character.setOverrides(rows)
        }
    }

    /**
     * Resolves a tapped `propertyId` against the current board.
     *
     * Same rule as the DiceCloud path: the UI hands back an id and nothing else, so a row that
     * has since been edited away resolves to nothing and the tap is dropped rather than
     * written blind.
     */
    private inline fun withRow(propertyId: String, act: (OpenCharacter, TrackedResource) -> Unit) {
        val character = open.value ?: return
        val board = character.board.value
        val row = (board.slots + board.resources + board.allItems + listOfNotNull(board.hp))
            .firstOrNull { it.propertyId == propertyId } ?: return
        act(character, row)
    }

    /**
     * Takes ownership of a freshly opened character, **or** hands it straight back because
     * this view model was cleared while [LocalOpenCharacterFactory.open] was still suspended.
     *
     * `internal` rather than private for the same reason `LocalOpenCharacter.awaitIdle` is: the
     * thing under test is an *interleaving*, and a unit test cannot schedule one. What it can
     * do is run the two halves in both orders and assert the contract below, which is the part
     * a future edit would break.
     *
     * @return the character the caller must close, or `null` when it was adopted.
     */
    internal fun adoptOrOrphan(opened: LocalOpenCharacter?): LocalOpenCharacter? =
        synchronized(lifecycle) {
            if (cleared) {
                opened
            } else {
                open.value = opened
                null
            }
        }

    /**
     * Marks this view model cleared and hands back the adopted character, if there is one.
     *
     * Together with [adoptOrOrphan] this is the whole contract, and it is worth stating as
     * one sentence: **run these two in either order and exactly one of them hands the
     * character back.** Neither is allowed to observe the other half-done, which is why both
     * take [lifecycle] and why the flag is not simply `@Volatile` — see there.
     */
    internal fun markCleared(): LocalOpenCharacter? = synchronized(lifecycle) {
        cleared = true
        open.value
    }

    override fun onCleared() {
        val character = markCleared() ?: return
        // `viewModelScope` is already cancelled by the time this runs, so the close has to
        // happen elsewhere. `LocalOpenCharacter.close` joins its cancellation, which is what
        // makes it safe for the database to be closed the moment this returns.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { character.close() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
