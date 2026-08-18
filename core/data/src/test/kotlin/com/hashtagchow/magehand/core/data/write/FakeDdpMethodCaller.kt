package com.hashtagchow.magehand.core.data.write

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A scripted [DdpMethodCaller] with a virtual clock.
 *
 * **No live mutation happens anywhere in WP4.** docs/design/08-testing-and-release.md puts
 * the sacrificial "MageHand Test Dummy" creature in WP7; until then every write is proven
 * against this fake, and the only live probe in the repo is read-only.
 */
class FakeDdpMethodCaller(
    /** Virtual millis, normally `TestScope.testScheduler::currentTime`. */
    private val nowMillis: () -> Long = { 0 },
) : DdpMethodCaller {

    data class Call(val method: String, val params: List<JsonElement>, val atMillis: Long) {
        val body: JsonObject get() = params.first() as JsonObject
        fun int(key: String): Int = body[key].toString().trim('"').toInt()
        fun text(key: String): String = body[key].toString().trim('"')
    }

    val calls = mutableListOf<Call>()

    /** How long each call takes on the wire, in virtual millis. */
    var latencyMillis: Long = 0

    /** Return `null` to succeed, or a throwable to fail the call. */
    var failWith: (Call) -> Throwable? = { null }

    /**
     * While set, every call parks here after being recorded. Completing it releases them
     * all — which is how a test builds up a queue behind one in-flight call.
     */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun call(method: String, params: List<JsonElement>): JsonElement {
        val call = Call(method, params, nowMillis())
        calls += call
        gate?.await()
        if (latencyMillis > 0) delay(latencyMillis)
        failWith(call)?.let { throw it }
        return JsonNull
    }

    fun methods(): List<String> = calls.map { it.method }
    fun timesFor(method: String): List<Long> = calls.filter { it.method == method }.map { it.atMillis }
    fun reset() = calls.clear()
}
