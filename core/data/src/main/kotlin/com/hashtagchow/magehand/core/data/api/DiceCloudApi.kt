package com.hashtagchow.magehand.core.data.api

/**
 * What `/api/login` returns (docs/design/02-ddp-and-api.md §Authentication):
 * `{"id": "<userId>", "token": "<resume token>", "tokenExpires": "<ISO date>"}`.
 *
 * [token] is a secret: it is handed straight to
 * [com.hashtagchow.magehand.core.data.auth.TokenStore] and never logged, never
 * persisted in Room, never put in a URL. [toString] is overridden to keep it out
 * of accidental log lines and crash reports.
 */
class LoginSession(
    val userId: String,
    val token: String,
    /** Epoch millis, or `null` when the server sent no parseable expiry. */
    val tokenExpiresAt: Long?,
) {
    override fun toString(): String =
        "LoginSession(userId=$userId, token=<redacted>, tokenExpiresAt=$tokenExpiresAt)"
}

/**
 * The DiceCloud REST surface WP3 needs. Deliberately tiny: everything live goes
 * over DDP (`:core:ddp`, WP2); REST is only used to *get* a token and to pull a
 * point-in-time snapshot.
 *
 * Every method throws a typed [ApiException] and nothing else.
 */
interface DiceCloudApi {

    /**
     * `POST <serverUrl>/api/login`.
     *
     * @param serverUrl raw user input or a normalized origin — normalized internally,
     *   so callers cannot accidentally skip validation.
     * @param usernameOrEmail sent as `email` when it contains `@`, else as `username`
     *   (the server accepts either key).
     * @param password never stored, never logged; only travels in the TLS request body.
     * @throws ApiException.InvalidCredentials wrong username/password.
     * @throws ApiException.ServerUnreachable no HTTP response at all.
     * @throws ApiException.NotADiceCloudServer reachable, but not DiceCloud.
     */
    suspend fun login(serverUrl: String, usernameOrEmail: String, password: String): LoginSession

    /**
     * `GET <serverUrl>/api/creature/<creatureId>` with `Authorization: Bearer <token>`.
     *
     * Returns the **raw JSON body** — `{creatures, creatureProperties, creatureVariables}`,
     * around 1 MB for a real character. WP3 does not parse it; WP4 owns the model
     * and the gzip-to-Room caching. Exposed now so WP4 has a stable seam and so
     * WP3 has an end-to-end acceptance probe against the live server.
     *
     * @throws ApiException.TokenRejected the token is expired or was evicted.
     * @throws ApiException.NotFound no such creature for this user.
     */
    suspend fun fetchCreatureSnapshot(serverUrl: String, token: String, creatureId: String): String
}
