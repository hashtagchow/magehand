package com.hashtagchow.magehand.core.model

/**
 * One signed-in DiceCloud identity: a (server, user) pair the app holds a resume
 * token for. See docs/design/03-data-model.md.
 *
 * The resume token itself is deliberately **not** a field here. It lives in
 * `EncryptedSharedPreferences` keyed by [id] (docs/design/05-security.md), so a
 * stray `toString()`, log line or crash report can never carry it.
 *
 * @param id local UUID — the primary key of the `accounts` table and the
 *   EncryptedSharedPreferences key for the token.
 * @param serverUrl normalized https origin, e.g. `https://dicecloud.com`.
 *   Always the output of `normalizeServerUrl` — never raw user input.
 * @param userId the Meteor user id returned by `/api/login`.
 * @param username the identifier the user signed in with (username or email).
 * @param addedAt epoch millis the account was first added.
 * @param lastUsedAt epoch millis the account was last selected as active; the
 *   account list is ordered by this descending.
 */
data class Account(
    val id: String,
    val serverUrl: String,
    val userId: String,
    val username: String,
    val addedAt: Long,
    val lastUsedAt: Long,
)
