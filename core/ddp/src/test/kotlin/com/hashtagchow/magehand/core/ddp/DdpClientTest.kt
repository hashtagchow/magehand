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
        resubscribeStagger: Duration = DdpClientConfig().resubscribeStagger,
        resubscribeRetryDelay: Duration = DdpClientConfig().resubscribeRetryDelay,
        tokenProvider: (suspend () -> String?)? = null,
        // BUG-16: the default sink is the one every other test wants — printed, and nothing
        // else. The redaction tests pass a collector so they can read back what a sink saw,
        // which is the only place the claim "no sink is handed the token" can be checked.
        logger: (String) -> Unit = { println("[ddp] $it") },
    ): DdpClient = DdpClient(
        socketFactory = server,
        config = DdpClientConfig(
            handshakeTimeout = 5.seconds,
            methodTimeout = 5.seconds,
            heartbeatInterval = heartbeatInterval,
            heartbeatTimeout = heartbeatTimeout,
            backoff = NoBackoff,
            resubscribeStagger = resubscribeStagger,
            resubscribeRetryDelay = resubscribeRetryDelay,
            logger = logger,
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

    // ------------------------------------------- reconnect: stagger and one retry

    /**
     * FR-19 hangs up to seven subscriptions off this one connection, and the server's
     * 50-per-10 s subscription limit is shared by every client at the table
     * (14-large-screen-arc.md decision 17). Firing the whole replay in a single tick
     * is the storm that rule forbids, so the *spacing* is the behaviour under test —
     * "all the subs came back" would pass just as happily against the old code.
     */
    @Test
    fun reconnect_replays_subscriptions_with_a_stagger() = runBlocking {
        val client = newClient(resubscribeStagger = 200.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val ids = (1..3).map { client.subscribe("singleCharacter", ejsonParams("creature-$it")).id }.toSet()
        awaitUntil(what = "3 subs sent") { first.sentFramesOf("sub").size == 3 }

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        awaitUntil(what = "3 subs replayed") { second.sentFramesOf("sub").size == 3 }

        val gaps = second.sentTimesOf("sub").zipWithNext { earlier, later -> later - earlier }
        assertTrue("replayed subs must not go out in one tick; gaps were $gaps ms", gaps.all { it >= 150 })
        assertEquals(ids, second.sentFramesOf("sub").mapNotNull { it.string("id") }.toSet())
    }

    /**
     * The stagger is paid *between* subs and never before the first, so every screen
     * that is not the DM view — all of which hold one or two subscriptions — must
     * reconnect at exactly the speed it did before decision 17 existed. Pinned with an
     * absurd 3 s stagger: a leading delay would be unmissable.
     */
    @Test
    fun a_single_subscription_reconnects_without_paying_the_stagger() = runBlocking {
        val client = newClient(resubscribeStagger = 3.seconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        first.awaitFrame("sub")
        first.dropConnection()

        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        val startedAt = System.currentTimeMillis()
        val replay = second.awaitFrame("sub")
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(sub.id, replay.string("id"))
        assertTrue("the lone sub waited ${elapsed}ms on a stagger it should not pay", elapsed < 1_000)
    }

    // ------------------------------------------- the replay window, adversarially
    //
    // Decision 17's stagger turned `replaySubscriptions` into a *suspending* function, and
    // that is the whole of this group's subject: for `stagger × (N - 1)` the socket is open,
    // the client dispatcher is free between delays, and the connection is NOT yet LIVE. Every
    // test above drives the replay while nothing else happens. These four drive it while
    // something does — which is what a Back press, a card opening, or a venue's wifi actually
    // look like from in here.

    /**
     * The drain loop's justification, asserted instead of argued.
     *
     * `subscribe()` adds to the map and — because the connection is not LIVE — deliberately
     * sends nothing itself, expecting the replay to carry it. A single `for` over a snapshot
     * taken before the first delay would therefore drop it silently until the *next* reconnect:
     * a DM card that stays empty with nothing in the log. The loop re-reads the map until it
     * stops producing unsent subscriptions, so the late arrival goes out on this session.
     */
    @Test
    fun subscribe_during_the_staggered_replay_is_still_sent() = runBlocking {
        val client = newClient(resubscribeStagger = 500.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val existing = (1..2).map { client.subscribe("singleCharacter", ejsonParams("creature-$it")).id }
        awaitUntil(what = "2 subs sent") { first.sentFramesOf("sub").size == 2 }

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        // The replay has started and is now inside its first stagger delay.
        second.awaitFrame("sub")

        val late = client.subscribe("singleCharacter", ejsonParams("creature-late"))
        assertTrue(
            "the fixture is wrong if the client was already LIVE — there would be no window",
            client.connectionState.value != ConnectionState.LIVE,
        )

        awaitUntil(what = "the late subscribe is replayed too") {
            second.sentFramesOf("sub").size == 3
        }
        assertEquals(
            (existing + late.id).toSet(),
            second.sentFramesOf("sub").mapNotNull { it.string("id") }.toSet(),
        )
        client.awaitLive(5.seconds)
    }

    /**
     * The same window, the other direction: a **ghost sub**.
     *
     * `pending` is a snapshot, so a subscription unsubscribed during one of the stagger delays
     * was still sent by the pass that had already decided to send it. The server then serves a
     * publication nobody holds: its documents land in the mirror and stay there — the `unsub`
     * this client sent was for a sub the server had not been told about yet — and one slot of
     * the 50-per-10 s bucket the whole table shares is spent on a screen that is gone.
     * `DmViewViewModel.onCleared` closes up to seven at once, so Back during a reconnect is the
     * ordinary way in.
     */
    @Test
    fun unsubscribe_during_the_staggered_replay_never_sends_a_ghost_sub() = runBlocking {
        val client = newClient(resubscribeStagger = 400.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val subs = (1..3).map { client.subscribe("singleCharacter", ejsonParams("creature-$it")) }
        awaitUntil(what = "3 subs sent") { first.sentFramesOf("sub").size == 3 }

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        second.awaitFrame("sub")

        // Whichever one the replay has not reached yet — the map's iteration order is not the
        // test's business, only that a not-yet-sent subscription exists to withdraw.
        val alreadySent = second.sentFramesOf("sub").mapNotNull { it.string("id") }.toSet()
        val victim = subs.first { it.id !in alreadySent }
        victim.stop()

        client.awaitLive(5.seconds)
        val replayed = second.sentFramesOf("sub").mapNotNull { it.string("id") }
        assertFalse(
            "a subscription withdrawn mid-replay must not be sent: it would be a ghost " +
                "publication with no owner and no way to stop it",
            victim.id in replayed,
        )
        assertEquals("the other two must still come back", 2, replayed.size)
        assertFalse(client.activeSubscriptions().containsKey(victim.id))
    }

    /**
     * …and the `unsub` for it has to reach the wire.
     *
     * `unsubscribe` used to send only while [ConnectionState.LIVE], which is exactly what the
     * staggered replay is not. Every `unsub` issued in that window was dropped on the floor:
     * this client forgot the subscription, the server never heard, and the publication stayed
     * open against the shared bucket for the life of the session — invisible, and impossible to
     * attribute to the screen that caused it. The gate is `session != null` now, which is the
     * question that was always being asked.
     */
    @Test
    fun unsubscribe_during_the_staggered_replay_still_reaches_the_wire() = runBlocking {
        val client = newClient(resubscribeStagger = 400.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val subs = (1..3).map { client.subscribe("singleCharacter", ejsonParams("creature-$it")) }
        awaitUntil(what = "3 subs sent") { first.sentFramesOf("sub").size == 3 }

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        second.awaitFrame("sub")

        val alreadySent = second.sentFramesOf("sub").mapNotNull { it.string("id") }.toSet()
        val victim = subs.first { it.id !in alreadySent }
        assertTrue(
            "the whole point is that this happens BEFORE the session goes live",
            client.connectionState.value != ConnectionState.LIVE,
        )
        victim.stop()

        assertEquals(
            "the unsub must go out on the session that is actually open",
            listOf(victim.id),
            second.sentFramesOf("unsub").mapNotNull { it.string("id") },
        )
        client.awaitLive(5.seconds)
    }

    /**
     * The socket dying *inside* the replay.
     *
     * The loop is suspended in a stagger delay when the wifi goes; the remaining sends land on
     * a dead socket and are lost. That is fine, and this pins why: the subscriptions are still
     * in the map — nothing about a failed send removes them — so the next session replays the
     * whole set, including the ones this one never reached. The failure this would catch is a
     * replay that consumed its subscriptions as it walked them.
     */
    @Test
    fun a_socket_dying_mid_replay_leaves_the_whole_set_for_the_next_one() = runBlocking {
        val client = newClient(resubscribeStagger = 400.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val ids = (1..3).map { client.subscribe("singleCharacter", ejsonParams("creature-$it")).id }.toSet()
        awaitUntil(what = "3 subs sent") { first.sentFramesOf("sub").size == 3 }

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        second.awaitFrame("sub")
        // Mid-stagger, with at least one subscription still unsent.
        second.dropConnection()
        assertTrue(
            "the fixture needs the replay to be genuinely incomplete",
            second.sentFramesOf("sub").size < 3,
        )

        val third = server.awaitSocket(2)
        third.completeHandshake(session = "session-3")
        awaitUntil(what = "the full set replayed on the third socket") {
            third.sentFramesOf("sub").size == 3
        }
        assertEquals(ids, third.sentFramesOf("sub").mapNotNull { it.string("id") }.toSet())
        client.awaitLive(5.seconds)
        assertEquals(ids, client.activeSubscriptions().keys)
    }

    /**
     * The defect: the shared subscription bucket can refuse a *replay* for reasons
     * that have nothing to do with us, and the old code removed such a sub from the
     * map — parking a live DM card stopped, with no data, forever, on a transient
     * server mood. One retry brings it back. The mirror must also still be serving the
     * previous session's documents while that retry is in flight: quiescence may not
     * close behind a sub that has not gone ready.
     */
    @Test
    fun a_refused_replay_is_retried_once_and_comes_back_live() = runBlocking {
        val client = newClient(resubscribeRetryDelay = 100.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        first.awaitFrame("sub")
        first.emit(added("creatureProperties", "p1", """"name":"1st Level","value":3"""))
        first.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        second.awaitFrame("sub")
        second.emit(rateLimitedNosub(sub.id))

        awaitUntil(what = "the refused replay is re-sent") { second.sentFramesOf("sub").size == 2 }
        assertEquals(
            "the retry must reuse the original sub id",
            listOf(sub.id, sub.id),
            second.sentFramesOf("sub").map { it.string("id") },
        )
        assertTrue("a retried sub must stay active", client.activeSubscriptions().containsKey(sub.id))
        assertFalse("a retried sub must not be parked stopped", sub.isStopped.value)
        assertNull("a retried sub must not surface the transient refusal", sub.error.value)
        assertEquals("stale docs must survive an open resync window", 1, client.mirror.size("creatureProperties"))

        second.emit(added("creatureProperties", "p1", """"name":"1st Level","value":1"""))
        second.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)
        assertTrue(sub.isReady.value)
    }

    /**
     * "Single retry — no storms" (decision 17). A refusal that survives the backoff is
     * a verdict, not congestion, so the second `nosub` must land on the ordinary path
     * verbatim: removed, stopped, and reporting the server's own reason.
     */
    @Test
    fun a_replay_refused_twice_stops_with_the_server_reason() = runBlocking {
        val client = newClient(resubscribeRetryDelay = 100.milliseconds)
        client.start()
        val first = server.awaitSocket(0)
        first.completeHandshake()
        client.awaitLive(5.seconds)

        val sub = client.subscribe("singleCharacter", ejsonParams(CREATURE))
        first.awaitFrame("sub")
        first.emit("""{"msg":"ready","subs":["${sub.id}"]}""")
        sub.awaitReady(5.seconds)

        first.dropConnection()
        val second = server.awaitSocket(1)
        second.completeHandshake(session = "session-2")
        second.awaitFrame("sub")
        second.emit(rateLimitedNosub(sub.id))
        awaitUntil(what = "the one retry") { second.sentFramesOf("sub").size == 2 }
        second.emit(rateLimitedNosub(sub.id))

        val error = runCatching { sub.awaitReady(5.seconds) }.exceptionOrNull()
        assertTrue("expected the server's DdpError, got $error", error is DdpError)
        assertEquals("too-many-requests", (error as DdpError).error)
        assertTrue(sub.isStopped.value)
        assertFalse(client.activeSubscriptions().containsKey(sub.id))
        Thread.sleep(400)
        assertEquals("one retry means one, ever", 2, second.sentFramesOf("sub").size)
    }

    /**
     * The fence around the retry. A `nosub` answering a fresh [DdpClient.subscribe] is
     * a verdict about the request itself — bad publication, bad args, a creature we may
     * not see — and re-sending it would only argue with the server and spend a slot of
     * the shared bucket. This pins that the retry is unreachable from the entry path,
     * which is the half of the change that must be provably inert.
     */
    @Test
    fun a_nosub_for_a_fresh_subscribe_is_never_retried() = runBlocking {
        val client = newClient(resubscribeRetryDelay = 100.milliseconds)
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
        assertEquals("404", (error as? DdpError)?.error)
        Thread.sleep(400)
        assertEquals("the entry path must never re-send a refusal", 1, socket.sentFramesOf("sub").size)
        assertTrue(sub.isStopped.value)
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

    // ------------------------------------------------- BUG-16: the frame log

    /**
     * **BUG-16.** A debug build wires `config.logger` to `Log.d("MageHandDdp", …)` and Maestro
     * copies logcat into every sweep flow's `logs/device-logcat.txt` — so the `login` frame's
     * `{"resume":"<token>"}` and its result's `{"token":"<token>"}` were both one `cp` from a
     * public tree, against `05-security.md`'s "never logged".
     *
     * The redaction is at the source, before the logger is called, so this asserts the thing
     * that matters: **no sink is ever handed the token, in either direction.** The collector
     * below is a sink like any other, which is what makes the claim general rather than a claim
     * about one logger.
     *
     * The diagnostics survive, and are asserted positively — the method name, the call id and
     * the user id. A redaction that swallowed the frame log would "pass" the token half and
     * destroy the thing the log exists for.
     */
    @Test
    fun login_frames_are_logged_without_the_token_in_either_direction() = runBlocking {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = newClient(logger = { lines += it })
        client.start()

        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect")
        socket.emit("""{"msg":"connected","session":"session-1"}""")
        val login = socket.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        val id = login.string("id")!!
        socket.completeMethod(id, LOGIN_RESULT)
        client.awaitLive(5.seconds)

        val logged = lines.toList()
        assertTrue("no frames were logged at all — the sink was never reached", logged.isNotEmpty())

        // The token literal, in ANY line. The outbound frame carried it in `params.resume`, the
        // inbound one in `result.token`, and one surviving line is one leaked token.
        logged.forEach { line ->
            assertFalse(
                "the resume token must not reach any logger sink: $line",
                line.contains(TOKEN),
            )
        }
        // …and it is still a usable frame log.
        assertTrue(
            "the outbound login frame is still logged, method name and all",
            logged.any { it.startsWith("→ ") && it.contains("\"login\"") && it.contains("\"id\":\"$id\"") },
        )
        assertTrue(
            "the redaction is visible rather than silent",
            logged.any { it.startsWith("→ ") && it.contains("<redacted>") },
        )
        assertTrue(
            "the inbound result is still logged, with the user id kept",
            logged.any { it.startsWith("← ") && it.contains("\"result\"") && it.contains("user-1") },
        )
        assertTrue(
            "tokenExpires survives — it is a fact about the session, not a credential",
            logged.any { it.startsWith("← ") && it.contains("tokenExpires") },
        )
    }

    /**
     * **Review M1, end to end.** The server refuses the `login` and echoes it back in the error
     * frame's `offendingMessage` — the shape that put the token in the log twice: once in the
     * echo, and once more raw by the client's own `"error" ->` branch. Neither survives.
     */
    @Test
    fun a_refused_login_echoed_in_an_error_frame_logs_no_token() = runBlocking {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = newClient(logger = { lines += it })
        client.start()

        val socket = server.awaitSocket(0)
        socket.awaitFrame("connect")
        socket.emit("""{"msg":"connected","session":"session-1"}""")
        val login = socket.awaitFrame { it.msg == "method" && it.string("method") == "login" }
        val id = login.string("id")!!

        socket.emit(
            """{"msg":"error","reason":"Must connect first","offendingMessage":""" +
                """{"msg":"method","method":"login","params":[{"resume":"$TOKEN"}],"id":"$id"}}"""
        )
        awaitUntil(what = "the error frame is logged") {
            lines.toList().any { it.contains("Must connect first") }
        }

        lines.toList().forEach { line ->
            assertFalse("a refused login must not put the token back in the log: $line", line.contains(TOKEN))
        }
        assertTrue(
            "the reason still reaches the log — it is the whole point of an error frame",
            lines.toList().any { it.startsWith("server protocol error:") && it.contains("Must connect first") },
        )
    }

    /**
     * **The redaction must not widen.** Every other method's `params` are the payload a blind
     * repro is about — a damage write's `_id`, `operation` and `value` — and a rule that scrubbed
     * "params" rather than "the login exchange" would take them with it and nobody would notice
     * until the next bug could not be reproduced.
     */
    @Test
    fun a_non_login_method_logs_its_params_verbatim() = runBlocking {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = newClient(logger = { lines += it })
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        val call = callAsync(client, "creatureProperties.damage", mapOf("_id" to "p1", "value" to 1))
        val frame = socket.awaitFrame { it.msg == "method" && it.string("method") == "creatureProperties.damage" }
        socket.completeMethod(frame.string("id")!!, """{"ok":true}""")
        call.await()

        val sent = lines.toList().filter { it.startsWith("→ ") && it.contains("creatureProperties.damage") }
        assertEquals("exactly one such frame was sent", 1, sent.size)
        assertTrue("its params must be logged as they were sent: ${sent.single()}", sent.single().contains("\"p1\""))
        assertFalse("nothing about it is redacted", sent.single().contains("<redacted>"))
    }

    /**
     * **An unparseable frame keeps its raw line.** Redacting requires parsing, and a frame that
     * does not parse is not one the server sent well-formed — seeing its actual bytes is the
     * whole repro value, so it is logged exactly as it arrived.
     */
    @Test
    fun an_unparseable_frame_is_logged_raw() = runBlocking {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = newClient(logger = { lines += it })
        client.start()
        val socket = server.awaitSocket(0)
        socket.completeHandshake()
        client.awaitLive(5.seconds)

        socket.emit("""{"msg":"result",""")
        awaitUntil(what = "the unparseable frame is logged") {
            lines.toList().any { it.startsWith("unparseable frame") }
        }

        val logged = lines.toList()
        assertTrue(
            "the raw text is logged on the way in, as it always was",
            logged.any { it == """← {"msg":"result",""" },
        )
        assertTrue(
            "and named as unparseable, with the bytes",
            logged.any { it.startsWith("unparseable frame") && it.endsWith("""{"msg":"result",""") },
        )
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

        /** What the shared 50/10 s subscription bucket says when it refuses a replay. */
        fun rateLimitedNosub(subId: String) =
            """{"msg":"nosub","id":"$subId","error":{"error":"too-many-requests",""" +
                """"reason":"Too many requests","details":"try again in 5 seconds","errorType":"Meteor.Error"}}"""
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
