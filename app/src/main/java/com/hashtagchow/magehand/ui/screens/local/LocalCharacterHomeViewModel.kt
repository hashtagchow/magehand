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
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.ui.screens.characterhome.TrackerEvent
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
 */
data class LocalCharacterHomeUiState(
    val characterId: String = "",
    val characterName: String? = null,
    val reference: LocalReferenceState? = null,
    val tracker: TrackerUiState = TrackerUiState(hasConnection = false),
    val customize: TrackerCustomizeState = TrackerCustomizeState(reorderOnly = true),
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

    private val trackerState: Flow<TrackerUiState> = open.flatMapLatest { local ->
        if (local == null) {
            flowOf(TrackerUiState(creatureId = characterId, hasConnection = false))
        } else {
            combine(
                local.board,
                local.canWrite,
                local.canUndo,
                local.writeHistory,
                appSettingsStore.showToggles,
            ) { board, canWrite, canUndo, history, showToggles ->
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
                    showToggles = showToggles,
                    // 09 decision 8, pinned — see TrackerUiState.hasConnection.
                    hasConnection = false,
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
    ) { character, tracker, customize ->
        LocalCharacterHomeUiState(
            characterId = characterId,
            characterName = character?.name,
            reference = LocalReferenceState.from(character),
            tracker = tracker,
            customize = customize,
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

    fun undoLastWrite() {
        val character = open.value ?: return
        viewModelScope.launch { character.undoLastWrite() }
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
