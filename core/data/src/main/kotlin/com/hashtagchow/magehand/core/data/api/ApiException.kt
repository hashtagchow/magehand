package com.hashtagchow.magehand.core.data.api

import com.hashtagchow.magehand.core.data.server.ServerUrlProblem
import java.io.IOException

/**
 * Every failure mode of **signing in** — the DiceCloud REST client's, plus the one
 * local failure that stops a sign-in dead ([SecureStorageUnavailable]) — as a closed
 * hierarchy.
 *
 * The point of the hierarchy is the distinction the login screen has to make:
 * **"you typed the wrong password"** ([InvalidCredentials]) versus **"this isn't
 * reachable / isn't a DiceCloud server"** ([ServerUnreachable], [NotADiceCloudServer]).
 * Collapsing those into one "login failed" is the single worst UX bug available
 * here, so they are separate types and separately tested.
 *
 * `message` is UI-ready. No subclass ever carries a token or a password.
 */
sealed class ApiException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** The server address itself did not survive [com.hashtagchow.magehand.core.data.server.normalizeServerUrl]. */
    class InvalidServerUrl(val problem: ServerUrlProblem) : ApiException(problem.message)

    /**
     * DNS failure, connection refused, TLS failure, timeout — the request never
     * got an HTTP response. Almost always a wrong hostname or no connectivity.
     */
    class ServerUnreachable(
        val serverUrl: String,
        cause: Throwable?,
    ) : ApiException("Can't reach $serverUrl. Check the address and your connection.", cause)

    /**
     * An HTTP response arrived but this is not a DiceCloud API: a 404 on
     * `/api/login`, or a 200 whose body is not the documented JSON shape (e.g. a
     * router login page, or someone's blog).
     */
    class NotADiceCloudServer(
        val serverUrl: String,
        val httpCode: Int,
    ) : ApiException("$serverUrl doesn't look like a DiceCloud server (HTTP $httpCode).")

    /** The username/email and password pair was rejected by the server. */
    class InvalidCredentials : ApiException("Incorrect username or password.")

    /** A bearer token was rejected — expired, or evicted from the server's resume-token pool. */
    class TokenRejected : ApiException("Your session expired. Sign in again.")

    /** Authenticated, but the requested creature does not exist or isn't visible to this user. */
    class NotFound(val what: String) : ApiException("Not found on the server: $what.")

    /** The server answered 5xx. Transient — worth a retry. */
    class ServerError(val httpCode: Int) : ApiException("The server had a problem (HTTP $httpCode). Try again.")

    /** Rate limiter (see docs/design/02-ddp-and-api.md — `too-many-requests`). */
    class TooManyRequests : ApiException("The server is rate-limiting us. Try again in a moment.")

    /** A 2xx response whose body could not be parsed as the documented JSON. */
    class MalformedResponse(detail: String) : ApiException("Unexpected response from the server ($detail).")

    /**
     * Re-login returned a different Meteor user than the account row expects —
     * the user typed credentials for some other account.
     */
    class AccountMismatch : ApiException("Those credentials are for a different DiceCloud account.")

    /**
     * The server said yes, but the device's Keystore would not seal the resume token.
     *
     * The only member of this hierarchy that never touched the network. It is here
     * rather than in its own hierarchy because this *is* the login screen's typed
     * error channel — `DefaultAccountRepository.runApi` converts exactly this type
     * into a failed `Result`, and `CredentialsViewModel` renders exactly that. A
     * wedged keymaster or an invalidated key would otherwise escape `runApi` as a
     * "programming error" and take the process down at the moment the user finished
     * typing their password.
     *
     * Not retried and not worked around: storing the token unsealed is the one thing
     * docs/design/05-security.md §"Token & credential handling" forbids outright.
     */
    class SecureStorageUnavailable(cause: Throwable?) : ApiException(
        "This device's secure storage isn't available, so the sign-in can't be saved.",
        cause,
    )
}
