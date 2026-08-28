package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.db.TrackerPrefEntity
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.ActionEngine
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.data.tracker.isTrue
import com.hashtagchow.magehand.core.data.write.OptimisticOverlay
import com.hashtagchow.magehand.core.data.write.WriteQueue
import com.hashtagchow.magehand.core.data.write.WriteQueueConfig
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class CreatureSessionConfig(
    /**
     * How long the reconnect loop may run before the session calls it
     * [ConnectionState.OFFLINE] — 06's "retries exhausted-for-now".
     *
     * WP2's backoff is 1 s → 60 s, so a real outage crosses this within the first few
     * attempts, while a wifi hiccup that recovers in a couple of seconds never shows the
     * offline banner at all.
     */
    val offlineAfter: Duration = 20.seconds,
    /**
     * The same idea as [offlineAfter], applied to the *other* road to `OFFLINE`: the
     * device telling us it has no network at all.
     *
     * That branch used to publish `OFFLINE` on the very first `false`, and it is the one
     * state the UI treats as terminal — `ConnectionStatus.isTerminalUntilActedOn`, which
     * is what puts the connection dot on screen even over a cold open's spinner. So a
     * `ConnectivityManager` that reports `false` for a moment (an interface handover, or
     * the first callback arriving before the current network is known) painted a red
     * "not live" dot for a frame and then took it away again — a mark that means
     * "something is wrong", shown at the one moment nothing is.
     *
     * A grace period is the same answer [offlineAfter] already gives for a flapping
     * socket, and it costs the user nothing: the reconnect loop is running throughout, so
     * the state during the grace is `CONNECTING`, which is true.
     *
     * Two seconds, because it has to outlast a wifi→cellular handover (hundreds of ms,
     * occasionally over a second) without approaching [offlineAfter] — this branch exists
     * precisely so a genuinely networkless device does not wait out the full timeout, and
     * 2 s keeps ~90% of that.
     */
    val offlineOnNetworkLossAfter: Duration = 2.seconds,
    val writeQueue: WriteQueueConfig = WriteQueueConfig(),
)

/**
 * One open character screen.
 *
 * Owns the `singleCharacter` subscription (through [CreatureFeed]), the snapshot fallback,
 * the tracker preference layer and the [WriteQueue], and publishes exactly two things the
 * UI needs: [connectionState] and [board].
 *
 * The read path, in the order 06-offline-and-sync.md specifies:
 *
 * ```
 * Room snapshot (inflate) ─┐
 *                          ├─► TrackerEngine ─► optimistic overlay ─► board
 * DDP mirror (wins) ───────┘        ▲
 *                          tracker_prefs ──────┘
 * ```
 *
 * "Mirror state always wins over snapshot" is implemented literally: the snapshot is used
 * only while the mirror holds no properties for this creature.
 *
 * Plain constructor by design; the sibling work package owns the Hilt wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatureSession(
    val accountId: String,
    val creatureId: String,
    private val feed: CreatureFeed,
    private val snapshotStore: SnapshotStore,
    private val trackerPrefDao: TrackerPrefDao,
    private val scope: CoroutineScope,
    /**
     * Whether the device believes it has a network at all. Wired to `ConnectivityManager`
     * by the UI layer; `true` by default so a caller that does not care still gets the
     * timeout-driven OFFLINE.
     */
    networkAvailable: StateFlow<Boolean> = MutableStateFlow(true),
    private val config: CreatureSessionConfig = CreatureSessionConfig(),
    /** Injected so [lastSyncedAt]'s stamps are assertable; same convention as `SnapshotStore`. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Set when the caller wants [refreshSnapshot] to be able to do a REST fetch. */
    var serverUrl: String? = null
    var tokenProvider: (suspend () -> String?)? = null

    private val _snapshot = MutableStateFlow<CreatureSheet?>(null)
    private val _lastSyncedAt = MutableStateFlow<Long?>(null)

    /**
     * Epoch millis of **the last moment this creature's data was known to be current** —
     * what the connection sheet renders as *"Last synced at HH:MM"*.
     *
     * ### Why the subscription stamps this, and not only the snapshot
     *
     * It used to be stamped by [loadSnapshot], [refreshSnapshot] and [captureSnapshot]
     * alone, i.e. only when a *copy* was written to Room. A session that went live at
     * 13:00 and dropped at 15:00 therefore told the user "last synced at 13:00", when the
     * mirror had in fact been current until seconds earlier. The sheet's sentence says
     * *synced*, not *saved*, so that was simply wrong.
     *
     * A ready `singleCharacter` subscription **is** currency: Meteor sends the initial
     * document set before `ready`, and every later change arrives as it happens. So the
     * window during which the data is current opens when the sub goes ready and closes
     * when it stops being ready — and those two edges are the only moments worth
     * recording, because in between the answer is continuously "now".
     *
     * ### Why not stamp on each live update batch
     *
     * Because that would quietly redefine the string as *"last time something changed"*.
     * A character nobody touched for an hour of a LIVE session would then read as an hour
     * stale, which is the same lie in the opposite direction — and the batch that does
     * arrive says nothing stronger about currency than the readiness that preceded it.
     *
     * `null` until something has actually synced: never a fabricated "now" at
     * construction, which is what the sheet's "has never finished syncing" line depends
     * on, and why the collector below drops the readiness flow's leading `false`.
     */
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    init {
        scope.launch {
            // `dropWhile` skips the StateFlow's initial `false` — a session that never
            // reaches the server must keep reporting "never synced". After that, every
            // edge stamps: `true` opens the current-data window, `false` closes it at the
            // moment it closed rather than leaving the last *saved* time standing.
            feed.isReady.dropWhile { !it }.collect { _lastSyncedAt.value = now() }
        }
    }

    /**
     * 06's four-state model, completed.
     *
     * `DdpClient` emits only `CONNECTING`, `LIVE` and `AUTH_FAILED`
     * (docs/verification/WP2.md deviation #1); deriving `OFFLINE` is this layer's job,
     * because only the data layer knows there is a snapshot to fall back to.
     *
     * Also note `LIVE` here means connected **and the subscription is ready** — 06's own
     * definition — which `DdpClient` deliberately does not implement at the connection
     * level (WP2 deviation #2).
     */
    val connectionState: StateFlow<ConnectionState> =
        combine(feed.connectionState, feed.isReady, networkAvailable) { state, ready, network ->
            Triple(state, ready, network)
        }
            .distinctUntilChanged()
            .flatMapLatest { (state, ready, network) ->
                when {
                    state == ConnectionState.AUTH_FAILED -> flowOf(ConnectionState.AUTH_FAILED)
                    state == ConnectionState.LIVE && ready -> flowOf(ConnectionState.LIVE)
                    // Connected but the publication has not finished: still "connecting"
                    // from the user's point of view, and writes stay refused.
                    state == ConnectionState.LIVE -> flowOf(ConnectionState.CONNECTING)
                    // No network *and* no socket: still OFFLINE far sooner than the
                    // timeout below, but not on the first frame of a `false` — see
                    // CreatureSessionConfig.offlineOnNetworkLossAfter.
                    //
                    // No leading `emit(CONNECTING)`, deliberately, and unlike the branch
                    // below. `stateIn` holds the previous value across a `flatMapLatest`
                    // switch, so the grace period is served by *silence* — whatever was
                    // true a moment ago stays true until the delay elapses. Emitting
                    // CONNECTING here instead **overwrote** a state that had already
                    // settled: a router that is dead while wi-fi stays associated reaches
                    // OFFLINE through the branch below (`offlineAfter`), and then
                    // `ConnectivityManager` notices and flips `network` to false — a
                    // *late* flip, long after the user has been told they are offline.
                    // That re-entered this branch and put CONNECTING back for two full
                    // seconds, un-telling them. The cold-open case the emit was there for
                    // is already covered: `stateIn`'s initial value is CONNECTING.
                    !network -> flow {
                        delay(config.offlineOnNetworkLossAfter)
                        emit(ConnectionState.OFFLINE)
                    }
                    else -> flow {
                        emit(ConnectionState.CONNECTING)
                        delay(config.offlineAfter)
                        emit(ConnectionState.OFFLINE)
                    }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.CONNECTING)

    /**
     * Writes for this character. Refuses everything unless [connectionState] is
     * [ConnectionState.LIVE] — which, per above, also means the subscription is ready, so
     * a write can never be aimed at a sheet we have not actually loaded.
     */
    val writeQueue: WriteQueue = WriteQueue(
        caller = feed,
        connectionState = connectionState,
        scope = scope,
        config = config.writeQueue,
    )

    val overrides: Flow<List<TrackerOverride>> =
        trackerPrefDao.observe(accountId, creatureId).map { rows -> rows.map { it.toDomain() } }

    /**
     * The same flow, but pre-seeded with "no overrides".
     *
     * [board] combines four sources and cannot emit until every one of them has. Gating
     * the first render on a Room round-trip would mean a blank tracker for as long as
     * SQLite takes to answer, for a table that is empty on almost every character.
     */
    private val overridesForBoard: Flow<List<TrackerOverride>> =
        overrides.onStart { emit(emptyList()) }.distinctUntilChanged()

    /** The mirror, assembled into an engine input. */
    private val liveSheet: Flow<CreatureSheet> = combine(
        feed.documents(CreatureSheet.CREATURE_PROPERTIES),
        feed.documents(CreatureSheet.CREATURES),
        feed.documents(CreatureSheet.CREATURE_VARIABLES),
    ) { properties, creatures, variables ->
        CreatureSheet.fromMirror(properties, creatures, variables, creatureId)
    }

    /**
     * The engine's input: the mirror when it holds anything, the Room snapshot otherwise.
     * 06's "mirror state always wins over snapshot", in one place so the two boards below
     * cannot disagree about which source they are reading.
     *
     * **"Holds anything" means anything *live*.** The test was `properties.isNotEmpty()`,
     * which counts soft-deleted documents — and DiceCloud delivers those (10's probe facts;
     * `CreatureSheet.livePropertyList`). A mirror carrying nothing but `removed:true` docs
     * would therefore win against a perfectly good cached snapshot and render an empty
     * tracker. Narrow, but the failure mode is the worst shape available: a blank screen
     * where the offline path had real data, with no error to explain it.
     */
    private val sheet: StateFlow<CreatureSheet> = combine(liveSheet, _snapshot) { live, snapshot ->
        if (live.hasLiveProperties) live else snapshot ?: CreatureSheet.EMPTY
    }.stateIn(scope, SharingStarted.Eagerly, CreatureSheet.EMPTY)

    /**
     * The sheet as it stands right now, for the writes that have to *read* it before they can
     * be built — `creatureProperties.insert` needs a parent id and an `order`, and both are
     * facts about the current tree rather than about the row that was tapped.
     *
     * A `StateFlow` rather than the cold `Flow` this used to be, so there is a `.value` to
     * ask at all. `Eagerly` matches [board], which was already collecting the same upstream,
     * so this shares that work rather than doubling it.
     */
    internal val currentSheet: CreatureSheet get() = sheet.value

    /** What the inventory tab renders (docs/design/10-inventory.md). */
    val inventory: StateFlow<InventoryBoard> = sheet
        .map { InventoryEngine.build(it) }
        .stateIn(scope, SharingStarted.Eagerly, InventoryBoard.EMPTY)

    /**
     * What the Actions surface renders (docs/design/16-actions-and-feed.md, FR-26).
     *
     * ### `WhileSubscribed`, where [inventory] is `Eagerly`
     *
     * The two are not inconsistent; they have different readers. The inventory board is read by
     * the DM dashboard's card summary as well as by the inventory tab, so it is warm on every
     * open session. **Nothing reads this one unless an Actions surface is on screen** — the DM
     * view has no actions column, and a character screen holds the subscription only while the
     * tab or pane is drawn. Eager derivation would therefore run this engine on every mirror
     * frame of all six DM sessions to produce a board no composable would ever collect.
     *
     * The grace period is `boardIgnoringHidden`'s, for its reason: a tab switch or a pane toggle
     * must not drop and rebuild the board, and five seconds outlasts any of those while still
     * releasing on a real navigation away.
     *
     * ### Read-only, and no override layer
     *
     * Unlike [board] this takes no `overrides` and no `optimistic` overlay. Neither has anything
     * to say here: 16 decision 7 makes the surface read-only so there is no optimistic write to
     * overlay, and the customize sheet offers no control that could hide or pin a spell — the
     * same argument `TrackerEngine.orderRolls` makes for rolls, one surface further out.
     */
    val actions: StateFlow<ActionBoard> = sheet
        .map { ActionEngine.build(it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(UNHIDDEN_BOARD_GRACE_MILLIS), ActionBoard.EMPTY)

    /** What the tracker renders. */
    val board: StateFlow<TrackerBoard> = combine(
        sheet,
        overridesForBoard,
        writeQueue.optimistic,
    ) { sheet, prefs, overlay ->
        overlay.applyTo(TrackerEngine.build(sheet, prefs))
    }.stateIn(scope, SharingStarted.Eagerly, TrackerBoard.EMPTY)

    /**
     * The same board with the **hide** layer suppressed; pins and ordering still apply.
     *
     * The customize sheet (04 §5) has a "hidden section" the user un-hides from, and a
     * hidden row is by construction absent from [board] — so the sheet cannot be built
     * from it. `WhileSubscribed` because nothing collects this until that sheet opens.
     */
    val boardIgnoringHidden: StateFlow<TrackerBoard> = combine(
        sheet,
        overridesForBoard,
    ) { sheet, prefs ->
        TrackerEngine.build(sheet, prefs.map { it.copy(hidden = false) })
    }.stateIn(scope, SharingStarted.WhileSubscribed(UNHIDDEN_BOARD_GRACE_MILLIS), TrackerBoard.EMPTY)

    /**
     * True while the board is coming from Room rather than the live mirror.
     *
     * Counts only *live* documents, for the same reason [sheet] does and so that the two
     * cannot disagree: this flag's whole job is to say which of those two branches was taken,
     * and a mirror of nothing but soft-deleted docs would make it claim "live" over a board
     * that came from the snapshot.
     */
    val isShowingSnapshot: StateFlow<Boolean> = combine(
        feed.documents(CreatureSheet.CREATURE_PROPERTIES),
        _snapshot,
    ) { properties, snapshot ->
        properties.values.none { !it.isTrue(CreatureSheet.REMOVED) } && snapshot != null
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Opens the session: shows the cached snapshot immediately (06 step 3), then hands
     * over to the live subscription (06 step 1).
     *
     * Loading the snapshot first is deliberate — a character screen that renders in
     * milliseconds from Room and then quietly upgrades to live is the whole point of
     * caching it.
     */
    suspend fun start() {
        loadSnapshot()
        feed.start()
    }

    /** Re-reads the cached snapshot from Room without touching the network. */
    suspend fun loadSnapshot() {
        val cached = snapshotStore.load(accountId, creatureId) ?: return
        _snapshot.value = cached.sheet
        _lastSyncedAt.value = cached.fetchedAt
    }

    /**
     * REST-refreshes the snapshot (06 step 1). Requires [serverUrl] and [tokenProvider].
     *
     * Returns `false` when there is no token or no server to ask — never throws for that
     * reason, because "we are not signed in yet" is not an error the tracker can act on.
     * A genuine transport failure still propagates as an `ApiException`.
     */
    suspend fun refreshSnapshot(): Boolean {
        val url = serverUrl ?: return false
        val token = tokenProvider?.invoke() ?: return false
        val cached = snapshotStore.refresh(accountId, url, token, creatureId)
        _snapshot.value = cached.sheet
        _lastSyncedAt.value = cached.fetchedAt
        return true
    }

    /**
     * Serializes the live mirror into the snapshot cache — 06 step 2, "mirror → snapshot
     * refresh on every app-background". No network involved.
     *
     * Returns `false` when the mirror is empty, so a background event that arrives before
     * the subscription is ready cannot overwrite a good snapshot with nothing.
     *
     * "Empty" again means *no live documents*: overwriting a real snapshot with a body
     * holding only soft-deleted properties is precisely the "overwrite a good snapshot with
     * nothing" this guard exists to prevent, and the unfiltered count let it through. The
     * body that gets stored is still the whole mirror, soft-deletes included — a snapshot is
     * a faithful copy of the source, and filtering it on the way to disk would make the
     * cached sheet disagree with the REST body it is standing in for.
     */
    suspend fun captureSnapshot(): Boolean {
        val sheet = CreatureSheet.fromMirror(
            properties = feed.documents(CreatureSheet.CREATURE_PROPERTIES).value,
            creatures = feed.documents(CreatureSheet.CREATURES).value,
            variables = feed.documents(CreatureSheet.CREATURE_VARIABLES).value,
            creatureId = creatureId,
        )
        if (!sheet.hasLiveProperties) return false
        val stored = snapshotStore.store(accountId, creatureId, sheet.toSnapshotBody())
        _snapshot.value = sheet
        _lastSyncedAt.value = stored.fetchedAt
        return true
    }

    // --- override layer (03 §6) ---------------------------------------------

    suspend fun setOverride(override: TrackerOverride) = trackerPrefDao.upsert(
        TrackerPrefEntity(
            accountId = accountId,
            creatureId = creatureId,
            propertyId = override.propertyId,
            pinned = override.pinned,
            hidden = override.hidden,
            sortIndex = override.sortIndex,
        ),
    )

    suspend fun clearOverride(propertyId: String) =
        trackerPrefDao.delete(accountId, creatureId, propertyId)

    /** Stops the subscription and the write queue. The DDP client outlives the session. */
    suspend fun close() {
        writeQueue.close()
        feed.stop()
    }

    /** Only for diagnostics/tests — the overlay is already folded into [board]. */
    val optimistic: StateFlow<OptimisticOverlay> get() = writeQueue.optimistic

    private companion object {
        /** Survives a recomposition / configuration change without rebuilding the board. */
        const val UNHIDDEN_BOARD_GRACE_MILLIS = 5_000L
    }
}
