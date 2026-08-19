package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.EquipGroup
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

        // LOW-8, and the load-bearing half of K9 (11 decision 5). The UI folds this purse's
        // section away entirely, because it has no displayable rows — and the shell's 0.1 lb is
        // still in the grand total, because `carriedWeightLb` is a client sum over items *plus
        // every container's own empty weight* and never consulted the section list.
        //
        // 0.1 (the purse) + 10 × 0.02 (the coins, at CoinKind's rulebook weight, since this
        // sheet gave its gold none) = 0.3. Counted exactly once each: a reader who "fixes" the
        // folded-away section by adding the shell back in gets 0.4 and this failing.
        assertEquals(0.3, board.carriedWeightLb, EPSILON)
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

    // --- move targeting (FR-9, 12 decision 8) --------------------------------------

    /**
     * A move to the carried root is [InventoryEngine.insertTarget]'s answer, unaltered — one
     * reader, one opinion about where "loose in my pack" is, on a tree that `equip` rewrites.
     */
    @Test
    fun `moving to the carried root resolves exactly where an add would go`() {
        val built = sheet(
            """{"_id":"car","type":"folder","name":"Carried","tags":["carried"],"order":20}""",
            item("a", "Rope", extra = ""","order":42"""),
        )

        assertEquals(InventoryEngine.insertTarget(built), InventoryEngine.moveTarget(built, containerId = null))
    }

    @Test
    fun `moving into a container keeps the end-of-sheet order`() {
        val built = sheet(
            """{"_id":"car","type":"folder","name":"Carried","tags":["carried"],"order":20}""",
            """{"_id":"bag","type":"container","name":"Belt Pouch","quantity":1,"order":21}""",
            item("a", "Rope", extra = ""","order":42"""),
        )

        val target = InventoryEngine.moveTarget(built, containerId = "bag")!!
        assertEquals("bag", target.parentId)
        assertEquals(CreatureSheet.CREATURE_PROPERTIES, target.parentCollection)
        assertEquals("one past the sheet's highest order — the bottom of the destination", 43, target.order)
    }

    /**
     * The carried root can be the **creature itself** on a sheet with neither folder, so the
     * collection has to travel with the id. A move into a container is always a property.
     */
    @Test
    fun `a sheet with no inventory folders moves items to the creature root`() {
        val target = InventoryEngine.moveTarget(sheet(item("a", "Rope")), containerId = null)!!
        assertEquals("c1", target.parentId)
        assertEquals(CreatureSheet.CREATURES, target.parentCollection)
    }

    @Test
    fun `an empty sheet has nowhere to move anything either`() {
        assertNull(InventoryEngine.moveTarget(CreatureSheet.EMPTY, containerId = null))
        assertNull(InventoryEngine.moveTarget(CreatureSheet.EMPTY, containerId = "bag"))
    }

    // --- the prior location, which is what makes a move's undo possible -------------

    @Test
    fun `an item's current location is its parent and its own order`() {
        val built = sheet(
            """{"_id":"bag","type":"container","name":"Belt Pouch","quantity":1,"order":2}""",
            item("a", "Rope", extra = ""","order":7,"parent":{"id":"bag","collection":"creatureProperties"}"""),
        )

        val from = InventoryEngine.currentLocation(built, "a")!!
        assertEquals("bag", from.parentId)
        assertEquals(CreatureSheet.CREATURE_PROPERTIES, from.parentCollection)
        assertEquals("the order it has, not a fresh end-of-sheet one", 7, from.order)
    }

    /**
     * An unparented property lives at the creature, which is where `insertTarget`'s last branch
     * puts a new one. Returning `null` here would make a top-level item unmovable — refusing
     * the one operation that would tidy it up.
     */
    @Test
    fun `a property with no parent is located at the creature`() {
        val from = InventoryEngine.currentLocation(sheet(item("a", "Rope", extra = ""","order":3""")), "a")!!
        assertEquals("c1", from.parentId)
        assertEquals(CreatureSheet.CREATURES, from.parentCollection)
    }

    /**
     * A soft-removed property has no current location worth moving away from, and no honest
     * inverse — `livePropertyList` is removed-filtered, so this falls out of 10 decision 3
     * rather than being a branch of its own.
     */
    @Test
    fun `a soft-removed or unknown property has no current location`() {
        val built = sheet(item("a", "Rope", extra = ""","removed":true"""))
        assertNull(InventoryEngine.currentLocation(built, "a"))
        assertNull(InventoryEngine.currentLocation(built, "never-existed"))
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

    // -----------------------------------------------------------------------
    // Equippability (docs/design/11-inventory-polish.md decisions 1 and 3)
    // -----------------------------------------------------------------------

    /** The one row on the board with this id, wherever the precedence put it. */
    private fun InventoryBoard.item(id: String) = allItems.single { it.propertyId == id }

    @Test
    fun `a tagged but unequipped weapon is equippable, and files under Weapons`() {
        val board = InventoryEngine.build(
            sheet(
                item(
                    "d", "Dagger", quantity = 2,
                    extra = ""","tags":["simple weapon","melee weapon","mundane","dagger"]""",
                ),
            ),
        )

        val dagger = board.item("d")
        assertTrue("the tag set is the rule's second disjunct", dagger.isEquippable)
        assertEquals(EquipGroup.WEAPON, dagger.equipGroup)
    }

    @Test
    fun `a tinderbox is not equippable, and gets no control`() {
        // The row FR-10 exists for: no tags at all, not equipped, and nothing about it says a
        // character could wear it. This is the assertion that makes the whole feature visible.
        val board = InventoryEngine.build(sheet(item("t", "Tinderbox")))

        val tinderbox = board.item("t")
        assertFalse(tinderbox.isEquippable)
        assertEquals(EquipGroup.GEAR, tinderbox.equipGroup)
    }

    @Test
    fun `an equipped untagged item is equippable, so its unequip control survives`() {
        // "A Small Knife" on the reference sheet — hand-made, tagless, already worn. Without
        // the rule's `equipped` disjunct, equipping a home-made item would be a one-way door:
        // the control that put it on would vanish the moment it went on.
        val board = InventoryEngine.build(
            sheet(item("k", "A Small Knife", extra = ""","equipped":true""")),
        )

        val knife = board.item("k")
        assertTrue(knife.isEquippable)
        // Gear, not Weapons: the override buys the *control*, never a claim about what the
        // thing is. Nothing on this property says "weapon" — only that it is being worn.
        assertEquals(EquipGroup.GEAR, knife.equipGroup)
    }

    @Test
    fun `Half Plate is equippable by its own tags, though it lacks the bare armor tag`() {
        // Data defect 1, pinned. The SRD entry carries `medium armor` and does NOT carry
        // `armor`, so a rule keyed on the bare word would refuse an equip control to a suit of
        // armor. Casing is the sheet author's, not ours — hence the mixed case here.
        val board = InventoryEngine.build(
            sheet(
                item("h", "Half Plate", extra = ""","tags":["Medium Armor","mundane"]"""),
                item("s", "Shield", extra = ""","tags":["shield"]"""),
            ),
        )

        assertTrue("Half Plate must not need the bare `armor` tag", board.item("h").isEquippable)
        assertEquals(EquipGroup.ARMOR, board.item("h").equipGroup)
        assertEquals("a shield is armor for every purpose this screen has", EquipGroup.ARMOR, board.item("s").equipGroup)
    }

    @Test
    fun `a homemade item that is unequipped loses its control - the documented residual`() {
        // 11 decision 1's known false negative, asserted rather than left as prose. The item
        // is the same "A Small Knife" as two tests up, with `equipped` gone: the rule now
        // matches neither disjunct and this app stops offering to equip it. That is exactly
        // what decision 2's override exists to undo, and it is pinned here so a future reader
        // who is tempted to "fix" it with a name heuristic has to delete a test that says why.
        val board = InventoryEngine.build(sheet(item("k", "A Small Knife")))

        assertFalse(board.item("k").isEquippable)
    }

    @Test
    fun `libraryTags count towards the rule, not just the sheet's own tags`() {
        // An item whose own `tags` were edited to nothing but which still carries what the SRD
        // library node said. The rule reads the union (11 decision 1), so this is equippable.
        val board = InventoryEngine.build(
            sheet(item("q", "Quarterstaff", extra = ""","tags":[],"libraryTags":["simple weapon"]""")),
        )

        assertTrue(board.item("q").isEquippable)
        assertEquals(EquipGroup.WEAPON, board.item("q").equipGroup)
    }

    @Test
    fun `stray whitespace around a tag does not cost an item its equip control`() {
        // Ordinary in hand-typed and copy-pasted tags, and the cost of not trimming falls in
        // exactly the wrong place: a real weapon silently loses its control and the player has
        // to reach for 11 decision 2's override to rescue an item this app could have
        // classified. The override is for data the taxonomy does not cover, not for a space.
        val board = InventoryEngine.build(
            sheet(
                item("d", "Dagger", extra = ""","tags":[" simple weapon "]"""),
                item("a", "Breastplate", extra = ""","tags":["\tMedium Armor\n"]"""),
            ),
        )

        assertTrue(board.item("d").isEquippable)
        assertEquals(EquipGroup.WEAPON, board.item("d").equipGroup)
        assertTrue(board.item("a").isEquippable)
        assertEquals(EquipGroup.ARMOR, board.item("a").equipGroup)
    }

    @Test
    fun `a near-miss tag does not sweep an ordinary item in`() {
        // The whole-tag rule, from the other side: `spellcasting focus` and `adventuring gear`
        // are the tags real gear carries, and neither may reach the equippable set. A
        // `contains("weapon")` substring test would also have swept in the sheet's own
        // `simpleRangedWeapon` skill tag — which is why the set is matched whole.
        val board = InventoryEngine.build(
            sheet(
                item("p", "Component Pouch", extra = ""","tags":["adventuring gear","spellcasting focus"]"""),
                item("w", "Weapon Oil", extra = ""","tags":["simpleRangedWeapon"]"""),
            ),
        )

        assertFalse(board.item("p").isEquippable)
        assertFalse(board.item("w").isEquippable)
    }

    private companion object {
        /** Weights and gp values are `Double`; 1e-9 is far below anything a sheet expresses. */
        const val EPSILON = 1e-9
    }
}
