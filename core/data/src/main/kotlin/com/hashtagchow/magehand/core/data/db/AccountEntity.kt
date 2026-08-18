package com.hashtagchow.magehand.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hashtagchow.magehand.core.model.Account

/**
 * The `accounts` table from docs/design/03-data-model.md:
 * `accounts(id PK, serverUrl, userId, username, addedAt, lastUsedAt)`.
 *
 * There is **no token column and there never will be** — tokens live only in
 * [com.hashtagchow.magehand.core.data.auth.TokenStore] (docs/design/05-security.md).
 *
 * The unique index on `(serverUrl, userId)` is the invariant that makes re-login
 * idempotent: signing in again as the same user on the same server updates the
 * existing row (and replaces the token) instead of growing a duplicate account.
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["serverUrl", "userId"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val serverUrl: String,
    val userId: String,
    val username: String,
    val addedAt: Long,
    val lastUsedAt: Long,
)

internal fun AccountEntity.toDomain(): Account = Account(
    id = id,
    serverUrl = serverUrl,
    userId = userId,
    username = username,
    addedAt = addedAt,
    lastUsedAt = lastUsedAt,
)

internal fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    serverUrl = serverUrl,
    userId = userId,
    username = username,
    addedAt = addedAt,
    lastUsedAt = lastUsedAt,
)
