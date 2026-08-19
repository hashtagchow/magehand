package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * The three write ops FR-9 adds (docs/design/12-inventory-layout.md decisions 7 and 8).
 *
 * ### Why a second op test rather than more cases in [InventoryWriteOpTest]
 *
 * That class is FR-8's, and its KDoc names what it holds down: the method name the probe
 * corrected, the mandatory `order`, the rate class and the equip inverse. FR-9's ops share the
 * rate class and nothing else — the delete is the **first op in this app whose inverse is a
 * different method**, and the move is the first that carries a whole *location* rather than a
 * value. Both are assertions about a shape nothing in the suite had before, and burying them
 * in a file about equipping and inserting would make neither easy to find.
 *
 * Every assertion here is about something a live server or a live player would punish: the
 * method names, the `docRef`/`parentRef` nesting `organizeDoc` requires, the inverse that
 * decides whether the snackbar's UNDO does anything, and the merge rule that decides where a
 * doubly-moved item ends up.
 */
class InventoryMoveDeleteWriteOpTest {

    private val WriteOp.body: JsonObject get() = (params.single() as JsonObject)

    private fun WriteOp.text(key: String): String? =
        (body[key] as? JsonPrimitive)?.content

    private fun move(
        propertyId: String = "i1",
        parentId: String = "bag1",
        order: Int = 9,
        previousParentId: String = "carried1",
        previousOrder: Int = 4,
        targetName: String = "Belt Pouch",
    ) = WriteOp.moveItem(
        propertyId = propertyId,
        parentId = parentId,
        order = order,
        previousParentId = previousParentId,
        previousOrder = previousOrder,
        targetName = targetName,
    )

    // -----------------------------------------------------------------------
    // Delete (decision 7)
    // -----------------------------------------------------------------------

    @Test
    fun `delete calls softRemove with the id and nothing else`() {
        val op = WriteOp.removeItem("i1", targetName = "Torch")

        assertEquals("creatureProperties.softRemove", op.method)
        assertEquals("i1", op.text("_id"))
        assertEquals("i1", op.targetId)
        assertEquals("Torch", op.targetName)
        // The whole body. A stray field on a delete is the kind of thing a validator rejects
        // outright, and the one parameter this method takes is the id.
        assertEquals(setOf("_id"), op.body.keys)
    }

    /**
     * The headline of decision 7, and the reason a delete may offer UNDO at all.
     *
     * `softRemove` is the *only* deletion DiceCloud exposes — it sets `removed: true` and
     * `restore` clears it — so the inverse is a real one rather than a best effort. This is
     * also the first op in this app whose inverse is a **different method**, which is why it
     * is asserted rather than left to the reader of the type.
     */
    @Test
    fun `delete inverts into restore - the first op whose inverse is a different method`() {
        val op = WriteOp.removeItem("i1", targetName = "Torch")
        val inverse = op.inverse

        assertEquals("creatureProperties.restore", inverse?.method)
        assertEquals("i1", (inverse as WriteOp).text("_id"))
        assertEquals("the undo has to be able to name the item too", "Torch", inverse.targetName)
        assertNotEquals(
            "this is the assertion the type's KDoc is about",
            op.method,
            inverse.method,
        )
    }

    @Test
    fun `delete and restore name each other's intents, both ways`() {
        assertEquals(TrackerWriteKind.ITEM_DELETE, WriteOp.removeItem("i1").intent)
        assertEquals(TrackerWriteKind.ITEM_RESTORE, WriteOp.removeItem("i1").inverse?.intent)
        assertEquals(TrackerWriteKind.ITEM_RESTORE, TrackerWriteKind.ITEM_DELETE.inverted())
        assertEquals(TrackerWriteKind.ITEM_DELETE, TrackerWriteKind.ITEM_RESTORE.inverted())
    }

    /**
     * The restore is symmetric so the type tells the truth about itself — but note that
     * nothing walks it: `WriteQueue.undo` submits an inverse with `recordUndo = false`, so an
     * undone delete does not become a redoable restore.
     */
    @Test
    fun `restore inverts back into the delete`() {
        val restore = WriteOp.removeItem("i1", "Torch").inverse as WriteOp
        assertEquals("creatureProperties.softRemove", restore.inverse?.method)
    }

    /**
     * A delete is not a rest. It invalidates nothing that came before it — undoing a spend
     * from before a delete is still perfectly correct — so it must not clear the undo stack.
     */
    @Test
    fun `delete is not a barrier and predicts nothing`() {
        val op = WriteOp.removeItem("i1")
        assertFalse("only a rest rewrites the whole sheet", op.isBarrier)
        // OptimisticChange's vocabulary is values and toggles; "this row is gone" is neither.
        assertNull(op.optimistic)
    }

    /**
     * Never merged. A second delete of one item means nothing, and a coalesce key would open
     * the possibility of a delete merging with the `restore` queued behind it — a pair the
     * player asked for **in order**, not a pair that cancels out.
     */
    @Test
    fun `deletes never coalesce, and cannot swallow the restore behind them`() {
        val delete = WriteOp.removeItem("i1")
        val restore = delete.inverse as WriteOp

        assertNull(delete.coalesceKey)
        assertNull(restore.coalesceKey)
        assertNull(delete.coalesceWith(WriteOp.removeItem("i1")))
        assertNull(delete.coalesceWith(restore))
    }

    @Test
    fun `delete and restore are in the slow rate class, like everything but damage`() {
        val delete = WriteOp.removeItem("i1")
        val restore = delete.inverse as WriteOp
        val damage = WriteOp.Damage("p3", WriteOperation.INCREMENT, 1)

        assertEquals(WriteOp.SLOW_SPACING_MILLIS, delete.minSpacingMillis)
        assertEquals(WriteOp.SLOW_SPACING_MILLIS, restore.minSpacingMillis)
        assertTrue(
            "deleting is emphatically not damage; it must not get the 20/5 s lane",
            delete.minSpacingMillis > damage.minSpacingMillis,
        )
    }

    /**
     * The queue's rate gate is keyed on the **method name** (`WriteQueue.markDispatched`), so
     * an op whose inverse is a different method waits in a different lane. Stated as a test
     * because it is a real behavioural consequence of decision 7's shape that nobody would
     * predict from the type: the undo of a delete does not queue behind the delete.
     */
    @Test
    fun `the delete and its inverse occupy different rate lanes`() {
        val delete = WriteOp.removeItem("i1")
        assertNotEquals(delete.method, (delete.inverse as WriteOp).method)
    }

    // -----------------------------------------------------------------------
    // Move (decision 8)
    // -----------------------------------------------------------------------

    @Test
    fun `move calls organizeDoc with docRef, parentRef and order`() {
        val op = move()

        assertEquals("organize.organizeDoc", op.method)

        val docRef = op.body["docRef"]!!.jsonObject
        assertEquals("i1", (docRef["id"] as JsonPrimitive).content)
        assertEquals("creatureProperties", (docRef["collection"] as JsonPrimitive).content)

        val parentRef = op.body["parentRef"]!!.jsonObject
        assertEquals("bag1", (parentRef["id"] as JsonPrimitive).content)
        assertEquals("creatureProperties", (parentRef["collection"] as JsonPrimitive).content)

        assertEquals(9, (op.body["order"] as JsonPrimitive).intOrNull)
        assertEquals(setOf("docRef", "parentRef", "order"), op.body.keys)
    }

    /**
     * The carried root can resolve to the **creature itself** on a sheet with neither a
     * `carried` nor an `inventory` folder — `InventoryEngine.insertTarget`'s last branch — so
     * the collection has to be carried through rather than hard-coded on both refs.
     */
    @Test
    fun `a move to the creature root sends the creatures collection on the parent only`() {
        val op = WriteOp.moveItem(
            propertyId = "i1",
            parentId = "c1",
            order = 9,
            previousParentId = "bag1",
            previousOrder = 4,
            parentCollection = "creatures",
        )

        assertEquals("creatures", (op.body["parentRef"]!!.jsonObject["collection"] as JsonPrimitive).content)
        assertEquals(
            "the doc being moved is always a property, whatever it is being moved under",
            "creatureProperties",
            (op.body["docRef"]!!.jsonObject["collection"] as JsonPrimitive).content,
        )
    }

    /**
     * Decision 8's undo, and the reason the op carries the prior location rather than deriving
     * one: a destination says nothing about where the thing came from.
     */
    @Test
    fun `a move inverts into the move back, parent and order both`() {
        val inverse = move().inverse as WriteOp

        assertEquals("organize.organizeDoc", inverse.method)
        assertEquals("carried1", (inverse.body["parentRef"]!!.jsonObject["id"] as JsonPrimitive).content)
        assertEquals(4, (inverse.body["order"] as JsonPrimitive).intOrNull)
        assertEquals("Belt Pouch", inverse.targetName)
        // And back again: the pair is symmetric, so a round trip through `inverse` is identity.
        assertEquals(move().params, (inverse.inverse as WriteOp).params)
    }

    @Test
    fun `a move's intent is self-inverting, like a toggle's`() {
        assertEquals(TrackerWriteKind.ITEM_MOVE, move().intent)
        assertEquals(TrackerWriteKind.ITEM_MOVE, TrackerWriteKind.ITEM_MOVE.inverted())
    }

    /**
     * The merge rule, and the half that matters: the **starting point survives**. Two picks
     * inside one rate window are one call, and its undo returns the item to where it was
     * before the *first* pick — not to the middle of the burst.
     */
    @Test
    fun `two moves of one item merge into the last destination, keeping the first origin`() {
        val first = move(parentId = "bag1", order = 9, previousParentId = "carried1", previousOrder = 4)
        val second = move(parentId = "bag2", order = 10, previousParentId = "bag1", previousOrder = 9)

        val merged = first.coalesceWith(second) as WriteOp
        assertEquals("bag2", (merged.body["parentRef"]!!.jsonObject["id"] as JsonPrimitive).content)

        val inverse = merged.inverse as WriteOp
        assertEquals(
            "the undo of a merged burst returns to where the item was before the first tap",
            "carried1",
            (inverse.body["parentRef"]!!.jsonObject["id"] as JsonPrimitive).content,
        )
        assertEquals(4, (inverse.body["order"] as JsonPrimitive).intOrNull)
    }

    /**
     * Moves of **different** items must not merge — they are two relocations of two things,
     * and the coalesce key is the item precisely so they cannot reorder around each other.
     */
    @Test
    fun `moves of two different items never merge`() {
        assertNull(move(propertyId = "i1").coalesceWith(move(propertyId = "i2")))
        assertEquals("move:i1", move(propertyId = "i1").coalesceKey)
        assertNotEquals(move(propertyId = "i1").coalesceKey, move(propertyId = "i2").coalesceKey)
    }

    /**
     * Deliberately **not** a [WriteOp.Noop], unlike a double equip.
     *
     * `order` is recomputed against the live sheet on every pick, so a there-and-back pair is
     * not the same destination it started from and collapsing it would drop a call that does
     * change the sheet. Pinned so a future "tidy this up like Equip does" edit has to argue
     * with a failing test rather than with a paragraph.
     */
    @Test
    fun `a move back to the original parent is still a call, not a Noop`() {
        val out = move(parentId = "bag1", order = 9, previousParentId = "carried1", previousOrder = 4)
        val back = move(parentId = "carried1", order = 11, previousParentId = "bag1", previousOrder = 9)

        val merged = out.coalesceWith(back)
        assertTrue("an order-recomputing move cannot honestly collapse", merged is WriteOp.MoveProperty)
        assertEquals(
            11,
            ((merged as WriteOp).body["order"] as JsonPrimitive).intOrNull,
        )
    }

    @Test
    fun `move is in the slow rate class and predicts nothing`() {
        val op = move()
        val insert = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 1)

        assertEquals(WriteOp.SLOW_SPACING_MILLIS, op.minSpacingMillis)
        assertEquals(insert.minSpacingMillis, op.minSpacingMillis)
        assertNull(op.optimistic)
        assertFalse(op.isBarrier)
    }

    /**
     * The coalesce key is the **item**, not the destination. Two moves conflict over the
     * property whose parent they both rewrite; keying on where they are going would let them
     * reorder around each other and land the item somewhere neither tap asked for.
     */
    @Test
    fun `a move's target is the item it relocates`() {
        assertEquals("i1", move(propertyId = "i1", parentId = "bag1").targetId)
    }
}
