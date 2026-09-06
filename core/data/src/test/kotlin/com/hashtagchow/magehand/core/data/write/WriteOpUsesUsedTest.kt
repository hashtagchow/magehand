package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * FR-44 R2's write — `creatureProperties.update {_id, path:['usesUsed'], value}`.
 *
 * ### What this class is for, and what it deliberately is not
 *
 * The *server's* half was settled by a live probe (docs/verification/probe-fr44.md): the method
 * accepts the path, recomputes, and a matching long rest clears the field. No JVM test can assert
 * any of that, and pretending otherwise is how a repo ends up with a green suite and a refused
 * write. What is asserted here is everything on **this** side of the socket: the frame's shape,
 * the inversion between "uses left" and "uses spent", the inverse, and the coalescing rule — each
 * of which is a place a sign or a stale `previous` would produce a plausible-looking call that
 * undoes to the wrong number.
 */
class WriteOpUsesUsedTest {

    /** 1 of 2 left — so one more spend is `usesUsed: 2`, and a restore is `usesUsed: 0`. */
    private val ability = TrackedResource(
        propertyId = "lu-1",
        kind = TrackerKind.LIMITED_USE,
        name = "Guiding Bolt (Star Map)",
        value = 1,
        total = 2,
    )

    private fun WriteOp.param(): JsonObject = params.single() as JsonObject

    private fun WriteOp.pathElements(): List<String> =
        (param()["path"] as JsonArray).map { (it as JsonPrimitive).content }

    private fun WriteOp.writtenValue(): Int? = (param()["value"] as? JsonPrimitive)?.intOrNull

    private fun merge(head: WriteOp, vararg rest: WriteOp): WriteOp =
        rest.fold(head) { acc, next -> requireNotNull(acc.coalesceWith(next)) { "$acc would not merge $next" } }

    // --- the frame ----------------------------------------------------------

    /**
     * **The method and the path, together.** Either one alone is a half-assertion: `update` with
     * the wrong path is a silent `$set` of a field nothing reads, and the right path on `damage`
     * is a refusal. `update`'s denied-path list is `type/order/parent/ancestors/damage` and this
     * is the only path this app ever sends to it.
     */
    @Test
    fun `a spend is update on the usesUsed path`() {
        val op = WriteOp.spend(ability)

        assertEquals("creatureProperties.update", op.method)
        assertEquals(WriteOp.METHOD_UPDATE, op.method)
        assertEquals(listOf(WriteOp.PATH_USES_USED), op.pathElements())
        assertEquals("lu-1", (op.param()["_id"] as JsonPrimitive).content)
    }

    /**
     * The inversion, which is the whole arithmetic of this op: the row shows uses **left** and the
     * server stores uses **spent**. A client that sent the remaining count would refill an ability
     * every time the player spent one of it.
     */
    @Test
    fun `the value written is uses SPENT, not uses left`() {
        assertEquals(2, WriteOp.spend(ability).writtenValue())
        assertEquals(0, WriteOp.restore(ability).writtenValue())
    }

    /** Spend and restore keep the tracker's existing vocabulary — R2's "existing vocabulary". */
    @Test
    fun `spend and restore carry the pip vocabulary`() {
        assertEquals(TrackerWriteKind.SPEND, WriteOp.spend(ability).intent)
        assertEquals(TrackerWriteKind.RESTORE, WriteOp.restore(ability).intent)
    }

    /**
     * Clamped into `0..total` at the factory.
     *
     * `update` writes what it is handed — there is no server-side clamp on this path, unlike
     * `damage` — so an extra tap at the last pip would otherwise store `usesUsed: 3` on a 2-use
     * ability and leave the row reading −1 until someone edited the sheet by hand.
     */
    @Test
    fun `the written value is clamped to the row`() {
        // Two spends from a row with one use left: the second cannot go past empty.
        assertEquals(2, WriteOp.spend(ability, 2).writtenValue())
        // Three restores from 1 of 2: cannot go past full.
        assertEquals(0, WriteOp.restore(ability, 3).writtenValue())
    }

    // --- the inverse --------------------------------------------------------

    /**
     * The inverse is the same op with the two numbers swapped — R2's own words — and it has to
     * carry [WriteOp.SetUsesUsed.previous] to be one at all, because an absolute set cannot
     * reconstruct what it overwrote. This is [WriteOp.Equip]'s shape, not [WriteOp.Damage]'s.
     */
    @Test
    fun `the inverse swaps the values and restores the label`() {
        val spend = WriteOp.spend(ability) as WriteOp.SetUsesUsed
        val undo = spend.inverse as WriteOp.SetUsesUsed

        assertEquals(spend.previous, undo.value)
        assertEquals(spend.value, undo.previous)
        assertEquals(TrackerWriteKind.RESTORE, undo.intent)
        assertEquals("the undo must be the same method", spend.method, undo.method)
    }

    /** Undoing the undo is the original call — the pair is symmetric, so UNDO is honest. */
    @Test
    fun `the inverse of the inverse is the original`() {
        val spend = WriteOp.spend(ability) as WriteOp.SetUsesUsed

        val roundTrip = spend.inverse.inverse as WriteOp.SetUsesUsed

        assertEquals(spend.value, roundTrip.value)
        assertEquals(spend.previous, roundTrip.previous)
    }

    /**
     * The optimistic prediction moves the *row*, in uses left, while the frame carries uses spent
     * — and the inverse's prediction has to walk back the same distance. Getting this wrong shows
     * the right number on the wire and the wrong number under the player's thumb.
     */
    @Test
    fun `the optimistic prediction is in the row's own units`() {
        val spend = WriteOp.spend(ability) as WriteOp.SetUsesUsed

        assertEquals(OptimisticChange.ValueAbsolute("lu-1", 0), spend.optimistic)
        assertEquals(OptimisticChange.ValueAbsolute("lu-1", 1), spend.inverse.optimistic)
    }

    /** A direct-entry target is a set, so it names itself a set and offers no inverted label. */
    @Test
    fun `direct entry writes an absolute and keeps the set vocabulary`() {
        val op = WriteOp.setValue(ability, 2) as WriteOp.SetUsesUsed

        assertEquals(0, op.value)
        assertEquals(1, op.previous)
        assertEquals(TrackerWriteKind.SET_VALUE, op.intent)
        // `SET_VALUE.inverted()` is null; the undo of a set is another set, so the label is kept
        // rather than dropped — otherwise the history sheet's undo row would be unlabelled.
        assertEquals(TrackerWriteKind.SET_VALUE, op.inverse.intent)
    }

    // --- coalescing ---------------------------------------------------------

    /**
     * A burst merges to **one** call carrying the last value and the **first** `previous`.
     *
     * Summing — [WriteOp.Damage]'s rule — would be nonsense on an absolute: three taps would send
     * `usesUsed: 6`. Keeping the later op's `previous` would be worse than nonsense, because it
     * looks right: the call would be correct and UNDO would put the row back to where the burst's
     * last step started rather than to where the player's thumb started.
     */
    @Test
    fun `a burst of spends merges to one absolute that still undoes to the start`() {
        val full = ability.copy(value = 3, total = 3)
        val merged = merge(
            WriteOp.spend(full),
            WriteOp.spend(full.copy(value = 2)),
            WriteOp.spend(full.copy(value = 1)),
        ) as WriteOp.SetUsesUsed

        assertEquals("all three spent", 3, merged.value)
        assertEquals("undo goes back to untouched", 0, merged.previous)
        assertEquals(OptimisticChange.ValueAbsolute("lu-1", 0), merged.optimistic)
        // And the undo of the merged burst writes `usesUsed: 0` — the whole burst reversed in one
        // call, which is the property the carried-forward `previous` buys.
        val undo = merged.inverse as WriteOp.SetUsesUsed
        assertEquals(0, undo.value)
        assertEquals(3, undo.previous)
        assertEquals(OptimisticChange.ValueAbsolute("lu-1", 3), undo.optimistic)
    }

    /**
     * **A burst that nets out to nothing sends nothing.**
     *
     * The case an absolute set hides. Two increments that cancel merge to `value 0`, which is
     * visibly a no-op; a spend and a restore merge to an `update` carrying the number the property
     * already holds, which looks like a perfectly good call. It is not free: it spends one of the
     * server's five requests per five seconds, files a history row reading "Spent …, 0", and
     * pushes an undo entry for a write that never happened. `FlipToggle` has collapsed its
     * equivalent to `Noop` since WP7 and this is the same rule.
     */
    @Test
    fun `a spend and a restore inside one window collapse to nothing`() {
        val merged = WriteOp.spend(ability).coalesceWith(WriteOp.restore(ability.copy(value = 0)))

        assertTrue("expected a Noop, got $merged", merged is WriteOp.Noop)
        assertEquals("the Noop must keep the key, so a third tap merges back out of it",
            WriteOp.spend(ability).coalesceKey, merged!!.coalesceKey)
    }

    /**
     * And a third tap merges back **out** of the Noop, so N taps cost N mod 2 calls — the property
     * `FlipToggle.coalesceWith` names and the reason the Noop keeps the key rather than being
     * an inert terminator.
     */
    @Test
    fun `a third tap after a cancelling pair sends one call again`() {
        val cancelled = requireNotNull(
            WriteOp.spend(ability).coalesceWith(WriteOp.restore(ability.copy(value = 0))),
        )

        val revived = cancelled.coalesceWith(WriteOp.spend(ability))

        assertTrue("expected the spend back, got $revived", revived is WriteOp.SetUsesUsed)
        assertEquals(2, (revived as WriteOp.SetUsesUsed).value)
    }

    /**
     * A round trip that does *not* start where it ends still sends a call — the guard is on the
     * value, not on the shape of the burst. Spending twice and restoring once from 2-of-2 lands on
     * one spent, which is a real change and must go out.
     */
    @Test
    fun `a burst that nets to a real change is not collapsed`() {
        val full = ability.copy(value = 2, total = 2)
        val merged = merge(
            WriteOp.spend(full),
            WriteOp.spend(full.copy(value = 1)),
            WriteOp.restore(full.copy(value = 0)),
        )

        assertTrue(merged is WriteOp.SetUsesUsed)
        assertEquals(1, (merged as WriteOp.SetUsesUsed).value)
        assertEquals(0, merged.previous)
    }

    /** Two rows never merge, however alike the calls look. */
    @Test
    fun `ops on different properties do not merge`() {
        val other = ability.copy(propertyId = "lu-2")

        assertNull(WriteOp.spend(ability).coalesceWith(WriteOp.spend(other)))
    }

    /** Nor does anything else, including the `damage` op this one is often mistaken for. */
    @Test
    fun `a usesUsed op does not merge with a damage op`() {
        val slot = TrackedResource(
            propertyId = "lu-1",
            kind = TrackerKind.SPELL_SLOT,
            name = "1st Level",
            value = 3,
            total = 3,
        )

        assertNull(WriteOp.spend(ability).coalesceWith(WriteOp.spend(slot)))
    }

    // --- queue posture ------------------------------------------------------

    /**
     * Replayable and not a barrier, both of which are consequences of the call being an absolute
     * `set` rather than judgement calls.
     *
     * Re-sending the identical value after a `too-many-requests` produces the identical state,
     * which is exactly what [WriteOp.DoAction] cannot claim — and the reason this op carries an
     * undo where a Use does not. The rate class is `update`'s own 5 per 5 s, not `damage`'s fast
     * lane, however much a pip tap feels like a slot spend.
     */
    @Test
    fun `the op is replayable, not a barrier, and in the default rate class`() {
        val op = WriteOp.spend(ability)

        assertTrue(op.isReplayable)
        assertFalse(op.isBarrier)
        assertEquals(WriteOp.SLOW_SPACING_MILLIS, op.minSpacingMillis)
    }
}
