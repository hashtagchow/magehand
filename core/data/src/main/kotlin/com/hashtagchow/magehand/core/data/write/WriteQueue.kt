package com.hashtagchow.magehand.core.data.write

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import com.hashtagchow.magehand.core.ddp.DdpConnectionException
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import java.util.concurrent.atomic.AtomicLong

/** The one thing [WriteQueue] needs from `:core:ddp`. A seam, so tests need no socket. */
fun interface DdpMethodCaller {
    suspend fun call(method: String, params: List<JsonElement>): JsonElement
}

/** Thrown (and delivered to the caller's [Deferred]) when a write is attempted off-LIVE. */
class WriteRefusedException(val state: ConnectionState) : IllegalStateException(
    "writes require ConnectionState.LIVE, current state is $state",
)

/** Reported on [WriteQueue.failures] whenever an op's optimistic layer had to be rolled back. */
data class WriteFailure(val op: WriteOp, val cause: Throwable) {
    val isRateLimit: Boolean get() = (cause as? DdpError)?.isRateLimit == true
    val isRefusal: Boolean get() = cause is WriteRefusedException
}

data class WriteQueueConfig(
    /**
     * How long to wait after a `too-many-requests` before the single retry
     * docs/design/02-ddp-and-api.md prescribes. The server's windows are all 5 s wide.
     */
    val rateLimitBackoffMillis: Long = 5_000,
    /** How many inverse ops the session-scoped undo stack keeps. */
    val undoDepth: Int = 32,
    /**
     * How many rows the history sheet keeps. Larger than [undoDepth] on purpose: an entry
     * stops being undoable long before it stops being worth reading ("what did I spend
     * before the fight?").
     */
    val historyDepth: Int = 100,
    val logger: (String) -> Unit = {},
)

/**
 * The tracker's only path to a server write.
 *
 * What it guarantees, and why each one is load-bearing:
 *
 * 1. **Serial per connection.** One worker coroutine, one call in flight. DiceCloud
 *    recomputes the whole sheet on every write; overlapping increments against a
 *    recomputing sheet is the corruption 06-offline-and-sync.md is written to avoid.
 * 2. **Client-side rate limiting**, per method class:
 *    `creatureProperties.damage` ≥250 ms apart, `adjustQuantity` / `rest` / `flipToggle`
 *    ≥1 s apart (docs/design/02-ddp-and-api.md §Method catalog + §Client rule). The gate
 *    is applied *before* taking work off the queue, which is what makes coalescing
 *    effective: everything that piles up during the wait merges into one call.
 * 3. **Coalescing.** Rapid taps on the same property become one `increment` with the
 *    summed value; a pair that cancels out is dropped entirely
 *    ([WriteOp.Noop]) rather than sent as `increment 0`.
 * 4. **Optimistic overlay + rollback.** An op's predicted change is published the moment
 *    it is submitted and removed the moment the call resolves — success *or* failure.
 *    Rollback is therefore not a separate code path (see [OptimisticOverlay]).
 * 5. **Undo stack of inverse ops.** Pushed on success only. `rest` has no inverse and can
 *    never enter the stack (docs/design/03-data-model.md).
 * 6. **Writes are refused unless [ConnectionState.LIVE]** — checked at submit *and* again
 *    at dispatch, because the socket can die while an op sits in the queue.
 *
 * **Retries.** Exactly one case retries: `too-many-requests`, after the server's window
 * (02 §Known server quirks). A write that failed because the socket died is **not**
 * replayed — we never saw its result, and replaying an `increment` whose outcome is
 * unknown is precisely the silent slot corruption WP2 refused to risk
 * (docs/verification/WP2.md deviation #3).
 *
 * Plain constructor by design; the sibling work package owns the Hilt wiring.
 */
class WriteQueue(
    private val caller: DdpMethodCaller,
    private val connectionState: StateFlow<ConnectionState>,
    scope: CoroutineScope,
    /** Injectable so tests can drive it from `TestScope.testScheduler.currentTime`. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val config: WriteQueueConfig = WriteQueueConfig(),
) {

    private class Submission(
        val id: Long,
        val op: WriteOp,
        val deferred: CompletableDeferred<Unit>,
        val recordUndo: Boolean,
    )

    /** One dispatchable unit: a (possibly coalesced) op plus every submission behind it. */
    private class Entry(var op: WriteOp, val submissions: MutableList<Submission>)

    private val lock = Any()
    private val queue = ArrayDeque<Entry>()

    /**
     * The entry [dispatch] is currently working on, or `null` when the worker is idle.
     *
     * It has to be tracked separately because [takeCoalescedHead] *removes* it from
     * [queue] before the call goes out, so between that removal and [resolve] the
     * entry — and every submission behind it — is reachable from nowhere else. That is
     * the window [close] used to miss.
     *
     * Guarded by [lock], like [queue].
     */
    private var inFlight: Entry? = null

    /** Pending optimistic changes, oldest first, keyed by submission id. */
    private val pending = LinkedHashMap<Long, OptimisticChange>()

    /** Inverse ops, newest last, each tagged with the [TrackerWrite] it would undo. */
    private class Undoable(val entryId: Long, val inverse: WriteOp)

    private val undoStack = ArrayDeque<Undoable>()
    private val lastDispatchAt = HashMap<String, Long>()
    private val ids = AtomicLong(0)
    private val entryIds = AtomicLong(0)

    private val wakeUp = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _optimistic = MutableStateFlow(OptimisticOverlay.EMPTY)

    /** Latency compensation to apply on top of the engine's board (06 §Reconciliation). */
    val optimistic: StateFlow<OptimisticOverlay> = _optimistic.asStateFlow()

    private val _failures = MutableSharedFlow<WriteFailure>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every rolled-back write, for the UI's "that didn't stick" surface. */
    val failures: SharedFlow<WriteFailure> = _failures.asSharedFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _history = MutableStateFlow<List<TrackerWrite>>(emptyList())

    /**
     * Everything this session actually sent, **newest first** — the undo snackbar's source
     * and the history sheet's list (docs/design/04-screens-ux.md §3).
     *
     * One entry per *dispatched call*, so a burst that coalesced into `increment +3` is one
     * row saying "spent 3", not three rows promising three undos that do not exist.
     */
    val history: StateFlow<List<TrackerWrite>> = _history.asStateFlow()

    private val _outstanding = MutableStateFlow(0)

    /** Submitted but not yet resolved. `0` means the queue is idle. */
    val outstanding: StateFlow<Int> = _outstanding.asStateFlow()

    private val worker: Job = scope.launch { run() }

    // -----------------------------------------------------------------------
    // Submission
    // -----------------------------------------------------------------------

    /**
     * Queues [op] and returns immediately — this is what a UI tap calls.
     *
     * The returned [Deferred] completes when the server has acknowledged the call
     * (`result` **and** `updated`, per WP2), or fails with the [DdpError],
     * [WriteRefusedException] or [DdpConnectionException] that killed it.
     */
    fun submit(op: WriteOp): Deferred<Unit> = submitInternal(op, recordUndo = true)

    /** [submit] plus awaiting the outcome. Throws on failure. */
    suspend fun enqueue(op: WriteOp) = submit(op).await()

    private fun submitInternal(op: WriteOp, recordUndo: Boolean): Deferred<Unit> {
        val deferred = CompletableDeferred<Unit>()

        val state = connectionState.value
        if (state != ConnectionState.LIVE) {
            // 06-offline-and-sync.md: taps require LIVE. Failing here rather than queueing
            // is the whole point — a queued increment replayed hours later is a
            // conflict machine.
            val refusal = WriteRefusedException(state)
            deferred.completeExceptionally(refusal)
            _failures.tryEmit(WriteFailure(op, refusal))
            return deferred
        }

        val submission = Submission(ids.incrementAndGet(), op, deferred, recordUndo)
        synchronized(lock) {
            queue.addLast(Entry(op, mutableListOf(submission)))
            op.optimistic?.let { pending[submission.id] = it }
            _outstanding.value = _outstanding.value + 1
            republishOptimistic()
        }
        wakeUp.trySend(Unit)
        return deferred
    }

    /**
     * Applies the newest inverse op from the session-scoped undo stack.
     *
     * Returns `false` when there is nothing to undo. The undo itself is *not* pushed back
     * onto the stack — undo is not redo.
     */
    suspend fun undo(): Boolean {
        val undoable = synchronized(lock) {
            undoStack.removeLastOrNull().also { _canUndo.value = undoStack.isNotEmpty() }
        } ?: return false

        val succeeded = runCatching { submitInternal(undoable.inverse, recordUndo = false).await() }.isSuccess
        synchronized(lock) {
            if (succeeded) {
                markUndone(undoable.entryId)
            } else {
                // A failed undo leaves the world exactly as it was, so the entry has to go
                // back on the stack or the user loses their only way to reverse it.
                undoStack.addLast(undoable)
                _canUndo.value = true
            }
        }
        return succeeded
    }

    /** Drops the undo history — call when the character or the account changes. */
    fun clearUndoHistory() = synchronized(lock) {
        undoStack.clear()
        _canUndo.value = false
        _history.value = _history.value.map { if (it.undoable) it.copy(undoable = false) else it }
    }

    /** Suspends until nothing is queued or in flight. Mostly a test affordance. */
    suspend fun awaitIdle() {
        _outstanding.first { it == 0 }
    }

    /**
     * Shuts the worker down and settles **everything** it will now never finish —
     * both the entries still queued and the one already on the wire.
     *
     * The in-flight entry is the interesting half. Cancelling [worker] cancels it at
     * its `caller.call` suspension point, so [dispatch] unwinds on a
     * `CancellationException` and [resolve] is never reached; a caller awaiting that
     * submission from a scope this cancellation does not reach — anything outside a
     * `viewModelScope` — would suspend forever. Settling it here rather than by
     * running [resolve] under `NonCancellable` is deliberate: `NonCancellable` would
     * only protect the resolve *after* the call returns, and the call is precisely
     * what got cancelled, so the deferred would still never complete. This also keeps
     * every completion decision in one place holding one lock, which is the
     * concurrency argument the rest of the class is built on.
     *
     * `cancel()` rather than `completeExceptionally()` for the same reason it was
     * already used for the queued ones: the write did not fail, it never happened.
     * A close is the app tearing the connection down, not the server saying no —
     * so no [WriteFailure] is emitted either.
     */
    fun close() {
        worker.cancel()
        wakeUp.close()
        val stranded = synchronized(lock) {
            val all = queue.flatMap { it.submissions } + inFlight?.submissions.orEmpty()
            queue.clear()
            inFlight = null
            pending.clear()
            _outstanding.value = 0
            republishOptimistic()
            all
        }
        stranded.forEach { it.deferred.cancel() }
    }

    // -----------------------------------------------------------------------
    // The single worker
    // -----------------------------------------------------------------------

    private suspend fun run() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            if (isQueueEmpty()) {
                wakeUp.receive()
                continue
            }
            // Rate-limit gate first, *then* take: everything that arrives during the wait
            // gets a chance to coalesce into the call we are about to make.
            val head = peekOp() ?: continue
            awaitSpacing(head)

            val entry = takeCoalescedHead() ?: continue
            dispatch(entry)
        }
    }

    private fun isQueueEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    private fun peekOp(): WriteOp? = synchronized(lock) { queue.firstOrNull()?.op }

    private suspend fun awaitSpacing(op: WriteOp) {
        val spacing = op.minSpacingMillis
        if (spacing <= 0) return
        val last = synchronized(lock) { lastDispatchAt[op.method] } ?: return
        val wait = spacing - (nowMillis() - last)
        if (wait > 0) delay(wait)
    }

    /**
     * Pops the head and merges every later entry that shares its coalesce key.
     *
     * The scan stops at a **barrier**: a rest, or any op aimed at the same property that
     * cannot be merged into the head. Without that stop, `spend / set-to-0 / spend` would
     * be reordered into `spend×2` then `set-to-0`, and the user would end up with a
     * different number than they asked for. Ops aimed at *other* properties are safely
     * skipped over — the server applies each property's write independently.
     *
     * **It also claims the entry as [inFlight], under the same lock acquisition that
     * removes it from [queue].** Removing and claiming were two separate `synchronized`
     * blocks — this one and the one that opened `dispatch` — and between them the entry
     * was in neither: a [close] landing in that window found an empty queue and a `null`
     * `inFlight`, and every submission behind the entry stayed unsettled forever. The
     * window was a handful of instructions wide and needed a close on another thread to
     * hit it, which is exactly the kind of bug that only ever reproduces in the field.
     * Making removal and claim one atomic step removes the window rather than narrowing
     * it; there is no longer any moment at which a dispatchable entry is unreachable.
     */
    private fun takeCoalescedHead(): Entry? = synchronized(lock) {
        val head = queue.removeFirstOrNull() ?: return@synchronized null
        inFlight = head
        val key = head.op.coalesceKey ?: return@synchronized head
        val target = head.op.targetId

        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.op.isBarrier) break
            if (candidate.op.coalesceKey != key) {
                if (candidate.op.targetId == target) break
                continue
            }
            val merged = head.op.coalesceWith(candidate.op) ?: break
            head.op = merged
            head.submissions.addAll(candidate.submissions)
            iterator.remove()
        }

        // Increments that sum to zero are nothing to say to the server.
        //
        // **Increments only.** `set 0` is a real instruction — "this row is now empty", and
        // on the HP row "this character is down" — and dropping it would be a silent,
        // invisible failure of exactly the kind the number pad exists to avoid. Today a
        // `set` never reaches this line anyway (it has no coalesce key, so the scan above
        // returns early), but that is a property of two functions agreeing, and this is the
        // one that would do the damage.
        val isZeroIncrement = when (val op = head.op) {
            is WriteOp.Damage -> op.operation == WriteOperation.INCREMENT && op.value == 0
            is WriteOp.AdjustQuantity -> op.operation == WriteOperation.INCREMENT && op.value == 0
            else -> false
        }
        if (isZeroIncrement) head.op = WriteOp.Noop(key)
        head
    }

    /**
     * [entry] arrives already claimed as [inFlight] — see [takeCoalescedHead]. From here
     * until [resolve], [close] is the only other thing that can settle these submissions.
     */
    private suspend fun dispatch(entry: Entry) {
        if (entry.op is WriteOp.Noop) {
            config.logger("write: coalesced away ${entry.submissions.size} op(s)")
            resolve(entry, null)
            return
        }

        val state = connectionState.value
        if (state != ConnectionState.LIVE) {
            resolve(entry, WriteRefusedException(state))
            return
        }

        val failure = callWithRateLimitRetry(entry.op)

        if (failure == null && entry.submissions.any { it.recordUndo }) {
            record(entry.op)
        }
        resolve(entry, failure)
    }

    /**
     * One call, plus the single retry 02 allows for `too-many-requests`.
     *
     * Every other failure — including a socket death mid-method — is final. See the
     * class KDoc for why an unacknowledged `increment` must never be replayed.
     */
    private suspend fun callWithRateLimitRetry(op: WriteOp): Throwable? {
        repeat(2) { attempt ->
            markDispatched(op)
            val error = try {
                caller.call(op.method, op.params)
                return null
            } catch (e: DdpError) {
                e
            } catch (e: DdpConnectionException) {
                config.logger("write: ${op.description} lost its connection — not replayed")
                return e
            }

            if (!error.isRateLimit || attempt == 1) return error
            config.logger("write: rate limited, retrying ${op.description} once")
            delay(config.rateLimitBackoffMillis)
            if (connectionState.value != ConnectionState.LIVE) {
                return WriteRefusedException(connectionState.value)
            }
        }
        return null
    }

    private fun markDispatched(op: WriteOp) = synchronized(lock) {
        lastDispatchAt[op.method] = nowMillis()
    }

    /**
     * Files one dispatched call in [history] and, when it has an inverse, on the undo stack.
     *
     * **A rest invalidates everything before it.** The server applies every reset and every
     * trigger, so "restore the slot I spent" after a long rest would send `damage -1` at a
     * slot the server has already put back — silently overfilling it. The stack is therefore
     * emptied and the older rows are marked non-undoable rather than left as traps.
     */
    private fun record(op: WriteOp) = synchronized(lock) {
        val entryId = entryIds.incrementAndGet()
        val isRest = op is WriteOp.Rest

        if (isRest) {
            undoStack.clear()
            _history.value = _history.value.map { if (it.undoable) it.copy(undoable = false) else it }
        }

        op.inverse?.let { inverse ->
            undoStack.addLast(Undoable(entryId, inverse))
            while (undoStack.size > config.undoDepth) undoStack.removeFirst()
        }
        _canUndo.value = undoStack.isNotEmpty()

        val entry = TrackerWrite(
            id = entryId,
            kind = op.intent ?: TrackerWriteKind.SET_VALUE,
            targetName = op.targetName,
            amount = op.magnitude,
            at = nowMillis(),
            undoable = op.inverse != null,
            undone = false,
        )
        _history.value = (listOf(entry) + _history.value).take(config.historyDepth)
    }

    /** Must be called while holding [lock]. */
    private fun markUndone(entryId: Long) {
        _history.value = _history.value.map {
            if (it.id == entryId) it.copy(undoable = false, undone = true) else it
        }
    }

    /**
     * Completes every submission behind [entry] and drops their optimistic layers.
     *
     * Dropping happens on success too: once the server has acknowledged the write, the
     * mirror carries the real value and keeping a delta on top would double-count it.
     */
    private fun resolve(entry: Entry, failure: Throwable?) {
        synchronized(lock) {
            // Identity, not null-check: a close() that already settled this entry has
            // moved on, and clearing unconditionally could drop a later claim.
            if (inFlight === entry) inFlight = null
            entry.submissions.forEach { pending.remove(it.id) }
            _outstanding.value = (_outstanding.value - entry.submissions.size).coerceAtLeast(0)
            republishOptimistic()
        }
        if (failure != null) {
            _failures.tryEmit(WriteFailure(entry.op, failure))
        }
        entry.submissions.forEach {
            if (failure == null) it.deferred.complete(Unit) else it.deferred.completeExceptionally(failure)
        }
    }

    /** Must be called while holding [lock]. */
    private fun republishOptimistic() {
        _optimistic.value = OptimisticOverlay.of(pending.values.toList())
    }
}
