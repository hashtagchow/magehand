package com.hashtagchow.magehand.core.data.auth

/**
 * A stored DiceCloud resume token.
 *
 * [toString] is overridden so the token cannot leak through a log statement, a
 * data-class `toString()` in a stack trace, or a crash reporter.
 */
class StoredToken(
    val token: String,
    /** Epoch millis the server said the token expires, or `null` if unknown. */
    val expiresAtEpochMillis: Long?,
) {
    override fun toString(): String = "StoredToken(<redacted>, expiresAt=$expiresAtEpochMillis)"

    override fun equals(other: Any?): Boolean =
        other is StoredToken &&
            other.token == token &&
            other.expiresAtEpochMillis == expiresAtEpochMillis

    override fun hashCode(): Int = 31 * token.hashCode() + (expiresAtEpochMillis?.hashCode() ?: 0)
}

/**
 * Where resume tokens live. **Never Room, never DataStore, never a log line**
 * (docs/design/05-security.md).
 *
 * Keyed by `Account.id` so the store is multi-account by construction; the
 * production implementation is [EncryptedPrefsTokenStore] (AES-256,
 * Android-Keystore master key), and tests use a fake.
 */
interface TokenStore {
    suspend fun save(accountId: String, token: StoredToken)

    suspend fun read(accountId: String): StoredToken?

    suspend fun delete(accountId: String)

    /** Wipes every stored token — used by "sign out of everything". */
    suspend fun clear()
}
