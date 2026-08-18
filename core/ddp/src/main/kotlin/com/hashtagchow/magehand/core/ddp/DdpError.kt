package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A `Meteor.Error` that came back on the wire, per docs/design/01-architecture.md
 * ("every DDP method error surfaces as a typed `DdpError(error, reason, details)`").
 *
 * Wire shape: `{"msg":"result","id":"7","error":{"error":403,"reason":"...",
 * "details":...,"errorType":"Meteor.Error"}}`.
 *
 * [error] is normalised to a String because Meteor uses both numeric codes (`403`)
 * and string codes (`too-many-requests`) in the same field; [errorCode] gives the
 * numeric form back when there is one.
 */
class DdpError(
    val error: String,
    val reason: String? = null,
    val details: JsonElement? = null,
    val errorType: String? = null,
) : RuntimeException(buildString {
    append(error)
    reason?.let { append(": ").append(it) }
}) {

    /** [error] as an Int when it is a numeric Meteor code (403, 500, …). */
    val errorCode: Int? get() = error.toIntOrNull()

    /** [details] flattened to text — DiceCloud sends a string here when it sends one. */
    val detailsText: String?
        get() = when (val d = details) {
            null, JsonNull -> null
            is JsonPrimitive -> d.contentOrNull
            else -> d.toString()
        }

    /**
     * True for the errors that mean "this token is no longer good" — the app must
     * clear the token and route to re-login (01-architecture.md, error doctrine).
     */
    val isAuthError: Boolean
        get() = errorCode == 403 ||
            error == "not-authorized" ||
            reason?.contains("logged out by the server", ignoreCase = true) == true ||
            reason?.contains("invalid login token", ignoreCase = true) == true

    /** True for the server-side rate limiter (02-ddp-and-api.md); WriteQueue backs off. */
    val isRateLimit: Boolean get() = error == "too-many-requests"

    companion object {
        fun fromJson(obj: JsonObject): DdpError = DdpError(
            error = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: obj["error"]?.toString() ?: "unknown",
            reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull,
            details = obj["details"],
            errorType = (obj["errorType"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/**
 * The socket died / never came up / the handshake failed. Distinct from [DdpError]:
 * a `DdpError` came *from the server*, a `DdpConnectionException` means we never got
 * an answer. Pending method calls are failed with this when a session ends — they are
 * deliberately **not** replayed on reconnect (see `DdpClient` KDoc).
 */
class DdpConnectionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
