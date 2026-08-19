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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacter
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacterFactory
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.ui.screens.characterhome.TrackerEvent
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
)

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
            combine(local.inventory, local.canWrite) { board, canWrite ->
                toInventoryUiState(
                    creatureId = characterId,
                    board = board,
                    connection = ConnectionState.LIVE,
                    isShowingSnapshot = false,
                    canWrite = canWrite,
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

    val uiState: StateFlow<LocalCharacterHomeUiState> = combine(
        character,
        trackerState,
        customizeState,
        inventoryState,
    ) { character, tracker, customize, inventory ->
        LocalCharacterHomeUiState(
            characterId = characterId,
            characterName = character?.name,
            reference = LocalReferenceState.from(character),
            tracker = tracker,
            customize = customize,
            inventory = inventory,
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

    /** 09 decision 7. The confirm dialog is the screen's job and has already been answered. */
    fun rest(kind: RestKind) {
        open.value?.rest(kind)
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
     * A wallet stepper. Locally the four denominations are integer columns, so
     * `WalletRow.propertyId` is never null and there is no insert branch — see
     * `LocalOpenCharacter.adjustCoins`. The screen cannot tell, which is the point.
     */
    fun adjustCoins(coin: CoinKind, delta: Int) {
        val character = open.value ?: return
        character.adjustCoins(character.inventory.value.wallet.row(coin), delta)
    }

    /** The add-item flow. Not undoable here either, and deliberately so — see the impl. */
    fun addItem(spec: NewItemSpec) {
        open.value?.addItem(spec)
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

    /** The customize sheet's ▲/▼. The only mutation that sheet offers for a local character. */
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
