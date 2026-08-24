package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import com.hashtagchow.magehand.core.model.ConnectionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A live DDP subscription handle.
 *
 * The subscription outlives the socket: [DdpClient] re-sends it (same id) after every
 * reconnect, so [isReady] drops to `false` while the connection is down and flips back
 * to `true` when the server has replayed the publication. Consumers never re-subscribe
 * by hand.
 */
class DdpSubscription internal constructor(
    val id: String,
    val name: String,
    val params: List<JsonElement>,
    private val client: DdpClient,
) {
    internal val readyState = MutableStateFlow(false)
    internal val failureState = MutableStateFlow<DdpError?>(null)
    internal val stoppedState = MutableStateFlow(false)

    /**
     * Wire-level readiness, updated the instant `ready` is parsed — before the mirror
     * is flushed and before [readyState] is published. `DdpClient` uses this (not
     * [readyState]) to decide when the mirror has quiesced after a reconnect, so that
     * quiescence lands in the same snapshot flush that consumers see on wake-up.
     */
    @Volatile
    internal var readyOnWire: Boolean = false

    /**
     * True while this subscription is a *replayed* one, still unanswered on the
     * current session, and still holding the single retry
     * 14-large-screen-arc.md decision 17 allows it.
     *
     * It exists to tell two identical-looking `nosub` frames apart. A refusal of a
     * replay is usually the shared 50/10 s subscription bucket saying "not now" —
     * this same publication was serving us seconds ago on the previous session — and
     * treating it as a verdict leaves a DM card stopped forever on a transient. A
     * refusal of a fresh [DdpClient.subscribe] is a verdict about the request itself.
     *
     * Armed only by `DdpClient`'s reconnect replay, so the fresh-subscribe path is
     * structurally unreachable from the retry. Disarmed the moment it is spent, the
     * moment the sub goes ready (a later `nosub` is then the server *stopping* a live
     * publication, which we must believe), and by [onDisconnected] — a retry that
     * outlives its socket would double-send on the next session's replay.
     *
     * Written and read on the client dispatcher only, so no atomic is needed;
     * `@Volatile` matches [readyOnWire] and keeps it honest for debuggers.
     */
    @Volatile
    internal var replayRetryArmed: Boolean = false

    /** `true` once the server has sent `ready` for this sub on the current session. */
    val isReady: StateFlow<Boolean> = readyState.asStateFlow()

    /** Set when the server answered `nosub` with an error (bad publication/args). */
    val error: StateFlow<DdpError?> = failureState.asStateFlow()

    /** `true` after [stop] or a server-side `nosub`; the sub is no longer re-subscribed. */
    val isStopped: StateFlow<Boolean> = stoppedState.asStateFlow()

    /**
     * Suspends until the publication is ready.
     *
     * Survives reconnects — if the socket drops mid-wait this keeps waiting for the
     * `ready` of the *next* session. Throws [DdpError] on `nosub`/auth failure and
     * [DdpConnectionException] if the subscription is stopped.
     */
    suspend fun awaitReady(timeout: Duration = DEFAULT_READY_TIMEOUT) {
        withTimeout(timeout) {
            val failure: Throwable? = merge(
                readyState.filter { it }.map<Boolean, Throwable?> { null },
                failureState.filterNotNull().map<DdpError, Throwable?> { it },
                stoppedState.filter { it }.map<Boolean, Throwable?> {
                    // A `nosub` sets both flags; report the server's reason, not "stopped".
                    failureState.value ?: DdpConnectionException("subscription '$name' was stopped")
                },
                client.connectionState.filter { it == ConnectionState.AUTH_FAILED }
                    .map<ConnectionState, Throwable?> {
                        client.authError ?: DdpError("403", "not authenticated")
                    },
            ).first()
            if (failure != null) throw failure
        }
    }

    /** Sends `unsub` and stops re-subscribing this publication on reconnect. */
    suspend fun stop() = client.unsubscribe(this)

    internal fun onDisconnected() {
        readyOnWire = false
        readyState.value = false
        replayRetryArmed = false
    }

    override fun toString(): String = "DdpSubscription(id=$id, name=$name, ready=${readyState.value})"

    companion object {
        val DEFAULT_READY_TIMEOUT: Duration = 60.seconds
    }
}
