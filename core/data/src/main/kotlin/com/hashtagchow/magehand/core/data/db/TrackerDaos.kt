package com.hashtagchow.magehand.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Cached character selector rows. */
@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters WHERE accountId = :accountId ORDER BY lastOpenedAt DESC, name ASC")
    fun observeForAccount(accountId: String): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE accountId = :accountId ORDER BY lastOpenedAt DESC, name ASC")
    suspend fun getForAccount(accountId: String): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun find(accountId: String, creatureId: String): CharacterEntity?

    @Upsert
    suspend fun upsert(characters: List<CharacterEntity>)

    @Upsert
    suspend fun upsert(character: CharacterEntity)

    @Query("UPDATE characters SET lastOpenedAt = :at WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun touch(accountId: String, creatureId: String, at: Long)

    /**
     * Replaces the cached list for one account. `characterList` is authoritative, so a
     * creature the publication no longer yields must disappear from the selector rather
     * than linger forever.
     */
    @Query("DELETE FROM characters WHERE accountId = :accountId AND creatureId NOT IN (:keep)")
    suspend fun deleteMissing(accountId: String, keep: List<String>)

    @Query("DELETE FROM characters WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

/** Gzipped creature snapshots — the offline read path (06 §Snapshot lifecycle). */
@Dao
interface SnapshotDao {

    @Query("SELECT * FROM snapshots WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun find(accountId: String, creatureId: String): SnapshotEntity?

    @Query("SELECT fetchedAt FROM snapshots WHERE accountId = :accountId AND creatureId = :creatureId")
    fun observeFetchedAt(accountId: String, creatureId: String): Flow<Long?>

    @Upsert
    suspend fun upsert(snapshot: SnapshotEntity)

    @Query("SELECT COUNT(*) FROM snapshots WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT creatureId FROM snapshots WHERE accountId = :accountId ORDER BY fetchedAt DESC")
    suspend fun creatureIdsByRecency(accountId: String): List<String>

    /**
     * LRU eviction, 06 §Data budget: "≤10 characters cached per account; LRU-evict
     * beyond that". Keeps the [limit] most recently fetched rows.
     */
    @Query(
        """
        DELETE FROM snapshots
        WHERE accountId = :accountId
          AND creatureId NOT IN (
            SELECT creatureId FROM snapshots
            WHERE accountId = :accountId
            ORDER BY fetchedAt DESC
            LIMIT :limit
          )
        """,
    )
    suspend fun evictBeyond(accountId: String, limit: Int)

    @Query("DELETE FROM snapshots WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun delete(accountId: String, creatureId: String)

    @Query("DELETE FROM snapshots WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

/** The pin / hide / reorder layer (03 §6). */
@Dao
interface TrackerPrefDao {

    @Query("SELECT * FROM tracker_prefs WHERE accountId = :accountId AND creatureId = :creatureId")
    fun observe(accountId: String, creatureId: String): Flow<List<TrackerPrefEntity>>

    @Query("SELECT * FROM tracker_prefs WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun get(accountId: String, creatureId: String): List<TrackerPrefEntity>

    @Upsert
    suspend fun upsert(pref: TrackerPrefEntity)

    @Upsert
    suspend fun upsert(prefs: List<TrackerPrefEntity>)

    @Query(
        "DELETE FROM tracker_prefs WHERE accountId = :accountId AND creatureId = :creatureId " +
            "AND propertyId = :propertyId",
    )
    suspend fun delete(accountId: String, creatureId: String, propertyId: String)

    @Query("DELETE FROM tracker_prefs WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun clear(accountId: String, creatureId: String)

    @Query("DELETE FROM tracker_prefs WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

/** Per-character accent colour. */
@Dao
interface ThemePrefDao {

    @Query("SELECT * FROM theme_prefs WHERE accountId = :accountId AND creatureId = :creatureId")
    fun observe(accountId: String, creatureId: String): Flow<ThemePrefEntity?>

    @Query("SELECT * FROM theme_prefs WHERE accountId = :accountId AND creatureId = :creatureId")
    suspend fun find(accountId: String, creatureId: String): ThemePrefEntity?

    @Upsert
    suspend fun upsert(pref: ThemePrefEntity)

    @Query("DELETE FROM theme_prefs WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
