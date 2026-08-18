package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.db.ThemePrefDao
import com.hashtagchow.magehand.core.data.db.ThemePrefEntity
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.db.toEntity
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.write.WriteFailure
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteQueue
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * One opened character, as the **UI layer** sees it.
 *
 * ### Why this exists rather than handing `:app` a [CreatureSession]
 *
 * Two reasons, one structural and one about WP6's read-only posture.
 *
 * *Structural.* `:core:data` takes Room, OkHttp and DataStore as `implementation`
 * dependencies, so those types are not on `:app`'s compile classpath (the reason WP5's
 * `DataModule` refuses to bind `OkHttpClient`). [CreatureSession]'s constructor names
 * `SnapshotStore` and `TrackerPrefDao`; this interface names nothing but `:core:model`
 * types and `StateFlow`, so `:app` can hold it without the classpath leaking.
 *
 * *Write posture (WP7).* [CreatureSession.writeQueue] is still deliberately **not**
 * re-exported. WP6's posture was "the UI cannot write at all"; WP7's is narrower and has to
 * survive the tracker becoming writable: **the UI can only write through the intents named
 * below, and every one of them goes through the [WriteQueue]**. That is what keeps the
 * rate limiting, the coalescing, the optimistic overlay and the undo stack unbypassable —
 * a composable holding a `WriteQueue` could call `submit` with a hand-built op and skip
 * none of it, but a composable holding an `OpenCharacter` cannot construct a DDP method
 * call at all: the method names live in `:core:data`. `WritePostureTest` pins both halves.
 */
interface OpenCharacter {

    val accountId: String
    val creatureId: String

    /** The account's https origin — the Sheet tab's target and the REST base. */
    val serverOrigin: String

    /** What the tracker renders (docs/design/04-screens-ux.md §3). */
    val board: StateFlow<TrackerBoard>

    /**
     * [board] with the hide layer suppressed. The customize sheet lists rows the tracker
     * is hiding so they can be un-hidden — they are absent from [board] by construction.
     */
    val boardIgnoringHidden: StateFlow<TrackerBoard>

    /** 06's four-state model, including `OFFLINE`. Drives the status strip. */
    val connectionState: StateFlow<ConnectionState>

    /** Epoch millis of the cached snapshot — the "synced HH:MM" half of the strip. */
    val lastSyncedAt: StateFlow<Long?>

    /** True while [board] is coming from the Room snapshot rather than the live mirror. */
    val isShowingSnapshot: StateFlow<Boolean>

    /** The local pin / hide / reorder layer, for the customize sheet (04 §5). */
    val overrides: StateFlow<List<TrackerOverride>>

    /** `"#RRGGBB"` from `theme_prefs`, or `null` for the app default (04 §6). */
    val accentColor: StateFlow<String?>

    // --- writes (04 §3) -----------------------------------------------------

    /**
     * Whether a tap may reach the server right now — `connectionState == LIVE`, which per
     * [CreatureSession] also means the `singleCharacter` subscription is ready.
     *
     * The queue refuses non-LIVE writes anyway; this exists so the controls can be *dimmed*
     * rather than silently swallowing taps (04 §UX principles: "connection state is always
     * visible, never a surprise error dialog").
     */
    val canWrite: StateFlow<Boolean>

    /** This session's dispatched writes, newest first (04 §3's undo history sheet). */
    val writeHistory: StateFlow<List<TrackerWrite>>

    /** True while [undoLastWrite] has something to reverse. */
    val canUndo: StateFlow<Boolean>

    /** Every rolled-back write, for the shake animation and the error snackbar. */
    val writeFailures: Flow<TrackerWriteFailure>

    /** Spend [amount] charges of a slot or resource row (a filled pip tapped). */
    fun spend(row: TrackedResource, amount: Int = 1)

    /** Restore [amount] charges (an empty pip tapped). */
    fun restore(row: TrackedResource, amount: Int = 1)

    /** Move HP by [delta]: negative takes damage, positive heals. The server clamps. */
    fun changeHitPoints(delta: Int)

    /** Set the HP row to an absolute value — the number pad's third option. */
    fun setHitPoints(value: Int)

    /** Move a pinned item's quantity by [delta] (the consumable steppers). */
    fun adjustItem(item: TrackedResource, delta: Int)

    /** Flip a condition toggle (a chip tapped). */
    fun toggle(condition: ConditionToggle)

    /**
     * Short or long rest. **Not undoable** — the server applies every reset and every
     * trigger — so 04 §3 requires a confirm dialog first, and this call assumes the user
     * has already seen it.
     */
    fun rest(kind: RestKind)

    /** Reverses the newest undoable write. Returns false when there is nothing to undo. */
    suspend fun undoLastWrite(): Boolean

    suspend fun setOverride(override: TrackerOverride)

    /** Applies a whole reordering in one transaction, so no intermediate order ever renders. */
    suspend fun setOverrides(overrides: List<TrackerOverride>)

    suspend fun clearOverride(propertyId: String)

    suspend fun setAccentColor(hex: String?)

    /** 06 step 2: serialize the live mirror into the snapshot cache on app-background. */
    suspend fun captureSnapshot(): Boolean

    /** Stops the subscription and cancels this character's scope. Idempotent. */
    suspend fun close()
}

/**
 * Opens characters. `null` from [open] means "no account is signed in", which is a
 * navigation problem, not an error the tracker can render.
 */
interface OpenCharacterFactory {
    suspend fun open(creatureId: String): OpenCharacter?
}

/**
 * Production [OpenCharacterFactory].
 *
 * The lifecycle 04 §3 implies and WP6's brief states: **create on enter, close on exit**.
 * Each open builds a private [CoroutineScope]; [DefaultOpenCharacter.close] cancels it,
 * which is what tears down the `singleCharacter` subscription, the board's `stateIn` and
 * the (unreachable) write queue together. The [DdpClient] itself is *not* touched — it
 * belongs to the account and the character selector is still using it.
 */
class DefaultOpenCharacterFactory(
    private val connectionManager: DdpConnectionManager,
    private val accountRepository: AccountRepository,
    private val snapshotStore: SnapshotStore,
    private val trackerPrefDao: TrackerPrefDao,
    private val themePrefDao: ThemePrefDao,
    private val characterCache: CharacterCache,
    private val now: () -> Long = System::currentTimeMillis,
) : OpenCharacterFactory {

    override suspend fun open(creatureId: String): OpenCharacter {
        // Suspends until the account's socket object exists. It does not wait for the
        // socket to be *live*: rendering the Room snapshot while CONNECTING is the whole
        // point of 06's fallback.
        val connection = connectionManager.connection.filterNotNull().first()
        val account = connection.account

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("character-$creatureId"),
        )

        val session = CreatureSession(
            accountId = account.id,
            creatureId = creatureId,
            feed = DdpCreatureFeed(connection.client, creatureId, scope),
            snapshotStore = snapshotStore,
            trackerPrefDao = trackerPrefDao,
            scope = scope,
        ).apply {
            serverUrl = account.serverUrl
            tokenProvider = { accountRepository.tokenFor(account.id) }
        }

        try {
            val open = DefaultOpenCharacter(
                session = session,
                scope = scope,
                serverOrigin = account.serverUrl,
                themePrefDao = themePrefDao,
                trackerPrefDao = trackerPrefDao,
            )

            // Ordering matters: `start()` renders the cached snapshot and subscribes, so
            // the screen has content before any network round trip completes.
            session.start()

            scope.launch { characterCache.markOpened(account.id, creatureId, now()) }
            scope.launch {
                // 06 step 1's REST top-up. Read-only (`GET /api/creature/:id`) and best
                // effort: a failure here just leaves the older snapshot in place, and the
                // live subscription is the authority anyway.
                runCatching { session.refreshSnapshot() }
            }

            return open
        } catch (t: Throwable) {
            // Including cancellation: the caller navigated away mid-open, and a scope
            // nobody holds a reference to would keep the subscription alive forever.
            scope.cancel()
            throw t
        }
    }
}

internal class DefaultOpenCharacter(
    private val session: CreatureSession,
    private val scope: CoroutineScope,
    override val serverOrigin: String,
    private val themePrefDao: ThemePrefDao,
    private val trackerPrefDao: TrackerPrefDao,
) : OpenCharacter {

    override val accountId: String get() = session.accountId
    override val creatureId: String get() = session.creatureId

    override val board: StateFlow<TrackerBoard> get() = session.board
    override val boardIgnoringHidden: StateFlow<TrackerBoard> get() = session.boardIgnoringHidden
    override val connectionState: StateFlow<ConnectionState> get() = session.connectionState
    override val lastSyncedAt: StateFlow<Long?> get() = session.lastSyncedAt
    override val isShowingSnapshot: StateFlow<Boolean> get() = session.isShowingSnapshot

    override val overrides: StateFlow<List<TrackerOverride>> =
        session.overrides.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val accentColor: StateFlow<String?> =
        themePrefDao.observe(session.accountId, session.creatureId)
            .map { it?.accentColor }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // --- writes -------------------------------------------------------------

    override val canWrite: StateFlow<Boolean> = session.connectionState
        .map { it == ConnectionState.LIVE }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val writeHistory: StateFlow<List<TrackerWrite>> get() = session.writeQueue.history
    override val canUndo: StateFlow<Boolean> get() = session.writeQueue.canUndo

    override val writeFailures: Flow<TrackerWriteFailure> =
        session.writeQueue.failures.map { it.toDomain() }

    /**
     * The clamps live here rather than in the composable so that every caller — including a
     * press-and-hold that outruns the server — gets them. Spending a row that already reads
     * zero would send `damage +1` past the floor; DiceCloud would accept it and the row
     * would come back needing two restores to show one charge.
     */
    override fun spend(row: TrackedResource, amount: Int) {
        if (amount <= 0 || row.value <= 0) return
        session.writeQueue.submit(WriteOp.spend(row, amount.coerceAtMost(row.value)))
    }

    override fun restore(row: TrackedResource, amount: Int) {
        if (amount <= 0) return
        val room = (row.total - row.value).coerceAtLeast(0)
        if (room == 0) return
        session.writeQueue.submit(WriteOp.restore(row, amount.coerceAtMost(room)))
    }

    override fun changeHitPoints(delta: Int) {
        val hp = session.board.value.hp ?: return
        when {
            delta < 0 -> session.writeQueue.submit(WriteOp.takeDamage(hp, -delta))
            delta > 0 -> session.writeQueue.submit(WriteOp.heal(hp, delta))
        }
    }

    override fun setHitPoints(value: Int) {
        val hp = session.board.value.hp ?: return
        session.writeQueue.submit(WriteOp.setValue(hp, value.coerceIn(0, hp.total)))
    }

    override fun adjustItem(item: TrackedResource, delta: Int) {
        if (delta == 0) return
        // An item cannot go below zero, and the server would happily store −1.
        if (delta < 0 && item.value <= 0) return
        val bounded = if (delta < 0) -minOf(-delta, item.value) else delta
        session.writeQueue.submit(WriteOp.adjust(item, bounded))
    }

    override fun toggle(condition: ConditionToggle) {
        session.writeQueue.submit(WriteOp.flip(condition))
    }

    override fun rest(kind: RestKind) {
        session.writeQueue.submit(WriteOp.rest(session.creatureId, kind))
    }

    override suspend fun undoLastWrite(): Boolean = session.writeQueue.undo()

    private val closed = MutableStateFlow(false)

    /** Exposed for tests; nothing in the UI needs to ask. */
    val isClosed: StateFlow<Boolean> = closed.asStateFlow()

    override suspend fun setOverride(override: TrackerOverride) = session.setOverride(override)

    override suspend fun setOverrides(overrides: List<TrackerOverride>) =
        trackerPrefDao.upsert(overrides.map { it.toEntity(session.accountId, session.creatureId) })

    override suspend fun clearOverride(propertyId: String) = session.clearOverride(propertyId)

    override suspend fun setAccentColor(hex: String?) = themePrefDao.upsert(
        ThemePrefEntity(
            accountId = session.accountId,
            creatureId = session.creatureId,
            accentColor = hex,
        ),
    )

    override suspend fun captureSnapshot(): Boolean = session.captureSnapshot()

    override suspend fun close() {
        if (closed.value) return
        closed.value = true
        session.close()
        scope.cancel()
    }
}

/**
 * A queue-level failure, narrowed to what the tracker can render.
 *
 * The `reason` is the server's own words (04 §1's rule for the login screen, applied here
 * too: a DiceCloud validation message is more useful than "something went wrong"), but the
 * exception *type* is deliberately not exposed — `:app` has no business branching on
 * `DdpError` vs `DdpConnectionException`, only on the two cases it has different copy for.
 */
internal fun WriteFailure.toDomain(): TrackerWriteFailure {
    val ddp = cause as? DdpError
    return TrackerWriteFailure(
        // Monotonic, so the composable can key a shake on it: two identical failures in a
        // row must animate twice, and a data class equal to its predecessor would not.
        id = FAILURE_IDS.incrementAndGet(),
        kind = op.intent ?: TrackerWriteKind.SET_VALUE,
        // `null` for a rest, per TrackerWriteFailure's contract: a rest is not row-shaped,
        // and `WriteOp.Rest.targetId` is the *creature* id, which would send the UI
        // hunting for a row to shake that does not exist. Written as an `if` because the
        // previous `(op as? Rest)?.let { null } ?: op.targetId` could not do this — a
        // `let` returning `null` is `Nothing?`, so the elvis always took the right side.
        propertyId = if (op is WriteOp.Rest) null else op.targetId,
        targetName = op.targetName,
        reason = ddp?.reason ?: ddp?.detailsText,
        refusedOffline = isRefusal,
        rateLimited = isRateLimit,
    )
}

private val FAILURE_IDS = java.util.concurrent.atomic.AtomicLong(0)
