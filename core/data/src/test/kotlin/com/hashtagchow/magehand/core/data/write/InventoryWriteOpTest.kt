package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ItemCatalog
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * The two write ops FR-8 adds (docs/design/10-inventory.md decisions 4, 5, 6, 11).
 *
 * Every assertion here is about something a live server would punish: the method name the
 * probe corrected, the `order` field the probe proved mandatory, the rate class that keeps
 * the queue under 5 calls per 5 seconds, and the undo shape that decides whether the history
 * sheet offers a button that works.
 */
class InventoryWriteOpTest {

    private val WriteOp.body: JsonObject get() = (params.single() as JsonObject)

    // -----------------------------------------------------------------------
    // Equip (decision 4)
    // -----------------------------------------------------------------------

    /**
     * The correction 10 decision 12 rides along with: docs/design/02-ddp-and-api.md said
     * `equipItem` from the start and nothing had ever called it. `equip` is the name the live
     * server answers to.
     */
    @Test
    fun `equip calls the method the probe found, not the one the doc guessed`() {
        val op = WriteOp.equip("p1", equipped = true, currentlyEquipped = false, targetName = "Quarterstaff")

        assertEquals("creatureProperties.equip", op.method)
        assertEquals("p1", op.body["_id"]?.let { (it as JsonPrimitive).content })
        assertEquals(true, (op.body["equipped"] as JsonPrimitive).booleanOrNull)
        assertEquals("p1", op.targetId)
        assertEquals("Quarterstaff", op.targetName)
    }

    /**
     * The 5-per-5-seconds class — the same one `adjustQuantity`, `flipToggle` and `rest` are
     * in. Only `creatureProperties.damage` gets the fast 20/5 s class, and equip is not damage.
     */
    @Test
    fun `equip is in the slow rate class, exactly like adjustQuantity`() {
        val equip = WriteOp.equip("p1", equipped = true, currentlyEquipped = false)
        val adjust = WriteOp.AdjustQuantity("p2", WriteOperation.INCREMENT, 1)
        val damage = WriteOp.Damage("p3", WriteOperation.INCREMENT, 1)

        assertEquals(WriteOp.SLOW_SPACING_MILLIS, equip.minSpacingMillis)
        assertEquals(adjust.minSpacingMillis, equip.minSpacingMillis)
        assertTrue(
            "equip must not be given damage's fast lane",
            equip.minSpacingMillis > damage.minSpacingMillis,
        )
    }

    @Test
    fun `equip's intent names the direction, and inverts into the other one`() {
        assertEquals(
            TrackerWriteKind.EQUIP,
            WriteOp.equip("p1", equipped = true, currentlyEquipped = false).intent,
        )
        assertEquals(
            TrackerWriteKind.UNEQUIP,
            WriteOp.equip("p1", equipped = false, currentlyEquipped = true).intent,
        )
        assertEquals(TrackerWriteKind.UNEQUIP, TrackerWriteKind.EQUIP.inverted())
        assertEquals(TrackerWriteKind.EQUIP, TrackerWriteKind.UNEQUIP.inverted())
    }

    /**
     * The inverse is the opposite `equip` call **and nothing else** — no parent restoration.
     *
     * The server reparents on equip and does not record where the item was, so an undo that
     * claimed to put it back would be lying. `WriteOp.Equip`'s KDoc states the limit; this
     * pins the shape, so nobody can quietly add a second op to the inverse and call it fixed.
     */
    @Test
    fun `equip's inverse is one opposite equip call and carries no parent restoration`() {
        val op = WriteOp.equip("p1", equipped = true, currentlyEquipped = false, targetName = "Cloak")
        val inverse = op.inverse as WriteOp.Equip

        assertEquals("creatureProperties.equip", inverse.method)
        assertEquals(false, (inverse.body["equipped"] as JsonPrimitive).booleanOrNull)
        assertEquals("p1", inverse.propertyId)
        assertEquals("Cloak", inverse.targetName)
        assertEquals(1, inverse.params.size)

        // And it round-trips: undoing the undo is the original call.
        assertEquals(op, inverse.inverse)
    }

    @Test
    fun `two equips of one item cancel to nothing rather than two reparents`() {
        val on = WriteOp.equip("p1", equipped = true, currentlyEquipped = false)
        val off = WriteOp.equip("p1", equipped = false, currentlyEquipped = true)

        val merged = on.coalesceWith(off)
        assertTrue("a round trip is nothing to say to the server: $merged", merged is WriteOp.Noop)

        // A third tap revives the real op out of the Noop, so N taps cost N mod 2 calls.
        val third = merged!!.coalesceWith(on)
        assertEquals(on, third)
    }

    /**
     * A coalesced burst inverts to where it **started**, not to where its last op started.
     * That is what [WriteOp.Equip.previousEquipped] is carried for.
     */
    @Test
    fun `a merged burst keeps the state it began in, so the undo stays honest`() {
        val on = WriteOp.equip("p1", equipped = true, currentlyEquipped = false, targetName = "Cloak")
        val offAgain = WriteOp.equip("p1", equipped = false, currentlyEquipped = true)
        val onAgain = WriteOp.equip("p1", equipped = true, currentlyEquipped = false)

        // on → off → on: the last state wins, and the start was "not equipped".
        val merged = on.coalesceWith(offAgain)!!.coalesceWith(onAgain) as WriteOp.Equip
        assertEquals(true, merged.equipped)
        assertEquals(false, merged.previousEquipped)
        assertEquals(false, (merged.inverse as WriteOp.Equip).equipped)
    }

    @Test
    fun `equips of different items never merge`() {
        val a = WriteOp.equip("a", equipped = true, currentlyEquipped = false)
        val b = WriteOp.equip("b", equipped = true, currentlyEquipped = false)
        assertNull(a.coalesceWith(b))
        assertNull(a.coalesceWith(WriteOp.AdjustQuantity("a", WriteOperation.INCREMENT, 1)))
    }

    @Test
    fun `equip predicts nothing and blocks nothing`() {
        val op = WriteOp.equip("p1", equipped = true, currentlyEquipped = false)
        assertTrue(op.optimistic is OptimisticChange.None)
        assertFalse("an equip does not invalidate the writes before it", op.isBarrier)
    }

    // -----------------------------------------------------------------------
    // Insert (decisions 5 and 6)
    // -----------------------------------------------------------------------

    /**
     * **`order` is mandatory** — probe-verified, 2026-08-19: the server rejects an insert
     * whose `creatureProperty` body omits it. This is the single assertion that would have
     * caught the failure mode before it reached a device.
     */
    @Test
    fun `an insert body always carries order`() {
        val op = WriteOp.insertItem(NewItemSpec(name = "Torch"), parentId = "f1", order = 512)
        val property = op.body["creatureProperty"]!!.jsonObject

        assertEquals(512, (property["order"] as JsonPrimitive).intOrNull)
        assertEquals("creatureProperties.insert", op.method)
    }

    @Test
    fun `the insert body carries type, name and quantity, and names its parent`() {
        val op = WriteOp.insertItem(
            NewItemSpec(name = "Arrows (20)", quantity = 20),
            parentId = "f1",
            order = 3,
        )
        val property = op.body["creatureProperty"]!!.jsonObject
        val parentRef = op.body["parentRef"]!!.jsonObject

        assertEquals("item", (property["type"] as JsonPrimitive).content)
        assertEquals("Arrows (20)", (property["name"] as JsonPrimitive).content)
        assertEquals(20, (property["quantity"] as JsonPrimitive).intOrNull)
        assertEquals("f1", (parentRef["id"] as JsonPrimitive).content)
        assertEquals("creatureProperties", (parentRef["collection"] as JsonPrimitive).content)
    }

    /**
     * Optional fields are **omitted**, not zero-filled. `weight: 0` on a sheet is a claim that
     * the thing is weightless; an absent field is "nobody weighed it", which is what a custom
     * item the player did not weigh actually is.
     */
    @Test
    fun `a spec that states nothing optional sends nothing optional`() {
        val property = WriteOp.insertItem(NewItemSpec(name = "Thing"), "f1", 1)
            .body["creatureProperty"]!!.jsonObject

        listOf("weight", "value", "description", "tags").forEach {
            assertFalse("$it must be omitted, not zero-filled: $property", property.containsKey(it))
        }
    }

    @Test
    fun `a fully specified item sends every field it states`() {
        val property = WriteOp.insertItem(
            NewItemSpec(
                name = "Torch",
                quantity = 5,
                weightLb = 1.0,
                valueGp = 0.01,
                description = "Burns for 1 hour.",
                tags = listOf("adventuring gear", "mundane"),
            ),
            parentId = "f1",
            order = 7,
        ).body["creatureProperty"]!!.jsonObject

        assertEquals(1.0, (property["weight"] as JsonPrimitive).doubleOrNull)
        assertEquals(0.01, (property["value"] as JsonPrimitive).doubleOrNull)
        // `{text: …}`, not a bare string: the server rejects the string form with
        // "400: Description must be of type Object" (probe, 2026-08-19). See
        // `WriteOp.insertItem`'s KDoc — this cost the 1.3.0 pre-release probe a failure.
        assertEquals(
            "Burns for 1 hour.",
            (property["description"]!!.jsonObject["text"] as JsonPrimitive).content,
        )
        assertEquals(
            listOf("adventuring gear", "mundane"),
            property["tags"]!!.jsonArray.map { (it as JsonPrimitive).content },
        )
    }

    /**
     * The catalog id is this app's own vocabulary and would be meaningless to anyone opening
     * the sheet in DiceCloud's web UI, so it never goes on the wire.
     */
    @Test
    fun `the catalog id is never sent to the server`() {
        val spec = NewItemSpec.of(ItemCatalog.byId("torch")!!)
        assertEquals("torch", spec.catalogId)

        val property = WriteOp.insertItem(spec, "f1", 1).body["creatureProperty"]!!.jsonObject
        assertFalse(property.containsKey("catalogId"))
        assertFalse(property.toString().contains("catalogId"))
    }

    /**
     * The same, for `NewItemSpec.category` — the claim its KDoc makes, run rather than read.
     *
     * "Never sent to the server" was a **comment** until the 1.6.0 review, which is the exact
     * shape `ItemCatalogCategoryTest`'s KDoc condemns: a check that would otherwise be a comment
     * nobody runs. It is load-bearing twice over. It is why 13 lists server-side category editing
     * as out of scope, and it is therefore why `AddItemFormState.offersCategoryChooser` is false
     * on a server character — a chooser drawn over a field this method drops would take the
     * player's answer and discard it silently.
     *
     * Asserted for **every** category, not just the non-default one, so the test cannot pass by
     * the value happening to equal `GEAR`; and asserted against the whole serialized body, so a
     * future field spelled `itemCategory` is caught too.
     */
    @Test
    fun `the category is never sent to the server, whatever it is set to`() {
        CatalogCategory.entries.forEach { category ->
            val spec = NewItemSpec(name = "Torch", category = category)
            assertEquals(category, spec.category)

            val op = WriteOp.insertItem(spec, "f1", 1)
            val property = op.body["creatureProperty"]!!.jsonObject

            assertFalse("$category: the insert body must carry no category", property.containsKey("category"))
            assertFalse(
                "$category: nothing in the body may spell it, however the field is named — " +
                    "a DiceCloud item is classified by its tags",
                op.body.toString().lowercase().contains("categor") ||
                    op.body.toString().contains(category.storedValue),
            )
        }
    }

    /**
     * …and the catalog path is the case that would actually have leaked one.
     *
     * `NewItemSpec.of` copies the entry's own category (13 decision 9's first capture point), so
     * the catalog add is the one server-reachable path whose spec carries a category nobody
     * typed. The tags ride; the category does not.
     */
    @Test
    fun `a catalog add sends the entry's tags and not the entry's category`() {
        val spec = NewItemSpec.of(ItemCatalog.byId("torch")!!)
        assertEquals(CatalogCategory.GEAR, spec.category)

        val property = WriteOp.insertItem(spec, "f1", 1).body["creatureProperty"]!!.jsonObject

        assertTrue("the tags are the server's own vocabulary and do ride", property.containsKey("tags"))
        assertFalse(property.containsKey("category"))
    }

    @Test
    fun `a blank description is omitted rather than sent as whitespace`() {
        val property = WriteOp.insertItem(NewItemSpec(name = "X", description = "   "), "f1", 1)
            .body["creatureProperty"]!!.jsonObject
        assertFalse(property.containsKey("description"))
    }

    /**
     * Not undoable: the inverse would be a soft-remove, and item deletion is fenced out of
     * this release (10 decision 12). The history entry is filed the way a rest's is — a fact,
     * with no UNDO offered.
     */
    @Test
    fun `an insert is not undoable`() {
        val op = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 1)
        assertNull("the inverse would be a softRemove, which FR-8 does not ship", op.inverse)
        assertEquals(TrackerWriteKind.ITEM_CREATE, op.intent)
        assertNull(TrackerWriteKind.ITEM_CREATE.inverted())
    }

    /**
     * The one way an insert differs from a rest: it invalidates **nothing** before it. Undoing
     * a spend from before an add is still perfectly correct, so this must not be a barrier.
     */
    @Test
    fun `an insert is not a barrier and does not invalidate earlier writes`() {
        val op = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 1)
        assertFalse(op.isBarrier)
        assertTrue("a rest is the barrier; an add is not", WriteOp.rest("c1", RestType.LONG_REST).isBarrier)
    }

    @Test
    fun `inserts never coalesce - two adds are two items`() {
        val a = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 1)
        val b = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 2)
        assertNull(a.coalesceKey)
        assertNull(a.coalesceWith(b))
    }

    @Test
    fun `insert is in the slow rate class and predicts nothing`() {
        val op = WriteOp.insertItem(NewItemSpec(name = "Torch"), "f1", 1)
        assertEquals(WriteOp.SLOW_SPACING_MILLIS, op.minSpacingMillis)
        // The new property's _id is minted by the server, so there is nothing to key a
        // prediction on until the call returns.
        assertNull(op.optimistic)
    }

    @Test
    fun `the history entry says how many were added`() {
        assertEquals(20, WriteOp.insertItem(NewItemSpec(name = "Arrows", quantity = 20), "f1", 1).magnitude)
    }

    // -----------------------------------------------------------------------
    // The coin spec (decision 5)
    // -----------------------------------------------------------------------

    @Test
    fun `a created coin carries its denomination's tag, value and weight`() {
        val property = WriteOp.insertItem(NewItemSpec.ofCoin(CoinKind.PLATINUM, 3), "f1", 1)
            .body["creatureProperty"]!!.jsonObject

        assertEquals("Platinum piece", (property["name"] as JsonPrimitive).content)
        assertEquals(3, (property["quantity"] as JsonPrimitive).intOrNull)
        assertEquals(10.0, (property["value"] as JsonPrimitive).doubleOrNull)
        assertEquals(0.02, (property["weight"] as JsonPrimitive).doubleOrNull)
        assertEquals(
            "the tag is what every later discovery keys on",
            listOf("platinum"),
            property["tags"]!!.jsonArray.map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `every denomination's created item is discoverable as that denomination`() {
        CoinKind.entries.forEach { coin ->
            val spec = NewItemSpec.ofCoin(coin, 1)
            assertEquals(
                "a coin this app creates must be one it can find again",
                coin,
                CoinKind.fromTags(spec.tags),
            )
        }
    }
}
