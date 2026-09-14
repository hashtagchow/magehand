package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * FR-53 R4's write pair — `TurnOffBuff` and `RestoreBuff` (design 21 decision 4).
 *
 * ### Why a third op-shape test file
 *
 * `InventoryMoveDeleteWriteOpTest`'s own argument, one wave on. That class holds FR-9's ops down
 * and its KDoc says what it is about; these two share a *method name* with its delete and share
 * nothing else that matters — a different intent, a different receipt, a coalesce key where the
 * delete deliberately has none, and an optimistic layer where the delete deliberately has none.
 * Filing them under a class about equipping and moving would make neither easy to find.
 *
 * ### What the 1.19.0 review found here, and what this file exists to stop
 *
 * MEDIUM-4: `TurnOffBuff.inverse` was changed to `RestoreProperty` — the *item* restore, which
 * sends the identical method with the identical params — and the whole of `:core:data` **and**
 * the whole of `:app` stayed green. `undoing a turn-off restores the same property` asserts the
 * wire call and the id, and those are byte-identical between the two types, so nothing could tell
 * them apart. The only observable difference is the `intent` the history entry is filed under, and
 * that difference is the *entire stated reason* `RestoreBuff` and [TrackerWriteKind.BUFF_RESTORE]
 * exist. A justification that strong needs a test, or the type it justifies is deletable without a
 * red — which is exactly what the reviewer demonstrated.
 *
 * So the assertions below are deliberately about the **types and the intents**, not only about the
 * frames. A frame test cannot distinguish these two ops and never could.
 */
class BuffWriteOpTest {

    private val WriteOp.body: JsonObject get() = (params.single() as JsonObject)

    private fun WriteOp.text(key: String): String? = (body[key] as? JsonPrimitive)?.content

    private val turnOff = WriteOp.turnOffBuff("b1", targetName = "Shield")

    // -----------------------------------------------------------------------
    // The frame
    // -----------------------------------------------------------------------

    @Test
    fun `turning a buff off calls softRemove with the id and nothing else`() {
        assertEquals("creatureProperties.softRemove", turnOff.method)
        assertEquals("b1", turnOff.text("_id"))
        assertEquals("b1", turnOff.targetId)
        assertEquals("Shield", turnOff.targetName)
        // The whole body. A stray field on this call is the kind of thing a validator rejects
        // outright, and the one parameter the method takes is the id.
        assertEquals(setOf("_id"), turnOff.body.keys)
        assertEquals(TrackerWriteKind.BUFF_OFF, turnOff.intent)
    }

    /**
     * R4's *"rate class `default`"*, and the correction the review made to the prose about it
     * (MEDIUM-1): this is `SLOW_SPACING_MILLIS`, which **is** the default class, and it is the
     * same number `removeItem` uses. Asserted against the delete rather than against a literal so
     * the two cannot drift apart while a KDoc claims they are the same.
     */
    @Test
    fun `the turn-off shares the delete's rate lane`() {
        assertEquals(WriteOp.SLOW_SPACING_MILLIS, turnOff.minSpacingMillis)
        assertEquals(WriteOp.removeItem("i1").minSpacingMillis, turnOff.minSpacingMillis)
    }

    // -----------------------------------------------------------------------
    // MEDIUM-4 — the inverse is RestoreBuff, and it is the same property
    // -----------------------------------------------------------------------

    /**
     * The exact mutation the reviewer ran: `inverse` → `RestoreProperty`. This is what turns it
     * red.
     *
     * Three assertions, because two of them pass under the mutation. The **type** is the one that
     * catches it; the method and the id are there so a future change that keeps the type and
     * breaks the call still fails, and the intent is there because the intent is the whole reason
     * the type exists.
     */
    @Test
    fun `the inverse is a RestoreBuff for the same property`() {
        val inverse = assertNotNull("a turn-off must be undoable", turnOff.inverse).let { turnOff.inverse!! }

        assertTrue(
            "RestoreProperty sends the identical frame — only the TYPE tells them apart, and " +
                "the type is what decides the history entry's sentence",
            inverse is WriteOp.RestoreBuff,
        )
        assertEquals(TrackerWriteKind.BUFF_RESTORE, inverse.intent)
        assertEquals("creatureProperties.restore", inverse.method)
        assertEquals("b1", inverse.text("_id"))
        assertEquals("the name rides along so the undo's receipt names the buff", "Shield", inverse.targetName)
    }

    /**
     * The journal round-trip: undoing the undo is the original op again, in type, id, name and
     * intent.
     *
     * This is what "the undo stack has something to hold" means in practice — `WriteQueue` walks
     * `inverse` to build the entry it will send, and a pair whose second hop landed on a different
     * type would file a `softRemove` under the wrong sentence the moment anything replayed it. It
     * also pins the symmetry `RestoreBuff`'s own KDoc claims ("the type tells the truth about
     * itself") rather than leaving it as prose.
     */
    @Test
    fun `the pair round-trips back to the same turn-off`() {
        val back = turnOff.inverse?.inverse

        assertTrue("two hops must land on TurnOffBuff, not on RemoveProperty", back is WriteOp.TurnOffBuff)
        assertEquals(turnOff, back)
        assertEquals(TrackerWriteKind.BUFF_OFF, back?.intent)
    }

    /**
     * …and the two pairs do not cross. A `removeItem` never inverts into a buff restore and a
     * turn-off never inverts into an item restore, which is the confusion MEDIUM-4 found nothing
     * guarding.
     */
    @Test
    fun `the buff pair and the item pair stay apart`() {
        assertTrue(WriteOp.removeItem("i1").inverse is WriteOp.RestoreProperty)
        assertTrue(turnOff.inverse is WriteOp.RestoreBuff)
        assertEquals(TrackerWriteKind.ITEM_RESTORE, WriteOp.removeItem("i1").inverse?.intent)
        assertEquals(TrackerWriteKind.BUFF_RESTORE, turnOff.inverse?.intent)
    }

    // -----------------------------------------------------------------------
    // Coalescing — the difference from the delete that a user can see
    // -----------------------------------------------------------------------

    /**
     * Two ✕ taps on one chip merge into **one of them**, not into a [WriteOp.Noop].
     *
     * The asymmetry with `FlipToggle` is the point and is easy to get wrong: two flips genuinely
     * cancel, and two soft-removes of one property leave it removed. A `Noop` here would swallow
     * the write the user asked for.
     */
    @Test
    fun `two turn-offs of one buff merge into one call`() {
        val merged = turnOff.coalesceWith(WriteOp.turnOffBuff("b1", targetName = "Shield"))

        assertEquals("buff:b1", turnOff.coalesceKey)
        assertTrue("not a Noop — the buff must still end", merged is WriteOp.TurnOffBuff)
        assertEquals(turnOff, merged)
    }

    /** Two different buffs are two writes; the key is per property. */
    @Test
    fun `turn-offs of different buffs do not merge`() {
        assertNull(turnOff.coalesceWith(WriteOp.turnOffBuff("b2", targetName = "Bless")))
    }

    /**
     * The restore never merges — `RemoveProperty`'s own reason, and sharper here: a coalesce key
     * on the inverse would let an undo fold into the turn-off still queued in front of it, which
     * is a pair the user asked for *in order*.
     */
    @Test
    fun `a restore carries no coalesce key`() {
        assertNull(turnOff.inverse?.coalesceKey)
    }

    // -----------------------------------------------------------------------
    // The optimistic layer
    // -----------------------------------------------------------------------

    @Test
    fun `the turn-off predicts the chip's removal and the restore predicts nothing`() {
        assertEquals(OptimisticChange.Removed("b1"), turnOff.optimistic)
        assertNull(
            "the reappearance is not predicted — this app would have to mint an AppliedBuff to " +
                "do it, and the user pressed UNDO knowingly",
            turnOff.inverse?.optimistic,
        )
    }
}
