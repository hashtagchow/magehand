package com.hashtagchow.magehand.core.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.data.server.ServerUrlProblem
import java.net.ServerSocket

/**
 * Login and snapshot behaviour against a local MockWebServer.
 *
 * Two deliberate choices:
 * - **MockWebServer speaks https.** The client is https-only by design, so an
 *   http mock could not exercise it at all; a self-signed localhost certificate
 *   also means every test here proves the real TLS path.
 * - **No password ever leaves this JVM.** docs/design/07-build-plan.md forbids
 *   live login attempts (there is no throwaway account), so every login outcome —
 *   including success — is scripted here.
 */
class OkHttpDiceCloudApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpDiceCloudApi

    /** `localhost:<port>` — deliberately unnormalized, so every call re-proves normalization runs. */
    private lateinit var serverAddress: String

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
        serverAddress = "localhost:${server.port}"

        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        api = OkHttpDiceCloudApi(client)
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ---- login: happy path ---------------------------------------------------

    @Test
    fun `login returns the documented id token and expiry`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"id":"FakeDmUser23456ab","token":"FakeR-not-a-real-token","tokenExpires":"2026-11-15T18:32:00Z"}""",
            ),
        )

        val session = api.login(serverAddress, "DungeonMaster", "hunter2")

        assertEquals("FakeDmUser23456ab", session.userId)
        assertEquals("FakeR-not-a-real-token", session.token)
        // 2026-11-15T18:32:00Z
        assertEquals(1_794_767_520_000L, session.tokenExpiresAt)
    }

    @Test
    fun `login posts json to api login on the normalized origin`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"t1","tokenExpires":"2026-11-15T18:32:00Z"}"""))

        api.login(serverAddress, "DungeonMaster", "hunter2")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/login", recorded.target)
        assertTrue(
            "content type should be json, was ${recorded.headers["Content-Type"]}",
            recorded.headers["Content-Type"].orEmpty().startsWith("application/json"),
        )

        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals("DungeonMaster", body["username"]?.jsonPrimitive?.content)
        assertEquals("hunter2", body["password"]?.jsonPrimitive?.content)
        assertNull("an @-less identifier must be sent as username", body["email"])
    }

    @Test
    fun `an identifier containing an at sign is sent as email`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"t1"}"""))

        api.login(serverAddress, "dm@example.com", "hunter2")

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals("dm@example.com", body["email"]?.jsonPrimitive?.content)
        assertNull(body["username"])
    }

    @Test
    fun `a missing or unparseable tokenExpires is tolerated`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"t1"}"""))
        assertNull(api.login(serverAddress, "dm", "p").tokenExpiresAt)

        server.enqueue(jsonResponse(200, """{"id":"u1","token":"t1","tokenExpires":"never"}"""))
        assertNull(api.login(serverAddress, "dm", "p").tokenExpiresAt)
    }

    @Test
    fun `an EJSON date tokenExpires is accepted`() = runBlocking {
        // The same field arrives in this shape over DDP (docs/design/02-ddp-and-api.md).
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"t1","tokenExpires":{"${'$'}date":1794767520000}}"""))
        assertEquals(1_794_767_520_000L, api.login(serverAddress, "dm", "p").tokenExpiresAt)
    }

    @Test
    fun `the token never appears in LoginSession toString`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"super-secret-resume-token"}"""))
        val session = api.login(serverAddress, "dm", "p")
        assertTrue(
            "toString leaked the token: $session",
            !session.toString().contains("super-secret-resume-token"),
        )
    }

    // ---- login: the distinction that matters ---------------------------------

    @Test
    fun `401 403 and 400 are wrong credentials, not a wrong server`() = runBlocking {
        for (code in listOf(400, 401, 403)) {
            server.enqueue(jsonResponse(code, """{"error":"unauthorized"}"""))
            val thrown = assertThrows { api.login(serverAddress, "dm", "wrong") }
            assertTrue(
                "HTTP $code should be InvalidCredentials, was ${thrown::class.simpleName}",
                thrown is ApiException.InvalidCredentials,
            )
        }
    }

    @Test
    fun `an unreachable host is ServerUnreachable, not wrong credentials`() = runBlocking {
        val deadPort = ServerSocket(0).use { it.localPort } // closed immediately: nothing listens
        val thrown = assertThrows { api.login("localhost:$deadPort", "dm", "p") }
        assertTrue(
            "expected ServerUnreachable, was ${thrown::class.simpleName}",
            thrown is ApiException.ServerUnreachable,
        )
    }

    @Test
    fun `a 404 on api login means this is not a DiceCloud server`() = runBlocking {
        server.enqueue(jsonResponse(404, "<html>Not Found</html>"))
        val thrown = assertThrows { api.login(serverAddress, "dm", "p") }
        assertTrue(thrown is ApiException.NotADiceCloudServer)
        assertEquals(404, (thrown as ApiException.NotADiceCloudServer).httpCode)
    }

    @Test
    fun `a 200 that is not json is not a DiceCloud server`() = runBlocking {
        // The captive-portal / reverse-proxy-landing-page case.
        server.enqueue(jsonResponse(200, "<html><body>Welcome to nginx</body></html>"))
        val thrown = assertThrows { api.login(serverAddress, "dm", "p") }
        assertTrue("expected NotADiceCloudServer, was ${thrown::class.simpleName}", thrown is ApiException.NotADiceCloudServer)
    }

    @Test
    fun `a 200 json without id or token is not a DiceCloud server`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"status":"ok"}"""))
        assertTrue(assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.NotADiceCloudServer)

        server.enqueue(jsonResponse(200, """{"id":"u1"}"""))
        assertTrue(assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.NotADiceCloudServer)
    }

    @Test
    fun `a JSON null token is not a token`() = runBlocking {
        // `JsonNull` is a `JsonPrimitive` whose `content` is the four characters `null`,
        // so a naive `.content` read stored the string "null" as a resume token: the app
        // would then look signed in and fail every call with it.
        server.enqueue(jsonResponse(200, """{"id":"u1","token":null}"""))
        assertTrue(
            "a null token must fail the shape check, not become the token \"null\"",
            assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.NotADiceCloudServer,
        )

        server.enqueue(jsonResponse(200, """{"id":null,"token":"t1"}"""))
        assertTrue(assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.NotADiceCloudServer)

        // The same garbage with quotes on it is refused for the same reason.
        server.enqueue(jsonResponse(200, """{"id":"u1","token":"null"}"""))
        assertTrue(assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.NotADiceCloudServer)
    }

    @Test
    fun `5xx is a transient server error`() = runBlocking {
        server.enqueue(jsonResponse(503, "unavailable"))
        val thrown = assertThrows { api.login(serverAddress, "dm", "p") }
        assertTrue(thrown is ApiException.ServerError)
        assertEquals(503, (thrown as ApiException.ServerError).httpCode)
    }

    @Test
    fun `429 is surfaced as rate limiting`() = runBlocking {
        server.enqueue(jsonResponse(429, """{"error":"too-many-requests"}"""))
        assertTrue(assertThrows { api.login(serverAddress, "dm", "p") } is ApiException.TooManyRequests)
    }

    @Test
    fun `an http server url is rejected before any request is made`() = runBlocking {
        val thrown = assertThrows { api.login("http://dicecloud.com", "dm", "p") }
        assertTrue(thrown is ApiException.InvalidServerUrl)
        assertEquals(ServerUrlProblem.INSECURE_SCHEME, (thrown as ApiException.InvalidServerUrl).problem)
        assertEquals("no request should have been made", 0, server.requestCount)
    }

    // ---- snapshot -------------------------------------------------------------

    @Test
    fun `fetchCreatureSnapshot returns the raw body and sends a bearer header`() = runBlocking {
        val payload = """{"creatures":[{"_id":"FakeCreature23456"}],"creatureProperties":[],"creatureVariables":{}}"""
        server.enqueue(jsonResponse(200, payload))

        val body = api.fetchCreatureSnapshot(serverAddress, "tok-123", "FakeCreature23456")

        assertEquals("the body must be passed through byte-for-byte for WP4", payload, body)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/creature/FakeCreature23456", recorded.target)
        assertEquals("Bearer tok-123", recorded.headers["Authorization"])
        assertTrue(
            "the token must never appear in the URL (docs/design/05-security.md)",
            !recorded.target.contains("tok-123"),
        )
    }

    @Test
    fun `a creature id cannot escape the api creature path`() = runBlocking {
        // Interpolating the id into the URL let a `/` re-point the request at another
        // endpoint and a `?` turn the rest of it into a query string. The id is a path
        // segment and has to be encoded as one.
        server.enqueue(jsonResponse(200, "{}"))

        api.fetchCreatureSnapshot(serverAddress, "t", "../../api/login?x=1")

        val target = server.takeRequest().target
        assertTrue(
            "the request must still be under /api/creature/, was $target",
            target.startsWith("/api/creature/"),
        )
        val segment = target.removePrefix("/api/creature/")
        assertTrue("the id must stay one path segment, was $segment", !segment.contains('/'))
        assertTrue("the id must not open a query string, was $segment", !segment.contains('?'))
    }

    @Test
    fun `a rejected token is distinguishable from a missing creature`() = runBlocking {
        server.enqueue(jsonResponse(401, """{"error":"unauthorized"}"""))
        assertTrue(assertThrows { api.fetchCreatureSnapshot(serverAddress, "t", "x") } is ApiException.TokenRejected)

        server.enqueue(jsonResponse(404, """{"error":"not found"}"""))
        assertTrue(assertThrows { api.fetchCreatureSnapshot(serverAddress, "t", "x") } is ApiException.NotFound)
    }

    @Test
    fun `a non json snapshot body is a malformed response`() = runBlocking {
        server.enqueue(jsonResponse(200, "<html>502 Bad Gateway</html>"))
        assertTrue(
            assertThrows { api.fetchCreatureSnapshot(serverAddress, "t", "x") } is ApiException.MalformedResponse,
        )
    }

    // ---- helpers ---------------------------------------------------------------

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()

    /** Runs [block] and returns the [ApiException] it threw, failing the test if it did not throw. */
    private inline fun assertThrows(block: () -> Unit): ApiException =
        try {
            block()
            error("expected an ApiException but the call succeeded")
        } catch (e: ApiException) {
            e
        }
}
