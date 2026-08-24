package com.hashtagchow.magehand.core.data.contract

import com.hashtagchow.magehand.core.ddp.DdpSocket
import com.hashtagchow.magehand.core.ddp.DdpSocketFactory
import com.hashtagchow.magehand.core.ddp.DdpSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Reads a string field off a recorded frame. */
internal fun JsonObject.frameString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A minimal DDP server that **records every frame the production client sends** and answers
 * it well enough for the client to keep going.
 *
 * ### Why the export is recorded rather than written down
 *
 * The whole value of `contract-export/` to WebHand is that the frames in it are the frames
 * MageHand actually puts on the wire — a hand-typed JSON file is a second implementation of
 * the protocol, and a second implementation is a thing that can be wrong on its own. So the
 * emitter drives a real [com.hashtagchow.magehand.core.ddp.DdpClient] through a real
 * handshake, a real login, real subscriptions and a real method call per vector, and writes
 * down what came out of the socket. If `DdpClient` changes how it frames a call, the export
 * changes with it and the golden-file test fails — which is exactly the alarm WebHand needs.
 *
 * This is deliberately **not** `:core:ddp`'s own `FakeDdpServer`: that one is a *scripted*
 * fake driven frame by frame from a test body, and it lives in another module's test source
 * set (not on this module's classpath). This one is an *auto-responder* — it needs no script,
 * because the emitter cares about the client's half of the conversation and not the server's.
 *
 * ### Re-entrancy
 *
 * [DdpSocket.send] answers inline, on the client's own dispatcher thread. That is safe
 * because `DdpClient`'s socket listener only ever does a non-blocking `trySend` onto an
 * unlimited channel, so an answer delivered from inside `send` is queued, not recursed into.
 */
class ContractDdpRecorder(
    private val userId: String,
    /** `tokenExpires` on the login result. Fixed, so the recording is deterministic. */
    private val tokenExpiresMillis: Long,
) : DdpSocketFactory {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val recorded = ArrayList<JsonObject>()
    private val answered = ArrayList<JsonObject>()

    @Volatile
    private var latest: Socket? = null

    /** Every frame the client has sent, in order. */
    fun frames(): List<JsonObject> = synchronized(lock) { recorded.toList() }

    fun framesOf(msg: String): List<JsonObject> = frames().filter { it.frameString("msg") == msg }

    /**
     * Every frame this fake server sent back, in emission order.
     *
     * The client's half of the conversation is the export's main subject, but a *reply* is
     * part of the contract too — most sharply for method completion, where the rule is that
     * two frames arrive and not one. The export used to hand-build the login reply, which is
     * the one thing this file exists to prevent: hand-built JSON stated a single `result` and
     * quietly dropped the `updated` that the production client actually waits for. Recording
     * the answers instead means the export cannot describe a completion the client would not
     * accept, because these are the frames that made the recorded session succeed.
     */
    fun serverFrames(): List<JsonObject> = synchronized(lock) { answered.toList() }

    /**
     * The reply frames that completed the method call carrying [methodId] — the `result`
     * addressed to it plus the `updated` naming it, in the order they went out.
     */
    fun repliesForMethod(methodId: String): List<JsonObject> = serverFrames().filter { frame ->
        when (frame.frameString("msg")) {
            "result" -> frame.frameString("id") == methodId
            "updated" -> (frame["methods"] as? JsonArray)
                ?.any { (it as? JsonPrimitive)?.contentOrNull == methodId } == true

            else -> false
        }
    }

    /** Blocks until a frame matching [predicate] has been recorded, and returns it. */
    fun awaitFrame(timeoutMs: Long = 10_000, predicate: (JsonObject) -> Boolean): JsonObject =
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var cursor = 0
            while (true) {
                while (cursor < recorded.size) {
                    val frame = recorded[cursor++]
                    if (predicate(frame)) return frame
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    error("no matching frame within ${timeoutMs}ms; saw ${recorded.map { it.frameString("msg") }}")
                }
                lock.wait(remaining)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    /** Pushes an unsolicited server `ping`, so the client's `pong` can be recorded. */
    fun pushServerPing(id: String) {
        (latest ?: error("no socket has been opened yet")).serverPing(id)
    }

    override fun open(listener: DdpSocketListener): DdpSocket {
        val socket = Socket(listener)
        latest = socket
        listener.onOpen()
        return socket
    }

    private inner class Socket(private val listener: DdpSocketListener) : DdpSocket {

        @Volatile
        private var dead = false

        override fun send(text: String): Boolean {
            if (dead) return false
            val frame = Json.parseToJsonElement(text).jsonObject
            synchronized(lock) {
                recorded += frame
                lock.notifyAll()
            }
            respondTo(frame)
            return true
        }

        override fun close(code: Int, reason: String?) {
            if (dead) return
            dead = true
            listener.onClosed(code, reason)
        }

        fun serverPing(id: String) = emit {
            put("msg", "ping")
            put("id", id)
        }

        private fun respondTo(frame: JsonObject) {
            when (frame.frameString("msg")) {
                "connect" -> emit {
                    put("msg", "connected")
                    put("session", SESSION_ID)
                }

                "method" -> {
                    val id = frame.frameString("id") ?: return
                    val method = frame.frameString("method")
                    // 02: a method completes only when BOTH `result` and `updated` arrive.
                    //
                    // The ORDER differs by method, and that is a probed fact rather than a
                    // stylistic choice: for `login` the live server sends `updated` first,
                    // every time, on the success path and on a 403 alike
                    // (docs/verification/WP2.md §3.1, observed 2026-08-17; regression test
                    // DdpClientTest.updated_may_arrive_before_result). Design 02's transcript
                    // shows the other order, which is what the rest of the catalogue does.
                    // Answering login the live way keeps the recorded export from implying an
                    // ordering the server does not use — and makes this recording exercise the
                    // client's both-orders tolerance rather than only the easy path.
                    if (method == LOGIN) {
                        emitUpdated(id)
                        emitResult(id, method)
                    } else {
                        emitResult(id, method)
                        emitUpdated(id)
                    }
                }

                "sub" -> {
                    val id = frame.frameString("id") ?: return
                    emit {
                        put("msg", "ready")
                        put("subs", JsonArray(listOf(JsonPrimitive(id))))
                    }
                }

                "ping" -> emit {
                    put("msg", "pong")
                    frame.frameString("id")?.let { put("id", it) }
                }
            }
        }

        private fun emitResult(id: String, method: String?) = emit {
            put("msg", "result")
            put("id", id)
            put("result", resultFor(method))
        }

        private fun emitUpdated(id: String) = emit {
            put("msg", "updated")
            put("methods", JsonArray(listOf(JsonPrimitive(id))))
        }

        private fun resultFor(method: String?): JsonObject = when (method) {
            LOGIN -> buildJsonObject {
                put("id", userId)
                // EJSON date — the one extension DiceCloud uses on the wire.
                put("tokenExpires", buildJsonObject { put(EJSON_DATE_KEY, tokenExpiresMillis) })
            }

            else -> buildJsonObject { }
        }

        private fun emit(build: JsonObjectBuilder.() -> Unit) {
            val frame = buildJsonObject(build)
            synchronized(lock) { answered += frame }
            listener.onText(frame.toString())
        }
    }

    companion object {
        /**
         * The session id the fake hands out.
         *
         * A **server**-minted value, so it is not a Meteor id and deliberately does not look
         * like one: a consumer must not infer that `connected.session` is 17 unmistakable
         * characters, because on a real server it is not.
         */
        const val SESSION_ID: String = "contract-export-session"

        /** The one method whose reply frames the live server sends in the reverse order. */
        const val LOGIN: String = "login"

        private const val EJSON_DATE_KEY = "\$date"
    }
}
