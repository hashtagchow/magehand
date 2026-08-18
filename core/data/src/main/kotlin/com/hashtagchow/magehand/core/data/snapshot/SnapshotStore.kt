package com.hashtagchow.magehand.core.data.snapshot

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.hashtagchow.magehand.core.data.api.DiceCloudApi
import com.hashtagchow.magehand.core.data.db.SnapshotDao
import com.hashtagchow.magehand.core.data.db.SnapshotEntity
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet

/** A snapshot as it comes back out of Room: the inflated body plus when it was captured. */
class CachedSnapshot(
    val accountId: String,
    val creatureId: String,
    /** The raw `GET /api/creature/:id` JSON body. */
    val body: String,
    /** Epoch millis — what the "Offline — synced HH:MM" banner shows (06 §Connectivity states). */
    val fetchedAt: Long,
) {
    /** Parsed once, lazily: a ~1 MB parse is not something to do on every recomposition. */
    val sheet: CreatureSheet by lazy { CreatureSheet.fromSnapshotJson(body, creatureId) }

    override fun toString(): String =
        "CachedSnapshot(accountId=$accountId, creatureId=$creatureId, bytes=${body.length}, fetchedAt=$fetchedAt)"
}

/**
 * The snapshot half of 06-offline-and-sync.md §Snapshot lifecycle.
 *
 * ```
 * fetch (REST) ──► gzip ──► Room ──► LRU evict beyond 10 per account
 *                                │
 *                inflate ◄───────┘ ──► CreatureSheet ──► TrackerEngine
 * ```
 *
 * Deliberately **not** a repository: it has no opinion about accounts, tokens or
 * connection state, so it can be driven by the character-open path, by the
 * app-background mirror flush (06 step 2), and by tests, without any of them knowing
 * about the others. The sibling work package owns the Hilt wiring; this takes a plain
 * constructor.
 */
class SnapshotStore(
    private val snapshotDao: SnapshotDao,
    private val api: DiceCloudApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    /** 06 §Data budget: "≤10 characters cached per account; LRU-evict beyond that". */
    private val maxPerAccount: Int = DEFAULT_MAX_PER_ACCOUNT,
) {

    /**
     * Fetches a fresh snapshot over REST, stores it gzipped, and evicts down to the
     * cache budget.
     *
     * Throws whatever [DiceCloudApi.fetchCreatureSnapshot] throws (always a typed
     * `ApiException`) — a failed refresh must not silently overwrite a good cached
     * snapshot with nothing, so nothing is written unless the fetch succeeded.
     */
    suspend fun refresh(
        accountId: String,
        serverUrl: String,
        token: String,
        creatureId: String,
    ): CachedSnapshot {
        val body = api.fetchCreatureSnapshot(serverUrl, token, creatureId)
        return store(accountId, creatureId, body)
    }

    /**
     * Stores a body that the caller already has.
     *
     * This is the path 06 step 2 uses on app-background: the live mirror is serialized
     * back into the same `{creatures, creatureProperties, creatureVariables}` shape and
     * written here, so an offline open sees the newest state rather than whatever REST
     * last returned. `_id` is inside every mirrored document, which is what makes the
     * two shapes interchangeable (docs/verification/WP2.md deviation #6).
     */
    suspend fun store(
        accountId: String,
        creatureId: String,
        body: String,
        fetchedAt: Long = now(),
    ): CachedSnapshot = withContext(ioDispatcher) {
        snapshotDao.upsert(
            SnapshotEntity(
                accountId = accountId,
                creatureId = creatureId,
                json = Gzip.deflate(body),
                fetchedAt = fetchedAt,
            ),
        )
        snapshotDao.evictBeyond(accountId, maxPerAccount)
        CachedSnapshot(accountId, creatureId, body, fetchedAt)
    }

    /** Inflates the cached snapshot, or `null` when this character has never been synced. */
    suspend fun load(accountId: String, creatureId: String): CachedSnapshot? =
        withContext(ioDispatcher) {
            val row = snapshotDao.find(accountId, creatureId) ?: return@withContext null
            CachedSnapshot(
                accountId = row.accountId,
                creatureId = row.creatureId,
                body = Gzip.inflate(row.json),
                fetchedAt = row.fetchedAt,
            )
        }

    /** Convenience for the offline read path: inflate straight into the engine's input. */
    suspend fun loadSheet(accountId: String, creatureId: String): CreatureSheet? =
        load(accountId, creatureId)?.sheet

    /** When this character was last synced, live. Drives the offline banner. */
    fun observeFetchedAt(accountId: String, creatureId: String): Flow<Long?> =
        snapshotDao.observeFetchedAt(accountId, creatureId)

    suspend fun cachedCreatureIds(accountId: String): List<String> =
        withContext(ioDispatcher) { snapshotDao.creatureIdsByRecency(accountId) }

    suspend fun delete(accountId: String, creatureId: String) =
        withContext(ioDispatcher) { snapshotDao.delete(accountId, creatureId) }

    /** Called on sign-out: a signed-out account must leave no cached sheet behind. */
    suspend fun clearAccount(accountId: String) =
        withContext(ioDispatcher) { snapshotDao.deleteForAccount(accountId) }

    companion object {
        const val DEFAULT_MAX_PER_ACCOUNT: Int = 10
    }
}
