package com.hashtagchow.magehand.core.data.fake

import com.hashtagchow.magehand.core.data.api.ApiException
import com.hashtagchow.magehand.core.data.api.DiceCloudApi
import com.hashtagchow.magehand.core.data.api.LoginSession

/**
 * Scriptable [DiceCloudApi]. The repository tests care about *what the repository
 * does with* a login outcome, not about HTTP — HTTP is covered separately against
 * MockWebServer in `OkHttpDiceCloudApiTest`.
 */
class FakeDiceCloudApi : DiceCloudApi {

    /** Called with (serverUrl, usernameOrEmail, password) — password asserted to be non-persisted. */
    val loginCalls = mutableListOf<Triple<String, String, String>>()

    /** Next login outcome. Default: succeeds as user `u1` with token `tok-1`. */
    var loginResult: () -> LoginSession = { LoginSession("u1", "tok-1", 1_700_000_000_000L) }

    var snapshotResult: () -> String = { """{"creatures":[],"creatureProperties":[]}""" }

    /** Called with (serverUrl, token, creatureId) — WP4's snapshot pipeline asserts on these. */
    val snapshotCalls = mutableListOf<Triple<String, String, String>>()

    override suspend fun login(
        serverUrl: String,
        usernameOrEmail: String,
        password: String,
    ): LoginSession {
        loginCalls += Triple(serverUrl, usernameOrEmail, password)
        return loginResult()
    }

    override suspend fun fetchCreatureSnapshot(
        serverUrl: String,
        token: String,
        creatureId: String,
    ): String {
        snapshotCalls += Triple(serverUrl, token, creatureId)
        return snapshotResult()
    }

    /** Convenience: make the next login fail the way a wrong password does. */
    fun failWithInvalidCredentials() {
        loginResult = { throw ApiException.InvalidCredentials() }
    }
}
