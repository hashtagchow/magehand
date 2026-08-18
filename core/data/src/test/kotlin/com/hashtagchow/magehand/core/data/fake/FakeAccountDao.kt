package com.hashtagchow.magehand.core.data.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.db.AccountDao
import com.hashtagchow.magehand.core.data.db.AccountEntity

/**
 * In-memory [AccountDao].
 *
 * It reproduces the two behaviours the repository actually depends on: rows come
 * back most-recently-used first, and the `(serverUrl, userId)` unique index means
 * a second sign-in as the same user replaces rather than duplicates. Both are also
 * verified against real SQLite in `AccountDaoTest`.
 */
class FakeAccountDao(initial: List<AccountEntity> = emptyList()) : AccountDao {

    private val rows = MutableStateFlow(initial.associateBy { it.id })

    override fun observeAll(): Flow<List<AccountEntity>> = rows.map { it.values.sortedDescending() }

    override suspend fun getAll(): List<AccountEntity> = rows.value.values.sortedDescending()

    override suspend fun findById(id: String): AccountEntity? = rows.value[id]

    override suspend fun findByServerAndUser(serverUrl: String, userId: String): AccountEntity? =
        rows.value.values.firstOrNull { it.serverUrl == serverUrl && it.userId == userId }

    override suspend fun upsert(account: AccountEntity) {
        val clash = rows.value.values.firstOrNull {
            it.id != account.id && it.serverUrl == account.serverUrl && it.userId == account.userId
        }
        check(clash == null) {
            "unique index (serverUrl, userId) violated: ${account.serverUrl} / ${account.userId}"
        }
        rows.value = rows.value + (account.id to account)
    }

    override suspend fun insert(account: AccountEntity) {
        check(!rows.value.containsKey(account.id)) { "primary key ${account.id} already exists" }
        upsert(account)
    }

    override suspend fun delete(account: AccountEntity) = deleteById(account.id)

    override suspend fun deleteById(id: String) {
        rows.value = rows.value - id
    }

    override suspend fun touch(id: String, atEpochMillis: Long) {
        rows.value[id]?.let { rows.value = rows.value + (id to it.copy(lastUsedAt = atEpochMillis)) }
    }
}

/** Mirrors `ORDER BY lastUsedAt DESC`, with the id as a stable tie-break. */
private fun Collection<AccountEntity>.sortedDescending(): List<AccountEntity> =
    sortedWith(compareByDescending<AccountEntity> { it.lastUsedAt }.thenBy { it.id })
