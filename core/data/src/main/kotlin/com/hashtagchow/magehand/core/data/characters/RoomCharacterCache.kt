package com.hashtagchow.magehand.core.data.characters

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hashtagchow.magehand.core.data.db.CharacterDao
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.core.data.db.toEntity
import com.hashtagchow.magehand.core.model.CharacterSummary
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [CharacterCache]: WP4's `characters` table, which is what WP5's
 * `TODO(WP4)` on [CharacterCache] and on `DataModule.provideCharacterCache` was
 * waiting for.
 *
 * Two things it has to get right that an in-memory map got for free:
 *
 * 1. **`lastOpenedAt` must survive a re-sync.** `CharacterSummary.toEntity` defaults the
 *    column to `0`, so writing the list straight through would reset every "last opened"
 *    stamp on each `characterList` emission — and 04's start destination would then always
 *    resolve to "nothing". [write] therefore reads the existing stamps first and carries
 *    them across.
 * 2. **A creature the publication stopped yielding must disappear**, or the offline
 *    selector shows characters the account can no longer open. That is
 *    [CharacterDao.deleteMissing]; an *empty* live list is treated as "delete them all",
 *    which is the same thing the in-memory cache did by overwriting.
 *
 * `cachedAt` is deliberately not persisted — see the note on [CachedCharacters].
 */
class RoomCharacterCache(
    private val characterDao: CharacterDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CharacterCache {

    /** Write times for this process only; a cold read reports `null`, honestly. */
    private val writtenAt = ConcurrentHashMap<String, Long>()

    override suspend fun read(accountId: String): CachedCharacters? = withContext(ioDispatcher) {
        val rows = characterDao.getForAccount(accountId)
        if (rows.isEmpty()) null
        else CachedCharacters(rows.map { it.toDomain() }, writtenAt[accountId])
    }

    override suspend fun write(
        accountId: String,
        characters: List<CharacterSummary>,
        at: Long,
    ) = withContext(ioDispatcher) {
        val existingOpenedAt = characterDao.getForAccount(accountId)
            .associate { it.creatureId to it.lastOpenedAt }

        characterDao.upsert(
            characters.map { summary ->
                summary.toEntity(
                    accountId = accountId,
                    lastOpenedAt = existingOpenedAt[summary.creatureId] ?: 0L,
                )
            },
        )
        characterDao.deleteMissing(accountId, characters.map { it.creatureId })
        writtenAt[accountId] = at
    }

    override suspend fun lastOpenedCreatureId(accountId: String): String? =
        withContext(ioDispatcher) {
            // `observeForAccount`/`getForAccount` already order by lastOpenedAt DESC; a
            // zero stamp means "never opened" and must not win.
            characterDao.getForAccount(accountId)
                .firstOrNull { it.lastOpenedAt > 0L }
                ?.creatureId
        }

    override suspend fun markOpened(accountId: String, creatureId: String, at: Long) =
        withContext(ioDispatcher) { characterDao.touch(accountId, creatureId, at) }

    override suspend fun clear(accountId: String) = withContext(ioDispatcher) {
        characterDao.deleteForAccount(accountId)
        writtenAt.remove(accountId)
        Unit
    }
}
