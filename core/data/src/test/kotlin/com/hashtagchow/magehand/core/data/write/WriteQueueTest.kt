package com.hashtagchow.magehand.core.data.write

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.ddp.DdpConnectionException
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * Everything the WP4 brief asks the queue to prove — timing, coalescing, rollback and
 * undo — under `kotlinx-coroutines-test` virtual time, against
 * [FakeDdpMethodCaller]. **Nothing here touches the live server.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteQueueTest {

    private val slot = TrackedResource(
        propertyId = "slot-1",
        kind = TrackerKind.SPELL_SLOT,
        name = "1st Level",
        value = 4,
        total = 4,
        spellSlotLevel = 1,
    )

    private val potion = TrackedResource(
        propertyId = "item-1",
        kind = TrackerKind.ITEM,
        name = "Potion of Healing",
        value = 3,
        total = 3,
    )

    /**
     * A queue wired to virtual time.
     *
     * The worker runs on the **test scope**, not `backgroundScope`: `advanceUntilIdle`
     * stops as soon as no *foreground* work remains, so a worker parked in the background
     * would simply never be scheduled and every timing assertion here would pass
     * vacuously. The price is that each harness must be closed, which [queueTest] does.
     */
    private class Harness(
        scope: TestScope,
        state: ConnectionState,
        config: WriteQueueConfig,
    ) {
        val connection = MutableStateFlow(state)
        val caller = FakeDdpMethodCaller { scope.testScheduler.currentTime }
        val failures = mutableListOf<WriteFailure>()

        val queue = WriteQueue(
            caller = caller,
            connectionState = connection,
            scope = scope,
            nowMillis = { scope.testScheduler.currentTime },
            config = config,
        )

        // Unconfined so the collector is subscribed before the test body runs: `failures`
        // is a zero-replay SharedFlow and a refusal is emitted synchronously on submit.
        private val collector: Job = scope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            queue.failures.collect { failures += it }
        }

        fun close() {
            queue.close()
            collector.cancel()
        }
    }

    private val opened = mutableListOf<Harness>()

    private fun TestScope.harness(
        state: ConnectionState = ConnectionState.LIVE,
        config: WriteQueueConfig = WriteQueueConfig(),
    ): Harness = Harness(this, state, config).also { opened += it }

    /** [runTest] that shuts every harness down, so the forever-looping worker can finish. */
    private fun queueTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            opened.forEach { it.close() }
        }
    }

    // -----------------------------------------------------------------------
    // Rate limiting (docs/design/02-ddp-and-api.md §Client rule)
    // -----------------------------------------------------------------------

    @Test
    fun `damage calls are spaced at least 250 ms apart`() = queueTest {
        val h = harness()
        // Distinct properties, so the spacing is measured and not the coalescing.
        repeat(4) { index -> h.queue.submit(WriteOp.spend(slot.copy(propertyId = "slot-$index"))) }
        advanceUntilIdle()

        val times = h.caller.timesFor("creatureProperties.damage")
        assertEquals(4, times.size)
        times.zipWithNext { a, b ->
            assertTrue("calls $a and $b are only ${b - a} ms apart", b - a >= 250)
        }
        // …and no slower than it has to be.
        assertEquals(750L, times.last() - times.first())
    }

    @Test
    fun `adjustQuantity, flipToggle and rest are spaced at least one second apart`() = queueTest {
        val h = harness()
        // Distinct properties so nothing coalesces.
        h.queue.submit(WriteOp.AdjustQuantity("i1", WriteOperation.INCREMENT, -1))
        h.queue.submit(WriteOp.AdjustQuantity("i2", WriteOperation.INCREMENT, -1))
        h.queue.submit(WriteOp.FlipToggle("t1"))
        h.queue.submit(WriteOp.FlipToggle("t2"))
        h.queue.submit(WriteOp.rest("cr-1", RestType.SHORT_REST))
        advanceUntilIdle()

        listOf("creatureProperties.adjustQuantity", "creatureProperties.flipToggle").forEach { method ->
            h.caller.timesFor(method).zipWithNext { a, b ->
                assertTrue("$method calls only ${b - a} ms apart", b - a >= 1_000)
            }
        }
        assertEquals(5, h.caller.calls.size)
    }

    @Test
    fun `the rate-limit classes are independent - a rest does not slow slot taps`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.rest("cr-1", RestType.LONG_REST))
        h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()

        // The damage call must not have waited out the rest's 1 s class spacing.
        val rest = h.caller.timesFor("creature.methods.rest").single()
        val damage = h.caller.timesFor("creatureProperties.damage").single()
        assertTrue("damage waited ${damage - rest} ms behind the rest", damage - rest < 1_000)
    }

    @Test
    fun `the very first call goes out immediately`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()
        assertEquals(listOf(0L), h.caller.timesFor("creatureProperties.damage"))
    }

    // -----------------------------------------------------------------------
    // Coalescing
    // -----------------------------------------------------------------------

    @Test
    fun `rapid taps on one slot coalesce into a single summed increment`() = queueTest {
        val h = harness()
        // Four taps faster than the queue can dispatch the first: one call, value 4.
        repeat(4) { h.queue.submit(WriteOp.spend(slot)) }
        advanceUntilIdle()

        val damage = h.caller.calls.filter { it.method == "creatureProperties.damage" }
        assertEquals("expected the burst to collapse to one call, got ${h.caller.calls}", 1, damage.size)
        assertEquals(4, damage.single().int("value"))
        assertEquals("increment", damage.single().text("operation"))
        assertEquals("slot-1", damage.single().text("_id"))
    }

    @Test
    fun `taps arriving while a call is in flight coalesce into the next one`() = queueTest {
        val h = harness()
        val gate = CompletableDeferred<Unit>()
        h.caller.gate = gate

        h.queue.submit(WriteOp.spend(slot))
        advanceTimeBy(10) // the first tap is now on the wire and parked
        repeat(3) { h.queue.submit(WriteOp.spend(slot)) }
        gate.complete(Unit)
        advanceUntilIdle()

        val damage = h.caller.calls.filter { it.method == "creatureProperties.damage" }
        assertEquals(2, damage.size)
        assertEquals(1, damage[0].int("value"))
        assertEquals(3, damage[1].int("value"))
        assertEquals(4, damage.sumOf { it.int("value") })
        // …and the second call still respected the 250 ms floor.
        assertTrue(damage[1].atMillis - damage[0].atMillis >= 250)
    }

    @Test
    fun `taps on different properties are never merged`() = queueTest {
        val h = harness()
        repeat(3) { index ->
            h.queue.submit(WriteOp.Damage("slot-$index", WriteOperation.INCREMENT, 1))
        }
        advanceUntilIdle()
        assertEquals(3, h.caller.calls.size)
        assertEquals(setOf("slot-0", "slot-1", "slot-2"), h.caller.calls.map { it.text("_id") }.toSet())
    }

    @Test
    fun `a spend and an immediate restore cancel out and are never sent`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred() // hold the first call so a queue can form
        h.queue.submit(WriteOp.spend(slot)) // this one is already on the wire
        h.queue.submit(WriteOp.spend(slot))
        h.queue.submit(WriteOp.restore(slot))
        advanceTimeBy(10)
        h.caller.gate?.complete(Unit)
        advanceUntilIdle()

        // Two calls would have been +1 then +1-1 = 0; the zero is dropped entirely.
        assertEquals(1, h.caller.calls.size)
        assertEquals(1, h.caller.calls.single().int("value"))
    }

    /**
     * The end-to-end version of `WriteOpCoalesceTest`: one call *and* one truthful
     * history row. The burst below is +1 then −3, so the server sees a restore of 2 and
     * the user must be told about a restore of 2 — not the "Spent" the head op wanted.
     *
     * (Every submit lands before the worker's first turn, so the whole burst is one
     * coalesced entry — which is exactly the window the bug lived in.)
     */
    @Test
    fun `a burst that nets out the other way is filed as what it actually did`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.spend(slot))
        repeat(3) { h.queue.submit(WriteOp.restore(slot)) }
        advanceUntilIdle()

        assertEquals(1, h.caller.calls.size)
        assertEquals("a net restore of 2 is `increment -2`", -2, h.caller.calls.single().int("value"))

        val burst = h.queue.history.value.first() // newest first
        assertEquals(TrackerWriteKind.RESTORE, burst.kind)
        assertEquals(2, burst.amount)
        assertEquals("1st Level", burst.targetName)
    }

    @Test
    fun `a double toggle tap collapses to nothing`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        h.queue.submit(WriteOp.FlipToggle("t1"))
        h.queue.submit(WriteOp.FlipToggle("t1"))
        h.queue.submit(WriteOp.FlipToggle("t1"))
        advanceTimeBy(10)
        h.caller.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, h.caller.calls.size)
    }

    @Test
    fun `an absolute set is never coalesced with an increment`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        h.queue.submit(WriteOp.spend(slot))
        h.queue.submit(WriteOp.setValue(slot, desired = 0))
        h.queue.submit(WriteOp.spend(slot))
        advanceTimeBy(10)
        h.caller.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, h.caller.calls.size)
        assertEquals("set", h.caller.calls[1].text("operation"))
        assertEquals("`set` carries the desired value itself", 0, h.caller.calls[1].int("value"))
    }

    @Test
    fun `a rest is never coalesced with anything`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.rest("cr-1", RestType.SHORT_REST))
        h.queue.submit(WriteOp.rest("cr-1", RestType.SHORT_REST))
        advanceUntilIdle()
        assertEquals(2, h.caller.timesFor("creature.methods.rest").size)
    }

    // -----------------------------------------------------------------------
    // Optimistic overlay + rollback (06 §Reconciliation)
    // -----------------------------------------------------------------------

    @Test
    fun `the overlay appears on submit and clears when the server acknowledges`() = queueTest {
        val h = harness()
        h.caller.latencyMillis = 100

        h.queue.submit(WriteOp.spend(slot))
        assertEquals(3, h.queue.optimistic.value.valueFor("slot-1", serverValue = 4))

        advanceUntilIdle()
        assertTrue("overlay must be dropped once the server answered", h.queue.optimistic.value.isEmpty)
    }

    @Test
    fun `a failed write rolls its overlay back and reports the failure`() = queueTest {
        val h = harness()
        h.caller.latencyMillis = 50
        h.caller.failWith = { DdpError("too-many-requests", "slow down") }

        val ticket = h.queue.submit(WriteOp.spend(slot))
        assertEquals(3, h.queue.optimistic.value.valueFor("slot-1", 4))

        advanceUntilIdle()

        assertTrue(ticket.isCompleted)
        assertTrue(runCatching { ticket.await() }.isFailure)
        assertTrue("overlay must roll back on error", h.queue.optimistic.value.isEmpty)
        assertEquals(1, h.failures.size)
        assertTrue(h.failures.single().isRateLimit)
    }

    @Test
    fun `several in-flight ops on one property stack their deltas`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        repeat(3) { h.queue.submit(WriteOp.spend(slot)) }
        advanceTimeBy(10)

        assertEquals(1, h.queue.optimistic.value.valueFor("slot-1", 4))

        h.caller.gate?.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.queue.optimistic.value.isEmpty)
    }

    @Test
    fun `the overlay clamps rather than showing an impossible value`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        repeat(9) { h.queue.submit(WriteOp.spend(slot)) }
        advanceTimeBy(10)

        val board = com.hashtagchow.magehand.core.model.TrackerBoard(slots = listOf(slot))
        assertEquals(0, h.queue.optimistic.value.applyTo(board).slots.single().value)

        h.caller.gate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `an optimistic item increase survives the clamp, a slot increase does not`() = queueTest {
        // The clamp's upper bound is `maxOf(total, value)`, and an item row is built with
        // `total == value == quantity` — so on an item that bound *is* the current
        // quantity, and clamping to it silently deleted every "+1". Picking up a potion
        // showed 3 until the server answered, then jumped to 4.
        val h = harness()
        h.caller.gate = CompletableDeferred()
        h.queue.submit(WriteOp.adjust(potion, +1))
        h.queue.submit(WriteOp.restore(slot)) // already at 4/4
        advanceTimeBy(10)

        val board = com.hashtagchow.magehand.core.model.TrackerBoard(
            slots = listOf(slot),
            allItems = listOf(potion),
        )
        val applied = h.queue.optimistic.value.applyTo(board)
        assertEquals("items have no cap, so +1 on 3 must read 4", 4, applied.allItems.single().value)
        assertEquals("a full slot row still clamps at its total", 4, applied.slots.single().value)

        h.caller.gate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `an item consumption predicts a lower quantity, not a higher one`() = queueTest {
        val h = harness()
        h.caller.latencyMillis = 10
        h.queue.submit(WriteOp.consumeItem(potion))
        assertEquals(2, h.queue.optimistic.value.valueFor("item-1", serverValue = 3))
        advanceUntilIdle()
    }

    @Test
    fun `a rest shows no optimistic change at all`() = queueTest {
        val h = harness()
        h.caller.latencyMillis = 10
        h.queue.submit(WriteOp.rest("cr-1", RestType.LONG_REST))
        assertTrue(h.queue.optimistic.value.isEmpty)
        advanceUntilIdle()
    }

    // -----------------------------------------------------------------------
    // Undo (03 §Write semantics)
    // -----------------------------------------------------------------------

    @Test
    fun `undo sends the inverse increment`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.spend(slot, amount = 2))
        advanceUntilIdle()
        assertTrue(h.queue.canUndo.value)

        h.caller.reset()
        val undone = h.queue.undo()
        advanceUntilIdle()

        assertTrue(undone)
        val call = h.caller.calls.single()
        assertEquals("creatureProperties.damage", call.method)
        assertEquals(-2, call.int("value"))
        assertFalse("undo is not redo", h.queue.canUndo.value)
    }

    @Test
    fun `undo of a coalesced burst restores the whole burst`() = queueTest {
        val h = harness()
        repeat(4) { h.queue.submit(WriteOp.spend(slot)) }
        advanceUntilIdle()
        h.caller.reset()

        // Two calls went out (+1, +3) so there are two undo entries; both together
        // restore exactly the four charges that were actually spent.
        var restored = 0
        while (h.queue.undo()) {
            advanceUntilIdle()
        }
        advanceUntilIdle()
        h.caller.calls.forEach { restored += it.int("value") }

        assertEquals(-4, restored)
    }

    @Test
    fun `a rest can never enter the undo stack`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.rest("cr-1", RestType.LONG_REST))
        advanceUntilIdle()

        assertFalse("rest is not undoable — 03 requires a confirm dialog instead", h.queue.canUndo.value)
        assertFalse(h.queue.undo())
    }

    @Test
    fun `a failed write leaves nothing to undo`() = queueTest {
        val h = harness()
        h.caller.failWith = { DdpError("internal", "boom") }
        runCatching { h.queue.enqueue(WriteOp.spend(slot)) }
        advanceUntilIdle()
        assertFalse(h.queue.canUndo.value)
    }

    @Test
    fun `undo of an absolute set restores the previous value`() = queueTest {
        val h = harness()
        val partiallySpent = slot.copy(value = 3)
        h.queue.submit(WriteOp.setValue(partiallySpent, desired = 0))
        advanceUntilIdle()
        h.caller.reset()

        h.queue.undo()
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("set", call.text("operation"))
        // `set` carries the remaining value, so the inverse of "set to 0" is "set back to
        // the 3 it was". (03 says `total − value`; the live server says otherwise — WP7.)
        assertEquals(3, call.int("value"))
    }

    @Test
    fun `setting a row to zero is sent, not mistaken for a cancelled-out pair`() = queueTest {
        // `set 0` and `increment 0` are both "value zero" ops and only one of them means
        // nothing. On the HP row the difference is "the character is at 0" versus silence.
        val h = harness()
        h.queue.submit(WriteOp.setValue(slot, desired = 0))
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("set", call.text("operation"))
        assertEquals(0, call.int("value"))
    }

    @Test
    fun `the undo stack is bounded`() = queueTest {
        val h = harness(config = WriteQueueConfig(undoDepth = 3))
        repeat(5) { index ->
            h.queue.submit(WriteOp.Damage("slot-$index", WriteOperation.INCREMENT, 1))
        }
        advanceUntilIdle()
        h.caller.reset()

        var undone = 0
        while (h.queue.undo()) {
            advanceUntilIdle()
            undone++
        }
        assertEquals(3, undone)
    }

    @Test
    fun `clearUndoHistory empties the stack`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()
        h.queue.clearUndoHistory()
        assertFalse(h.queue.canUndo.value)
    }

    // -----------------------------------------------------------------------
    // Online-only writes (06 §Why v1 writes are online-only)
    // -----------------------------------------------------------------------

    @Test
    fun `writes are refused unless the connection is LIVE`() = queueTest {
        for (state in listOf(ConnectionState.CONNECTING, ConnectionState.OFFLINE, ConnectionState.AUTH_FAILED)) {
            val h = harness(state = state)
            val ticket = h.queue.submit(WriteOp.spend(slot))
            advanceUntilIdle()

            assertTrue("expected refusal in $state", ticket.isCompleted)
            val thrown = runCatching { ticket.await() }.exceptionOrNull()
            assertTrue("$state produced $thrown", thrown is WriteRefusedException)
            assertEquals(state, (thrown as WriteRefusedException).state)
            assertTrue("nothing may reach the wire in $state", h.caller.calls.isEmpty())
            assertTrue(h.queue.optimistic.value.isEmpty)
            assertEquals(1, h.failures.size)
            assertTrue(h.failures.single().isRefusal)
        }
    }

    @Test
    fun `an op that was queued while LIVE is refused if the socket dies before its turn`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        val second = h.queue.submit(WriteOp.Damage("b", WriteOperation.INCREMENT, 1))
        advanceTimeBy(10)

        h.connection.value = ConnectionState.CONNECTING
        h.caller.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a"), h.caller.calls.map { it.text("_id") })
        assertTrue(runCatching { second.await() }.exceptionOrNull() is WriteRefusedException)
        assertTrue(h.queue.optimistic.value.isEmpty)
    }

    // -----------------------------------------------------------------------
    // Failure handling
    // -----------------------------------------------------------------------

    @Test
    fun `a rate-limited call is retried exactly once, after the server window`() = queueTest {
        val h = harness(config = WriteQueueConfig(rateLimitBackoffMillis = 5_000))
        var attempts = 0
        h.caller.failWith = { if (++attempts == 1) DdpError("too-many-requests", "slow down") else null }

        h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()

        assertEquals(2, h.caller.calls.size)
        assertEquals(5_000L, h.caller.calls[1].atMillis - h.caller.calls[0].atMillis)
        assertTrue("a successful retry is still a success", h.queue.canUndo.value)
        assertTrue(h.failures.isEmpty())
    }

    @Test
    fun `a rate limit that survives the retry fails the write`() = queueTest {
        val h = harness()
        h.caller.failWith = { DdpError("too-many-requests", "slow down") }

        val ticket = h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()

        assertEquals(2, h.caller.calls.size)
        assertTrue(runCatching { ticket.await() }.exceptionOrNull() is DdpError)
        assertFalse(h.queue.canUndo.value)
    }

    @Test
    fun `a write lost to a dying socket is never replayed`() = queueTest {
        // docs/verification/WP2.md deviation #3: replaying an increment whose result we
        // never saw is exactly the silent slot corruption this design refuses.
        val h = harness()
        h.caller.failWith = { DdpConnectionException("socket died") }

        val ticket = h.queue.submit(WriteOp.spend(slot))
        advanceUntilIdle()

        assertEquals(1, h.caller.calls.size)
        assertTrue(runCatching { ticket.await() }.exceptionOrNull() is DdpConnectionException)
        assertTrue(h.queue.optimistic.value.isEmpty)
    }

    @Test
    fun `one failure does not stall the queue`() = queueTest {
        val h = harness()
        h.caller.failWith = { call -> DdpError("internal", "boom").takeIf { call.text("_id") == "a" } }

        val first = h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        val second = h.queue.submit(WriteOp.Damage("b", WriteOperation.INCREMENT, 1))
        advanceUntilIdle()

        assertTrue(runCatching { first.await() }.isFailure)
        assertTrue(runCatching { second.await() }.isSuccess)
    }

    // -----------------------------------------------------------------------
    // Serial execution and bookkeeping
    // -----------------------------------------------------------------------

    @Test
    fun `only one call is ever in flight`() = queueTest {
        val h = harness()
        var concurrent = 0
        var maxConcurrent = 0
        h.caller.failWith = { null }
        val counting = DdpMethodCaller { method, params ->
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            val result = h.caller.call(method, params)
            concurrent--
            result
        }
        val queue = WriteQueue(
            caller = counting,
            connectionState = h.connection,
            scope = this,
            nowMillis = { testScheduler.currentTime },
        )
        h.caller.latencyMillis = 40
        repeat(5) { index -> queue.submit(WriteOp.Damage("p$index", WriteOperation.INCREMENT, 1)) }
        advanceUntilIdle()

        assertEquals(1, maxConcurrent)
        assertEquals(5, h.caller.calls.size)
        queue.close()
    }

    @Test
    fun `outstanding drops back to zero and awaitIdle returns`() = queueTest {
        val h = harness()
        h.caller.latencyMillis = 20
        repeat(3) { index -> h.queue.submit(WriteOp.Damage("p$index", WriteOperation.INCREMENT, 1)) }
        assertEquals(3, h.queue.outstanding.value)
        advanceUntilIdle()
        h.queue.awaitIdle()
        assertEquals(0, h.queue.outstanding.value)
    }

    @Test
    fun `closing the queue cancels whatever is still waiting`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        val stranded = h.queue.submit(WriteOp.Damage("b", WriteOperation.INCREMENT, 1))
        advanceTimeBy(10)

        h.queue.close()
        h.caller.gate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(stranded.isCancelled)
        assertTrue(h.queue.optimistic.value.isEmpty)
    }

    /**
     * The half the test above did not cover: `takeCoalescedHead()` has already removed
     * the head from the queue, so a `close()` that only drains the queue leaves the
     * submissions behind the *in-flight* call unresolved forever. A caller awaiting one
     * from outside a `viewModelScope` would simply never wake up.
     */
    @Test
    fun `closing the queue also settles the call that is already in flight`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        val inFlight = h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        advanceTimeBy(10)
        // It really is on the wire, so the assertion below is about the right window.
        assertEquals(1, h.caller.calls.size)
        assertFalse(inFlight.isCompleted)

        h.queue.close()
        advanceUntilIdle()

        assertTrue("the in-flight submission never settled", inFlight.isCompleted)
        assertTrue(inFlight.isCancelled)
    }

    /**
     * Every submission the head *absorbed* has to be settled too, not just the one that
     * created it — the claim is made on the merged entry, which is why the claim now
     * happens inside `takeCoalescedHead()` after the merge rather than in `dispatch()`.
     *
     * **What this does not cover, honestly.** Moving the claim also closed a window of a
     * few instructions between "removed from the queue" and "claimed as in-flight", during
     * which a concurrent `close()` saw the entry in neither place and left it unsettled.
     * That window has no suspension point in it, so a single-threaded `TestScope` cannot
     * schedule a `close()` inside it — there is no deterministic way to hit it from a test
     * without instrumenting the production code with a seam that exists only for the test.
     * A multi-threaded stress loop could hit it *sometimes*, which is worse than not
     * testing it: a race test that passes with the bug present is false assurance. So the
     * window is closed by construction — removal and claim are now one lock acquisition,
     * and there is no intermediate state to observe — and what is pinned here is the
     * observable consequence: whatever `close()` finds, no submission is left hanging.
     */
    @Test
    fun `closing settles every submission behind a coalesced in-flight entry`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        // The first goes on the wire; the rest pile up and merge into the next head.
        val first = h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        advanceTimeBy(10)
        assertEquals(1, h.caller.calls.size)

        val merged = (1..3).map { h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1)) }
        // Let the gated first call finish so the worker takes and coalesces the rest.
        h.caller.gate?.complete(Unit)
        h.caller.gate = CompletableDeferred()
        advanceUntilIdle()
        assertEquals("the three taps should have coalesced into one call", 2, h.caller.calls.size)
        merged.forEach { assertFalse("a coalesced submission settled early", it.isCompleted) }

        h.queue.close()
        advanceUntilIdle()

        assertTrue(first.isCompleted)
        merged.forEachIndexed { index, deferred ->
            assertTrue("coalesced submission $index never settled", deferred.isCompleted)
            assertTrue("coalesced submission $index should be cancelled", deferred.isCancelled)
        }
        assertTrue(h.queue.optimistic.value.isEmpty)
    }

    /**
     * The consequence that made it a bug rather than an untidiness: `await()` has to
     * return control, one way or another.
     */
    @Test
    fun `awaiting an in-flight write across a close completes exceptionally`() = queueTest {
        val h = harness()
        h.caller.gate = CompletableDeferred()
        val inFlight = h.queue.submit(WriteOp.Damage("a", WriteOperation.INCREMENT, 1))
        advanceTimeBy(10)

        var outcome: Throwable? = null
        // A scope the queue's close() does not cancel — a repository awaiting a write,
        // not a ViewModel.
        val awaiter = backgroundScope.launch {
            outcome = runCatching { inFlight.await() }.exceptionOrNull()
        }
        h.queue.close()
        advanceUntilIdle()

        assertTrue("await() never returned", awaiter.isCompleted)
        assertTrue("expected a cancellation, got $outcome", outcome is CancellationException)
    }

    // -----------------------------------------------------------------------
    // Wire shapes (docs/design/02-ddp-and-api.md §Method catalog)
    // -----------------------------------------------------------------------

    @Test
    fun `every op serializes to the documented parameter shape`() = queueTest {
        val h = harness()
        h.queue.submit(WriteOp.takeDamage(slot.copy(kind = TrackerKind.HIT_POINTS), 7))
        h.queue.submit(WriteOp.consumeItem(potion))
        h.queue.submit(WriteOp.FlipToggle("t1"))
        h.queue.submit(WriteOp.rest("cr-1", RestType.LONG_REST))
        advanceUntilIdle()

        val byMethod = h.caller.calls.associateBy { it.method }
        with(byMethod.getValue("creatureProperties.damage")) {
            assertEquals("slot-1", text("_id"))
            assertEquals("increment", text("operation"))
            assertEquals(7, int("value"))
        }
        with(byMethod.getValue("creatureProperties.adjustQuantity")) {
            assertEquals("item-1", text("_id"))
            // `increment` is a consumption amount, exactly as on `damage`: +1 removes one.
            // 03's write table says -1 and is wrong for this server — WP7 probed it.
            assertEquals(1, int("value"))
        }
        with(byMethod.getValue("creatureProperties.flipToggle")) {
            assertEquals("t1", text("_id"))
            assertEquals(setOf("_id"), body.keys)
        }
        with(byMethod.getValue("creature.methods.rest")) {
            assertEquals("cr-1", text("creatureId"))
            assertEquals("longRest", text("restType"))
        }
        assertNotNull(byMethod["creature.methods.rest"])
    }

    @Test
    fun `healing is a negative damage increment and the server clamps it`() = queueTest {
        val h = harness()
        val hp = slot.copy(kind = TrackerKind.HIT_POINTS, propertyId = "hp", value = 3, total = 17)
        h.queue.submit(WriteOp.heal(hp, 100))
        advanceUntilIdle()
        assertEquals(-100, h.caller.calls.single().int("value"))
    }
}
