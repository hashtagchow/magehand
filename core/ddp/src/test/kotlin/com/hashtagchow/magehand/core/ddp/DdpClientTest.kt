package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ConnectionState
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The DDP protocol machine, driven end-to-end against a scripted fake websocket.
 * Every message shape here is taken verbatim from docs/design/02-ddp-and-api.md.
 */
class DdpClientTest {

    private val server = FakeDdpServer()
    private var client: DdpClient? = null

    @After
    fun tearDown() {
        client?.close()
    }

    private fun newClient(
        token: String? = TOKEN,
        heartbeatInterval: Duration = Duration.ZERO,
        heartbeatTimeout: Duration = 500.milliseconds,
        tokenProvider: (suspend () -> String?)? = null,
    ): DdpClient = DdpClient(
        socketFactory = server,
        config = DdpClientConfig(
            handshakeTimeout = 5.seconds,
            methodTimeout = 5.seconds,
            heartbeatInterval = heartbeatInterval,
            heartbeatTimeout = heartbeatTimeout,
            backoff = NoBackoff,
            logger = { println("[ddp] $it") },
        ),
        resumeTokenProvider = tokenProvider ?: { token },
    ).also { client = it }

    // ------------------------------------------------------------- happy path

    @Test
    fun handshake_login_subscribe_and_mirror() = runBlocking {
        val client = newClient()
        client.start()

        val socket = server.awaitSocket(0)
        val connect = socket.awaitFrame("connect")
        assertEquals("1", connect.string("version"))
        assertEquals(listOf("1"), (connect["support"] as JsonArray).map { (it as JsonPrimitive).content })

        socket.emit("""{"msg":"connected","session":"session-1"}""")

        val login = socket.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        val resume = ((login["params"] as JsonArray)[0] as JsonObject).string("resume")
        assertEquals(TOKEN, resume)
        socket.completeMethod(login.string("id")!!, LOGIN_RESULT)

        client.awaitLive(5.seconds)
        assertEquals(ConnectionState.LIVE, client.connectionState.value)
        assertEquals("user-1", client.userId.value)
        assertEquals("session-1", client.sessionId)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        val subFrame = socket.awaitFrame("sub")
        assertEquals("singleCharacter", subFrame.string("name"))
        assertEquals(listOf(CREATURE), (subFrame["params"] as JsonArray).map { (it as JsonPrimitive).content })
        assertTrue("sub id must be a Meteor id", MeteorId.isValid(subFrame.string("id")!!))
        assertEquals(sub.id, subFrame.string("id"))

        socket.emit(added("creatureProperties", "p1", """"name":"1st Level","value":3,"total":4"""))
        socket.emit(added("creatureProperties", "p2", """"name":"2nd Level","value":2,"total":2"""))
        socket.emit(added("creatures", CREATURE, """"name":"Sabriel""""))
        socket.emit("""{"msg":"ready","subs":["${sub.id}"]}""")

        sub.awaitReady(5.seconds)

        // awaitReady() returning must imply the documents are already visible.
        assertEquals(2, client.mirror.size("creatureProperties"))
        assertEquals(1, client.mirror.size("creatures"))
        val slot = client.mirror.document("creatureProperties", "p1")!!
        assertEquals("1st Level", slot.string("name"))
        assertEquals(3, (slot["value"] as JsonPrimitive).intOrNull)
        assertEquals("p1", slot.string("_id")) // _id is injected from the DDP envelope

        // live update
        socket.emit("""{"msg":"changed","collection":"creatureProperties","id":"p1","fields":{"value":2}}""")
        awaitUntil(what = "value=2") {
            (client.mirror.document("creatureProperties", "p1")?.get("value") as? JsonPrimitive)?.intOrNull == 2
        }
        assertEquals("1st Level", client.mirror.document("creatureProperties", "p1")!!.string("name"))

        socket.emit("""{"msg":"removed","collection":"creatureProperties","id":"p2"}""")
        awaitUntil(what = "p2 removed") { client.mirror.size("creatureProperties") == 1 }
    }

    // ------------------------------------------------------------ method calls

    @Test
    fun method_completes_only_after_both_result_and_updated() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val call = callAsync(client, "creatureProperties.damage", mapOf("_id" to "p1", "operation" to "increment", "value" to 1))
        val frame = socket.awaitFrame { it.msg == "method" && it.string("method") == "creatureProperties.damage" }
        val id = frame.string("id")!!

        socket.emit("""{"msg":"result","id":"$id","result":{"ok":true}}""")
        Thread.sleep(200)
        assertFalse("result alone must not complete the call", call.isCompleted)

        socket.emit("""{"msg":"updated","methods":["$id"]}""")
        val result = call.await().getOrThrow()
        assertEquals(true, ((result as JsonObject)["ok"] as JsonPrimitive).content.toBoolean())
    }

    /**
     * The live DiceCloud server sends `updated` **before** `result` for `login`
     * (observed 2026-08-17 — see docs/verification/WP2.md). 02-ddp-and-api.md shows
     * them the other way round, so both orders must complete the call.
     */
    @Test
    fun updated_may_arrive_before_result() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val call = callAsync(client, "creature.methods.rest", mapOf("creatureId" to CREATURE, "restType" to "longRest"))
        val frame = socket.awaitFrame { it.msg == "method" && it.string("method") == "creature.methods.rest" }
        val id = frame.string("id")!!
        socket.emit("""{"msg":"updated","methods":["$id"]}""")
        Thread.sleep(150)
        assertFalse("updated alone must not complete the call", call.isCompleted)
        socket.emit("""{"msg":"result","id":"$id","result":null}""")
        assertEquals("null", call.await().getOrThrow().toString())
    }

    /**
     * The live server pushes a `users` document during the login exchange, before any
     * subscription exists. That document must not be swept away by the first
     * quiescence pass (regression: the resync window used to open after login).
     */
    @Test
    fun documents_pushed_during_login_survive_the_first_quiescence() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect")
        socket.emit("""{"msg":"connected","session":"session-1"}""")
        val login = socket.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        val id = login.string("id")!!
        socket.emit(added("users", "user-1", """"username":"DungeonMaster""""))
        socket.emit("""{"msg":"updated","methods":["$id"]}""")
        socket.emit("""{"msg":"result","id":"$id","result":{"id":"user-1"}}""")

        client.awaitLive(5.seconds)
        awaitUntil(what = "users doc published") { client.mirror.size("users") == 1 }
        Thread.sleep(200)
        assertEquals("the login-pushed doc must survive quiescence", 1, client.mirror.size("users"))
    }

    @Test
    fun method_error_surfaces_typed_DdpError() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val call = callAsync(client, "creatureProperties.damage", mapOf("_id" to "p1"))
        val frame = socket.awaitFrame { it.msg == "method" && it.string("method") == "creatureProperties.damage" }
        val id = frame.string("id")!!
        socket.emit(
            """{"msg":"result","id":"$id","error":{"error":"too-many-requests","reason":"Too many requests",""" +
                """"details":"try again in 5 seconds","errorType":"Meteor.Error"}}"""
        )
        socket.emit("""{"msg":"updated","methods":["$id"]}""")

        val error = call.await().exceptionOrNull()
        assertTrue("expected DdpError, got $error", error is DdpError)
        error as DdpError
        assertEquals("too-many-requests", error.error)
        assertEquals("Too many requests", error.reason)
        assertEquals("try again in 5 seconds", error.detailsText)
        assertEquals("Meteor.Error", error.errorType)
        assertTrue(error.isRateLimit)
        assertFalse(error.isAuthError)
        assertNull(error.errorCode)
    }

    @Test
    fun numeric_403_error_is_recognised_as_auth() {
        val error = DdpError.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"error":403,"reason":"You've been logged out by the server","errorType":"Meteor.Error"}"""
            ) as JsonObject
        )
        assertEquals("403", error.error)
        assertEquals(403, error.errorCode)
        assertTrue(error.isAuthError)
    }

    @Test
    fun call_while_not_live_fails_fast() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect") // handshake deliberately left unfinished

        val error = runCatching { client.call("creature.methods.rest") }.exceptionOrNull()
        assertTrue("expected DdpConnectionException, got $error", error is DdpConnectionException)
    }

    // -------------------------------------------------------- ready semantics

    @Test
    fun ready_arrives_out_of_order() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val first = client.subscribe("characterList")
        val second = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        socket.awaitFrame { it.msg == "sub" && it.string("name") == "characterList" }
        socket.awaitFrame { it.msg == "sub" && it.string("name") == "singleCharacter" }

        // The server readies the *second* subscription first.
        socket.emit("""{"msg":"ready","subs":["${second.id}"]}""")
        second.awaitReady(5.seconds)
        assertTrue(second.isReady.value)
        assertFalse("characterList must not be marked ready", first.isReady.value)

        socket.emit("""{"msg":"ready","subs":["${first.id}"]}""")
        first.awaitReady(5.seconds)
        assertTrue(first.isReady.value)
    }

    @Test
    fun one_ready_frame_can_carry_several_subs() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val a = client.subscribe("characterList")
        val b = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        socket.awaitFrame { it.msg == "sub" && it.string("name") == "singleCharacter" }
        socket.emit("""{"msg":"ready","subs":["${a.id}","${b.id}"]}""")
        a.awaitReady(5.seconds)
        b.awaitReady(5.seconds)
    }

    @Test
    fun nosub_with_error_fails_awaitReady() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams("nope"))
        socket.awaitFrame("sub")
        socket.emit(
            """{"msg":"nosub","id":"${sub.id}","error":{"error":404,"reason":"Creature not found","errorType":"Meteor.Error"}}"""
        )

        val error = runCatching { sub.awaitReady(5.seconds) }.exceptionOrNull()
        assertTrue("expected DdpError, got $error", error is DdpError)
        assertEquals("404", (error as DdpError).error)
        assertTrue(sub.isStopped.value)
        assertFalse(client.activeSubscriptions().containsKey(sub.id))
    }

    @Test
    fun unsub_stops_the_subscription_and_it_is_not_replayed() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        socket.awaitFrame("sub")
        sub.stop()
        val unsub = socket.awaitFrame("unsub")
        assertEquals(sub.id, unsub.string("id"))
        assertTrue(client.activeSubscriptions().isEmpty())

        socket.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        client.awaitLive(5.seconds)
        assertTrue("a stopped sub must not be replayed", second.sentFramesOf("sub").isEmpty())
    }

    // ------------------------------------------------------------- ping / pong

    @Test
    fun server_ping_is_answered_with_a_matching_pong() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        socket.emit("""{"msg":"ping","id":"srv-7"}""")
        val pong = socket.awaitFrame { it.msg == "pong" }
        assertEquals("srv-7", pong.string("id"))

        // Meteor's own server heartbeat sends an id-less ping; the reply must be id-less.
        socket.emit("""{"msg":"ping"}""")
        val bare = socket.awaitFrame { it.msg == "pong" && it["id"] == null }
        assertNull(bare["id"])
    }

    @Test
    fun client_sends_its_own_pings_and_a_pong_keeps_the_session_alive() = runBlocking {
        val client = newClient(heartbeatInterval = 100.milliseconds, heartbeatTimeout = 2.seconds)
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        repeat(3) {
            val ping = socket.awaitFrame { it.msg == "ping" && it.string("id")?.startsWith("hb-") == true }
            socket.emit("""{"msg":"pong","id":"${ping.string("id")}"}""")
        }
        Thread.sleep(150)
        assertEquals(ConnectionState.LIVE, client.connectionState.value)
        assertEquals("no reconnect expected while pongs keep coming", 1, server.socketCount)
    }

    @Test
    fun missing_pong_drops_the_socket_and_reconnects() = runBlocking {
        val client = newClient(heartbeatInterval = 100.milliseconds, heartbeatTimeout = 200.milliseconds)
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        socket.awaitFrame { it.msg == "ping" } // deliberately never answered
        val second = server.awaitSocket(1, timeoutMs = 5_000)
        second.completeHandshake(session = "session-2")
        client.awaitLive(5.seconds)
        assertEquals(ConnectionState.LIVE, client.connectionState.value)
    }

    // ----------------------------------------------------------------- EJSON

    @Test
    fun ejson_dates_survive_the_mirror_and_method_params() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        socket.awaitFrame("sub")
        socket.emit(
            """{"msg":"added","collection":"creatureProperties","id":"p1","fields":""" +
                """{"name":"Heroic Inspiration","dateCreated":{"${'$'}date":1755448320000},""" +
                """"nested":{"at":{"${'$'}date":1000}}}}"""
        )
        socket.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)

        val doc = client.mirror.document("creatureProperties", "p1")!!
        assertEquals(Instant.ofEpochMilli(1_755_448_320_000), doc.ejsonInstant("dateCreated"))
        // the raw EJSON is preserved verbatim, at any depth
        assertEquals(
            Instant.ofEpochMilli(1_000),
            Ejson.toInstantOrNull((doc["nested"] as JsonObject)["at"]),
        )
        @Suppress("UNCHECKED_CAST")
        val decoded = Ejson.decode(doc) as Map<String, Any?>
        assertEquals(Instant.ofEpochMilli(1_755_448_320_000), decoded["dateCreated"])

        // and Instants go back out as {"$date": ms}
        val call = callAsync(client, "creatures.update", mapOf("_id" to CREATURE, "value" to Instant.ofEpochMilli(42)))
        val frame = socket.awaitFrame { it.msg == "method" && it.string("method") == "creatures.update" }
        val param = (frame["params"] as JsonArray)[0] as JsonObject
        assertEquals("""{"${'$'}date":42}""", (param["value"] as JsonObject).toString())
        socket.completeMethod(frame.string("id")!!, "{}")
        call.await().getOrThrow()
        Unit
    }

    @Test
    fun changed_with_cleared_drops_only_the_cleared_fields() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        socket.awaitFrame("sub")
        socket.emit(added("creatureProperties", "p1", """"name":"Bless","damage":2,"deactivatedByAncestor":true"""))
        socket.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)

        socket.emit(
            """{"msg":"changed","collection":"creatureProperties","id":"p1",""" +
                """"fields":{"name":"Bless (concentrating)"},"cleared":["damage","deactivatedByAncestor"]}"""
        )
        awaitUntil(what = "cleared fields gone") {
            client.mirror.document("creatureProperties", "p1")?.containsKey("damage") == false
        }

        val doc = client.mirror.document("creatureProperties", "p1")!!
        assertEquals("Bless (concentrating)", doc.string("name"))
        assertFalse(doc.containsKey("damage"))
        assertFalse(doc.containsKey("deactivatedByAncestor"))
        assertEquals("p1", doc.string("_id"))
    }

    // -------------------------------------------------------------- reconnect

    @Test
    fun reconnect_mid_subscription_relogs_resubscribes_and_reconciles_the_mirror() = runBlocking {
        val logins = AtomicInteger(0)
        val client = newClient(tokenProvider = { "TOKEN-${logins.incrementAndGet()}" })
        client.start()

        val first = server.awaitSocket(0)
        first.awaitFrame("connect")
        first.emit("""{"msg":"connected","session":"session-1"}""")
        val login1 = first.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        assertEquals("TOKEN-1", ((login1["params"] as JsonArray)[0] as JsonObject).string("resume"))
        first.completeMethod(login1.string("id")!!, LOGIN_RESULT)
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        first.awaitFrame("sub")
        first.emit(added("creatureProperties", "p1", """"name":"1st Level","value":3"""))
        first.emit(added("creatureProperties", "p2", """"name":"2nd Level","value":2"""))
        first.emit(added("creatureProperties", "p3", """"name":"Doomed Spell","value":1"""))
        first.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)
        assertEquals(3, client.mirror.size("creatureProperties"))

        // --- wifi drops mid-session -------------------------------------------
        first.dropConnection()
        awaitUntil(what = "CONNECTING") { client.connectionState.value == ConnectionState.CONNECTING }
        assertFalse("a dropped socket must un-ready its subs", sub.isReady.value)
        assertEquals("the mirror must NOT be blanked on disconnect", 3, client.mirror.size("creatureProperties"))

        val second = server.awaitSocket(1)
        val connect2 = second.awaitFrame("connect")
        assertNull("we must not try to resume the old session", connect2["session"])
        second.emit("""{"msg":"connected","session":"session-2"}""")

        val login2 = second.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        assertEquals(
            "the token must be re-read, not cached",
            "TOKEN-2",
            ((login2["params"] as JsonArray)[0] as JsonObject).string("resume"),
        )
        second.completeMethod(login2.string("id")!!, LOGIN_RESULT)

        // the subscription is replayed with its original id
        val resub = second.awaitFrame("sub")
        assertEquals(sub.id, resub.string("id"))
        assertEquals("singleCharacter", resub.string("name"))
        assertEquals(listOf(CREATURE), (resub["params"] as JsonArray).map { (it as JsonPrimitive).content })

        client.awaitLive(5.seconds)
        assertEquals("still serving stale data until quiescence", 3, client.mirror.size("creatureProperties"))

        // The server replays p1 (changed) and p2, but p3 was deleted while we were away.
        second.emit(added("creatureProperties", "p1", """"name":"1st Level","value":1"""))
        second.emit(added("creatureProperties", "p2", """"name":"2nd Level","value":2"""))
        second.emit(added("creatureProperties", "p4", """"name":"3rd Level","value":1"""))
        assertEquals("nothing may be dropped before ready", 3 + 1, client.mirrorAfterFlush())

        second.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)

        // quiescence: p3 is gone, p1 has the new value, p2 untouched, p4 added
        awaitUntil(what = "p3 dropped at quiescence") { client.mirror.document("creatureProperties", "p3") == null }
        val docs = client.mirror.documents("creatureProperties")
        assertEquals(setOf("p1", "p2", "p4"), docs.keys)
        assertEquals(1, (docs["p1"]!!["value"] as JsonPrimitive).intOrNull)
        assertEquals(2, (docs["p2"]!!["value"] as JsonPrimitive).intOrNull)
        assertEquals("user-1", client.userId.value)
        assertEquals("session-2", client.sessionId)
    }

    @Test
    fun in_flight_method_fails_when_the_socket_dies_and_is_not_replayed() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val call = callAsync(client, "creatureProperties.damage", mapOf("_id" to "p1"))
        socket.awaitFrame { it.msg == "method" && it.string("method") == "creatureProperties.damage" }
        socket.dropConnection()

        val error = call.await().exceptionOrNull()
        assertTrue("expected DdpConnectionException, got $error", error is DdpConnectionException)

        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        client.awaitLive(5.seconds)
        assertTrue(
            "a method in flight when the socket died must NOT be replayed",
            second.sentFramesOf("method").none { it.string("method") == "creatureProperties.damage" },
        )
    }

    // ------------------------------------------------------------------- auth

    @Test
    fun rejected_resume_token_parks_the_client_in_AUTH_FAILED() = runBlocking {
        val client = newClient()
        client.start()
        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect")
        socket.emit("""{"msg":"connected","session":"session-1"}""")
        val login = socket.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        val id = login.string("id")!!
        socket.emit(
            """{"msg":"result","id":"$id","error":{"error":403,"reason":"You've been logged out by the server",""" +
                """"errorType":"Meteor.Error"}}"""
        )
        socket.emit("""{"msg":"updated","methods":["$id"]}""")

        awaitUntil(what = "AUTH_FAILED") { client.connectionState.value == ConnectionState.AUTH_FAILED }
        val error = client.authError
        assertNotNull(error)
        assertEquals(403, error!!.errorCode)
        assertTrue(error.isAuthError)

        val failure = runCatching { client.awaitLive(2.seconds) }.exceptionOrNull()
        assertTrue(failure is DdpError)

        // AUTH_FAILED is terminal: no reconnect storm against a server that said 403.
        server.assertNoSocketBeyond(0)
    }

    @Test
    fun anonymous_connection_skips_login() = runBlocking {
        val client = newClient(token = null)
        client.start()
        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect")
        socket.emit("""{"msg":"connected","session":"session-1"}""")
        client.awaitLive(5.seconds)
        assertTrue(socket.sentFramesOf("method").isEmpty())
        assertNull(client.userId.value)
    }

    // --------------------------------------------------------------- helpers

    /**
     * Runs a method off the test thread. The call is wrapped in [runCatching] *inside*
     * the coroutine so a failing method surfaces as a value rather than cancelling the
     * whole `runBlocking` scope.
     */
    private fun CoroutineScope.callAsync(
        client: DdpClient,
        method: String,
        args: Map<String, Any?>,
    ): Deferred<Result<kotlinx.serialization.json.JsonElement>> =
        async(Dispatchers.IO) { runCatching { client.call(method, ejsonParams(args)) } }

    /** Forces a mirror read after the pump has settled; returns the doc count. */
    private fun DdpClient.mirrorAfterFlush(): Int {
        awaitUntil(what = "mirror flush") { mirror.size("creatureProperties") >= 4 }
        return mirror.size("creatureProperties")
    }

    private companion object {
        const val TOKEN = "FakeResumeToken0FakeResumeToken0"
        const val CREATURE = "FakeCreature23456"
        val LOGIN_RESULT = """{"id":"user-1","token":"$TOKEN","tokenExpires":{"${'$'}date":1786000000000}}"""

        fun added(collection: String, id: String, fields: String) =
            """{"msg":"added","collection":"$collection","id":"$id","fields":{$fields}}"""
    }
}

/** Completes a method the client sent: `result` then `updated`, as Meteor does. */
private fun FakeSocket.completeMethod(id: String, resultJson: String) {
    emit("""{"msg":"result","id":"$id","result":$resultJson}""")
    emit("""{"msg":"updated","methods":["$id"]}""")
}

/** connect → connected → login → result+updated. */
private fun FakeSocket.completeHandshake(
    session: String = "session-1",
    userId: String = "user-1",
) {
    awaitFrame("connect")
    emit("""{"msg":"connected","session":"$session"}""")
    val login = awaitFrame { it.msg == "method" && it.string("method") == "login" }
    completeMethod(
        login.string("id")!!,
        """{"id":"$userId","tokenExpires":{"${'$'}date":1786000000000}}""",
    )
}
