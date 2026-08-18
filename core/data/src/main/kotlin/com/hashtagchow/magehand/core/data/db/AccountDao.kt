package com.hashtagchow.magehand.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Accounts CRUD. Kept as an interface (rather than an abstract class) so tests
 * that cannot run Room can substitute an in-memory fake with no mocking framework.
 */
@Dao
interface AccountDao {

    /** Most-recently-used first — the order the account switcher renders. */
    @Query("SELECT * FROM accounts ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY lastUsedAt DESC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findById(id: String): AccountEntity?

    /** The re-login lookup: same person on the same server is the same account row. */
    @Query("SELECT * FROM accounts WHERE serverUrl = :serverUrl AND userId = :userId LIMIT 1")
    suspend fun findByServerAndUser(serverUrl: String, userId: String): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE accounts SET lastUsedAt = :atEpochMillis WHERE id = :id")
    suspend fun touch(id: String, atEpochMillis: Long)
}
