package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.model.ConnectionState
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * WP2 acceptance probe against the **live** table server
 * (docs/design/07-build-plan.md WP2, 08-testing-and-release.md §2).
 *
 * Gated on `MAGEHAND_IT=1`:
 * ```
 * MAGEHAND_IT=1 ./gradlew :core:ddp:test --tests '*Integration*'
 * ```
 *
 * **READ-ONLY.** The only method this test calls is `login`. It never calls
 * `creatureProperties.damage`, `adjustQuantity`, `flipToggle`, `update` or
 * `creature.methods.rest` — party sheets are read-only for all tests
 * (08-testing-and-release.md, "Test data"). Mutation probes wait for WP7 and target
 * the dedicated "MageHand Test Dummy" creature.
 */
class DdpLiveIntegrationTest {

    private val frames = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun requireOptIn() {
        assumeTrue(
            "live integration test — set MAGEHAND_IT=1 to run it",
            System.getenv("MAGEHAND_IT") == "1",
        )
    }

    @Test
    fun connects_logs_in_and_mirrors_sabriel() = runBlocking {
        val client = DdpClient.okHttp(
            url = URL,
            config = DdpClientConfig(
                handshakeTimeout = 30.seconds,
                methodTimeout = 30.seconds,
                // short enough that one test run exercises client→server pings
                heartbeatInterval = 8.seconds,
                heartbeatTimeout = 15.seconds,
                logger = ::record,
            ),
            resumeTokenProvider = { TOKEN },
        )

        try {
            val connectStart = System.currentTimeMillis()
            client.connect(45.seconds)
            println("== connected+logged in in ${System.currentTimeMillis() - connectStart} ms")
            assertEquals(ConnectionState.LIVE, client.connectionState.value)
            assertEquals("resume login must return the DungeonMaster user id", USER_ID, client.userId.value)
            assertNotNull("server must hand out a session id", client.sessionId)

            val subStart = System.currentTimeMillis()
            val sub = client.subscribe("singleCharacter", ejsonParams(SABRIEL))
            sub.awaitReady(120.seconds)
            val subMillis = System.currentTimeMillis() - subStart

            val properties = client.mirror.documents("creatureProperties")
            val creatures = client.mirror.documents("creatures")
            val variables = client.mirror.documents("creatureVariables")

            println("== singleCharacter($SABRIEL) ready in $subMillis ms")
            println("== mirror: " + client.mirror.snapshot().mapValues { it.value.size })

            assertTrue(
                "expected >= 500 creatureProperties, got ${properties.size}",
                properties.size >= 500,
            )
            assertNotNull("the creature itself must be in the mirror", creatures[SABRIEL])
            assertEquals(CREATURE_NAME, creatures.getValue(SABRIEL).text("name"))
            assertTrue("creatureVariables should be published too", variables.isNotEmpty())

            // The login exchange itself pushes a `users` document *before* we ever
            // subscribe (singleCharacter later adds the creature's owner). The
            // login-pushed one must survive the first quiescence pass.
            val users = client.mirror.documents("users")
            println("== users in mirror: ${users.keys}")
            assertTrue(
                "the login-pushed users document must survive quiescence, got ${users.keys}",
                users.containsKey(USER_ID),
            )

            // every document carries the injected _id
            assertTrue(properties.all { (id, doc) -> doc.text("_id") == id })

            // shape spot-checks from docs/dicecloud-api.md
            val slots = properties.values.filter {
                it.text("type") == "attribute" && it.text("attributeType") == "spellSlot"
            }
            val resources = properties.values.filter {
                it.text("type") == "attribute" && it.text("attributeType") == "resource"
            }
            println("== spellSlot properties: ${slots.size}, resource properties: ${resources.size}")
            println("== spellSlot names: " + slots.mapNotNull { it.text("name") }.sorted())
            assertTrue("expected spell-slot attributes on Sabriel", slots.isNotEmpty())

            // EJSON on the wire: creatureProperties carry no dates, but creatureLogs do
            // (`date`), so this is where the live EJSON path is actually exercised.
            val logs = client.mirror.documents("creatureLogs")
            val dated = logs.values.filter { Ejson.isDate(it["date"]) }
            println("== creatureLogs with an EJSON date: ${dated.size}/${logs.size}")
            dated.take(3).forEach { println("==   ${it["date"]} → ${it.ejsonInstant("date")}") }
            assertTrue("expected EJSON dates on creatureLogs", dated.isNotEmpty())
            assertTrue(
                "decoded dates must be plausible epoch-millis instants",
                dated.all { it.ejsonInstant("date")!!.toEpochMilli() > 1_600_000_000_000L },
            )
            assertTrue(
                "creatureProperties carry no top-level EJSON dates on this server",
                properties.values.none { doc -> doc.values.any { Ejson.isDate(it) } },
            )

            // Hold the connection open long enough to exercise the heartbeat: we ping
            // every 8 s and the server must pong. (Meteor's own server→client ping only
            // fires on an *idle* connection, so our pings suppress it — the inbound
            // ping path is covered by the unit tests instead.)
            Thread.sleep(25_000)
            val pongs = frames.toList().count { it.startsWith("← ") && it.contains("\"msg\":\"pong\"") }
            println("== pongs received during the 25 s heartbeat window: $pongs")
            assertTrue("the server must answer our DDP pings", pongs >= 2)
            assertEquals(
                "connection must survive the heartbeat window",
                ConnectionState.LIVE,
                client.connectionState.value,
            )

            sub.stop()
            println(protocolSummary())
        } finally {
            client.close()
        }
    }

    /**
     * The auth-failure path against the real server: a syntactically valid but
     * unknown resume token must land us in AUTH_FAILED rather than a reconnect loop.
     * Still read-only — a rejected `login` writes nothing.
     */
    @Test
    fun invalid_resume_token_is_rejected_with_403() = runBlocking {
        val client = DdpClient.okHttp(
            url = URL,
            config = DdpClientConfig(
                handshakeTimeout = 30.seconds,
                methodTimeout = 20.seconds,
                heartbeatInterval = kotlin.time.Duration.ZERO,
                logger = ::record,
            ),
            resumeTokenProvider = { "ThisTokenIsNotRealAndNeverWas000" },
        )
        try {
            client.start()
            val error = runCatching { client.awaitLive(40.seconds) }.exceptionOrNull()
            println("== invalid-token outcome: state=${client.connectionState.value} error=$error")
            assertEquals(ConnectionState.AUTH_FAILED, client.connectionState.value)
            val authError = client.authError
            assertNotNull("expected a typed DdpError for the rejected token", authError)
            println("== authError: error=${authError!!.error} reason=${authError.reason} type=${authError.errorType}")
            assertTrue("rejected token must classify as an auth error", authError.isAuthError)
            println(protocolSummary())
        } finally {
            client.close()
        }
    }

    // -------------------------------------------------------------- observation

    private fun record(line: String) {
        frames += line
        if (line.length <= 400) println(line) else println(line.take(400) + "…")
    }

    /** Histogram of the DDP message types the live server actually sent us. */
    private fun protocolSummary(): String {
        val inbound = frames.toList().filter { it.startsWith("← ") }.map { it.removePrefix("← ") }
        val histogram = inbound
            .mapNotNull { runCatching { (Json.parseToJsonElement(it) as JsonObject).text("msg") }.getOrNull() ?: "<no msg key>" }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        return buildString {
            appendLine("== inbound DDP frames by type: $histogram")
            appendLine("== total inbound frames: ${inbound.size}")
            val firstFrame = inbound.firstOrNull()
            appendLine("== first inbound frame: $firstFrame")
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        // Live-probe endpoints come from the environment so no server, token or id
        // is baked into the source. Point them at YOUR server + a creature your
        // token may read. The probe is read-only either way (class doc above).
        // Lazy on purpose: companion initializers run in the test class's static init,
        // BEFORE the MAGEHAND_IT assumption gate - an eager error() would fail (not
        // skip) every ungated run. by lazy defers the error() to first gated access.
        val URL: String by lazy { System.getenv("MAGEHAND_IT_WS_URL") ?: "wss://dicecloud.com/websocket" }
        val TOKEN: String by lazy { System.getenv("MAGEHAND_IT_TOKEN") ?: error("set MAGEHAND_IT_TOKEN") }
        val USER_ID: String by lazy { System.getenv("MAGEHAND_IT_USER_ID") ?: error("set MAGEHAND_IT_USER_ID") }
        val SABRIEL: String by lazy { System.getenv("MAGEHAND_IT_CREATURE_ID") ?: error("set MAGEHAND_IT_CREATURE_ID") }
        val CREATURE_NAME: String by lazy { System.getenv("MAGEHAND_IT_CREATURE_NAME") ?: error("set MAGEHAND_IT_CREATURE_NAME") }
    }
}
