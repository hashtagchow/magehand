package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalInventoryBoard
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.CoinPurse
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.core.model.WalletRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Board → UI for the inventory tab (docs/design/10-inventory.md, wave B acceptance).
 *
 * Every assertion here is about a decision that has no other witness: the sections a board
 * turns into, the two gates that decide whether a line renders at all (capacity, attunement),
 * and the arithmetic behind every number on screen. None of it needs a device, which is the
 * point of `toInventoryUiState` being a pure function.
 *
 * The **local** board is exercised through the real `LocalInventoryBoard` rather than a
 * hand-shaped `InventoryBoard`, because "local and server differ in exactly three ways" is a
 * claim about that builder and a hand-shaped fixture would only assert the shaping.
 */
class InventoryUiStateTest {

    // --- fixtures ---------------------------------------------------------------

    private fun item(
        id: String,
        name: String,
        quantity: Int = 1,
        weightLb: Double? = 1.0,
        valueGp: Double? = 1.0,
        equipped: Boolean = false,
        description: String? = null,
        requiresAttunement: Boolean? = null,
        attuned: Boolean? = null,
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = quantity,
        weightLb = weightLb,
        valueGp = valueGp,
        description = description,
        equipped = equipped,
        requiresAttunement = requiresAttunement,
        attuned = attuned,
    )

    /** Four coin rows with quantities, all backed by real properties. */
    private fun wallet(pp: Int = 0, gp: Int = 0, sp: Int = 0, cp: Int = 0) = Wallet(
        listOf(
            WalletRow(CoinKind.PLATINUM, pp, "coin-pp"),
            WalletRow(CoinKind.GOLD, gp, "coin-gp"),
            WalletRow(CoinKind.SILVER, sp, "coin-sp"),
            WalletRow(CoinKind.COPPER, cp, "coin-cp"),
        ),
    )

    private val board = InventoryBoard(
        wallet = wallet(gp = 109, sp = 4),
        equipped = listOf(item("eq1", "Longsword", weightLb = 3.0, valueGp = 15.0)),
        containers = listOf(
            InventoryContainer(
                propertyId = "cont1",
                name = "Backpack",
                quantity = 1,
                weightLb = 5.0,
                valueGp = 2.0,
                rollupWeightLb = 12.5,
                rollupValueGp = 8.0,
                contents = listOf(item("in1", "Rations (1 day)", quantity = 5, weightLb = 2.0)),
            ),
        ),
        carried = listOf(item("c1", "Torch", quantity = 3, weightLb = 1.0, valueGp = 0.01)),
        carriedWeightLb = 142.0,
        capacityLb = 225,
    )

    private fun map(
        board: InventoryBoard = this.board,
        connection: ConnectionState = ConnectionState.LIVE,
        isShowingSnapshot: Boolean = false,
        canWrite: Boolean = true,
    ) = toInventoryUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        isShowingSnapshot = isShowingSnapshot,
        canWrite = canWrite,
    )

    // --- section composition (10 decision 2) -------------------------------------

    @Test
    fun `sections are equipped, then each container, then carried`() {
        val state = map()

        assertEquals(
            listOf(
                InventorySectionKind.EQUIPPED,
                InventorySectionKind.CONTAINER,
                InventorySectionKind.CARRIED,
            ),
            state.sections.map { it.kind },
        )
        assertEquals(listOf("equipped", "container:cont1", "carried"), state.sections.map { it.key })
    }

    @Test
    fun `an empty equipped or carried section is absent, header and all`() {
        val state = map(board.copy(equipped = emptyList(), carried = emptyList()))

        assertEquals(listOf(InventorySectionKind.CONTAINER), state.sections.map { it.kind })
    }

    @Test
    fun `an empty container still renders - it is a thing the player carries`() {
        val empty = board.containers.first().copy(contents = emptyList(), rollupWeightLb = null)
        val state = map(board.copy(containers = listOf(empty)))

        val section = state.sections.single { it.kind == InventorySectionKind.CONTAINER }
        assertTrue(section.isEmpty)
        // Its own 5 lb still shows, which is what the grand total is partly made of.
        assertEquals("5", section.weight)
    }

    @Test
    fun `a container names itself and falls back to a generic title when the sheet did not`() {
        assertEquals("Backpack", map().sections[1].containerName)

        val unnamed = board.containers.first().copy(name = "   ")
        val state = map(board.copy(containers = listOf(unnamed)))
        assertNull(state.sections.single { it.kind == InventorySectionKind.CONTAINER }.containerName)
        assertEquals(
            R.string.inventory_section_container,
            InventorySectionKind.CONTAINER.titleRes,
        )
    }

    @Test
    fun `each item lands in exactly one section`() {
        val ids = map().sections.flatMap { section -> section.rows.map { it.propertyId } }

        assertEquals(listOf("eq1", "in1", "c1"), ids)
        assertEquals(ids.size, ids.distinct().size)
    }

    // --- weights: two different sums, deliberately (10 decision 8) ----------------

    @Test
    fun `a container header prints the server rollup, plus the container's own weight`() {
        // 5 lb of empty backpack + the server's 12.5 lb rollup — not the client's 10 lb sum
        // over its one visible row. The server's number is preferred *here and only here*.
        assertEquals("17.5", map().sections[1].weight)
    }

    @Test
    fun `equipped and carried headers are client sums`() {
        val state = map()

        assertEquals("3", state.sections[0].weight)
        // 3 torches at 1 lb.
        assertEquals("3", state.sections[2].weight)
    }

    @Test
    fun `the top line is the board's own client-summed total`() {
        assertEquals("142", map().carriedWeight)
    }

    // --- capacity nullability (10 decision 8) -------------------------------------

    @Test
    fun `capacity renders when the source expresses Strength`() {
        val state = map()

        assertEquals("225", state.capacityWeight)
        assertFalse(state.isOverCapacity)
    }

    @Test
    fun `no Strength means no denominator, not a guessed one`() {
        val state = map(board.copy(capacityLb = null))

        assertNull(state.capacityWeight)
        // And nothing can be "over" a capacity that does not exist.
        assertFalse(state.isOverCapacity)
    }

    @Test
    fun `over capacity is flagged from the board, not recomputed`() {
        val state = map(board.copy(carriedWeightLb = 300.0))

        assertTrue(state.isOverCapacity)
    }

    // --- attunement gating (10 decision 9) ----------------------------------------

    @Test
    fun `no attunement data means no chip - not a confident zero of three`() {
        assertNull(map().attunement)
    }

    @Test
    fun `the chip appears only once the sheet says something about attunement`() {
        val state = map(
            board.copy(
                equipped = listOf(
                    item("eq1", "Cloak of Protection", requiresAttunement = true, attuned = true),
                ),
                attunedCount = 1,
                hasAttunementData = true,
            ),
        )

        assertEquals(AttunementChipState(attuned = 1, slots = 3), state.attunement)
        assertEquals(InventoryBoard.ATTUNEMENT_SLOTS, state.attunement!!.slots)
    }

    @Test
    fun `a row shows attunement only when that row carries a field`() {
        val state = map(
            board.copy(
                equipped = listOf(
                    item("eq1", "Longsword"),
                    item("eq2", "Ring of Warmth", requiresAttunement = true),
                ),
                hasAttunementData = true,
            ),
        )

        val rows = state.sections.first().rows
        assertFalse(rows[0].showsAttunement)
        assertTrue(rows[1].showsAttunement)
        // `attuned` absent stays absent — the detail sheet prints nothing rather than
        // "Not attuned", which would answer a question the sheet never asked.
        assertNull(rows[1].attuned)
    }

    // --- the wallet (10 decision 5) ------------------------------------------------

    @Test
    fun `an empty wallet renders four zero rows, not an empty section`() {
        val state = map(InventoryBoard.EMPTY)

        assertEquals(4, state.wallet.rows.size)
        assertEquals(CoinKind.inWalletOrder, state.wallet.rows.map { it.coin })
        assertTrue(state.wallet.rows.all { it.quantity == 0 })
        assertEquals("0", state.wallet.totalGp)
    }

    @Test
    fun `a zero row cannot decrement and every row can increment`() {
        val state = map(board.copy(wallet = wallet(gp = 2)))

        assertTrue(state.wallet.rows.single { it.coin == CoinKind.GOLD }.canDecrement)
        assertFalse(state.wallet.rows.single { it.coin == CoinKind.SILVER }.canDecrement)
    }

    @Test
    fun `the total-in-gp line is the client sum across denominations`() {
        // 1 pp + 2 gp + 3 sp + 4 cp == 10 + 2 + 0.3 + 0.04
        val state = map(board.copy(wallet = wallet(pp = 1, gp = 2, sp = 3, cp = 4)))

        assertEquals("12.34", state.wallet.totalGp)
    }

    // --- rows ----------------------------------------------------------------------

    @Test
    fun `a quantity of one prints no badge, and anything else does`() {
        val state = map(
            board.copy(
                carried = listOf(item("c1", "Torch", quantity = 1), item("c2", "Arrows", quantity = 20)),
            ),
        )

        val rows = state.sections.single { it.kind == InventorySectionKind.CARRIED }.rows
        assertFalse(rows[0].showsQuantity)
        assertTrue(rows[1].showsQuantity)
    }

    @Test
    fun `a missing weight stays missing rather than becoming zero`() {
        val state = map(
            board.copy(carried = listOf(item("c1", "Letter", weightLb = null, valueGp = null))),
        )

        val row = state.sections.single { it.kind == InventorySectionKind.CARRIED }.rows.single()
        assertNull(row.stackWeight)
        assertNull(row.unitWeight)
        assertNull(row.unitValue)
    }

    @Test
    fun `stack figures multiply by quantity`() {
        val state = map(
            board.copy(carried = listOf(item("c1", "Torch", quantity = 3, weightLb = 1.0, valueGp = 0.01))),
        )

        val row = state.sections.single { it.kind == InventorySectionKind.CARRIED }.rows.single()
        assertEquals("3", row.stackWeight)
        assertEquals("0.03", row.stackValue)
        assertEquals("0.01", row.unitValue)
    }

    @Test
    fun `a blank description is absent, so the detail sheet renders no empty block`() {
        val state = map(board.copy(carried = listOf(item("c1", "Torch", description = "   "))))

        assertNull(state.sections.single { it.kind == InventorySectionKind.CARRIED }.rows.single().description)
    }

    @Test
    fun `a row is findable by id from anywhere on the board, and unknown ids are null`() {
        val state = map()

        assertEquals("Rations (1 day)", state.row("in1")?.name)
        assertEquals("Longsword", state.row("eq1")?.name)
        assertNull(state.row("gone"))
    }

    // --- loading / empty ------------------------------------------------------------

    @Test
    fun `a cold open with no snapshot and no socket is loading`() {
        assertTrue(map(InventoryBoard.EMPTY, connection = ConnectionState.CONNECTING).isLoading)
    }

    @Test
    fun `a snapshot ends the loading state, and so does a live socket`() {
        assertFalse(
            map(InventoryBoard.EMPTY, connection = ConnectionState.CONNECTING, isShowingSnapshot = true)
                .isLoading,
        )
        assertFalse(map(InventoryBoard.EMPTY, connection = ConnectionState.LIVE).isLoading)
    }

    @Test
    fun `a character who genuinely owns nothing is empty, not loading`() {
        val state = map(InventoryBoard.EMPTY, connection = ConnectionState.LIVE)

        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `a character with only coins is not empty`() {
        val state = map(InventoryBoard(wallet = wallet(gp = 1)))

        assertFalse(state.isEmpty)
    }

    @Test
    fun `canWrite passes straight through, so every control dims together`() {
        assertFalse(map(canWrite = false).canWrite)
        assertTrue(map(canWrite = true).canWrite)
    }

    // --- local vs server (10 decision 10) --------------------------------------------

    private fun localCharacter(
        strength: Int = 14,
        coins: CoinPurse = CoinPurse(gold = 7),
    ) = LocalCharacter(
        id = "local-1",
        name = "Test Character",
        level = 3,
        abilities = AbilityScores(strength = strength),
        maxHp = 24,
        currentHp = 24,
        armorClass = 15,
        coins = coins,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun localItem(id: String, label: String, equipped: Boolean, weightLb: Double? = 2.0) =
        LocalTrackerRow(
            id = id,
            characterId = "local-1",
            kind = LocalRowKind.ITEM,
            label = label,
            total = 1,
            current = 1,
            reset = null,
            sortIndex = 0,
            weightLb = weightLb,
            valueGp = null,
            description = null,
            equipped = equipped,
        )

    @Test
    fun `a local board renders the same two item sections and never a container`() {
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "Shield", equipped = true), localItem("r2", "Rope", equipped = false)),
        )

        val state = map(local)

        assertEquals(
            listOf(InventorySectionKind.EQUIPPED, InventorySectionKind.CARRIED),
            state.sections.map { it.kind },
        )
        assertTrue(state.sections.none { it.kind == InventorySectionKind.CONTAINER })
    }

    @Test
    fun `a local character never gets the attunement chip - the form captures no such field`() {
        val local = LocalInventoryBoard.build(localCharacter(), listOf(localItem("r1", "Shield", true)))

        assertNull(map(local).attunement)
    }

    @Test
    fun `a local capacity always renders, because the form always has a Strength score`() {
        // The honest difference from a DiceCloud sheet, which may simply not express one.
        val local = LocalInventoryBoard.build(localCharacter(strength = 14), emptyList())

        assertEquals("210", map(local).capacityWeight)
    }

    @Test
    fun `a local wallet is four rows off four columns`() {
        val local = LocalInventoryBoard.build(
            localCharacter(coins = CoinPurse(platinum = 1, gold = 2, silver = 3, copper = 4)),
            emptyList(),
        )

        val state = map(local)
        assertEquals(listOf(1, 2, 3, 4), state.wallet.rows.map { it.quantity })
        assertEquals("12.34", state.wallet.totalGp)
    }

    @Test
    fun `a local board is never loading, because Room has no cold-open gap`() {
        val local = LocalInventoryBoard.build(null, emptyList())

        assertFalse(map(local, connection = ConnectionState.LIVE).isLoading)
    }

    // --- formatting -------------------------------------------------------------------

    @Test
    fun `whole numbers lose their decimals and small ones keep them`() {
        assertEquals("142", formatAmount(142.0))
        assertEquals("1.5", formatAmount(1.5))
        assertEquals("0.02", formatAmount(0.02))
        assertEquals("0.1", formatAmount(0.1))
        assertEquals("0", formatAmount(0.0))
    }

    @Test
    fun `rounding is to two places, and a value that rounds to zero says zero`() {
        assertEquals("0.01", formatAmount(0.005))
        assertEquals("0", formatAmount(0.004))
        assertEquals("2.35", formatAmount(2.345))
    }

    @Test
    fun `a negative rounding to zero does not print a lone minus sign`() {
        assertEquals("0", formatAmount(-0.001))
    }

    @Test
    fun `the section kinds all name a real string resource`() {
        InventorySectionKind.entries.forEach { kind ->
            assertNotNull(kind.name, kind.titleRes)
            assertTrue(kind.name, kind.titleRes != 0)
        }
    }
}
