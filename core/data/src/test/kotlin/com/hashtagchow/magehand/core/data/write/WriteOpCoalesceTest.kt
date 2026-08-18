package com.hashtagchow.magehand.core.data.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * What a merged increment *says it did*.
 *
 * `coalesceWith` sums values of either sign but used to keep the head op's
 * [WriteOp.intent] verbatim, so a spend-then-restore burst inside one coalesce window
 * produced a net restore filed in the history — and offered on the undo stack — as
 * "Spent". The arithmetic was right and the sentence was wrong, which is the worse of
 * the two failures: the number pad exists so the user can trust what they are told.
 *
 * The wire shape of coalescing is covered by `WriteQueueTest`; this is the labelling.
 */
class WriteOpCoalesceTest {

    private val slot = TrackedResource(
        propertyId = "slot-1",
        kind = TrackerKind.SPELL_SLOT,
        name = "1st Level",
        value = 4,
        total = 4,
        spellSlotLevel = 1,
    )

    private val hp = TrackedResource(
        propertyId = "hp",
        kind = TrackerKind.HIT_POINTS,
        name = "Hit Points",
        value = 20,
        total = 20,
    )

    private val potion = TrackedResource(
        propertyId = "item-1",
        kind = TrackerKind.ITEM,
        name = "Potion of Healing",
        value = 3,
        total = 3,
    )

    private fun merge(head: WriteOp, vararg rest: WriteOp): WriteOp =
        rest.fold(head) { acc, next -> requireNotNull(acc.coalesceWith(next)) { "$acc would not merge $next" } }

    // -----------------------------------------------------------------------
    // The direction flips with the sum
    // -----------------------------------------------------------------------

    @Test
    fun `a spend burst that nets out to a restore is labelled RESTORE`() {
        val merged = merge(WriteOp.spend(slot), WriteOp.restore(slot, 3))

        assertEquals(TrackerWriteKind.RESTORE, merged.intent)
        assertEquals(2, merged.magnitude)
    }

    @Test
    fun `a restore burst that nets out to a spend is labelled SPEND`() {
        val merged = merge(WriteOp.restore(slot), WriteOp.spend(slot, 3))

        assertEquals(TrackerWriteKind.SPEND, merged.intent)
        assertEquals(2, merged.magnitude)
    }

    /**
     * The vocabulary must not drift. `damage increment −1` is "restore a slot" on a
     * spell slot and "heal 1" on the HP row, and a merge may only ever move along the
     * pair it started in.
     */
    @Test
    fun `an HP burst stays in the damage-heal vocabulary`() {
        val merged = merge(WriteOp.takeDamage(hp, 2), WriteOp.heal(hp, 5))

        assertEquals(TrackerWriteKind.HEAL, merged.intent)
        assertEquals(3, merged.magnitude)
    }

    @Test
    fun `a heal burst that nets out to damage is labelled TAKE_DAMAGE`() {
        val merged = merge(WriteOp.heal(hp, 2), WriteOp.takeDamage(hp, 5))

        assertEquals(TrackerWriteKind.TAKE_DAMAGE, merged.intent)
        assertEquals(3, merged.magnitude)
    }

    @Test
    fun `an item burst that nets out to an addition is labelled ITEM_ADD`() {
        val merged = merge(WriteOp.consumeItem(potion), WriteOp.adjust(potion, 4))

        assertEquals(TrackerWriteKind.ITEM_ADD, merged.intent)
        assertEquals(3, merged.magnitude)
    }

    // -----------------------------------------------------------------------
    // …and stays put when it should
    // -----------------------------------------------------------------------

    @Test
    fun `a same-direction burst keeps the head's label`() {
        val merged = merge(WriteOp.spend(slot), WriteOp.spend(slot), WriteOp.spend(slot))

        assertEquals(TrackerWriteKind.SPEND, merged.intent)
        assertEquals(3, merged.magnitude)
    }

    /**
     * A zero sum is dropped by the queue before it can be filed, so the label is
     * academic — but it must not become something *new*, which would be a fresh bug
     * the day the drop rule changed.
     */
    @Test
    fun `a burst that cancels out keeps the head's label`() {
        val merged = merge(WriteOp.spend(slot), WriteOp.restore(slot))

        assertEquals(TrackerWriteKind.SPEND, merged.intent)
        assertEquals(0, merged.magnitude)
    }

    @Test
    fun `an op with no intent gains none from merging`() {
        val merged = merge(
            WriteOp.Damage("p", WriteOperation.INCREMENT, 1),
            WriteOp.Damage("p", WriteOperation.INCREMENT, -4),
        )

        assertNull(merged.intent)
    }

    /** The merged op's inverse is derived from the *corrected* label, not the head's. */
    @Test
    fun `the inverse of a flipped burst undoes what actually happened`() {
        val merged = merge(WriteOp.spend(slot), WriteOp.restore(slot, 3))

        assertEquals(TrackerWriteKind.SPEND, merged.inverse?.intent)
        assertEquals(2, merged.inverse?.magnitude)
    }
}
