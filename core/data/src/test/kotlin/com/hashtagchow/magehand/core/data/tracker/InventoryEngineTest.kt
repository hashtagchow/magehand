package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.InventoryBoard

/**
 * Inventory discovery (docs/design/10-inventory.md decisions 2, 3, 5, 8, 9).
 *
 * Synthetic sheets throughout, rather than the committed capture. Two reasons, and the
 * second one is the important one:
 *
 * 1. The capture has no attunement fields on any property and no platinum, so it cannot
 *    exercise two of the six things this engine does.
 * 2. It is a **private fixture** that is absent from a public clone, where every assertion
 *    coupled to it is skipped. A discovery engine whose only tests skip in the environment
 *    the repo is published into is a discovery engine with no tests. `Fixtures`-coupled
 *    assertions live in [InventoryEngineCaptureTest], where skipping is the correct outcome.
 */
class InventoryEngineTest {

    private fun sheet(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"c1","name":"Test"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    private fun item(
        id: String,
        name: String,
        quantity: Int = 1,
        extra: String = "",
    ) = """{"_id":"$id","type":"item","name":"$name","quantity":$quantity$extra}"""

    private val strength8 =
        """{"_id":"str","type":"attribute","attributeType":"ability",
            "variableName":"strength","name":"Strength","total":8,"value":8,"modifier":-1}"""

    // -----------------------------------------------------------------------
    // Sections (decision 2)
    // -----------------------------------------------------------------------

    @Test
    fun `equipped and carried are separate sections`() {
        val board = InventoryEngine.build(
            sheet(
                item("a", "Quarterstaff", extra = ""","equipped":true"""),
                item("b", "Bedroll"),
            ),
        )

        assertEquals(listOf("Quarterstaff"), board.equipped.map { it.name })
        assertEquals(listOf("Bedroll"), board.carried.map { it.name })
    }

    @Test
    fun `a container carries the items parented to it, and they are not also loose`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"pack","type":"container","name":"Backpack","quantity":1,"weight":5,
                    "contentsWeight":8.0,"contentsValue":12.5,"order":10}""",
                item("a", "Book", extra = ""","parent":{"id":"pack","collection":"creatureProperties"},"weight":5"""),
                item("b", "Bedroll", extra = ""","weight":7"""),
            ),
        )

        val container = board.containers.single()
        assertEquals(listOf("Book"), container.contents.map { it.name })
        assertEquals(
            "an item inside a container must not also render loose",
            listOf("Bedroll"),
            board.carried.map { it.name },
        )
    }

    /**
     * The precedence rule, on the exact shape the live capture contains: an **equipped** item
     * that lives **inside a container**. Four independent section filters would render it
     * twice and count its weight twice.
     */
    @Test
    fun `an equipped item inside a container renders once, under equipped`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"pack","type":"container","name":"Pack","quantity":1,"weight":0}""",
                item(
                    "knife", "Small Knife", quantity = 1,
                    extra = ""","equipped":true,"weight":1,
                              "parent":{"id":"pack","collection":"creatureProperties"}""",
                ),
            ),
        )

        assertEquals(listOf("Small Knife"), board.equipped.map { it.name })
        assertTrue(board.containers.single().contents.isEmpty())
        assertEquals(1, board.allItems.size)
        assertEquals(1.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `folders are flattened away rather than rendered`() {
        // 10 decision 2: `equip` moves items between the equipment and carried folders, so
        // rendering the tree would make every equip look like the item teleporting.
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"f","type":"folder","name":"Carried","tags":["carried"]}""",
                item("a", "Rope", extra = ""","parent":{"id":"f","collection":"creatureProperties"}"""),
            ),
        )

        assertEquals(listOf("Rope"), board.carried.map { it.name })
        assertTrue("a folder is not a container", board.containers.isEmpty())
    }

    @Test
    fun `items keep the sheet's own order`() {
        val board = InventoryEngine.build(
            sheet(
                item("a", "Zither", extra = ""","order":5"""),
                item("b", "Anvil", extra = ""","order":1"""),
            ),
        )
        assertEquals(listOf("Anvil", "Zither"), board.carried.map { it.name })
    }

    // -----------------------------------------------------------------------
    // The removed filter (decision 3)
    // -----------------------------------------------------------------------

    @Test
    fun `a soft-deleted item is in no list and in no sum`() {
        val board = InventoryEngine.build(
            sheet(
                item("a", "Kept", quantity = 2, extra = ""","weight":3"""),
                item("b", "Gone", quantity = 10, extra = ""","weight":100,"removed":true"""),
            ),
        )

        assertEquals(listOf("Kept"), board.carried.map { it.name })
        assertEquals(6.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `a soft-deleted container is neither a section nor a summand`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"pack","type":"container","name":"Gone","quantity":1,"weight":5,
                    "contentsWeight":40.0,"removed":true}""",
            ),
        )
        assertTrue(board.containers.isEmpty())
        assertEquals(0.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `a soft-deleted coin item leaves its wallet row absent, not merely zero`() {
        val board = InventoryEngine.build(
            sheet(item("g", "Gold piece", quantity = 100, extra = ""","tags":["gold"],"removed":true""")),
        )

        val gold = board.wallet.row(CoinKind.GOLD)
        assertEquals(0, gold.quantity)
        assertTrue(
            "a deleted coin item must not be the stepper's write target",
            gold.isAbsent,
        )
    }

    @Test
    fun `an item deactivated by an ancestor is out, matching the tracker's own item list`() {
        val json = item("a", "Backpack", extra = ""","inactive":true,"deactivatedByAncestor":true""")
        assertTrue(InventoryEngine.build(sheet(json)).carried.isEmpty())
        assertTrue(
            "the two screens must agree about what the character owns",
            TrackerEngine.build(sheet(json)).allItems.isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Wallet (decision 5)
    // -----------------------------------------------------------------------

    @Test
    fun `the wallet always has four rows, in denomination order`() {
        val board = InventoryEngine.build(sheet(item("a", "Rope")))

        assertEquals(
            listOf(CoinKind.PLATINUM, CoinKind.GOLD, CoinKind.SILVER, CoinKind.COPPER),
            board.wallet.rows.map { it.coin },
        )
        assertTrue(board.wallet.rows.all { it.quantity == 0 && it.isAbsent })
        assertTrue(board.wallet.isEmpty)
    }

    @Test
    fun `coins are found by tag and totalled in gp`() {
        val board = InventoryEngine.build(
            sheet(
                item("g", "Gold piece", quantity = 109, extra = ""","tags":["gold"],"value":1"""),
                item("s", "Silver piece", quantity = 57, extra = ""","tags":["silver"],"value":0.1"""),
                item("c", "Copper piece", quantity = 351, extra = ""","tags":["copper"],"value":0.01"""),
            ),
        )

        assertEquals(109, board.wallet.row(CoinKind.GOLD).quantity)
        assertEquals("g", board.wallet.row(CoinKind.GOLD).propertyId)
        assertEquals(0, board.wallet.row(CoinKind.PLATINUM).quantity)
        assertTrue(board.wallet.row(CoinKind.PLATINUM).isAbsent)
        // 109 + 5.7 + 3.51
        assertEquals(118.21, board.wallet.totalGp, EPSILON)
    }

    @Test
    fun `a coin item is in the wallet and in no other section`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"purse","type":"container","name":"Purse","quantity":1,"weight":0.1}""",
                item(
                    "g", "Gold piece", quantity = 10,
                    extra = ""","tags":["gold"],"parent":{"id":"purse","collection":"creatureProperties"}""",
                ),
            ),
        )

        assertEquals(10, board.wallet.row(CoinKind.GOLD).quantity)
        assertTrue(
            "coins render in the wallet, not again inside the purse they live in",
            board.containers.single().contents.isEmpty(),
        )
        assertTrue(board.carried.isEmpty())
        assertTrue(board.equipped.isEmpty())
    }

    @Test
    fun `the coin tag is matched case-insensitively`() {
        val board = InventoryEngine.build(
            sheet(item("g", "Gold", quantity = 3, extra = ""","tags":["Gold"]""")),
        )
        assertEquals(3, board.wallet.row(CoinKind.GOLD).quantity)
    }

    /**
     * Several stacks of one denomination: the quantities sum, and the row points at the first
     * by the sheet's own order so the stepper has exactly one thing to adjust.
     */
    @Test
    fun `two stacks of one denomination sum, and the row targets the first`() {
        val board = InventoryEngine.build(
            sheet(
                item("second", "Gold piece", quantity = 5, extra = ""","tags":["gold"],"order":9"""),
                item("first", "Gold piece", quantity = 20, extra = ""","tags":["gold"],"order":2"""),
            ),
        )

        val gold = board.wallet.row(CoinKind.GOLD)
        assertEquals(25, gold.quantity)
        assertEquals("first", gold.propertyId)
        assertTrue("neither stack may also render as a carried item", board.carried.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Weight and capacity (decision 8)
    // -----------------------------------------------------------------------

    @Test
    fun `carried weight is quantity times weight over every section`() {
        val board = InventoryEngine.build(
            sheet(
                item("a", "Dagger", quantity = 2, extra = ""","weight":1,"equipped":true"""),
                item("b", "Rations", quantity = 15, extra = ""","weight":1"""),
                """{"_id":"pack","type":"container","name":"Pack","quantity":1,"weight":5}""",
                item("c", "Book", extra = ""","weight":5,"parent":{"id":"pack","collection":"creatureProperties"}"""),
            ),
        )

        // 2 + 15 + 5 (the empty pack) + 5 (the book in it)
        assertEquals(27.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `an item with no recorded weight counts as nothing but keeps the distinction`() {
        val board = InventoryEngine.build(sheet(item("a", "Lamp", quantity = 3)))

        assertNull("a blank weight is not a claim that it is weightless", board.carried.single().weightLb)
        assertEquals(0.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `a missing quantity reads as one, not as zero`() {
        val board = InventoryEngine.build(
            sheet("""{"_id":"a","type":"item","name":"Spellbook","weight":3}"""),
        )
        assertEquals(1, board.carried.single().quantity)
        assertEquals(3.0, board.carriedWeightLb, EPSILON)
    }

    /**
     * The grand total is the **client sum**; the container header prefers the **server
     * rollup**. Both numbers are on the board and they are allowed to disagree — see
     * `InventoryBoard.carriedWeightLb` for why a hybrid would be worse than either.
     */
    @Test
    fun `the container header uses the server rollup while the grand total does not`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"pack","type":"container","name":"Pack","quantity":1,"weight":2,
                    "contentsWeight":30.0,"contentsValue":11.0}""",
                item("a", "Book", extra = ""","weight":5,"parent":{"id":"pack","collection":"creatureProperties"}"""),
            ),
        )

        val container = board.containers.single()
        assertEquals(30.0, container.rollupWeightLb!!, EPSILON)
        assertEquals(5.0, container.contentsWeightLb, EPSILON)
        assertEquals("2 (own) + 30 (server rollup)", 32.0, container.displayWeightLb, EPSILON)
        assertEquals("2 (own) + 5 (client sum)", 7.0, board.carriedWeightLb, EPSILON)
    }

    @Test
    fun `carriedWeight is preferred over contentsWeight when the sheet has both`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"pack","type":"container","name":"Pack","quantity":1,
                    "carriedWeight":33.0,"contentsWeight":30.0}""",
            ),
        )
        assertEquals(33.0, board.containers.single().rollupWeightLb!!, EPSILON)
    }

    @Test
    fun `capacity is strength times fifteen`() {
        val board = InventoryEngine.build(sheet(strength8, item("a", "Rope")))
        assertEquals(120, board.capacityLb)
        assertEquals(15, InventoryBoard.CAPACITY_PER_STRENGTH)
    }

    @Test
    fun `capacity reads the total, so an effect that raises strength raises it`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"str","type":"attribute","attributeType":"ability",
                    "variableName":"strength","name":"Strength","total":19,"value":8}""",
            ),
        )
        assertEquals(285, board.capacityLb)
    }

    @Test
    fun `no strength on the sheet means no capacity line rather than an assumed ten`() {
        val board = InventoryEngine.build(sheet(item("a", "Rope")))
        assertNull(board.capacityLb)
        assertFalse(board.isOverCapacity)
    }

    @Test
    fun `over capacity is a comparison, not a tier`() {
        val overloaded = InventoryEngine.build(
            sheet(strength8, item("a", "Anvil", quantity = 3, extra = ""","weight":50""")),
        )
        assertEquals(150.0, overloaded.carriedWeightLb, EPSILON)
        assertTrue(overloaded.isOverCapacity)
    }

    // -----------------------------------------------------------------------
    // Attunement (decision 9)
    // -----------------------------------------------------------------------

    @Test
    fun `a sheet whose items never mention attunement gets no chip`() {
        val board = InventoryEngine.build(sheet(item("a", "Rope"), item("b", "Torch")))
        assertFalse(
            "a confident 0 of 3 is an answer to a question this sheet never asked",
            board.hasAttunementData,
        )
        assertEquals(0, board.attunedCount)
    }

    @Test
    fun `either field turns the chip on, and attuned true is what is counted`() {
        val onlyRequires = InventoryEngine.build(
            sheet(item("a", "Cloak", extra = ""","requiresAttunement":true""")),
        )
        assertTrue(onlyRequires.hasAttunementData)
        assertEquals(0, onlyRequires.attunedCount)

        val attuned = InventoryEngine.build(
            sheet(
                item("a", "Cloak", extra = ""","requiresAttunement":true,"attuned":true"""),
                item("b", "Ring", extra = ""","requiresAttunement":true,"attuned":false"""),
                item("c", "Rope"),
            ),
        )
        assertTrue(attuned.hasAttunementData)
        assertEquals(1, attuned.attunedCount)
        assertEquals(3, InventoryBoard.ATTUNEMENT_SLOTS)
    }

    @Test
    fun `a soft-deleted attuned item neither counts nor turns the chip on`() {
        val board = InventoryEngine.build(
            sheet(item("a", "Cloak", extra = ""","requiresAttunement":true,"attuned":true,"removed":true""")),
        )
        assertFalse(board.hasAttunementData)
        assertEquals(0, board.attunedCount)
    }

    // -----------------------------------------------------------------------
    // Description
    // -----------------------------------------------------------------------

    @Test
    fun `the description is unwrapped from DiceCloud's calculation object`() {
        val board = InventoryEngine.build(
            sheet(
                """{"_id":"a","type":"item","name":"Spellbook","quantity":1,
                    "description":{"text":"A leather tome.","value":"A leather tome.","hash":1}}""",
            ),
        )
        assertEquals("A leather tome.", board.carried.single().description)
    }

    @Test
    fun `a plain string description and a blank one both read correctly`() {
        assertEquals(
            "Plain.",
            InventoryEngine.build(sheet(item("a", "X", extra = ""","description":"Plain."""")))
                .carried.single().description,
        )
        assertNull(
            InventoryEngine.build(sheet(item("a", "X", extra = ""","description":"   """")))
                .carried.single().description,
        )
    }

    // -----------------------------------------------------------------------
    // Insert targeting (decisions 5 and 6)
    // -----------------------------------------------------------------------

    @Test
    fun `an added item goes under the carried folder, one past the highest order`() {
        val built = sheet(
            """{"_id":"inv","type":"folder","name":"Inventory","tags":["inventory"],"order":10}""",
            """{"_id":"car","type":"folder","name":"Carried","tags":["carried"],"order":20}""",
            item("a", "Rope", extra = ""","order":42"""),
        )

        val target = InventoryEngine.insertTarget(built)!!
        assertEquals("carried is preferred over inventory", "car", target.parentId)
        assertEquals(CreatureSheet.CREATURE_PROPERTIES, target.parentCollection)
        assertEquals(43, target.order)
    }

    @Test
    fun `the inventory folder is used when there is no carried folder`() {
        val built = sheet("""{"_id":"inv","type":"folder","name":"Inventory","tags":["inventory"]}""")
        assertEquals("inv", InventoryEngine.insertTarget(built)!!.parentId)
    }

    @Test
    fun `a sheet with no inventory folders parents the new item to the creature itself`() {
        val target = InventoryEngine.insertTarget(sheet(item("a", "Rope")))!!
        assertEquals("c1", target.parentId)
        assertEquals(CreatureSheet.CREATURES, target.parentCollection)
    }

    @Test
    fun `a new coin joins the purse its siblings are in`() {
        val built = sheet(
            """{"_id":"car","type":"folder","name":"Carried","tags":["carried"]}""",
            """{"_id":"purse","type":"container","name":"Purse","quantity":1}""",
            item("g", "Gold piece", quantity = 5, extra = ""","tags":["gold"],"parent":{"id":"purse","collection":"creatureProperties"}"""),
        )

        val target = InventoryEngine.insertTarget(built, siblingOf = "g")!!
        assertEquals(
            "a sheet that keeps its coins in a purse should get its new silver in that purse",
            "purse",
            target.parentId,
        )
    }

    @Test
    fun `a sibling that no longer exists falls through to the folder rule`() {
        val built = sheet("""{"_id":"car","type":"folder","name":"Carried","tags":["carried"]}""")
        assertEquals("car", InventoryEngine.insertTarget(built, siblingOf = "vanished")!!.parentId)
    }

    @Test
    fun `a soft-deleted folder is not an insert target`() {
        val built = sheet(
            """{"_id":"car","type":"folder","name":"Carried","tags":["carried"],"removed":true}""",
        )
        assertEquals("c1", InventoryEngine.insertTarget(built)!!.parentId)
    }

    @Test
    fun `an empty sheet has nowhere to put an item`() {
        assertNull(InventoryEngine.insertTarget(CreatureSheet.EMPTY))
    }

    // -----------------------------------------------------------------------
    // Empty
    // -----------------------------------------------------------------------

    @Test
    fun `a sheet with nothing in it produces an empty board`() {
        val board = InventoryEngine.build(CreatureSheet.EMPTY)
        assertTrue(board.isEmpty)
        assertEquals(InventoryBoard.EMPTY.wallet.rows.size, board.wallet.rows.size)
        assertEquals(0.0, board.carriedWeightLb, EPSILON)
    }

    private companion object {
        /** Weights and gp values are `Double`; 1e-9 is far below anything a sheet expresses. */
        const val EPSILON = 1e-9
    }
}
