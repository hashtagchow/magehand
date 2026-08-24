package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import com.hashtagchow.magehand.core.model.ConnectionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Tunables for [DdpClient]. Defaults are the production values. */
data class DdpClientConfig(
    /** Socket open + `connect`/`connected` round trip. */
    val handshakeTimeout: Duration = 20.seconds,
    /** How long a method may take to produce both `result` and `updated`. */
    val methodTimeout: Duration = 30.seconds,
    /** How often we send a DDP `ping`. [Duration.ZERO] disables client-side pings. */
    val heartbeatInterval: Duration = 25.seconds,
    /** How long we wait for the matching `pong` before declaring the socket dead. */
    val heartbeatTimeout: Duration = 15.seconds,
    /** Reconnect schedule; 1 s → 60 s with jitter by default. */
    val backoff: BackoffPolicy = ExponentialBackoff(),
    /**
     * Spacing between the subscriptions replayed after a reconnect.
     *
     * The DM view (FR-19) parks up to seven subscriptions on this one connection, and
     * DiceCloud's subscription rate limit — 50 per 10 s — is **global across all
     * users**: the whole table draws on one bucket (14-large-screen-arc.md decision
     * 17, "no storms"). Our own burst is not the danger; the danger is the shape a
     * router blip makes, where every client at the table reconnects in the same second
     * and each fires N subs in one tick. The client that loses that race gets `nosub`
     * on a card and, before this knob existed, lost that card's live data for good.
     * 100 ms de-phases the replay: the largest set we ship spreads over 600 ms, which
     * is small beside the ~1.2 s the six-character initial sync costs anyway and
     * invisible beside [DdpSubscription.DEFAULT_READY_TIMEOUT].
     *
     * Deliberately **not** applied to a fresh [DdpClient.subscribe]. Decision 17
     * permits the entry burst outright (six subs ≈ 12 % of the bucket) and paying
     * stagger there would slow down the one moment a user is watching the screen fill.
     * Only the replay is a storm by construction — it is N-at-once whether anyone
     * asked for N or not.
     *
     * The first replayed subscription is never delayed, so the ordinary one- or
     * two-subscription client reconnects at exactly the speed it always did.
     */
    val resubscribeStagger: Duration = 100.milliseconds,
    /**
     * How long to wait before re-sending a replayed subscription the server refused.
     *
     * A knob rather than a constant because it is the twin of [resubscribeStagger] —
     * both exist only to keep the shared bucket happy, and a server whose limit moves
     * wants them re-tuned together, in one place, by the same argument. At 50 per 10 s
     * the bucket refills at five per second, so one second is the shortest wait that
     * has demonstrably made room for five more subscriptions: most of a DM set, and
     * all of an ordinary client's.
     *
     * Exactly **one** retry, and only on the replay path. Congestion that survives a
     * second of drain is not congestion, it is a refusal — and a client that keeps
     * re-sending a refusal is precisely the storm decision 17 forbids.
     */
    val resubscribeRetryDelay: Duration = 1.seconds,
    /** Every frame in and out, plus lifecycle notes. Off by default. */
    val logger: (String) -> Unit = {},
)

/**
 * The DiceCloud/Meteor DDP client (docs/design/02-ddp-and-api.md).
 *
 * ```
 * → {"msg":"connect","version":"1","support":["1"]}
 * ← {"msg":"connected","session":"…"}
 * → {"msg":"method","method":"login","params":[{"resume":"<token>"}],"id":"1"}
 * → {"msg":"sub","id":"…","name":"singleCharacter","params":["<creatureId>"]}
 * ← added…/ready/changed/removed          ↔ ping/pong in both directions
 * ```
 *
 * ### Threading
 * Everything — handshake, login, message parsing, mirror mutation, subscription
 * bookkeeping — runs on one dedicated single-thread dispatcher per client, which is
 * what gives the `added/changed/removed` ordering guarantee 01-architecture.md asks
 * for. Public suspend functions hop onto that dispatcher to register work and then
 * suspend on a [CompletableDeferred], so callers stay on their own dispatcher.
 *
 * ### Reconnection
 * A dropped socket is invisible to consumers except through [connectionState]: the
 * client re-handshakes, re-logs-in with a freshly-read resume token, re-sends every
 * active subscription with its original id, and reconciles [mirror] by Meteor's
 * quiescence rule (see [MongoMirror]). Backoff is 1 s → 60 s with jitter.
 *
 * The replay is spaced by [DdpClientConfig.resubscribeStagger] and a replayed
 * subscription the server refuses is re-sent once after
 * [DdpClientConfig.resubscribeRetryDelay] — 14-large-screen-arc.md decision 17, whose
 * DM view puts up to seven subscriptions here at once against a subscription rate
 * limit the whole table shares. Both knobs argue themselves in full at their
 * declarations; neither touches the fresh-[subscribe] path.
 *
 * ### In-flight methods are NOT replayed
 * If the socket dies with a method outstanding, that call fails with
 * [DdpConnectionException] instead of being re-sent. DiceCloud's write methods are
 * mostly `increment`s; replaying one whose result we never saw is exactly the silent
 * slot-corruption 06-offline-and-sync.md refuses to risk. Retry policy belongs to
 * `:core:data`'s `WriteQueue`, which knows whether an operation is safe to repeat.
 *
 * ### Auth
 * A rejected resume token stops the reconnect loop and parks the client in
 * [ConnectionState.AUTH_FAILED] with [authError] set. Call [restart] after storing a
 * fresh token.
 */
class DdpClient(
    private val socketFactory: DdpSocketFactory,
    private val config: DdpClientConfig = DdpClientConfig(),
    private val resumeTokenProvider: suspend () -> String? = { null },
) : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ddp-client-${INSTANCES.incrementAndGet()}").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("ddp-client"))
    private val json = Json { encodeDefaults = true }

    /** The client-side collection store fed by this connection. */
    val mirror: MongoMirror = MongoMirror()

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userId = MutableStateFlow<String?>(null)

    /** The logged-in Meteor user id, once `login` has succeeded. */
    val userId: StateFlow<String?> = _userId.asStateFlow()

    /** The current server session id (`connected.session`), or null when down. */
    @Volatile
    var sessionId: String? = null
        private set

    /** Why we are in [ConnectionState.AUTH_FAILED]. */
    @Volatile
    var authError: DdpError? = null
        private set

    private val subscriptions = ConcurrentHashMap<String, DdpSubscription>()
    private val methodCounter = AtomicLong(0)

    @Volatile
    private var session: Session? = null

    @Volatile
    private var activeSocket: DdpSocket? = null

    @Volatile
    private var closed = false

    private var loop: Job? = null

    // ------------------------------------------------------------------- public

    /** Starts the connect/reconnect loop. Idempotent. */
    @Synchronized
    fun start() {
        check(!closed) { "DdpClient is closed" }
        if (loop?.isActive == true) return
        authError = null
        loop = scope.launch { connectionLoop() }
    }

    /** [start] + [awaitLive]. */
    suspend fun connect(timeout: Duration = 30.seconds) {
        start()
        awaitLive(timeout)
    }

    /**
     * Suspends until the connection is [ConnectionState.LIVE].
     * Throws the server's [DdpError] if the token was rejected.
     */
    suspend fun awaitLive(timeout: Duration = 30.seconds) {
        val state = try {
            withTimeout(timeout) {
                connectionState.first { it == ConnectionState.LIVE || it == ConnectionState.AUTH_FAILED }
            }
        } catch (timedOut: TimeoutCancellationException) {
            throw DdpConnectionException("DDP did not reach LIVE within $timeout", timedOut)
        }
        if (state == ConnectionState.AUTH_FAILED) {
            throw authError ?: DdpError("403", "DDP login was rejected")
        }
    }

    /**
     * Re-arms the client after [ConnectionState.AUTH_FAILED] — call once a fresh
     * resume token is available to [resumeTokenProvider].
     */
    @Synchronized
    fun restart() {
        check(!closed) { "DdpClient is closed" }
        authError = null
        loop?.cancel()
        loop = scope.launch { connectionLoop() }
    }

    /**
     * Subscribes to [name]. Returns immediately with a handle; use
     * [DdpSubscription.awaitReady] to wait for the publication.
     *
     * The subscription is remembered and re-sent after every reconnect until
     * [DdpSubscription.stop].
     */
    suspend fun subscribe(name: String, params: List<JsonElement> = emptyList()): DdpSubscription =
        withContext(dispatcher) {
            if (closed) throw DdpConnectionException("DdpClient is closed")
            val sub = DdpSubscription(MeteorId.random(), name, params, this@DdpClient)
            subscriptions[sub.id] = sub
            if (_connectionState.value == ConnectionState.LIVE) {
                session?.send(subMessage(sub))
            }
            log("sub ${sub.name} id=${sub.id} params=${sub.params}")
            sub
        }

    /**
     * Calls a Meteor method and returns its `result`, but only **after** the server
     * has also sent `updated` for it — i.e. after the server's writes have been
     * flushed to us, so [mirror] already reflects them (02-ddp-and-api.md: "Method
     * completion = BOTH `result` … and `updated`").
     *
     * @throws DdpError when the method threw server-side.
     * @throws DdpConnectionException when the connection is not [ConnectionState.LIVE]
     *   or drops before completion.
     */
    suspend fun call(method: String, params: List<JsonElement> = emptyList()): JsonElement {
        val pending = withContext(dispatcher) {
            if (closed) throw DdpConnectionException("DdpClient is closed")
            if (_connectionState.value != ConnectionState.LIVE) {
                throw DdpConnectionException("DDP not live (state=${_connectionState.value})")
            }
            val active = session ?: throw DdpConnectionException("no active DDP session")
            active.beginCall(method, params)
        }
        return try {
            withTimeout(config.methodTimeout) { pending.deferred.await() }
        } catch (timedOut: TimeoutCancellationException) {
            throw DdpConnectionException(
                "method '$method' produced no result+updated within ${config.methodTimeout}",
                timedOut,
            )
        }
    }

    /** Active (not stopped) subscriptions, by id. */
    fun activeSubscriptions(): Map<String, DdpSubscription> = subscriptions.toMap()

    /**
     * Stops [sub] and tells the server so, whenever there is a session to tell.
     *
     * ### Why the gate is `session != null` and not `state == LIVE`
     *
     * There is a window — the staggered [replaySubscriptions] — in which the socket is open and
     * the server is accepting frames but [_connectionState] has not been flipped to LIVE yet. It
     * is not a theoretical window: decision 17's stagger makes it as long as
     * `resubscribeStagger × (N - 1)`, and `DmViewViewModel.onCleared` closes up to seven
     * characters at once, so a Back press during a reconnect lands squarely in it.
     *
     * Gated on LIVE, the `unsub` for every one of those was **dropped on the floor**: this client
     * forgot the subscription, the server never heard, and the publication stayed open against
     * the 50-per-10 s bucket the whole table shares — for the life of the session, with nothing
     * anywhere to attribute it to. Gated on the session, the frame goes out on the socket that is
     * actually there, which is the only thing that was ever being asked.
     *
     * `session` is null between sockets, and that case is already correct without a frame: a sub
     * removed from [subscriptions] is not replayed, and the server drops the whole session's
     * state when the socket dies anyway.
     *
     * The one narrowing kept from the old gate is the handshake. [session] is assigned before
     * `connect` goes out, and Meteor answers any frame sent ahead of `connected` with *"Must
     * connect first"* — so a socket that has not handshaken yet is one the server has no
     * subscriptions on, and the honest thing to send it is nothing.
     */
    internal suspend fun unsubscribe(sub: DdpSubscription) {
        withContext(dispatcher) {
            subscriptions.remove(sub.id)
            sub.readyOnWire = false
            sub.readyState.value = false
            sub.stoppedState.value = true
            session?.takeIf { it.isHandshaken }?.send(buildJsonObject {
                put("msg", "unsub")
                put("id", sub.id)
            })
            maybeEndResync()
            mirror.flush() // quiescence may have dropped documents; publish now
        }
    }

    /**
     * Releases everything this client owns: the socket, the coroutine scope, and the
     * single thread behind [dispatcher].
     *
     * Deliberately **not** the `OkHttpClient`. It is supplied by the caller (see
     * [okHttp] and `DataModule`), it is shared across every account this process
     * connects to, and shutting down another owner's dispatcher and connection pool
     * from here would break the next account's connection. The corresponding
     * obligation is on the wiring: exactly one `OkHttpClient` per process, so there is
     * never one to reclaim. See [OkHttpDdpSocketFactory.webSocketClient].
     */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        log("closing")
        runCatching { activeSocket?.close(1000, "client closing") }
        scope.cancel()
        // shutdown(), not shutdownNow(): let queued cancellation work drain first.
        executor.shutdown()
    }

    // ------------------------------------------------------------ connect loop

    private suspend fun connectionLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && !closed) {
            _connectionState.value = ConnectionState.CONNECTING
            var reachedLive = false
            try {
                reachedLive = runSession()
            } catch (cancel: CancellationException) {
                // A TimeoutCancellationException from an inner withTimeout is not *our*
                // cancellation: only bail out when the loop itself was cancelled.
                if (!currentCoroutineContext().isActive) throw cancel
                log("session aborted: $cancel")
            } catch (auth: AuthRejected) {
                log("login rejected: ${auth.error.message}")
                authError = auth.error
                _connectionState.value = ConnectionState.AUTH_FAILED
                return
            } catch (t: Throwable) {
                log("session ended: $t")
            }
            if (closed || !currentCoroutineContext().isActive) return
            if (reachedLive) attempt = 0
            val wait = config.backoff.delayMillis(attempt)
            attempt++
            _connectionState.value = ConnectionState.CONNECTING
            log("reconnecting in ${wait}ms (attempt $attempt)")
            delay(wait)
        }
    }

    /** Runs one socket from open to close. Returns true if it ever reached LIVE. */
    private suspend fun runSession(): Boolean {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        val current = Session(events)
        var live = false
        val socket = socketFactory.open(current.listener)
        current.socket = socket
        activeSocket = socket
        session = current
        try {
            coroutineScope {
                val pump = launch { current.pump() }
                try {
                    withTimeout(config.handshakeTimeout) {
                        current.opened.await()
                        current.send(
                            buildJsonObject {
                                put("msg", "connect")
                                put("version", DDP_VERSION)
                                put("support", JsonArray(listOf(JsonPrimitive(DDP_VERSION))))
                            }
                        )
                        sessionId = current.connected.await()
                    }
                    log("connected session=$sessionId")

                    // The resync window opens the moment the session does, NOT after
                    // login: the live server pushes the `users` document as part of the
                    // login exchange, and anything that arrives before beginResync()
                    // would be counted as un-replayed and dropped at quiescence.
                    mirror.beginResync()

                    val token = resumeTokenProvider()
                    if (token != null) login(current, token)

                    // Server state is per-session: replay every subscription and let
                    // the mirror reconcile what comes back.
                    replaySubscriptions(current)
                    maybeEndResync()
                    mirror.flush()

                    _connectionState.value = ConnectionState.LIVE
                    live = true

                    val heartbeat = launch { current.heartbeat() }
                    try {
                        current.closedSignal.await()
                    } finally {
                        heartbeat.cancel()
                    }
                } finally {
                    pump.cancel()
                }
            }
        } finally {
            session = null
            activeSocket = null
            sessionId = null
            runCatching { socket.close(1000, "session ended") }
            current.failPending(DdpConnectionException("DDP session ended"))
            subscriptions.values.forEach { it.onDisconnected() }
            mirror.flush()
        }
        return live
    }

    private suspend fun login(current: Session, token: String) {
        val params = listOf(buildJsonObject { put("resume", token) } as JsonElement)
        val pending = current.beginCall("login", params)
        val result = try {
            withTimeout(config.methodTimeout) { pending.deferred.await() }
        } catch (error: DdpError) {
            throw AuthRejected(error)
        }
        _userId.value = (result as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        log("logged in as ${_userId.value}")
    }

    /**
     * Re-sends every remembered subscription on a freshly-opened session, spaced by
     * [DdpClientConfig.resubscribeStagger] — the "small stagger" half of
     * 14-large-screen-arc.md decision 17. The spacing goes *between* sends and never
     * before the first, so a client with one subscription — every non-DM screen — pays
     * nothing at all: one sub in, one `sub` frame out, at the instant it always went.
     *
     * That "unchanged" claim is about the **frames**, and only on the path where the
     * server accepts them. It is deliberately not a claim that a reconnect is
     * indistinguishable from a pre-decision-17 one in every case: if the server refuses
     * a replayed sub, this client now swallows the first `nosub` and re-sends about
     * [DdpClientConfig.resubscribeRetryDelay] later instead of surfacing the refusal
     * immediately, so a refused reconnect takes ~1 s longer to report and emits one
     * extra `sub`. That is the point of the retry, and it is the one behavioural
     * difference a single-subscription client can observe.
     *
     * Each replayed sub is armed with its one retry
     * ([DdpSubscription.replayRetryArmed]) immediately before it goes out, because a
     * refusal that lands on a *replay* is far more likely to be the shared rate
     * bucket than a bad publication — the same sub was accepted on the previous
     * session, seconds ago.
     *
     * ### Why the drain loop rather than one pass over the map
     * The stagger makes this function suspend, which it never did before, and the
     * client dispatcher is free to run [subscribe] in the gaps. A subscription that
     * arrives mid-replay is added to [subscriptions] but *not* sent by [subscribe]
     * itself (the connection is not LIVE yet), so a single `for` over a snapshot
     * would drop it silently until the next reconnect — a DM card that never fills,
     * with nothing in the log. Re-reading the map until it stops producing unsent
     * subscriptions closes that window; the caller then flips to LIVE with no
     * suspension point in between, so there is no gap on the far side either.
     *
     * ### Why membership is re-checked *after* the delay, not only before
     *
     * The same open window runs the other way. `pending` is a snapshot, and every
     * [delay] in the loop hands the client dispatcher back — long enough for
     * [unsubscribe] to remove a subscription this pass has already decided to send.
     * Sending it anyway subscribes the server to a publication nobody is holding: the
     * documents land in [mirror] and stay (nothing will ever remove them, because the
     * `unsub` this client sent was for a sub the server had not been told about yet),
     * and one slot of the 50-per-10 s bucket the whole table shares is spent on a
     * screen that is gone. `DmViewViewModel.onCleared` closes up to seven at once, so
     * the way to hit this is a Back press during a reconnect — not an exotic
     * interleaving.
     *
     * So the membership test is the *last* thing before the send, on the far side of
     * the suspension point. A dropped subscription costs nothing: it is not in
     * [subscriptions], so quiescence does not wait for it and the next pass will not
     * re-offer it.
     */
    private suspend fun replaySubscriptions(current: Session) {
        val sent = HashSet<String>()
        var isFirst = true
        while (true) {
            val pending = subscriptions.values.filter { it.id !in sent }
            if (pending.isEmpty()) return
            for (sub in pending) {
                if (!isFirst) delay(config.resubscribeStagger)
                isFirst = false
                sent += sub.id
                // Re-read across the delay above: `pending` may name a subscription
                // that has since been unsubscribed. See the KDoc.
                if (!subscriptions.containsKey(sub.id)) {
                    log("skipping replay of ${sub.name} id=${sub.id} — unsubscribed mid-replay")
                    continue
                }
                sub.onDisconnected()
                sub.replayRetryArmed = true
                current.send(subMessage(sub))
            }
        }
    }

    /**
     * The "single retry" half of 14-large-screen-arc.md decision 17: re-sends one
     * replayed subscription the server refused, once, after
     * [DdpClientConfig.resubscribeRetryDelay].
     *
     * Guarded on the *identity* of the session that was refused. If the socket died
     * during the backoff, [replaySubscriptions] on the next session already owns this
     * subscription and re-armed it; sending here too would duplicate the sub and burn
     * a second slot out of the very bucket this mechanism exists to protect. The
     * membership check covers the other race — [unsubscribe] while we waited.
     */
    private fun retryRefusedReplay(refusedOn: Session, sub: DdpSubscription) {
        scope.launch {
            delay(config.resubscribeRetryDelay)
            if (closed || session !== refusedOn || !subscriptions.containsKey(sub.id)) return@launch
            log("retrying refused replay of ${sub.name} id=${sub.id}")
            refusedOn.send(subMessage(sub))
        }
    }

    private fun subMessage(sub: DdpSubscription): JsonObject = buildJsonObject {
        put("msg", "sub")
        put("id", sub.id)
        put("name", sub.name)
        put("params", JsonArray(sub.params))
    }

    /**
     * Quiescence: once every active sub is ready again, drop un-replayed documents.
     *
     * A subscription awaiting its one retry after a refused replay has NOT gone ready
     * and stays in [subscriptions] with `readyOnWire == false`, so it holds this
     * window open by itself — which is the behaviour we want, and the reason the
     * retry path deliberately does not call this at all. Closing quiescence while a
     * retry is in flight would sweep away the previous session's copy of that
     * creature's properties and blank the card for as long as the retry takes; only a
     * `ready`, a real refusal (which removes the sub), or an [unsubscribe] may end the
     * window.
     */
    private fun maybeEndResync() {
        if (!mirror.isResyncing) return
        if (subscriptions.values.all { it.readyOnWire }) {
            mirror.endResync()
            log("mirror resync complete: ${mirror.snapshot().mapValues { it.value.size }}")
        }
    }

    private fun log(message: String) = config.logger(message)

    // ------------------------------------------------------------------ session

    private sealed interface SocketEvent {
        data object Opened : SocketEvent
        data class Text(val text: String) : SocketEvent
        data class Closed(val code: Int, val reason: String?) : SocketEvent
        data class Failure(val error: Throwable) : SocketEvent
    }

    internal class PendingCall(val id: String, val method: String) {
        val deferred = CompletableDeferred<JsonElement>()

        @Volatile var result: JsonElement = JsonNull
        @Volatile var error: DdpError? = null
        @Volatile var resultSeen = false
        @Volatile var updatedSeen = false

        fun maybeComplete() {
            if (!resultSeen || !updatedSeen) return
            val failure = error
            if (failure != null) deferred.completeExceptionally(failure) else deferred.complete(result)
        }
    }

    /** One socket's worth of protocol state. Confined to the client dispatcher. */
    private inner class Session(private val events: Channel<SocketEvent>) {

        lateinit var socket: DdpSocket

        val opened = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<String>()
        val closedSignal = CompletableDeferred<Unit>()

        /**
         * Whether the server has answered `connect` — i.e. whether it will read anything else
         * we send. Meteor rejects every frame ahead of `connected` with *"Must connect first"*,
         * so this is the difference between "there is a socket" and "there is a session".
         * See [unsubscribe], which is the one sender outside the handshake path itself.
         */
        val isHandshaken: Boolean get() = connected.isCompleted

        private val pendingCalls = ConcurrentHashMap<String, PendingCall>()
        private val pendingPongs = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        /** Work deferred until after [MongoMirror.flush] — see [pump]. */
        private val signals = ArrayList<() -> Unit>()
        private var terminated = false
        private var terminalCause: Throwable? = null

        val listener = object : DdpSocketListener {
            override fun onOpen() {
                events.trySend(SocketEvent.Opened)
            }

            override fun onText(text: String) {
                events.trySend(SocketEvent.Text(text))
            }

            override fun onClosed(code: Int, reason: String?) {
                events.trySend(SocketEvent.Closed(code, reason))
            }

            override fun onFailure(error: Throwable) {
                events.trySend(SocketEvent.Failure(error))
            }
        }

        fun send(message: JsonObject): Boolean {
            val text = json.encodeToString(JsonObject.serializer(), message)
            log("→ $text")
            return socket.send(text)
        }

        fun beginCall(method: String, params: List<JsonElement>): PendingCall {
            val id = methodCounter.incrementAndGet().toString()
            val call = PendingCall(id, method)
            pendingCalls[id] = call
            call.deferred.invokeOnCompletion { pendingCalls.remove(id) }
            send(
                buildJsonObject {
                    put("msg", "method")
                    put("method", method)
                    put("params", JsonArray(params))
                    put("id", id)
                }
            )
            return call
        }

        fun failPending(cause: Throwable) {
            opened.completeExceptionally(cause)
            connected.completeExceptionally(cause)
            pendingCalls.values.toList().forEach { it.deferred.completeExceptionally(cause) }
            pendingCalls.clear()
            pendingPongs.values.toList().forEach { it.completeExceptionally(cause) }
            pendingPongs.clear()
        }

        /**
         * Reads the socket, drains everything already queued, flushes the mirror, and
         * only then delivers `ready`/`result`/`connected` signals. That ordering is
         * what makes "awaitReady() returned ⇒ the documents are in the mirror" true.
         */
        suspend fun pump() {
            while (true) {
                process(events.receive())
                while (true) {
                    val next = events.tryReceive().getOrNull() ?: break
                    process(next)
                }
                mirror.flush()
                if (signals.isNotEmpty()) {
                    val batch = signals.toList()
                    signals.clear()
                    batch.forEach { it() }
                }
                if (terminated) {
                    closedSignal.complete(Unit)
                    return
                }
            }
        }

        /** DDP-level heartbeat. Starts only once the session is LIVE. */
        suspend fun heartbeat() {
            if (config.heartbeatInterval <= Duration.ZERO) return
            while (true) {
                delay(config.heartbeatInterval)
                val id = "hb-${MeteorId.random(8)}"
                val pong = CompletableDeferred<Unit>()
                pendingPongs[id] = pong
                send(buildJsonObject { put("msg", "ping"); put("id", id) })
                try {
                    withTimeout(config.heartbeatTimeout) { pong.await() }
                } catch (timedOut: TimeoutCancellationException) {
                    // MUST be caught before CancellationException: a rethrown
                    // TimeoutCancellationException would just kill this coroutine and
                    // leave a dead socket open forever.
                    log("no pong within ${config.heartbeatTimeout} — dropping socket")
                    pendingPongs.remove(id)
                    socket.close(4000, "ddp heartbeat timeout")
                    return
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    log("heartbeat failed ($t) — dropping socket")
                    pendingPongs.remove(id)
                    socket.close(4000, "ddp heartbeat failure")
                    return
                }
                pendingPongs.remove(id)
            }
        }

        private fun process(event: SocketEvent) {
            when (event) {
                is SocketEvent.Opened -> signals += { opened.complete(Unit) }

                is SocketEvent.Text -> handleText(event.text)

                is SocketEvent.Closed -> terminate(
                    DdpConnectionException("socket closed (${event.code} ${event.reason.orEmpty()})")
                )

                is SocketEvent.Failure -> terminate(
                    DdpConnectionException("socket failure: ${event.error}", event.error)
                )
            }
        }

        private fun terminate(cause: Throwable) {
            if (terminated) return
            terminated = true
            terminalCause = cause
            log("socket down: ${cause.message}")
            signals += { failPending(cause) }
        }

        private fun handleText(text: String) {
            log("← $text")
            val message = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (t: Throwable) {
                log("unparseable frame ($t): $text")
                null
            } ?: return

            when (message.str("msg")) {
                // Meteor's SockJS heartbeat frames have no "msg" at all — ignored above.
                "connected" -> {
                    val id = message.str("session").orEmpty()
                    signals += { connected.complete(id) }
                }

                "failed" -> terminate(
                    DdpConnectionException("server requires DDP version ${message.str("version")}")
                )

                // Both directions, per 02-ddp-and-api.md. Answered inline rather than
                // deferred: a pong must not queue behind a 500-document batch.
                "ping" -> send(
                    buildJsonObject {
                        put("msg", "pong")
                        message["id"]?.let { put("id", it) }
                    }
                )

                "pong" -> {
                    val id = message.str("id")
                    signals += {
                        if (id == null) pendingPongs.values.firstOrNull()?.complete(Unit)
                        else pendingPongs[id]?.complete(Unit)
                    }
                }

                // `addedBefore` is the ordered-publication variant; DiceCloud does not
                // publish ordered collections, so position is dropped on purpose.
                "added", "addedBefore" -> {
                    val collection = message.str("collection") ?: return
                    val id = message.str("id") ?: return
                    mirror.applyAdded(collection, id, message["fields"] as? JsonObject ?: JsonObject(emptyMap()))
                }

                "changed" -> {
                    val collection = message.str("collection") ?: return
                    val id = message.str("id") ?: return
                    val cleared = (message["cleared"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList()
                    mirror.applyChanged(collection, id, message["fields"] as? JsonObject, cleared)
                }

                "removed" -> {
                    val collection = message.str("collection") ?: return
                    val id = message.str("id") ?: return
                    mirror.applyRemoved(collection, id)
                }

                "movedBefore" -> Unit // ordered publications only; not used by DiceCloud

                // Readiness is bookkept *now* (so quiescence lands in the same flush)
                // but only *published* in the signal phase, after MongoMirror.flush().
                // That is what makes "awaitReady() returned ⇒ documents are visible".
                "ready" -> {
                    val ready = (message["subs"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?.mapNotNull { subscriptions[it] }
                        ?: emptyList()
                    ready.forEach {
                        it.readyOnWire = true
                        // Readiness spends the replay retry: a `nosub` arriving after
                        // the server has already served this publication on this
                        // session is the server *stopping* it (permission revoked,
                        // creature deleted), never congestion — re-sending would
                        // argue with an answer we were given.
                        it.replayRetryArmed = false
                    }
                    maybeEndResync()
                    signals += { ready.forEach { it.readyState.value = true } }
                }

                // Two `nosub`s live here and they are not the same event.
                //
                // The ordinary one answers a fresh subscribe() — a bad publication
                // name, bad args, a creature we may not see. That is a verdict: the
                // sub is removed, the reason is published, and awaitReady() throws it.
                // Unchanged, and pinned by a test, because it is the only `nosub` any
                // caller outside the DM view can produce.
                //
                // The other answers a REPLAY, which the shared 50/10 s subscription
                // bucket can refuse for no reason of ours (decision 17). Removing the
                // sub there parks a live DM card stopped forever on a transient
                // server mood — the defect this branch closes. One retry, then the
                // ordinary path takes over and a second refusal is believed.
                "nosub" -> {
                    val subId = message.str("id") ?: return
                    val error = (message["error"] as? JsonObject)?.let(DdpError::fromJson)
                    val refusedReplay = subscriptions[subId]?.takeIf { it.replayRetryArmed }
                    if (refusedReplay != null) {
                        refusedReplay.replayRetryArmed = false // the retry is spent here
                        refusedReplay.readyOnWire = false
                        log("nosub for replayed ${refusedReplay.name} (${error?.reason}) — one retry")
                        // No maybeEndResync(): this sub has not gone ready, and it must
                        // keep the resync window open until the retry is answered.
                        retryRefusedReplay(this@Session, refusedReplay)
                        return
                    }
                    val sub = subscriptions.remove(subId)
                    sub?.readyOnWire = false
                    maybeEndResync()
                    signals += {
                        sub?.readyState?.value = false
                        // failure before stopped: awaitReady() reports the real reason.
                        if (error != null) sub?.failureState?.value = error
                        sub?.stoppedState?.value = true
                    }
                }

                "result" -> {
                    val id = message.str("id") ?: return
                    val call = pendingCalls[id] ?: return
                    val error = (message["error"] as? JsonObject)?.let(DdpError::fromJson)
                    if (error != null) call.error = error else call.result = message["result"] ?: JsonNull
                    call.resultSeen = true
                    signals += { call.maybeComplete() }
                }

                "updated" -> {
                    val ids = (message["methods"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: emptyList()
                    signals += {
                        ids.forEach { methodId ->
                            pendingCalls[methodId]?.let {
                                it.updatedSeen = true
                                it.maybeComplete()
                            }
                        }
                    }
                }

                "error" -> log("server protocol error: $text")

                else -> Unit
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private class AuthRejected(val error: DdpError) : RuntimeException(error.message)

    companion object {
        const val DDP_VERSION: String = "1"

        private val INSTANCES = AtomicLong(0)

        /** Convenience factory for `wss://<server>/websocket`. */
        fun okHttp(
            url: String,
            config: DdpClientConfig = DdpClientConfig(),
            httpClient: OkHttpClient = OkHttpDdpSocketFactory.defaultClient(),
            resumeTokenProvider: suspend () -> String? = { null },
        ): DdpClient = DdpClient(
            socketFactory = OkHttpDdpSocketFactory(url, httpClient),
            config = config,
            resumeTokenProvider = resumeTokenProvider,
        )
    }
}

/**
 * Meteor's id alphabet (02-ddp-and-api.md): 17 chars from UNMISTAKABLE_CHARS — no
 * `0`, `1`, `l`, `I` or `O`. Used for subscription ids so our traffic is
 * indistinguishable from the official client's, and ready for the day we insert
 * properties client-side.
 */
object MeteorId {
    const val UNMISTAKABLE_CHARS: String = "23456789ABCDEFGHJKLMNPQRSTWXYZabcdefghijkmnopqrstuvwxyz"

    fun random(length: Int = 17, random: kotlin.random.Random = kotlin.random.Random.Default): String =
        buildString(length) {
            repeat(length) { append(UNMISTAKABLE_CHARS[random.nextInt(UNMISTAKABLE_CHARS.length)]) }
        }

    /** True if [value] could have come out of [random]. */
    fun isValid(value: String, length: Int = 17): Boolean =
        value.length == length && value.all { it in UNMISTAKABLE_CHARS }
}
