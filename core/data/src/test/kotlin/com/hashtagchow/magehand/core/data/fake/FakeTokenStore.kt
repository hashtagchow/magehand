package com.hashtagchow.magehand.core.data.fake

import com.hashtagchow.magehand.core.data.auth.StoredToken
import com.hashtagchow.magehand.core.data.auth.TokenStore

/**
 * In-memory [TokenStore] for tests.
 *
 * The real [com.hashtagchow.magehand.core.data.auth.EncryptedPrefsTokenStore]
 * cannot be unit-tested on the JVM: its whole point is the Android Keystore, which
 * only exists on a device. Its behaviour is therefore exercised through this fake
 * at the repository level, and the encryption itself is Jetpack Security's
 * responsibility, verified on-device in WP5.
 *
 * [saveCount] exists so tests can prove *replacement* semantics on re-login
 * rather than just final state.
 */
class FakeTokenStore : TokenStore {

    private val tokens = mutableMapOf<String, StoredToken>()

    var saveCount: Int = 0
        private set

    var deleteCount: Int = 0
        private set

    override suspend fun save(accountId: String, token: StoredToken) {
        saveCount++
        tokens[accountId] = token
    }

    override suspend fun read(accountId: String): StoredToken? = tokens[accountId]

    override suspend fun delete(accountId: String) {
        deleteCount++
        tokens.remove(accountId)
    }

    override suspend fun clear() {
        tokens.clear()
    }

    /** Test-only view: which account ids currently hold a token. */
    fun accountIds(): Set<String> = tokens.keys.toSet()
}
