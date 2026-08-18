package com.hashtagchow.magehand.core.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import com.hashtagchow.magehand.core.data.server.ServerUrlResult
import com.hashtagchow.magehand.core.data.server.normalizeServerUrl
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp implementation of [DiceCloudApi].
 *
 * Takes a [Call.Factory] rather than a concrete `OkHttpClient` so tests can hand
 * it a client pointed at MockWebServer without any extra indirection.
 */
class OkHttpDiceCloudApi(
    private val callFactory: Call.Factory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiceCloudApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun login(
        serverUrl: String,
        usernameOrEmail: String,
        password: String,
    ): LoginSession {
        val origin = requireOrigin(serverUrl)

        // The contract accepts either key; pick by the shape of what was typed.
        val payload: JsonObject = buildJsonObject {
            if (usernameOrEmail.contains('@')) {
                put("email", usernameOrEmail)
            } else {
                put("username", usernameOrEmail)
            }
            put("password", password)
        }

        val request = Request.Builder()
            .url("$origin/api/login")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        val (code, body) = execute(request, origin)

        when {
            code == 401 || code == 403 || code == 400 -> throw ApiException.InvalidCredentials()
            code == 404 -> throw ApiException.NotADiceCloudServer(origin, code)
            code == 429 -> throw ApiException.TooManyRequests()
            code in 500..599 -> throw ApiException.ServerError(code)
            code !in 200..299 -> throw ApiException.NotADiceCloudServer(origin, code)
        }

        val obj = body.asJsonObjectOr {
            // A 200 that isn't JSON means we reached *something* on https, but it
            // isn't DiceCloud — a captive portal, a reverse proxy error page, a blog.
            throw ApiException.NotADiceCloudServer(origin, code)
        }

        val userId = obj["id"]?.jsonPrimitiveContentOrNull()
        val token = obj["token"]?.jsonPrimitiveContentOrNull()
        if (userId.isNullOrEmpty() || token.isNullOrEmpty()) {
            // Right transport, wrong shape: 200 JSON without id/token is not the
            // documented login response.
            throw ApiException.NotADiceCloudServer(origin, code)
        }

        return LoginSession(
            userId = userId,
            token = token,
            tokenExpiresAt = parseTokenExpires(obj["tokenExpires"]),
        )
    }

    override suspend fun fetchCreatureSnapshot(
        serverUrl: String,
        token: String,
        creatureId: String,
    ): String {
        val origin = requireOrigin(serverUrl)

        // The creature id is a *path segment*, not text to splice into a URL. It comes
        // from the server's `characterList`, so it is not attacker-controlled today — but
        // a `/` in it would silently re-point the request at another endpoint and a `?`
        // would turn the tail into a query string, and neither failure would look like a
        // bug in this method. The builder percent-encodes the segment, so a malformed id
        // can only ever produce a 404.
        val url = origin.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("creature")
            .addPathSegment(creatureId)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            // The token goes in a header, never in the URL (docs/design/05-security.md).
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()

        val (code, body) = execute(request, origin)

        when {
            code == 401 || code == 403 -> throw ApiException.TokenRejected()
            code == 404 -> throw ApiException.NotFound("creature $creatureId")
            code == 429 -> throw ApiException.TooManyRequests()
            code in 500..599 -> throw ApiException.ServerError(code)
            code !in 200..299 -> throw ApiException.NotADiceCloudServer(origin, code)
        }

        // Cheapest possible sanity check — WP4 owns real parsing. This only proves
        // we got JSON rather than an HTML error page from a misconfigured proxy.
        body.asJsonObjectOr { throw ApiException.MalformedResponse("body is not a JSON object") }

        return body
    }

    private fun requireOrigin(serverUrl: String): String =
        when (val result = normalizeServerUrl(serverUrl)) {
            is ServerUrlResult.Valid -> result.origin
            is ServerUrlResult.Invalid -> throw ApiException.InvalidServerUrl(result.problem)
        }

    /** Runs the call off the caller's thread and maps transport failures to [ApiException.ServerUnreachable]. */
    private suspend fun execute(request: Request, origin: String): Pair<Int, String> =
        withContext(ioDispatcher) {
            val response: Response = try {
                callFactory.newCall(request).await()
            } catch (e: IOException) {
                // No HTTP response at all: DNS, connect, TLS or timeout.
                throw ApiException.ServerUnreachable(origin, e)
            }
            response.use { it.code to it.body.string() }
        }

    private inline fun String.asJsonObjectOr(onFailure: () -> Nothing): JsonObject =
        try {
            json.parseToJsonElement(this) as? JsonObject ?: onFailure()
        } catch (_: Exception) {
            onFailure()
        }

    /**
     * The primitive's text, or `null` when there is no usable text there.
     *
     * `JsonNull` is a [JsonPrimitive] whose `content` is the four characters `null` and
     * whose `isString` is `false`, so reading `.content` off it turns `{"token": null}`
     * into the *token* `"null"` — non-empty, so it sails past the
     * [ApiException.NotADiceCloudServer] shape check and gets written to the Keystore as
     * a resume token. [contentOrNull] is the accessor that tells `null` and `"null"` apart.
     * The quoted `"null"` string is refused too: it is the same garbage with quotes on.
     */
    private fun JsonElement.jsonPrimitiveContentOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

    /**
     * `tokenExpires` is an ISO-8601 string on REST, but the same field arrives as
     * EJSON `{"$date": <millis>}` over DDP. Accept both so callers never care.
     * An unparseable value is not fatal — the app treats the token as valid until
     * the server rejects it, which it must handle anyway (tokens can be evicted
     * from the pool before their stated expiry; see docs/design/02-ddp-and-api.md).
     */
    private fun parseTokenExpires(element: kotlinx.serialization.json.JsonElement?): Long? {
        if (element == null) return null
        (element as? JsonObject)?.get("\$date")?.let { return it.jsonPrimitive.content.toLongOrNull() }
        val raw = (element as? JsonPrimitive)?.content ?: return null
        raw.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Bridges OkHttp's callback API to coroutines, propagating cancellation to the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
            // Cancelling a finished call is a no-op we don't care about.
        }
    }
}
