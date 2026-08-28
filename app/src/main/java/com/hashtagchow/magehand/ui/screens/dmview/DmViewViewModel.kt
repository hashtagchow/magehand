package com.hashtagchow.magehand.ui.screens.dmview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.feed.ActivityFeedRepository
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.session.OpenCharacterFactory
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.DmViewStore
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.FeedEntry
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.describe
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toTrackerUiState
import java.time.ZoneId
import javax.inject.Inject

/**
 * FR-19's DM dashboard (docs/design/14-large-screen-arc.md decisions 11–19).
 *
 * ### Session lifecycle: N [OpenCharacter]s on the ONE connection
 *
 * `CharacterHomeViewModel` opens exactly one character; this opens up to
 * [DM_VIEW_MAX_MEMBERS] the *same way*, through the same [OpenCharacterFactory]. That is not an
 * economy, it is decision 17's binding rule — *"N sessions on the one existing DDP connection"*.
 * `DefaultOpenCharacterFactory.open` takes the connection from `DdpConnectionManager`, which
 * holds exactly zero or one client for the active account, so six opens are six
 * `singleCharacter` subscriptions **multiplexed over one socket** and one login. Opening a second
 * connection would double the handshake, double the login and — the part that actually bites —
 * spend twice the shared subscription budget for the same data.
 *
 * ### The set is subscribed ONCE, on entry
 *
 * Decision 17: *"subscribe the set ONCE on entry (burst is fine — 6 ≈ 12% of budget), never
 * tear-down/re-subscribe on pane or card interactions"*. The mechanism is that [members] is read
 * with `first()` in `init` and **never read again**: the stored table is a snapshot taken at
 * entry, not a flow the screen follows. So nothing a DM does on this screen — flipping the
 * editing toggle, tapping a stepper, opening a card and coming back — can reach the subscription
 * layer, because there is no code path from an interaction to `open()`.
 *
 * Changing the table is deliberately a Back and a re-entry (see `DmPickerState`). That reads as a
 * limitation and is a budget decision: the server's subscription rate limit is **50 per 10 s
 * across every user at the table**, so a picker that re-subscribed live would let one DM
 * fidgeting with checkboxes exhaust a bucket five players are also drawing from.
 *
 * The reconnect half of decision 17 — *"re-subscribe with a small stagger and single retry"* — is
 * **not** here, and could not be: reconnect replay belongs to `DdpClient`, which is the only
 * object that knows the whole set of subscriptions on the socket. It is implemented there, and
 * this class benefits from it without naming it.
 *
 * ### Writes (decisions 14 and 18)
 *
 * This class holds no `WriteQueue`, names no DDP method and adds no new one. Every write is an
 * existing [OpenCharacter] intent — `spend`, `restore`, `changeHitPoints`, `toggle`, `rest` — on
 * the per-character session that owns it, so the rate limiting, coalescing, optimistic overlay,
 * rollback and undo stack all apply exactly as they do on the character screen. `WritePostureTest`
 * is unchanged by this feature *because* of that, which is the check that FR-19 widened nothing.
 *
 * ### One queue per character, one budget for all of them
 *
 * Decision 18 describes *"one WriteQueue on the one connection, shared across all cards"*. The
 * built shape is one queue per `CreatureSession` — which is what the existing machinery gives,
 * and reusing it is the same decision's *"the same OpenCharacter intents as the owner path"* —
 * and the sentence's substance holds regardless: **the server-side method budget (5 per 5 s) is
 * shared**, because it is a property of the connection and there is one connection. So six queues
 * can each be under their own client-side gate while collectively outrunning the server's. That
 * is accepted rather than fixed, on decision 18's own reasoning: this is a human DM tapping
 * steppers, not a burst source. The failure mode if it is ever wrong is the honest one — a
 * rate-limit refusal, which `WriteQueue` already retries once and then surfaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DmViewViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val characterListRepository: CharacterListRepository,
    private val dmViewStore: DmViewStore,
    private val openCharacterFactory: OpenCharacterFactory,
    private val appSettingsStore: AppSettingsStore,
    private val activityFeedRepository: ActivityFeedRepository,
) : ViewModel() {


    /**
     * The opened characters, keyed by creature id — populated once in `init` and never added to.
     *
     * A `StateFlow<Map<…>>` rather than a plain map because the cards have to render *before* the
     * opens complete: `open()` suspends on the connection object existing, and a dashboard that
     * showed nothing until all six had opened would show nothing for as long as the slowest one
     * took. The map fills in and the grid fills with it.
     */
    private val sessions = MutableStateFlow<Map<String, OpenCharacter>>(emptyMap())

    /** The resolved membership, in the live list's order. Empty until `init`'s read completes. */
    private val members = MutableStateFlow<List<String>>(emptyList())
    /**
     * FR-25's activity feed (docs/design/16-actions-and-feed.md decisions 8–12).
     *
     * ### Its own `StateFlow`, beside [uiState] rather than inside it
     *
     * [uiState]'s top-level `combine` is already at the five-flow typed ceiling — which is why
     * `error` and `connection` are bundled into a `Quintuple` — so a sixth input would demote it
     * to the untyped overload. But arity is not the real argument; independence is. The feed and
     * the cards share no input and change on different events: an HP tick must not recompute the
     * feed, and a log arriving must not recompute six cards. That is the same reasoning
     * `cardFor` gives for not folding the per-card flows into one big combine, and
     * `CharacterHomeViewModel.panes` gives for keeping chrome out of its uiState.
     *
     * ### No new subscription
     *
     * [ActivityFeedRepository] reads the `creatureLogs` map that the cards' own `singleCharacter`
     * subscriptions are already filling. Decision 8: no extra budget spend. Decision 12's
     * liveness then comes for free — this is the same reactive path the boards ride, so entries
     * arrive without polling and without a refresh control.
     *
     * Keyed on [members], so the feed carries exactly the creatures on this table and nothing
     * else the shared per-connection mirror happens to hold — see `ActivityFeedEngine`'s
     * cross-creature note, which is the one deliberate exception to the mirror-partition rule.
     */
    val feed: StateFlow<List<FeedEntry>> = members
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else activityFeedRepository.feed(ids.toSet())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * Decision 14's toggle. **Not** backed by any store, and that is the feature.
     *
     * A `MutableStateFlow` on the view model means its lifetime is the nav entry's: opening the
     * dashboard, backing out and re-entering gives a fresh `false`, and so does process death.
     * Persisting it — even in `SavedStateHandle` — would make "the DM turned editing on three
     * weeks ago" a thing that survives, which is exactly what decision 14 refuses.
     */
    private val editingEnabled = MutableStateFlow(false)

    /**
     * Cards the **server** has refused a write on (decision 18).
     *
     * Additive and never cleared while the screen lives. A refusal is the server correcting this
     * client's computed capability — admin overrides and server-side share changes are invisible
     * to us — so the honest response is to believe it for the rest of the session rather than to
     * re-offer the control on the next recomposition and collect a second refusal.
     */
    private val permissionDenied = MutableStateFlow<Set<String>>(emptySet())

    /** Decision 18's *"surfaced error"*, newest wins. Cleared when the DM dismisses it. */
    private val refusalError = MutableStateFlow<String?>(null)

    @Volatile
    private var cleared = false

    init {
        viewModelScope.launch {
            // `first()` and not `collect`: decision 17's "subscribe the set ONCE on entry". See
            // the class KDoc for why following the store here would put a rate-limited burst
            // behind an ordinary checkbox.
            val accountId = accountRepository.activeAccount.filterNotNull().first().id
            val stored = dmViewStore.members(DmViewStore.serverKey(accountId)).first()

            // The live list, waited for rather than sampled: the ids are resolved against it
            // (decision 16), and resolving against an empty cache-cold list would open nothing
            // and render a permanently empty dashboard.
            val live = characterListRepository.state
                .map { it.characters }
                .first { it.isNotEmpty() }

            val resolved = resolveDmMembers(stored, live)
            members.value = resolved

            resolved.forEach { creatureId ->
                launch {
                    val opened = openCharacterFactory.open(creatureId) ?: return@launch
                    // The screen can be popped while `open` is still suspended on the socket; an
                    // OpenCharacter nobody holds would keep its subscription forever. Same guard
                    // as `CharacterHomeViewModel`, and it matters more here: six of them.
                    if (cleared) {
                        opened.close()
                        return@launch
                    }
                    // Collection starts BEFORE the session is published, and the order is the
                    // point. `writeFailures` is a hot flow with no replay, so a refusal emitted
                    // in the gap between "the card can be tapped" and "somebody is listening"
                    // is gone — and the two things it would have done, surfacing the server's
                    // sentence and dropping the card to read-only, are exactly what decision 18
                    // forbids losing ("never silence"). Publishing second costs nothing: the
                    // card renders a frame later either way.
                    watchRefusals(creatureId, opened)
                    sessions.value = sessions.value + (creatureId to opened)
                }
            }
        }
    }

    /**
     * Decision 18's refusal path: *"a server `Edit permission denied` … surfaces as an honest
     * error + the card dropping to read-only, never silence."*
     *
     * Both halves, from the one signal. `OpenCharacter.writeFailures` is the same flow the
     * character screen turns into its error snackbar, so a refusal here is reported with the
     * server's own words for `TrackerWriteFailure.describe`'s reason — and, uniquely to this
     * screen, also *changes what is rendered*, because a capability this client computed has just
     * been contradicted by the only authority on it.
     *
     * Non-permission failures are deliberately **not** dropped to read-only. A rate limit or an
     * offline refusal says nothing about whether this DM may edit the sheet, and turning one into
     * a permanent read-only card would take a capability away over a wifi blip.
     */
    private fun watchRefusals(creatureId: String, character: OpenCharacter) {
        viewModelScope.launch {
            character.writeFailures.collect { failure ->
                if (isEditPermissionDenied(failure.reason)) {
                    permissionDenied.value = permissionDenied.value + creatureId
                }
                // The server's own words wherever it gave any — `describe()`'s rule, reused
                // rather than restated. A DM who has just lost write access to a player's sheet
                // needs the server's sentence to know whether to ask the player or blame the
                // network, and this app's paraphrase would take that away.
                refusalError.value = failure.describe()
            }
        }
    }

    /**
     * FR-6's `show_toggles`, which applies to **every** character (09 decision 9: "for ALL
     * characters") and therefore to a card's condition chips too.
     *
     * Read here rather than in the card composable so the gate is on the state the screen renders,
     * which is what `DmCardUiStateTest` can pin — `CharacterHomeViewModel`'s reasoning, applied to
     * six characters at once.
     */
    private val showToggles: Flow<Boolean> = appSettingsStore.showToggles

    /**
     * One character's card, live.
     *
     * A flow per member rather than one big `combine` over all of them, and that shape is load
     * bearing twice: `combine` tops out at five typed flows (the arity problem `Read` and `Prefs`
     * solve on the character screen), and — the real reason — a per-card flow means an HP tick on
     * one character recomputes one card instead of six. On a screen whose entire premise is six
     * live characters, the difference is every frame.
     */
    private fun cardFor(
        creatureId: String,
        summaries: Map<String, CharacterSummary>,
    ): Flow<DmCardUiState> {
        val summary = summaries[creatureId]
        val name = summary?.name.orEmpty()
        val character = sessions.value[creatureId]
            // Before the open completes there is nothing to read, and the card says so. Not an
            // error state: `open()` suspends on the connection object, which on a cold start is a
            // disk read away. The card is LOADING by `DmCardUiState`'s default.
            ?: return flowOf(
                DmCardUiState(
                    creatureId = creatureId,
                    name = name,
                    // FR-21: the portrait comes from the list, so it is on the card before the
                    // subscription readies — a LOADING card shows the face, not a placeholder.
                    portraitUrl = summary?.picture,
                    monogram = summary?.monogram ?: "?",
                    grantedEditing = summary?.isEditableByMe == true,
                ),
            )

        return combine(
            trackerFor(creatureId, character),
            character.inventory.map(::toDmInventorySummary),
            editingEnabled,
            permissionDenied.map { creatureId in it },
        ) { tracker, inventory, editing, denied ->
            toDmCardUiState(
                creatureId = creatureId,
                // From the character list, not the subscription — see `toDmCardUiState`. It is
                // what lets a decision-19 "Not available" card still name whose card it is.
                name = name,
                // FR-21 decision 1: the same two fields, from the same place and for the same
                // reason. `picture` is already `avatarPicture ?: picture` (`toCharacterSummary`).
                portraitUrl = summary?.picture,
                monogram = summary?.monogram ?: "?",
                tracker = tracker,
                inventory = inventory,
                // Decision 18's client-computed capability, straight off `characterList`. A
                // summary this account cannot currently see answers `false`, which is the
                // fail-closed direction.
                isEditableByMe = summary?.isEditableByMe == true,
                editingEnabled = editing,
                permissionDenied = denied,
            )
        }
    }

    /**
     * The **existing** tracker mapping, reused verbatim — decision 12's *"the tracker discovery
     * engine reused per character"*.
     *
     * Deliberately narrower than the character screen's call: no accent colour (a card is not
     * themed per character — six accents in a grid is confetti, and the accent is a *character
     * screen* affordance), no history and no `canUndo`. Undo is not offered on a card at all,
     * and that is decision 14 read strictly: the undo snackbar is a per-character affordance and
     * a dashboard-wide undo would have to answer "undo *whose* last write?", which is a question
     * with a wrong answer available.
     *
     * `canWrite` **is** passed, because it is what dims a control the queue would refuse.
     */
    private fun trackerFor(creatureId: String, character: OpenCharacter): Flow<TrackerUiState> =
        combine(
            character.board,
            character.connectionState,
            character.lastSyncedAt,
            character.isShowingSnapshot,
            combine(character.canWrite, showToggles) { canWrite, toggles -> canWrite to toggles },
        ) { board, connection, syncedAt, showingSnapshot, prefs ->
            toTrackerUiState(
                creatureId = creatureId,
                board = board,
                connection = connection,
                lastSyncedAt = syncedAt,
                isShowingSnapshot = showingSnapshot,
                canWrite = prefs.first,
                zone = ZoneId.systemDefault(),
                showToggles = prefs.second,
            )
        }

    val uiState: StateFlow<DmViewUiState> = combine(
        members,
        sessions,
        characterListRepository.state,
        editingEnabled,
        refusalError,
    ) { memberIds, open, listState, editing, error ->
        Quintuple(memberIds, open, listState.characters, editing, error to listState.connection)
    }.flatMapLatestCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DmViewUiState())

    /**
     * Fans the per-member card flows back into one screen state.
     *
     * An extension on the assembled inputs rather than a sixth `combine` argument, because the
     * card flows only exist once the members are known — this is the same `flatMapLatest`-over-a
     * -late-arriving-key shape `CharacterHomeViewModel.panes` uses, widened from one key to N.
     *
     * The empty-members case short-circuits to a plain state rather than `combine(emptyList())`,
     * which never emits — the defect being that the screen would sit on its seed forever for an
     * account whose stored table resolved to nothing.
     */
    private fun Flow<Quintuple>.flatMapLatestCards(): Flow<DmViewUiState> =
        flatMapLatest { (memberIds, _, summaries, editing, rest) ->
            val (error, connection) = rest
            val byId = summaries.associateBy { it.creatureId }
            val canEditAny = memberIds.any { byId[it]?.isEditableByMe == true }
            if (memberIds.isEmpty()) {
                flowOf(
                    DmViewUiState(
                        connection = connection,
                        editingEnabled = editing,
                        canEditAnyCard = false,
                        error = error,
                    ),
                )
            } else {
                combine(memberIds.map { cardFor(it, byId) }) { cards ->
                    DmViewUiState(
                        cards = cards.toList(),
                        connection = connection,
                        editingEnabled = editing,
                        canEditAnyCard = canEditAny,
                        error = error,
                    )
                }
            }
        }

    // --- the editing toggle (decision 14) ------------------------------------------

    /**
     * The top bar's "Enable editing".
     *
     * Turning it **off** also clears any standing refusal message: the error was about a write
     * the DM can no longer attempt, and leaving it above a read-only grid would be a warning
     * about nothing. The read-only *cards* are not cleared — see [permissionDenied] for why a
     * refusal is believed for the session.
     */
    fun setEditingEnabled(enabled: Boolean) {
        editingEnabled.value = enabled
        if (!enabled) refusalError.value = null
    }

    /** The error banner's dismiss. The card stays read-only; only the sentence goes. */
    fun dismissError() {
        refusalError.value = null
    }

    // --- card writes (decisions 14 and 18) -----------------------------------------
    //
    // Every one of these is an existing OpenCharacter intent on the session that owns the
    // creature. No new intent, no new method, no new queue — which is why `WritePostureTest`
    // needed no edit for this feature, and why that test still proves what it says it does.

    /** A filled pip on a card was tapped. */
    fun spend(creatureId: String, propertyId: String, amount: Int = 1) =
        withRow(creatureId, propertyId) { character, row -> character.spend(row, amount) }

    /** An empty pip on a card was tapped. */
    fun restore(creatureId: String, propertyId: String, amount: Int = 1) =
        withRow(creatureId, propertyId) { character, row -> character.restore(row, amount) }

    /** A card's HP steppers. Negative damages. */
    fun changeHitPoints(creatureId: String, delta: Int) {
        writable(creatureId)?.changeHitPoints(delta)
    }

    /** A condition chip on a card (or the concentration banner's ✕, which is the same write). */
    fun toggleCondition(creatureId: String, propertyId: String) {
        val character = writable(creatureId) ?: return
        val toggle = character.board.value.activeToggles
            .firstOrNull { it.propertyId == propertyId } ?: return
        character.toggle(toggle)
    }

    /**
     * Short or long rest, from a card.
     *
     * Not undoable, so the confirm dialog is the safety mechanism and the screen has already
     * shown it — `CharacterHomeViewModel.rest`'s contract, unchanged. It matters more here: the
     * dialog has to name *which* character is resting, because a DM looking at six cards has six
     * candidates and the wrong one cannot be taken back.
     */
    fun rest(creatureId: String, kind: RestKind) {
        writable(creatureId)?.rest(kind)
    }

    /**
     * The session for a card the DM is currently **allowed** to write to, or `null`.
     *
     * The gate again, at the last possible moment. The composable already refuses to draw a
     * control when `showsWriteControls` is false, so in practice nothing reaches here that should
     * not — and that is exactly the reasoning that stops being true quietly, which is why this is
     * the second gate rather than the only one. Three ways it could be reached wrongly: a stale
     * recomposition holding a callback from the frame before the toggle went off, a refusal
     * landing between the tap and the dispatch, and a future caller.
     *
     * Composed from [uiState] rather than from the raw flags so there is one rule, not two: the
     * card's own `showsWriteControls` is what is checked, so this cannot drift from what was
     * drawn.
     */
    private fun writable(creatureId: String): OpenCharacter? {
        val card = uiState.value.cards.firstOrNull { it.creatureId == creatureId } ?: return null
        if (!card.showsWriteControls) return null
        return sessions.value[creatureId]
    }

    /**
     * Looks a tapped row up on that card's own board.
     *
     * `CharacterHomeViewModel.withRow`'s contract, with the creature id added: the UI hands back
     * a `propertyId` and nothing else, so a stale row resolves to nothing and is dropped rather
     * than written blind. The board is already overlay-adjusted, so the clamps in `OpenCharacter`
     * see the value the DM is looking at.
     *
     * The id is looked up **within the named creature's board only**. That is not an
     * optimisation: property ids are globally unique on this server, but a search across six
     * boards for "whichever character has this property" is a function that could return the
     * wrong character, and on this screen the wrong character is somebody else's hit points.
     */
    private inline fun withRow(
        creatureId: String,
        propertyId: String,
        act: (OpenCharacter, TrackedResource) -> Unit,
    ) {
        val character = writable(creatureId) ?: return
        val board = character.board.value
        val row = (board.slots + board.resources + board.allItems + listOfNotNull(board.hp))
            .firstOrNull { it.propertyId == propertyId } ?: return
        act(character, row)
    }

    /**
     * 06 step 2, applied to every open card: re-serialize each mirror into the snapshot cache
     * when the app backgrounds.
     *
     * All of them rather than a focused one, because there is no focus on this screen — six cards
     * are equally on screen, and a dashboard that cached one of them would make the next cold
     * open show five spinners and one sheet.
     *
     * Called from `DmViewScreen`'s `ON_STOP` observer — `CharacterHomeScreen`'s hook, verbatim.
     * It shipped without one, which is a leak of a different kind: six live mirrors that were
     * never serialized, so the characters a DM had just been staring at were exactly the ones
     * whose next cold open had nothing cached. `DmViewUiStateTest` pins the caller, because dead
     * code with a KDoc on it is indistinguishable from wired code at a glance.
     */
    fun captureSnapshots() {
        val open = sessions.value.values.toList()
        viewModelScope.launch { open.forEach { it.captureSnapshot() } }
    }

    override fun onCleared() {
        cleared = true
        val open = sessions.value.values.toList()
        if (open.isEmpty()) return
        // `viewModelScope` is already cancelled by the time this runs, so closing has to happen
        // somewhere else — `CharacterHomeViewModel.onCleared`'s mechanism. Every session is
        // closed, and that is what releases N `singleCharacter` subscriptions back to the shared
        // budget; leaking even one would go unnoticed until the table hit the rate limit for
        // reasons nobody could trace to a screen that had been closed.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { open.forEach { it.close() } }
    }

    /**
     * The five screen-level inputs, bundled.
     *
     * `combine` tops out at five typed flows before it degenerates into an `Array<Any?>` with
     * unchecked casts — `CharacterHomeViewModel.Read`'s reason — and this screen has six things to
     * watch, so the connection rides along with the error in a pair. They belong together: both
     * are "something the screen says above the grid".
     */
    private data class Quintuple(
        val members: List<String>,
        val sessions: Map<String, OpenCharacter>,
        val summaries: List<CharacterSummary>,
        val editing: Boolean,
        val errorAndConnection: Pair<String?, ConnectionState>,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
