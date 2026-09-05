package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a redacted field reads as in the log. Not a token, and not the empty string either. */
private const val REDACTED = "<redacted>"

/** The one method whose payload is a credential in both directions. */
private const val METHOD_LOGIN = "login"

private val logJson = Json { encodeDefaults = true }

/**
 * One DDP frame as it should appear in a log: the `login` exchange scrubbed, everything else
 * verbatim (BUG-16).
 *
 * ### Why the redaction is here and not at the sink
 *
 * [DdpClient] hands every raw frame to [DdpClientConfig.logger], and a debug build wires that to
 * `Log.d("MageHandDdp", …)`. The outgoing `login` frame carries `{"resume":"<token>"}` and its
 * `result` carries `{"token":"<token>"}`, so both landed in logcat — and Maestro's
 * `--test-output-dir` copies logcat into every sweep flow's `logs/device-logcat.txt`, one `cp`
 * from a public tree. `docs/design/05-security.md` said resume tokens are never logged; in a
 * debug build that was false.
 *
 * Fixing it at the sink would have fixed one sink. There are three today — the debug logcat,
 * `DdpLiveIntegrationTest`'s `println`, and the tests' own collectors — and the next one added
 * would arrive unscrubbed. So the frame is scrubbed *before* the logger is called, once, and
 * every sink is covered by construction.
 *
 * ### Why it is a JSON transform and not string surgery
 *
 * A regex over the encoded text would be a rule about the *spelling* of a frame — key order,
 * escaping, whitespace — and would silently stop firing the day any of those changed. This reads
 * the frame the client already parsed, replaces one field, and re-encodes. The rule reads as a
 * rule.
 *
 * **The line in the log is therefore a re-encoding, not the bytes off the wire.** Key order and
 * numeric literals survive (kotlinx keeps insertion order and a `JsonPrimitive`'s own text), but
 * whitespace between tokens is gone and string escapes are normalised to kotlinx's spelling — so
 * a log line is the right thing to read a frame's *content* out of and the wrong thing to
 * byte-compare against a capture.
 *
 * ### The three shapes that are scrubbed
 *
 *  - an outbound `login` **method**: every value in every `params` object becomes `<redacted>`,
 *    keys kept (review L3 — so a password login, if one is ever added, is logged as *what it
 *    was* rather than as a `resume` it never had), and a `params` of any other shape becomes the
 *    marker outright rather than being passed through (review L2);
 *  - the matching inbound **result**: `result.token` becomes `<redacted>`;
 *  - an **error** frame whose `offendingMessage` echoes either of those — the same rule, applied
 *    by calling this function on the echo (review M1).
 *
 * ### What survives, deliberately
 *
 * Frame bodies keep being logged: the frame log is what every blind repro since the stepper-burst
 * bug has run on, and a client that logs nothing is a client nobody can debug. The `result`'s
 * `id` and `tokenExpires` survive too — the user id is the log's diagnostic value, and it is a
 * docs-zone identifier rather than a credential (`logged in as <userId>` says the same thing one
 * line later). Only the credential goes, and the redaction is not allowed to widen: a non-`login`
 * method's `params` are logged verbatim, because that is the payload a repro is about.
 *
 * @param frame the parsed frame, in either direction.
 * @param pendingMethodOf resolves a call id to the method it was opened for
 *   (`DdpClient.PendingCall.method`), which is the only way to know a bare `result` frame is
 *   answering a `login` — a `result` carries no method name of its own. An id this client has no
 *   pending call for resolves to `null`, and such a frame is encoded as-is.
 * @return the frame's encoding, scrubbed where the rules above say so.
 */
internal fun redactForLog(frame: JsonObject, pendingMethodOf: (String) -> String?): String =
    logJson.encodeToString(JsonObject.serializer(), redact(frame, pendingMethodOf))

private fun redact(frame: JsonObject, pendingMethodOf: (String) -> String?): JsonObject =
    when (frame.logStr("msg")) {
        // Outbound. The method name is on the frame itself, so no lookup is needed.
        // FAIL CLOSED (review L2). The `params` key present on a `login` is replaced whatever
        // its shape: an array is scrubbed entry by entry, and anything else — an object, a bare
        // string, a number — becomes the marker outright. The first cut returned the frame
        // untouched when `params` was not an array, which was safe only as long as every frame
        // reaching here was one this client had built. M1 broke that assumption: an `error`
        // frame's `offendingMessage` is the *server's* echo, so a refusal spelled
        // `"params":{"resume":"…"}` would have walked the token straight into the log through
        // the one branch that had decided not to look.
        "method" -> if (frame.logStr("method") == METHOD_LOGIN) {
            frame.replacing("params", scrubbed(frame["params"]))
        } else {
            frame
        }

        // Inbound.
        "result" -> {
            val id = frame.logStr("id")
            val result = frame["result"] as? JsonObject
            if (id != null && result != null && pendingMethodOf(id) == METHOD_LOGIN) {
                frame.replacing("result", result.replacing("token", JsonPrimitive(REDACTED)))
            } else {
                frame
            }
        }

        // A server→client `error` frame may echo the frame it is refusing, in
        // `offendingMessage` — and if that was the `login` method, the token is straight back in
        // the log (review M1). The same rule is applied to the echo by recursing, rather than by
        // a second copy of it here. It cannot loop: an `offendingMessage` that is itself an
        // `error` frame either carries no echo of its own or terminates on one that does not.
        "error" -> {
            val offending = frame["offendingMessage"] as? JsonObject
            if (offending == null) {
                frame
            } else {
                frame.replacing("offendingMessage", redact(offending, pendingMethodOf))
            }
        }

        else -> frame
    }

/**
 * A `login`'s `params`, with every value replaced — **keys kept** (review L3), and **nothing
 * trusted about the shape** (review L2).
 *
 * The first cut substituted a constant `[{"resume":"<redacted>"}]`, which told the reader what
 * this client sends *today* rather than what it sent *then*. `login` is Meteor's one method whose
 * arguments are all credentials in some spelling — `resume`, or a `user`/`password` pair if this
 * app ever grows one — so the safe rule is "no value from a login's params reaches a log", and
 * the useful half is that the shape does.
 *
 * Which is why the fallbacks all collapse to the bare marker rather than to the input. An entry
 * that is not an object, and a `params` that is not an array at all, are both shapes this client
 * never produces — but `redactForLog` also runs on frames the *server* wrote (an `error`'s
 * `offendingMessage`), and "we have never seen it" is not a reason to log something unread. A
 * credential in an unexpected wrapper is still a credential.
 */
private fun scrubbed(params: JsonElement?): JsonElement = when (params) {
    is JsonArray -> JsonArray(
        params.map { entry ->
            when (entry) {
                is JsonObject -> JsonObject(entry.mapValues { JsonPrimitive(REDACTED) })
                else -> JsonPrimitive(REDACTED)
            }
        }
    )
    else -> JsonPrimitive(REDACTED)
}

/**
 * [key] replaced by [value], every other entry left where it was, **and nothing added**.
 *
 * A `result` frame that carried no `token` does not grow one reading `<redacted>`: that would be
 * this function inventing a claim about what the server answered. This is also what keeps the
 * `params` branch honest now that it no longer inspects the value first (review L2): a `login`
 * frame with no `params` at all is left alone rather than given an imaginary redacted one.
 */
private fun JsonObject.replacing(key: String, value: JsonElement): JsonObject =
    if (!containsKey(key)) this else JsonObject(toMutableMap().apply { put(key, value) })

private fun JsonObject.logStr(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
