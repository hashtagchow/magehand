package com.hashtagchow.magehand.core.data.characters

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hashtagchow.magehand.core.model.CharacterSummary

/**
 * A previously stored character list plus when it was stored.
 *
 * [cachedAt] is nullable because "the list survived, the moment it was written did not"
 * is a real state: 03's `characters` schema has no `cachedAt` column, and inventing
 * schema v3 for a value nothing renders would be a migration for nothing
 * (docs/verification/WP6.md, deviations). [RoomCharacterCache] therefore reports the
 * write time within a process and `null` after a cold start.
 */
data class CachedCharacters(
    val characters: List<CharacterSummary>,
    val cachedAt: Long?,
)

/**
 * Where the character list is kept so screen 2 can render instantly and offline
 * ("Never block on network" — docs/design/04-screens-ux.md, UX principles).
 *
 * WP5 shipped [InMemoryCharacterCache] behind this seam because WP4's schema v2 had
 * not landed yet. **WP6 closed it**: the production binding is now
 * [RoomCharacterCache] over the `characters` table
 * (`accountId, creatureId, name, picture, owner, isOwned, lastOpenedAt`), so the
 * list — and [lastOpenedCreatureId], which 04's start-destination rule needs —
 * survive process death. [InMemoryCharacterCache] stays as the dependency-free
 * implementation for tests.
 */
interface CharacterCache {

    suspend fun read(accountId: String): CachedCharacters?

    suspend fun write(accountId: String, characters: List<CharacterSummary>, at: Long)

    /**
     * The creature this account opened most recently, or `null` if it has never
     * opened one. Drives 04's "start destination: last-used character's Tracker".
     */
    suspend fun lastOpenedCreatureId(accountId: String): String?

    /** Records that a character screen was opened, which is what [lastOpenedCreatureId] reads. */
    suspend fun markOpened(accountId: String, creatureId: String, at: Long)

    suspend fun clear(accountId: String)
}

/**
 * Process-lifetime cache. Survives navigation and account switching, not process
 * death. Kept for tests and for anything that must not touch Room; the production
 * binding is [RoomCharacterCache].
 */
class InMemoryCharacterCache : CharacterCache {

    private val mutex = Mutex()
    private val byAccount = mutableMapOf<String, CachedCharacters>()
    private val lastOpened = mutableMapOf<String, String>()

    override suspend fun read(accountId: String): CachedCharacters? =
        mutex.withLock { byAccount[accountId] }

    override suspend fun write(accountId: String, characters: List<CharacterSummary>, at: Long) {
        mutex.withLock { byAccount[accountId] = CachedCharacters(characters, at) }
    }

    override suspend fun lastOpenedCreatureId(accountId: String): String? =
        mutex.withLock { lastOpened[accountId] }

    override suspend fun markOpened(accountId: String, creatureId: String, at: Long) {
        mutex.withLock { lastOpened[accountId] = creatureId }
    }

    override suspend fun clear(accountId: String) {
        mutex.withLock {
            byAccount.remove(accountId)
            lastOpened.remove(accountId)
        }
    }
}
