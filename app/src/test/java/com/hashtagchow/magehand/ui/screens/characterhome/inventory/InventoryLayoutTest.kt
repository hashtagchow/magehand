package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalInventoryBoard
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.CoinPurse
import com.hashtagchow.magehand.core.model.EquipGroup
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-13's new default order and FR-14's customizable layout
 * (docs/design/12-inventory-layout.md decisions 1, 3, 4 and 6).
 *
 * ### What this class is for that `InventoryUiStateTest` is not
 *
 * That class pins *board → UI*: which sections a board turns into, and the arithmetic on each.
 * This one pins the layer above — **arrangement** — where the interesting failures are not
 * arithmetic at all:
 *
 *  - a hide that loses an item rather than regrouping it (decision 3's invariant, and the one
 *    thing on this tab that would be a data-loss bug from the player's point of view);
 *  - a stored order that stops applying because a container came or went (decision 4);
 *  - a reorder that writes an arrangement missing the sections the board happened not to have
 *    at that instant.
 *
 * None of the three is visible in a screenshot and all three are silent, which is why they are
 * pinned here rather than left to the device sweep.
 */
class InventoryLayoutTest {

    // --- fixtures ---------------------------------------------------------------

    private fun item(
        id: String,
        name: String,
        weightLb: Double? = 1.0,
        equipGroup: EquipGroup = EquipGroup.GEAR,
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = 1,
        weightLb = weightLb,
        valueGp = 1.0,
        description = null,
        equipped = false,
        requiresAttunement = null,
        attuned = null,
        isEquippable = true,
        equipGroup = equipGroup,
    )

    private fun container(id: String, name: String, vararg contents: InventoryItem) =
        InventoryContainer(
            propertyId = id,
            name = name,
            quantity = 1,
            weightLb = 5.0,
            valueGp = 2.0,
            rollupWeightLb = 12.5,
            rollupValueGp = 8.0,
            contents = contents.toList(),
        )

    /**
     * A character with **one of everything**: coins, an equipped item, weapons, armor, two
     * containers and gear. Deliberately the fullest board any test here needs, so that a hide
     * combination is a filter over one fixture rather than a fixture per case.
     */
    private val board = InventoryBoard(
        wallet = Wallet(
            listOf(
                WalletRow(CoinKind.PLATINUM, 0, "coin-pp"),
                WalletRow(CoinKind.GOLD, 12, "coin-gp"),
                WalletRow(CoinKind.SILVER, 0, "coin-sp"),
                WalletRow(CoinKind.COPPER, 0, "coin-cp"),
            ),
        ),
        equipped = listOf(item("eq1", "Longsword", weightLb = 3.0)),
        containers = listOf(
            container("cont1", "Backpack", item("in1", "Rations", weightLb = 2.0)),
            container("cont2", "Pouch", item("in2", "Chalk", weightLb = 0.5)),
        ),
        carried = listOf(
            item("w1", "Dagger", weightLb = 1.0, equipGroup = EquipGroup.WEAPON),
            item("a1", "Shield", weightLb = 6.0, equipGroup = EquipGroup.ARMOR),
            item("g1", "Torch", weightLb = 1.0),
            item("g2", "Tinderbox", weightLb = 1.0),
        ),
        carriedWeightLb = 42.0,
        capacityLb = 210,
    )

    private fun map(
        board: InventoryBoard = this.board,
        layout: List<InventoryLayoutEntry> = emptyList(),
    ) = toInventoryUiState(creatureId = "FakeCreature23456", board = board, layout = layout)

    /** Every item row on the tab, wherever it is. The subject of decision 3's invariant. */
    private fun InventoryUiState.itemIds(): Set<String> =
        blocks.filterIsInstance<InventoryBlock.Items>()
            .flatMap { it.section.rows }
            .map { it.propertyId }
            .toSet()

    private fun hide(vararg keys: String) = keys.map { InventoryLayoutEntry(it, hidden = true) }

    private companion object {
        val CONT1 = InventoryLayoutKeys.container("cont1")
        val CONT2 = InventoryLayoutKeys.container("cont2")

        /** Decision 1, spelled out once so every test below reads against the same list. */
        val DEFAULT_ORDER = listOf("wallet", "equipped", "weapons", "armor", CONT1, CONT2, "gear")

        /** Every item the fixture board carries, in every section. */
        val EVERY_ITEM = setOf("eq1", "in1", "in2", "w1", "a1", "g1", "g2")
    }

    // --- decision 1: the new default order --------------------------------------

    @Test
    fun `the default order is wallet, equipped, weapons, armor, containers, gear`() {
        assertEquals(DEFAULT_ORDER, map().blocks.map { it.key })
    }

    @Test
    fun `the wallet is a block in the order, not a fixture above it`() {
        // The load-bearing half of decision 1 for the wallet: it is *in* the list, which is what
        // makes decision 3 able to move and fold it at all.
        assertEquals(InventoryBlock.Wallet, map().blocks.first())
    }

    @Test
    fun `an absent section is absent from the order, not an empty slot in it`() {
        val bare = board.copy(equipped = emptyList(), containers = emptyList(), carried = emptyList())

        // Only the wallet, which every character has. Not "wallet plus five empty headers":
        // the "absent when empty" rule from FR-8 survives the order becoming customizable.
        assertEquals(listOf("wallet"), map(bare).blocks.map { it.key })
    }

    // --- decision 3: reorder ------------------------------------------------------

    @Test
    fun `a stored order is what renders, and it survives the sections it does not mention`() {
        val state = map(layout = listOf(InventoryLayoutEntry("gear"), InventoryLayoutEntry("wallet")))

        // Gear and Wallet where the player put them; everything they never touched woven back in
        // at its default place rather than appended in a heap at the end.
        assertEquals(listOf("gear", "wallet", "equipped", "weapons", "armor", CONT1, CONT2), state.blocks.map { it.key })
    }

    @Test
    fun `moving a section down swaps it with the next one and writes the whole arrangement`() {
        val state = map()
        val next = InventoryLayoutPlan.move(state.customize.resolved, emptyList(), "wallet", 1)

        assertEquals(
            listOf("equipped", "wallet", "weapons", "armor", CONT1, CONT2, "gear"),
            next.map { it.key },
        )
        // …and the move is real: feeding it back through the mapper renders it.
        assertEquals(next.map { it.key }, map(layout = next).blocks.map { it.key })
    }

    @Test
    fun `moving a section up is the same swap in the other direction`() {
        val state = map()
        val next = InventoryLayoutPlan.move(state.customize.resolved, emptyList(), "weapons", -1)

        assertEquals(
            listOf("wallet", "weapons", "equipped", "armor", CONT1, CONT2, "gear"),
            next.map { it.key },
        )
    }

    @Test
    fun `a bounce off either end of the list is not a write`() {
        val resolved = map().customize.resolved

        // `TrackerOverridePlan.reorder`'s contract, restated here: empty means "nothing to do",
        // and the view model does not write it. A real arrangement always has a section in it, so
        // the signal is unambiguous.
        assertTrue(InventoryLayoutPlan.move(resolved, emptyList(), "wallet", -1).isEmpty())
        assertTrue(InventoryLayoutPlan.move(resolved, emptyList(), "gear", 1).isEmpty())
        assertTrue(InventoryLayoutPlan.move(resolved, emptyList(), "wallet", 0).isEmpty())
        assertTrue(InventoryLayoutPlan.move(resolved, emptyList(), "nonesuch", 1).isEmpty())
    }

    @Test
    fun `a hidden section is stepped over by the arrows and stays where it was folded`() {
        val state = map(layout = hide("weapons"))
        val resolved = state.customize.resolved

        // Equipped moves past Armor — the next *visible* section — and Weapons, folded between
        // them, keeps its absolute place. That is what makes un-hiding predictable: it comes back
        // where it went, not at whichever end the visible moves pushed it.
        val next = InventoryLayoutPlan.move(resolved, emptyList(), "equipped", 1)

        assertEquals(
            listOf("wallet", "armor", "weapons", "equipped", CONT1, CONT2, "gear"),
            next.map { it.key },
        )
        assertEquals(
            "the fold is unchanged by a move that stepped over it",
            listOf(InventoryLayoutEntry("weapons", hidden = true)),
            next.filter { it.hidden },
        )
    }

    /**
     * The write-side [InventoryLayoutPlan] weave — the one that stops a gesture made during a
     * cold open from forgetting every container the board has not delivered yet.
     */
    @Test
    fun `a gesture made while a container is missing keeps that container's stored place`() {
        val offline = board.copy(containers = emptyList())
        val stored = listOf(
            InventoryLayoutEntry("wallet"),
            InventoryLayoutEntry("equipped"),
            InventoryLayoutEntry(CONT1, hidden = true),
            InventoryLayoutEntry("weapons"),
            InventoryLayoutEntry("armor"),
            InventoryLayoutEntry("gear"),
        )
        val resolved = map(offline, stored).customize.resolved
        assertFalse("the fixture must not have the container on screen", resolved.any { it.key == CONT1 })

        val next = InventoryLayoutPlan.move(resolved, stored, "wallet", 1)

        assertEquals(
            "the absent container keeps its place and its fold",
            InventoryLayoutEntry(CONT1, hidden = true),
            next.single { it.key == CONT1 },
        )
        assertEquals(
            // Woven back in after `equipped`, which is what it followed in storage — the
            // absent key is anchored to its stored *predecessor*, exactly as an unknown key is
            // anchored on the read side. One rule, both directions: a positional index would
            // mean nothing across two lists of different lengths.
            listOf("equipped", CONT1, "wallet", "weapons", "armor", "gear"),
            next.map { it.key },
        )
    }

    // --- decision 3: the guardrails ----------------------------------------------

    @Test
    fun `equipped and gear offer no hide control, and every other section does`() {
        val rows = map().customize.rows.associate { it.key to it.canHide }

        // Equipped: hiding what you are wielding is a footgun with no use case.
        assertEquals(false, rows["equipped"])
        // Gear: it is where everything folds TO, so hiding it is the one hide that could not
        // preserve the invariant. See `InventoryLayoutKeys.isHideable`.
        assertEquals(false, rows["gear"])
        assertEquals(true, rows["wallet"])
        assertEquals(true, rows["weapons"])
        assertEquals(true, rows["armor"])
        assertEquals(true, rows[CONT1])
    }

    @Test
    fun `the plan refuses to hide equipped or gear even when the sheet is bypassed`() {
        val resolved = map().customize.resolved

        // Enforced twice on purpose. The sheet does not draw the control, and this is the other
        // half: a caller that found the key some other way still cannot write the state.
        assertTrue(InventoryLayoutPlan.setHidden(resolved, emptyList(), "equipped", true).isEmpty())
        assertTrue(InventoryLayoutPlan.setHidden(resolved, emptyList(), "gear", true).isEmpty())
    }

    @Test
    fun `a stored hide of equipped or gear reads as visible rather than as a section lost`() {
        // The third layer, and the one that matters across versions: a preferences file written
        // by hand, or by a future build with different rules, cannot strand a section off-screen
        // with no control to bring it back.
        val state = map(layout = hide("equipped", "gear"))

        assertTrue(state.blocks.any { it.key == "equipped" })
        assertTrue(state.blocks.any { it.key == "gear" })
        assertTrue("nothing may be folded", state.customize.hidden.isEmpty())
    }

    @Test
    fun `hiding a section and showing it again returns the arrangement to where it was`() {
        val resolved = map().customize.resolved

        val hidden = InventoryLayoutPlan.setHidden(resolved, emptyList(), "weapons", true)
        assertEquals(true, hidden.single { it.key == "weapons" }.hidden)

        val shown = InventoryLayoutPlan.setHidden(map(layout = hidden).customize.resolved, hidden, "weapons", false)
        assertEquals(DEFAULT_ORDER, shown.map { it.key })
        assertTrue(shown.none { it.hidden })
    }

    @Test
    fun `hiding what is already hidden is not a write`() {
        val hidden = hide("weapons")
        val resolved = map(layout = hidden).customize.resolved

        assertTrue(InventoryLayoutPlan.setHidden(resolved, hidden, "weapons", true).isEmpty())
        assertTrue(InventoryLayoutPlan.setHidden(resolved, hidden, "nonesuch", false).isEmpty())
    }

    // --- decision 3: THE invariant — items fold, they never vanish ----------------

    @Test
    fun `every hide combination leaves every item on the tab`() {
        // The invariant, asserted **exhaustively** rather than on a case somebody thought of:
        // all 16 combinations of the four hideable item sections. Hiding on this tab changes
        // GROUPING and never visibility, and an inventory that could conceal an item is an
        // inventory a player cannot trust — so this is the test that must never be relaxed.
        val hideable = listOf("weapons", "armor", CONT1, CONT2)

        (0 until (1 shl hideable.size)).forEach { mask ->
            val folded = hideable.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
            val state = map(layout = folded.map { InventoryLayoutEntry(it, hidden = true) })

            assertEquals(
                "hiding $folded lost or invented an item",
                EVERY_ITEM,
                state.itemIds(),
            )
        }
    }

    @Test
    fun `hiding the wallet too still leaves every item, because coins are not items`() {
        val state = map(layout = hide("wallet", "weapons", "armor", CONT1, CONT2))

        assertEquals(EVERY_ITEM, state.itemIds())
        // …and the wallet block itself is gone, which is the one hide that removes rather than
        // regroups. It is allowed to because the coin rows are duplicated nowhere on this tab.
        assertTrue(state.blocks.none { it is InventoryBlock.Wallet })
        assertEquals("Wallet", 12, state.wallet.rows.single { it.coin == CoinKind.GOLD }.quantity)
    }

    @Test
    fun `folded items land in Gear, after Gear's own, in the default section order`() {
        val state = map(layout = hide("weapons", "armor", CONT1))

        assertEquals(
            listOf("wallet", "equipped", CONT2, "gear"),
            state.blocks.map { it.key },
        )
        assertEquals(
            // Gear's own first — it is the section the player did not touch, so its contents do
            // not reshuffle when an unrelated one is folded — then weapons, armor, containers.
            listOf("g1", "g2", "w1", "a1", "in1"),
            state.sections.single { it.key == "gear" }.rows.map { it.propertyId },
        )
    }

    @Test
    fun `Gear's weight grows by exactly what folded into it`() {
        val gearAlone = map().sections.single { it.key == "gear" }.weight
        val withWeapons = map(layout = hide("weapons")).sections.single { it.key == "gear" }.weight

        // g1 + g2 = 2 lb; the Dagger adds 1. A client sum over exactly the rows shown, so there
        // is no rollup for the header to disagree with. A folded container's *shell* weight is
        // deliberately not added — the top line already counts it, and adding it here would be a
        // double count.
        assertEquals("2", gearAlone)
        assertEquals("3", withWeapons)
        assertEquals(
            // 2 (Gear's own) + 2 (Rations) + 0.5 (Chalk). The two 5 lb shells are deliberately
            // absent: 14.5 here would be a double count against the top line, which already
            // counts every container's empty weight.
            "the containers' shells are not Gear's to carry",
            "4.5",
            map(layout = hide(CONT1, CONT2)).sections.single { it.key == "gear" }.weight,
        )
    }

    @Test
    fun `a folded section is still listed in the sheet, which is the only way back`() {
        val state = map(layout = hide("weapons", CONT1))

        assertEquals(listOf("weapons", CONT1), state.customize.hidden.map { it.key })
        assertEquals("Backpack", state.customize.hidden.single { it.key == CONT1 }.containerName)
        // Its own weight, not the folded Gear's: what the player needs to decide whether to bring
        // a section back is what is in *it*.
        assertEquals("1", state.customize.hidden.single { it.key == "weapons" }.weightLabel)
    }

    @Test
    fun `Gear appears in the sheet once something has folded into it`() {
        val onlyWeapons = board.copy(
            containers = emptyList(),
            carried = listOf(item("w1", "Dagger", equipGroup = EquipGroup.WEAPON)),
        )

        // Nothing in Gear and nothing folded: no Gear section on the tab, so none in the sheet.
        assertTrue(map(onlyWeapons).customize.rows.none { it.key == "gear" })

        // Fold Weapons and Gear is where the Dagger is — so it has to be arrangeable too.
        val folded = map(onlyWeapons, hide("weapons"))
        assertEquals(setOf("eq1", "w1"), folded.itemIds())
        assertEquals(listOf("wallet", "equipped", "gear"), folded.blocks.map { it.key })
        assertEquals(true, folded.customize.rows.any { it.key == "gear" })
    }

    // --- decision 4: stable keys, vanished containers, unknown keys ---------------

    @Test
    fun `a container that vanishes from the sheet drops out of the order harmlessly`() {
        val stored = listOf(
            InventoryLayoutEntry("gear"),
            InventoryLayoutEntry(CONT1),
            InventoryLayoutEntry("wallet"),
        )
        val gone = board.copy(containers = board.containers.filter { it.propertyId != "cont1" })

        val state = map(gone, stored)

        // The rest of the arrangement still applies — the vanished key is skipped, not a reason
        // to fall back to the default — and no section is left holding an empty header.
        assertEquals(listOf("gear", "wallet", "equipped", "weapons", "armor", CONT2), state.blocks.map { it.key })
    }

    @Test
    fun `a container that comes back finds its place and its fold still remembered`() {
        val stored = listOf(
            InventoryLayoutEntry("wallet"),
            InventoryLayoutEntry(CONT1, hidden = true),
            InventoryLayoutEntry("equipped"),
        )
        val gone = board.copy(containers = board.containers.filter { it.propertyId != "cont1" })

        assertTrue(map(gone, stored).customize.rows.none { it.key == CONT1 })
        // Order is a preference over whatever exists (decision 4), so nothing was deleted while
        // the container was away — a dropped socket must not cost the player their arrangement.
        assertEquals(
            InventoryLayoutEntry(CONT1, hidden = true),
            map(board, stored).customize.resolved.single { it.key == CONT1 },
        )
    }

    @Test
    fun `a section the stored order never heard of lands at its default position`() {
        // Decision 4's "unknown/new keys append at the default position" — *default position*,
        // not the bottom. A container added to the sheet today shows up between Armor and Gear
        // where decision 1 puts it, rather than under a list the player arranged last week.
        val stored = listOf(
            InventoryLayoutEntry("wallet"),
            InventoryLayoutEntry("equipped"),
            InventoryLayoutEntry("weapons"),
            InventoryLayoutEntry("armor"),
            InventoryLayoutEntry("gear"),
        )

        assertEquals(
            listOf("wallet", "equipped", "weapons", "armor", CONT1, CONT2, "gear"),
            map(layout = stored).blocks.map { it.key },
        )
    }

    @Test
    fun `a new section lands at its default position relative to a rearranged list`() {
        val stored = listOf(
            InventoryLayoutEntry("gear"),
            InventoryLayoutEntry("armor"),
            InventoryLayoutEntry("wallet"),
        )

        // Equipped and Weapons anchor off Wallet, which the player moved to the end; the two
        // containers anchor off Armor. Nothing is appended blindly and nothing jumps to the top.
        assertEquals(
            listOf("gear", "armor", CONT1, CONT2, "wallet", "equipped", "weapons"),
            map(layout = stored).blocks.map { it.key },
        )
    }

    @Test
    fun `section keys are the persisted vocabulary, not the enum's names`() {
        // Decision 4's stable keys. Pinned because they are written to disk: renaming an
        // `InventorySectionKind` value is an ordinary refactor and must not silently invalidate
        // every player's stored arrangement.
        assertEquals(DEFAULT_ORDER, map().customize.rows.map { it.key })
        assertEquals("container:cont1", CONT1)
    }

    // --- reset (decision 5) -------------------------------------------------------

    @Test
    fun `an empty arrangement is the default order, which is what Reset restores`() {
        // Reset deletes the key rather than writing the default (see `InventoryLayoutStore`), so
        // "what a reset character sees" is exactly "what an empty layout renders". A stored copy
        // of today's default would freeze it into that character forever.
        assertEquals(map(layout = emptyList()).blocks.map { it.key }, DEFAULT_ORDER)
        assertEquals(map().itemIds(), map(layout = hide("weapons", CONT1)).itemIds())
    }

    // --- decision 6: local characters --------------------------------------------

    private fun localBoard(vararg rows: LocalTrackerRow) = LocalInventoryBoard.build(
        LocalCharacter(
            id = "local-1",
            name = "Test Character",
            level = 3,
            abilities = AbilityScores(strength = 14),
            maxHp = 24,
            currentHp = 24,
            armorClass = 15,
            coins = CoinPurse(gold = 7),
            createdAt = 0L,
            updatedAt = 0L,
        ),
        rows.toList(),
    )

    private fun localRow(id: String, label: String, equipped: Boolean) = LocalTrackerRow(
        id = id,
        characterId = "local-1",
        kind = LocalRowKind.ITEM,
        label = label,
        total = 1,
        current = 1,
        reset = null,
        sortIndex = 0,
        weightLb = 2.0,
        valueGp = null,
        description = null,
        equipped = equipped,
    )

    @Test
    fun `a local character gets the same surface over a smaller set of sections`() {
        val state = map(
            localBoard(
                localRow("r1", "Shield", equipped = true),
                localRow("r2", "Rope", equipped = false),
            ),
        )

        // 12 decision 6: the same mechanism, fewer sections. No containers (a local character has
        // none) and no Weapons or Armor (a local row carries no tags, so `LocalInventoryBoard`
        // calls everything Gear) — which leaves exactly the three a local character can arrange.
        assertEquals(listOf("wallet", "equipped", "gear"), state.blocks.map { it.key })
        assertEquals(listOf("wallet", "equipped", "gear"), state.customize.rows.map { it.key })
        assertEquals(
            "the guardrails are the same guardrails",
            listOf(true, false, false),
            state.customize.rows.map { it.canHide },
        )
    }

    @Test
    fun `a local character can reorder and fold exactly as a DiceCloud one does`() {
        val local = localBoard(
            localRow("r1", "Shield", equipped = true),
            localRow("r2", "Rope", equipped = false),
        )

        val moved = InventoryLayoutPlan.move(map(local).customize.resolved, emptyList(), "wallet", 1)
        assertEquals(listOf("equipped", "wallet", "gear"), map(local, moved).blocks.map { it.key })

        val folded = map(local, hide("wallet"))
        assertTrue(folded.blocks.none { it is InventoryBlock.Wallet })
        assertEquals("no local item may go missing either", setOf("r1", "r2"), folded.itemIds())
    }

    // --- the customize sheet's own rows ------------------------------------------

    @Test
    fun `a customize row names its section the way the tab's header does`() {
        val rows = map().customize.rows.associateBy { it.key }

        assertEquals(R.string.inventory_section_wallet, rows.getValue("wallet").titleRes)
        assertEquals(R.string.inventory_section_weapons, rows.getValue("weapons").titleRes)
        // A container names itself; the generic title is only the fallback for a blank one.
        assertEquals("Backpack", rows.getValue(CONT1).containerName)
        assertEquals(R.string.inventory_section_container, rows.getValue(CONT1).titleRes)
        assertNull(
            "the fallback is for containers, not for the fixed sections",
            rows.getValue("weapons").containerName,
        )
    }

    @Test
    fun `the wallet's detail is its coins and every other row's is its weight`() {
        val rows = map().customize.rows.associateBy { it.key }

        // 10 decision 10: the wallet prints no weight figure, so the coin line is what tells the
        // player which row they are about to fold. Two same-named pouches are told apart by
        // theirs — which is the job the tracker sheet's `detail` does, with no new copy for it.
        assertEquals("12 gp", rows.getValue("wallet").summary)
        assertNull(rows.getValue("wallet").weightLabel)
        assertEquals("6", rows.getValue("armor").weightLabel)
        assertNull(rows.getValue("armor").summary)
    }

    @Test
    fun `the sheet lists exactly the sections the tab is drawing, plus the folded ones`() {
        val state = map(layout = hide("armor", CONT2))

        // The property that makes building both in one pass worth it: the sheet cannot name a
        // section the tab does not have, and cannot lose one the tab folded away.
        assertEquals(state.blocks.map { it.key }, state.customize.visible.map { it.key })
        assertEquals(listOf("armor", CONT2), state.customize.hidden.map { it.key })
        assertTrue(state.customize.hasHiddenRows)
        assertFalse(map().customize.hasHiddenRows)
    }
}
