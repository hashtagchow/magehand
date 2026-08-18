package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * A scripted DDP server for the unit tests: hands out [FakeSocket]s, records every
 * frame the client sends, and lets the test push server frames back byte-for-byte.
 *
 * Nothing here is asynchronous magic — the test thread drives the script and blocks
 * on [FakeSocket.awaitFrame], while the client runs on its own dispatcher thread.
 */
class FakeDdpServer : DdpSocketFactory {

    // java.lang.Object, not Any: the tests use wait()/notifyAll() as the handshake.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val sockets = ArrayList<FakeSocket>()

    override fun open(listener: DdpSocketListener): DdpSocket {
        val socket = FakeSocket(listener)
        synchronized(lock) {
            sockets += socket
            lock.notifyAll()
        }
        listener.onOpen()
        return socket
    }

    /** Blocks until the client has opened socket number [index] (0-based). */
    fun awaitSocket(index: Int, timeoutMs: Long = 5_000): FakeSocket = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sockets.size <= index) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                throw AssertionError("socket #$index was never opened (${sockets.size} so far)")
            }
            lock.wait(remaining)
        }
        sockets[index]
    }

    val socketCount: Int get() = synchronized(lock) { sockets.size }

    /** Fails the test if another socket shows up within [millis]. */
    fun assertNoSocketBeyond(index: Int, millis: Long = 300) {
        Thread.sleep(millis)
        synchronized(lock) {
            if (sockets.size > index + 1) {
                throw AssertionError("expected no reconnect, but ${sockets.size} sockets were opened")
            }
        }
    }
}

class FakeSocket(private val listener: DdpSocketListener) : DdpSocket {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val frames = ArrayList<JsonObject>()

    @Volatile
    private var dead = false

    // ------------------------------------------------------------ client → server

    override fun send(text: String): Boolean {
        if (dead) return false
        val frame = Json.parseToJsonElement(text).jsonObject
        synchronized(lock) {
            frames += frame
            lock.notifyAll()
        }
        return true
    }

    override fun close(code: Int, reason: String?) {
        if (dead) return
        dead = true
        listener.onClosed(code, reason)
    }

    /** All frames the client has sent so far, in order. */
    fun sentFrames(): List<JsonObject> = synchronized(lock) { frames.toList() }

    fun sentFramesOf(msg: String): List<JsonObject> = sentFrames().filter { it.msg == msg }

    /** Blocks until the client sends a frame matching [predicate], and returns it. */
    fun awaitFrame(timeoutMs: Long = 5_000, predicate: (JsonObject) -> Boolean): JsonObject {
        synchronized(lock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var cursor = 0
            while (true) {
                while (cursor < frames.size) {
                    val frame = frames[cursor++]
                    if (predicate(frame)) return frame
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw AssertionError("no matching frame within ${timeoutMs}ms; got ${frames.map { it.msg }}")
                }
                lock.wait(remaining)
            }
        }
    }

    fun awaitFrame(msg: String, timeoutMs: Long = 5_000): JsonObject =
        awaitFrame(timeoutMs) { it.msg == msg }

    // ------------------------------------------------------------ server → client

    fun emit(json: String) {
        listener.onText(json)
    }

    fun emit(frame: JsonObject) = emit(frame.toString())

    /** Simulates the socket dying mid-session (wifi drop). */
    fun dropConnection(cause: Throwable = IOException("connection reset by peer")) {
        if (dead) return
        dead = true
        listener.onFailure(cause)
    }

    /** Simulates a clean server-side close. */
    fun serverClose(code: Int = 1000, reason: String = "server closing") {
        if (dead) return
        dead = true
        listener.onClosed(code, reason)
    }

    val isDead: Boolean get() = dead
}

// -------------------------------------------------------------------- shorthands

val JsonObject.msg: String? get() = (this["msg"] as? JsonPrimitive)?.contentOrNull

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Polls [condition] until true, or fails the test. Used where no signal exists. */
fun awaitUntil(timeoutMs: Long = 5_000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
        Thread.sleep(2)
    }
}
