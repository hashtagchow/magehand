package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.write.DdpMethodCaller
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.DdpSubscription
import com.hashtagchow.magehand.core.ddp.ejsonParams
import com.hashtagchow.magehand.core.model.ConnectionState

/**
 * The live half of a [CreatureSession]: a `singleCharacter` subscription and the mirror
 * collections it fills.
 *
 * Why this interface exists rather than [CreatureSession] holding a [DdpClient] directly:
 * `DdpClient` is a final class whose only test seam is a scripted websocket living in
 * `:core:ddp`'s *test* source set, which `:core:data` cannot see. Narrowing the dependency
 * to the handful of things the session actually uses makes the session unit-testable here
 * and keeps [DdpCreatureFeed] thin enough to be obviously correct. No behaviour lives in
 * the seam.
 */
interface CreatureFeed : DdpMethodCaller {

    /** `CONNECTING` / `LIVE` / `AUTH_FAILED` straight from the DDP client; never `OFFLINE`. */
    val connectionState: StateFlow<ConnectionState>

    /** Whether the `singleCharacter` subscription has delivered its initial documents. */
    val isReady: StateFlow<Boolean>

    /** Live per-collection mirror snapshots, `_id` → document. */
    fun documents(collection: String): StateFlow<Map<String, JsonObject>>

    /** Subscribes. Idempotent. */
    suspend fun start()

    /** Unsubscribes. The DDP client itself is owned by the caller and is left running. */
    suspend fun stop()
}

/**
 * [CreatureFeed] over WP2's [DdpClient].
 *
 * The client is *not* owned here — one client per account backs several sessions over its
 * life, and closing it because one character screen went away would drop the connection
 * the character selector is still using.
 */
class DdpCreatureFeed(
    private val client: DdpClient,
    private val creatureId: String,
    private val scope: CoroutineScope,
) : CreatureFeed {

    private var subscription: DdpSubscription? = null
    private var readyMirror: Job? = null
    private val ready = MutableStateFlow(false)

    override val connectionState: StateFlow<ConnectionState> get() = client.connectionState

    /**
     * A `StateFlow` of our own rather than the subscription's, because the subscription
     * does not exist until [start] and is replaced when the session is restarted — a
     * consumer that captured the old one would silently stop updating.
     */
    override val isReady: StateFlow<Boolean> = ready.asStateFlow()

    override fun documents(collection: String): StateFlow<Map<String, JsonObject>> =
        client.mirror.documentsFlow(collection)

    override suspend fun start() {
        if (subscription?.isStopped?.value == false) return
        val sub = client.subscribe(SUBSCRIPTION, ejsonParams(creatureId))
        subscription = sub
        readyMirror?.cancel()
        readyMirror = scope.launch { sub.isReady.collect { ready.value = it } }
    }

    override suspend fun stop() {
        readyMirror?.cancel()
        readyMirror = null
        subscription?.stop()
        subscription = null
        ready.value = false
    }

    override suspend fun call(method: String, params: List<JsonElement>): JsonElement =
        client.call(method, params)

    /** A sheet built from whatever the mirror holds right now. */
    fun sheet(): CreatureSheet = CreatureSheet.fromMirror(client.mirror.snapshot(), creatureId)

    companion object {
        /** docs/design/02-ddp-and-api.md §Publications we use. */
        const val SUBSCRIPTION: String = "singleCharacter"
    }
}
