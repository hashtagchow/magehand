package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.CoinKind

/**
 * [InventoryEngine] against the **committed live capture** — a real sheet, with a real
 * inventory that nobody designed for this engine.
 *
 * Separate from [InventoryEngineTest] on purpose. Everything here touches `Fixtures`, which
 * raises a JUnit **skip** when the private capture is absent (a public clone). Keeping these
 * assertions apart means the engine's own rules stay covered in that environment while these
 * — which can only be checked against a sheet nobody has — skip cleanly.
 *
 * What this catches that a synthetic sheet cannot: the shapes a real sheet has and a test
 * author would not think to write. The capture's inventory contains an equipped item inside a
 * container, three denominations of coin inside a purse, two containers deactivated by an
 * ancestor, one soft-deleted item, and eleven items with no recorded weight — every one of
 * which is a branch in this engine.
 */
class InventoryEngineCaptureTest {

    private val sheet by lazy { Fixtures.sabrielSheet() }
    private val board by lazy { InventoryEngine.build(sheet) }

    @Test
    fun `the soft-deleted item reaches no section and no sum`() {
        val deletedId = Fixtures.REMOVED_PROPERTY_ID

        assertTrue(
            "the capture's soft-deleted item is on the inventory board",
            board.allItems.none { it.propertyId == deletedId },
        )
        assertTrue(
            "and it must not be hiding inside a container",
            board.containers.flatMap { it.contents }.none { it.propertyId == deletedId },
        )
        assertTrue(board.wallet.rows.none { it.propertyId == deletedId })

        // The strongest form: the board built from a sheet with the document physically
        // removed is the same board. Nothing about it leaks into a count or a total.
        val without = CreatureSheet(
            properties = sheet.properties.filterKeys { it != deletedId },
            creature = sheet.creature,
            variables = sheet.variables,
        )
        assertEquals(InventoryEngine.build(without), board)
    }

    @Test
    fun `every discovered item appears in exactly one section`() {
        val ids = board.equipped.map { it.propertyId } +
            board.containers.flatMap { it.contents }.map { it.propertyId } +
            board.carried.map { it.propertyId } +
            board.wallet.rows.mapNotNull { it.propertyId }

        assertEquals(
            "an item rendered twice would also be weighed twice: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `the wallet finds the sheet's three denominations and leaves platinum absent`() {
        assertTrue(board.wallet.row(CoinKind.GOLD).quantity > 0)
        assertTrue(board.wallet.row(CoinKind.SILVER).quantity > 0)
        assertTrue(board.wallet.row(CoinKind.COPPER).quantity > 0)

        val platinum = board.wallet.row(CoinKind.PLATINUM)
        assertEquals(0, platinum.quantity)
        assertTrue(
            "this sheet carries no platinum item, so the stepper must take the insert path",
            platinum.isAbsent,
        )
        assertTrue("but the row still renders", board.wallet.rows.size == 4)
    }

    @Test
    fun `the coins are in the wallet and nowhere else`() {
        val coinIds = board.wallet.rows.mapNotNull { it.propertyId }.toSet()
        assertEquals(3, coinIds.size)

        assertTrue(board.equipped.none { it.propertyId in coinIds })
        assertTrue(board.carried.none { it.propertyId in coinIds })
        assertTrue(board.containers.flatMap { it.contents }.none { it.propertyId in coinIds })
    }

    @Test
    fun `containers deactivated by an ancestor are not sections`() {
        // The capture holds four `container` properties, two of which are inactive because an
        // ancestor is switched off. Those two are not in the character's possession.
        val all = sheet.livePropertyList.count { it.string("type") == "container" }
        assertEquals(4, all)
        assertEquals(2, board.containers.size)
    }

    @Test
    fun `the capacity line is strength times fifteen from the sheet's own score`() {
        val strength = sheet.livePropertyList.single {
            it.string("type") == "attribute" &&
                it.string("attributeType") == "ability" &&
                it.string("variableName") == "strength"
        }
        assertEquals(strength.number("total")!! * 15, board.capacityLb)
    }

    @Test
    fun `this sheet expresses no attunement at all, so the chip stays away`() {
        assertFalse(board.hasAttunementData)
        assertEquals(0, board.attunedCount)
    }

    @Test
    fun `the REST snapshot and the DDP mirror produce the same inventory`() {
        // The same guarantee `MirrorVsSnapshotDeltaTest` gives the tracker board: the two
        // sources differ only by soft-deleted documents, and discovery drops those, so the
        // screen cannot change under the user when the live subscription takes over.
        val fromMirror = InventoryEngine.build(CreatureSheet.fromMirror(Fixtures.sabrielMirror(), Fixtures.SABRIEL_ID))
        assertEquals(board, fromMirror)
    }

    @Test
    fun `the carried weight is a real number and the sections add up to it`() {
        val sections = board.wallet.weightLb +
            board.equipped.sumOf { it.totalWeightLb } +
            board.carried.sumOf { it.totalWeightLb } +
            board.containers.sumOf { it.ownWeightLb + it.contentsWeightLb }

        assertEquals(sections, board.carriedWeightLb, 1e-9)
        assertTrue("a sheet with this much gear cannot weigh nothing", board.carriedWeightLb > 0.0)
    }

    @Test
    fun `an insert target is resolvable on a real sheet`() {
        val target = InventoryEngine.insertTarget(sheet)
        assertNotNull("a real sheet must resolve somewhere to put a new item", target)
        requireNotNull(target)

        // The capture has a `carried`-tagged folder, which is where a new item belongs.
        val parent = sheet.properties.getValue(target.parentId)
        assertEquals("folder", parent.string("type"))
        assertTrue("carried" in parent.strings("tags"))

        val maxOrder = sheet.livePropertyList.mapNotNull { it.number("order") }.max()
        assertEquals(maxOrder + 1, target.order)
    }
}
