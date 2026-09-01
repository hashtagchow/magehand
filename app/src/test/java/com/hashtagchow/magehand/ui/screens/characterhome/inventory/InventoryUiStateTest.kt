package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalInventoryBoard
import com.hashtagchow.magehand.stringsXml
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.ui.components.DirectEntryKeys
import com.hashtagchow.magehand.ui.components.DirectEntryKind
import com.hashtagchow.magehand.core.model.CoinPurse
import com.hashtagchow.magehand.core.model.ConnectionState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
        // 11 decisions 1 and 3. The engine computes both from a sheet's tags; here they are
        // parameters, because what this class tests is the *mapping*, and a fixture that
        // re-derived them would only assert its own re-derivation.
        isEquippable: Boolean = true,
        equipGroup: EquipGroup = EquipGroup.GEAR,
        // FR-9. `tags` is what the coin exclusion (12 decision 7) reads, and `containerId` is
        // what the move picker subtracts the item's current location by (decision 8). Both are
        // parameters here for the reason the two above are: this class tests the mapping.
        tags: List<String> = emptyList(),
        containerId: String? = null,
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
        isEquippable = isEquippable,
        equipGroup = equipGroup,
        tags = tags,
        containerId = containerId,
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
        // `equipped = true` on the item as well as membership of the board's `equipped` list.
        // The engine always sets both — the list *is* the items whose flag is set — and the
        // fixture used to set only the list, which was invisible until FR-9 gave the flag a
        // second job (12 decision 8 refuses to move an equipped item). A fixture that
        // disagrees with itself is a test that passes for the wrong reason.
        equipped = listOf(item("eq1", "Longsword", weightLb = 3.0, valueGp = 15.0, equipped = true)),
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
        equippableOverrides: Set<String> = emptySet(),
        // FR-16 only. The *arrangement* layer is `InventoryLayoutTest`'s subject; this class
        // needs the parameter so a section can be put in its collapsed state, which is a
        // property of the section this class does test.
        layout: List<InventoryLayoutEntry> = emptyList(),
        isLocal: Boolean = false,
        // FR-35. Defaulted to the sheet's own order, which is what every assertion in this class
        // written before FR-35 means — and the fact that none of them had to change is the
        // feature's central claim, pinned by name further down.
        sort: InventorySort = InventorySort.DEFAULT,
    ) = toInventoryUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        isShowingSnapshot = isShowingSnapshot,
        canWrite = canWrite,
        equippableOverrides = equippableOverrides,
        layout = layout,
        isLocal = isLocal,
        sort = sort,
    )

    // --- section composition (10 decision 2) -------------------------------------

    @Test
    fun `sections are equipped, then each container, then carried`() {
        val state = map()

        assertEquals(
            listOf(
                InventorySectionKind.EQUIPPED,
                InventorySectionKind.CONTAINER,
                InventorySectionKind.GEAR,
            ),
            state.sections.map { it.kind },
        )
        assertEquals(listOf("equipped", "container:cont1", "gear"), state.sections.map { it.key })
    }

    @Test
    fun `an empty equipped or carried section is absent, header and all`() {
        val state = map(board.copy(equipped = emptyList(), carried = emptyList()))

        assertEquals(listOf(InventorySectionKind.CONTAINER), state.sections.map { it.kind })
    }

    // --- K9: a container with nothing displayable folds away (11 decision 5) ----------

    @Test
    fun `a container with no displayable rows renders no section at all`() {
        // The live capture's coins-only purse, in miniature: `InventoryBoard`'s precedence has
        // already moved its coins into the Wallet, so `contents` is empty and the section would
        // be a header, a weight, and nothing else — directly above the Wallet holding what is
        // actually in it.
        val purse = board.containers.first().copy(contents = emptyList(), rollupWeightLb = null)
        val state = map(board.copy(containers = listOf(purse)))

        assertTrue(
            "11 decision 5: no displayable rows, no section",
            state.sections.none { it.kind == InventorySectionKind.CONTAINER },
        )
    }

    @Test
    fun `folding a container away does not change the grand carried weight`() {
        // The half of K9 that a future reader is most likely to "fix" into a double count: the
        // shell's own weight is already in `InventoryBoard.carriedWeightLb`, which is a client
        // sum that never consulted the section list. Adding it back on top of the folded-away
        // section would count the purse twice. `InventoryEngineTest` pins the same fact from
        // the engine's side, against a real sheet rather than a hand-built board.
        val purse = board.containers.first().copy(contents = emptyList(), rollupWeightLb = null)

        assertEquals(
            map(board).carriedWeight,
            map(board.copy(containers = listOf(purse))).carriedWeight,
        )
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

    // --- 11 decision 3: Carried subdivides into Weapons · Armor · Gear ----------------

    @Test
    fun `carried splits into weapons, armor and gear, in that order`() {
        val state = map(
            board.copy(
                carried = listOf(
                    item("g1", "Tinderbox", isEquippable = false),
                    item("a1", "Shield", equipGroup = EquipGroup.ARMOR),
                    item("w1", "Dagger", equipGroup = EquipGroup.WEAPON),
                ),
            ),
        )

        // Board order is deliberately scrambled above: the section order is this screen's, not
        // the sheet's, and it must not depend on which item happened to come first.
        //
        // The containers moved **below** Armor in FR-13 (12 decision 1) — they were between
        // Equipped and Weapons when this test was written. That is the whole of decision 1 as far
        // as this fixture can see it, and it is the reason the two sections a player reaches for
        // mid-combat are now adjacent instead of separated by however many backpacks the sheet
        // happens to have.
        assertEquals(
            listOf(
                InventorySectionKind.EQUIPPED,
                InventorySectionKind.WEAPONS,
                InventorySectionKind.ARMOR,
                InventorySectionKind.CONTAINER,
                InventorySectionKind.GEAR,
            ),
            state.sections.map { it.kind },
        )
        assertEquals(listOf("Dagger"), state.sections.single { it.kind == InventorySectionKind.WEAPONS }.rows.map { it.name })
        assertEquals(listOf("Shield"), state.sections.single { it.kind == InventorySectionKind.ARMOR }.rows.map { it.name })
        assertEquals(listOf("Tinderbox"), state.sections.single { it.kind == InventorySectionKind.GEAR }.rows.map { it.name })
    }

    @Test
    fun `an empty subsection is absent, so a sheet of pure gear shows one header`() {
        val state = map(
            board.copy(containers = emptyList(), carried = listOf(item("g1", "Torch"))),
        )

        assertEquals(
            listOf(InventorySectionKind.EQUIPPED, InventorySectionKind.GEAR),
            state.sections.map { it.kind },
        )
    }

    @Test
    fun `each subsection carries its own weight, summed over its own rows`() {
        val state = map(
            board.copy(
                carried = listOf(
                    item("w1", "Dagger", quantity = 2, weightLb = 1.0, equipGroup = EquipGroup.WEAPON),
                    item("a1", "Shield", weightLb = 6.0, equipGroup = EquipGroup.ARMOR),
                    item("g1", "Torch", quantity = 3, weightLb = 1.0),
                ),
            ),
        )

        assertEquals("2", state.sections.single { it.kind == InventorySectionKind.WEAPONS }.weight)
        assertEquals("6", state.sections.single { it.kind == InventorySectionKind.ARMOR }.weight)
        assertEquals("3", state.sections.single { it.kind == InventorySectionKind.GEAR }.weight)
    }

    @Test
    fun `the equipped section is unchanged - it is a state, not a group`() {
        // 11 decision 3 leaves Equipped alone, which matters for a weapon: it renders under
        // Equipped, not under Weapons, so equipping a dagger does not move it into a section
        // it was never in. `InventoryBoard`'s precedence is what guarantees this; the mapping
        // must not undo it by re-grouping the equipped list.
        val state = map(
            board.copy(
                equipped = listOf(item("e1", "Longsword", equipGroup = EquipGroup.WEAPON)),
                carried = emptyList(),
            ),
        )

        assertEquals(
            listOf("Longsword"),
            state.sections.single { it.kind == InventorySectionKind.EQUIPPED }.rows.map { it.name },
        )
        assertTrue(state.sections.none { it.kind == InventorySectionKind.WEAPONS })
    }

    // --- 11 decisions 1 and 2: the equip control and its override ---------------------

    private fun row(state: InventoryUiState, id: String) = state.row(id)!!

    @Test
    fun `a non-equippable row shows no equip control, and offers the override switch`() {
        val state = map(board.copy(carried = listOf(item("c1", "Tinderbox", isEquippable = false))))

        val tinderbox = row(state, "c1")
        assertFalse("a tinderbox is not something a character wears", tinderbox.showsEquipControl)
        assertTrue("…and this is the one row the switch exists for", tinderbox.showsEquippableToggle)
        assertFalse(tinderbox.equippableOverridden)
    }

    @Test
    fun `an equippable row shows the control and is offered no switch`() {
        val state = map(board.copy(carried = listOf(item("c1", "Dagger", equipGroup = EquipGroup.WEAPON))))

        val dagger = row(state, "c1")
        assertTrue(dagger.showsEquipControl)
        assertFalse(
            "an item the sheet already classified has nothing to gain from the switch",
            dagger.showsEquippableToggle,
        )
    }

    @Test
    fun `an override gives the control back and leaves the switch visible`() {
        val state = map(
            board.copy(carried = listOf(item("c1", "A Small Knife", isEquippable = false))),
            equippableOverrides = setOf("c1"),
        )

        val knife = row(state, "c1")
        assertTrue("11 decision 2's whole point", knife.showsEquipControl)
        assertTrue(knife.equippableOverridden)
        assertTrue(
            "the switch must stay: it is the only way back, and one that vanished on use " +
                "would be a one-way door",
            knife.showsEquippableToggle,
        )
    }

    @Test
    fun `an overridden item keeps its Gear grouping - the override buys a control, not a claim`() {
        val state = map(
            board.copy(
                containers = emptyList(),
                carried = listOf(item("c1", "A Small Knife", isEquippable = false)),
            ),
            equippableOverrides = setOf("c1"),
        )

        assertEquals(
            listOf(InventorySectionKind.EQUIPPED, InventorySectionKind.GEAR),
            state.sections.map { it.kind },
        )
    }

    @Test
    fun `equipping an overridden item hides the switch, and unequipping brings it back on`() {
        // LOW-1: the switch's visibility rule reads the *board's* answer, and 11 decision 1's
        // first disjunct makes an equipped item equippable outright — so once the player
        // equips their overridden knife, the board classifies it without help and the switch
        // has nothing left to correct.
        val equipped = map(
            board.copy(
                equipped = listOf(item("c1", "A Small Knife", equipped = true, isEquippable = true)),
                carried = emptyList(),
            ),
            equippableOverrides = setOf("c1"),
        )

        val worn = row(equipped, "c1")
        assertTrue("the equip control is there — that is what the override bought", worn.showsEquipControl)
        assertFalse("nothing left to correct while it is on", worn.showsEquippableToggle)
        assertTrue("but the override itself is untouched", worn.equippableOverridden)

        // The other leg: the store is never written by equipping, so taking the knife off
        // returns the board's answer to `false` and the switch reappears **already on**.
        val takenOff = map(
            board.copy(
                equipped = emptyList(),
                carried = listOf(item("c1", "A Small Knife", isEquippable = false)),
            ),
            equippableOverrides = setOf("c1"),
        )

        val stowed = row(takenOff, "c1")
        assertTrue(stowed.showsEquippableToggle)
        assertTrue("and still on — equipping did not silently spend the override", stowed.equippableOverridden)
        assertTrue(stowed.showsEquipControl)
    }

    @Test
    fun `an override naming an item this sheet does not have changes nothing`() {
        // Overrides are never swept — an id whose item comes back after an undo or a dropped
        // socket should find its override still there. So a stale id has to be inert.
        val plain = map(board.copy(carried = listOf(item("c1", "Tinderbox", isEquippable = false))))
        val withStale = map(
            board.copy(carried = listOf(item("c1", "Tinderbox", isEquippable = false))),
            equippableOverrides = setOf("some-item-that-was-deleted"),
        )

        assertEquals(plain.sections, withStale.sections)
    }

    // --- 12 decision 2: the chip's verb split, seen and spoken -------------------------

    @Test
    fun `the chip offers a verb while it is off and states a fact while it is on`() {
        val unequipped = item("c1", "Dagger")
        val equipped = item("eq2", "Rapier", equipped = true)

        // The whole of FR-13's addendum. The chip used to read "Equipped" in both states and
        // leave the difference to a tint, which is legible only when the two are side by side —
        // and a player scrolling Weapons never sees them side by side.
        assertEquals(R.string.inventory_chip_equip, unequipped.toRowState().equipChipLabelRes)
        assertEquals(R.string.inventory_chip_equipped, equipped.toRowState().equipChipLabelRes)
    }

    @Test
    fun `the spoken sentence names the item, then the state only when there is one, then the tap`() {
        // The house pattern `WalletUiState.spokenLabel` set, applied to a control: the copy
        // arrives as parameters and the *rule* is what is pinned here. That rule is the
        // asymmetry — "not equipped" is deliberately never said, because it would spend a whole
        // clause restating what the verb already implies, on the majority of rows on every sheet.
        assertEquals(
            "Dagger, tap to equip",
            item("c1", "Dagger").toRowState()
                .spokenEquipLabel(equippedLabel = "Equipped", action = "tap to equip"),
        )
        assertEquals(
            "Rapier, Equipped, tap to unequip",
            item("eq2", "Rapier", equipped = true).toRowState()
                .spokenEquipLabel(equippedLabel = "Equipped", action = "tap to unequip"),
        )
    }

    @Test
    fun `the spoken action is the same verb split the chip's label is`() {
        // One concept, one decision, two renderings — so a future edit cannot end up with a chip
        // reading "Equip" beside a sentence saying "tap to unequip".
        assertEquals(R.string.inventory_equip_action, item("c1", "Dagger").toRowState().equipActionRes)
        assertEquals(
            R.string.inventory_unequip_action,
            item("eq2", "Rapier", equipped = true).toRowState().equipActionRes,
        )
    }

    /** The row this item becomes on the tab — resolved through the real mapper, never by hand. */
    private fun InventoryItem.toRowState(): InventoryRowState {
        val state = map(board.copy(equipped = listOf(this).filter { it.equipped }, carried = listOf(this).filterNot { it.equipped }))
        return state.row(propertyId)!!
    }

    // --- rows ----------------------------------------------------------------------

    @Test
    fun `a quantity of one prints no badge, and anything else does`() {
        val state = map(
            board.copy(
                carried = listOf(item("c1", "Torch", quantity = 1), item("c2", "Arrows", quantity = 20)),
            ),
        )

        val rows = state.sections.single { it.kind == InventorySectionKind.GEAR }.rows
        assertFalse(rows[0].showsQuantity)
        assertTrue(rows[1].showsQuantity)
    }

    @Test
    fun `a missing weight stays missing rather than becoming zero`() {
        val state = map(
            board.copy(carried = listOf(item("c1", "Letter", weightLb = null, valueGp = null))),
        )

        val row = state.sections.single { it.kind == InventorySectionKind.GEAR }.rows.single()
        assertNull(row.stackWeight)
        assertNull(row.unitWeight)
        assertNull(row.unitValue)
    }

    // --- K10: absent weight prints an em dash (11 decision 6) -------------------------

    @Test
    fun `a row with no weight prints an em dash rather than a blank`() {
        val state = map(board.copy(carried = listOf(item("c1", "Letter", weightLb = null))))

        val row = row(state, "c1")
        assertEquals("—", row.stackWeightLabel)
        assertFalse("…and takes no unit: '— lb' is a measurement with a missing number", row.hasWeight)
    }

    @Test
    fun `a row with a weight prints the number and takes the unit`() {
        val state = map(board.copy(carried = listOf(item("c1", "Torch", quantity = 3, weightLb = 1.0))))

        val row = row(state, "c1")
        assertEquals("3", row.stackWeightLabel)
        assertTrue(row.hasWeight)
    }

    @Test
    fun `a weight of zero is a claim and prints as zero, not as an em dash`() {
        // The distinction the whole em-dash rule exists to keep: a torch recorded as weightless
        // and a torch with no recorded weight are different facts, and only the first is a claim.
        val state = map(board.copy(carried = listOf(item("c1", "Feather", weightLb = 0.0))))

        assertEquals("0", row(state, "c1").stackWeightLabel)
        assertTrue(row(state, "c1").hasWeight)
    }

    @Test
    fun `the em dash constant is the one the detail sheet already prints`() {
        // The invariant [EM_DASH]'s KDoc claims, asserted against the **resource that ships**
        // rather than against a second literal — two literals agreeing proves only that they
        // were typed the same day. `:app` has no Robolectric harness, so the file is read
        // directly; that is also the honest thing to check, since it is the file that ships.
        //
        // U+2014, not U+2013. Two spellings of "absent" on one screen (an en dash in the list,
        // an em dash in the detail sheet) is the kind of drift nobody reports and everybody
        // notices.
        val strings = stringsXml().readText()
        val declared = Regex("""<string name="inventory_unknown">(.*?)</string>""")
            .find(strings)
            ?.groupValues
            ?.get(1)

        assertNotNull("inventory_unknown must exist — the detail sheet prints it", declared)
        assertEquals(
            "EM_DASH and R.string.inventory_unknown must stay the same glyph",
            declared,
            EM_DASH,
        )
        assertEquals("and that glyph is U+2014", "—", EM_DASH)
    }

    /**
     * L3: the section header's item count is a **plural resource**, and it ships as one.
     *
     * `strings.xml` says why the count is a `<plurals>` and not a format string — *"this sentence
     * is read aloud, and '1 items' is the kind of thing a screen-reader user hears in full"* —
     * and until the 1.6.0 review that was the only place it was said. A `<string>` with `%1$d
     * items` in it renders identically on every screen and wrongly in exactly one place: the
     * TalkBack sentence on a section holding one thing.
     *
     * Read from the file that ships and formatted the way the header formats it, for the em-dash
     * test's reason: two literals agreeing proves only that they were typed the same day.
     * `pluralStringResource(id, count, count)` resolves the quantity and then applies the
     * platform's `String.format`, so the second half is reproducible here exactly; the first half
     * is English's own one/other rule, which is why both arms are asserted rather than one.
     */
    @Test
    fun `the section item count is a plural resource that reads 1 item and N items`() {
        val strings = stringsXml().readText()
        val block = Regex("""<plurals name="inventory_section_items">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?.groupValues
            ?.get(1)

        assertNotNull(
            "inventory_section_items must be a <plurals>, not a <string> — a format string " +
                "would have TalkBack say '1 items' on every single-item section",
            block,
        )

        val forms = Regex("""<item quantity="(\w+)">(.*?)</item>""")
            .findAll(block!!)
            .associate { it.groupValues[1] to it.groupValues[2] }

        assertEquals("English needs exactly one and other", setOf("one", "other"), forms.keys)
        // The header's own call is `pluralStringResource(R.plurals…, count, count)`: the quantity
        // picks the form, the same count fills the argument.
        assertEquals("1 item", String.format(Locale.US, forms.getValue("one"), 1))
        assertEquals("0 items", String.format(Locale.US, forms.getValue("other"), 0))
        assertEquals("2 items", String.format(Locale.US, forms.getValue("other"), 2))
        assertEquals("11 items", String.format(Locale.US, forms.getValue("other"), 11))
    }

    // --- FR-11: the collapsed wallet's summary (11 decision 4) ------------------------

    @Test
    fun `the wallet summary lists only nonzero denominations, in wallet order`() {
        val state = map(board.copy(wallet = wallet(pp = 2, gp = 15, sp = 3)))

        assertEquals("2 pp · 15 gp · 3 sp", state.wallet.summary)
    }

    @Test
    fun `an all-zero wallet has no summary, so the header can say Empty`() {
        // `null` rather than an empty string: a blank beside a collapsed header reads as "not
        // loaded yet", and the composable has a word for the true thing.
        assertNull(map(board.copy(wallet = wallet())).wallet.summary)
    }

    @Test
    fun `one denomination summarises as one entry, with no separator`() {
        assertEquals("15 gp", map(board.copy(wallet = wallet(gp = 15))).wallet.summary)
    }

    @Test
    fun `the spoken header keeps the money in the sentence, not just the action`() {
        // The header is a clickable, which merges its children into one accessibility node —
        // so an action-only description would REPLACE the title and the summary rather than
        // add to them, and a screen-reader user would be told the control exists and never
        // told how much money they have. That reading is what FR-11 trades the steppers for.
        val state = map(board.copy(wallet = wallet(pp = 2, gp = 15, sp = 3)))

        assertEquals(
            "Wallet, 2 pp · 15 gp · 3 sp, Show the coin steppers",
            state.wallet.spokenLabel(
                title = "Wallet",
                emptyLabel = "Empty",
                action = "Show the coin steppers",
            ),
        )
    }

    @Test
    fun `the spoken header says Empty rather than trailing a gap`() {
        val state = map(board.copy(wallet = wallet()))

        assertEquals(
            "Wallet, Empty, Show the coin steppers",
            state.wallet.spokenLabel("Wallet", "Empty", "Show the coin steppers"),
        )
    }

    // --- FR-16: the section header's summary and sentence (13 decisions 1 and 5) ---

    @Test
    fun `a section counts rows, not the things inside them`() {
        // The distinction the count exists to keep: a quiver of twenty arrows is ONE item on
        // this list. Summing quantities would print "22 items" over a section a player can
        // expand and count three of, and the number they can check has to be the right one.
        val section = map(
            board.copy(
                carried = listOf(
                    item("q1", "Arrows", quantity = 20, weightLb = 0.05),
                    item("q2", "Torch", quantity = 2, weightLb = 1.0),
                ),
            ),
        ).sections.single { it.kind == InventorySectionKind.GEAR }

        assertEquals(2, section.itemCount)
        // …and the weight beside it is still the summed figure, so nothing is lost between them.
        assertEquals("3", section.weight)
    }

    @Test
    fun `a section header summarises as count then weight, on this tab's own separator`() {
        val section = map().sections.first()

        // The copy arrives as parameters — "items" is a plural and "lb" is a unit — while the
        // separator and the order are this tab's punctuation, shared with the wallet's coin
        // line so the two summaries cannot drift apart.
        assertEquals("3 items · 41 lb", section.summary(countLabel = "3 items", weightLabel = "41 lb"))
    }

    @Test
    fun `the spoken section header carries all five facts, in order`() {
        // 13 decision 5's sentence. The header is a clickable, so this string is *everything* a
        // screen-reader user is told about the section — the name, how much is in it, what it
        // weighs, whether it is open, and that it can be tapped.
        val section = map(layout = listOf(InventoryLayoutEntry("equipped", collapsed = true)))
            .sections.single { it.key == "equipped" }

        assertTrue(section.collapsed)
        assertEquals(
            "Armor, 3 items, 41 lb, collapsed, tap to expand",
            section.spokenLabel(
                title = "Armor",
                countLabel = "3 items",
                weightLabel = "41 lb",
                stateLabel = "collapsed",
                action = "tap to expand",
            ),
        )
    }

    @Test
    fun `the spoken section header names no copy of its own`() {
        // Every word arrives as a parameter: the builder joins, it does not write. A future edit
        // that hard-codes "collapsed" or "lb" in here would be putting untranslatable copy in a
        // Kotlin file, and this is what notices. It is also what notices a *dropped* fragment —
        // a five-part sentence quietly becoming four is invisible until TalkBack is on.
        val section = map().sections.first()

        assertEquals("A, B, C, D, E", section.spokenLabel("A", "B", "C", "D", "E"))
    }

    @Test
    fun `the spoken header names no copy of its own`() {
        // Every word in the sentence arrives as a parameter: the builder joins, it does not
        // write. A future edit that hard-codes "Wallet" or "Empty" in here would be putting
        // untranslatable copy in a Kotlin file, and this is what notices.
        val state = map(board.copy(wallet = wallet(gp = 1)))

        assertEquals("A, 1 gp, C", state.wallet.spokenLabel("A", "B", "C"))
        assertEquals("A, B, C", map(board.copy(wallet = wallet())).wallet.spokenLabel("A", "B", "C"))
    }

    @Test
    fun `collapsing changes no number - the expanded rows are untouched`() {
        // FR-11 collapses the *steppers*. The four rows and the total are the same objects
        // either way, which is what makes the expansion a pure view concern in the composable
        // rather than a second shape of state.
        val state = map(board.copy(wallet = wallet(pp = 2, gp = 15, sp = 3)))

        assertEquals(4, state.wallet.rows.size)
        assertEquals(listOf(2, 15, 3, 0), state.wallet.rows.map { it.quantity })
        // 2 × 10 + 15 + 3 × 0.1
        assertEquals("35.3", state.wallet.totalGp)
    }

    @Test
    fun `stack figures multiply by quantity`() {
        val state = map(
            board.copy(carried = listOf(item("c1", "Torch", quantity = 3, weightLb = 1.0, valueGp = 0.01))),
        )

        val row = state.sections.single { it.kind == InventorySectionKind.GEAR }.rows.single()
        assertEquals("3", row.stackWeight)
        assertEquals("0.03", row.stackValue)
        assertEquals("0.01", row.unitValue)
    }

    @Test
    fun `a blank description is absent, so the detail sheet renders no empty block`() {
        val state = map(board.copy(carried = listOf(item("c1", "Torch", description = "   "))))

        assertNull(state.sections.single { it.kind == InventorySectionKind.GEAR }.rows.single().description)
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

    private fun localItem(
        id: String,
        label: String,
        equipped: Boolean,
        weightLb: Double? = 2.0,
        category: CatalogCategory = CatalogCategory.GEAR,
    ) =
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
            category = category,
        )

    @Test
    fun `a local board subdivides Carried by category, exactly as a server board does by tags`() {
        // FR-10b (13 decision 10). Until 1.6.0 this asserted the opposite — one GEAR section for
        // everything, because a local character had no data to subdivide by. It has now.
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(
                localItem("r1", "Longsword", equipped = false, category = CatalogCategory.WEAPON),
                localItem("r2", "Chain Shirt", equipped = false, category = CatalogCategory.ARMOR),
                localItem("r3", "Rope", equipped = false),
            ),
        )

        val state = map(local)

        assertEquals(
            listOf(
                InventorySectionKind.WEAPONS,
                InventorySectionKind.ARMOR,
                InventorySectionKind.GEAR,
            ),
            state.sections.map { it.kind },
        )
        // Still never a container: 09 decision 8's flat `sortIndex` is the only structure there
        // is, and FR-10b did not change that.
        assertTrue(state.sections.none { it.kind == InventorySectionKind.CONTAINER })
    }

    @Test
    fun `a local weapon or armor row is equippable outright`() {
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "Longsword", equipped = false, category = CatalogCategory.WEAPON)),
        )

        val row = map(local).sections.single { it.kind == InventorySectionKind.WEAPONS }.rows.single()

        // 13 decision 10's first disjunct, `category != GEAR`. The board has classified it, so
        // 11 decision 2's override toggle has nothing to rescue — the same rule a tagged
        // DiceCloud weapon already gets.
        assertTrue(row.showsEquipControl)
        assertFalse(row.showsEquippableToggle)
    }

    @Test
    fun `a local gear row loses the chip and gains the override switch, as a server gear row does`() {
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "Rope", equipped = false)),
        )

        val row = map(local).sections.single { it.kind == InventorySectionKind.GEAR }.rows.single()

        // The retirement of the FR-10 interim, stated as the behaviour a player sees: an
        // unequipped, uncategorised local row now reads exactly like a tinderbox on a DiceCloud
        // sheet — no equip chip, and the "Can be equipped" switch offered to get one back.
        assertFalse(row.showsEquipControl)
        assertTrue("the override is the way back, and it must be offered", row.showsEquippableToggle)
    }

    @Test
    fun `an equipped local gear row keeps its control, which is decision 11's upgrade honesty`() {
        // The row every 1.4.x/1.5.x local character has: added before the category column
        // existed, so it reads as gear — but the player equipped it, and the migration must not
        // take the unequip control away from an item they are holding.
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "A Small Knife", equipped = true)),
        )

        val row = map(local).sections.single { it.kind == InventorySectionKind.EQUIPPED }.rows.single()

        assertTrue(row.showsEquipControl)
        // And the switch is absent, for `showsEquippableToggle`'s stated reason: the board has
        // classified it through the `equipped` disjunct, so there is no doubt to correct.
        assertFalse(row.showsEquippableToggle)
    }

    @Test
    fun `a local gear row the player overrides gets its chip back and stays in Gear`() {
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "A Little Bag of Sand", equipped = false)),
        )

        val row = map(local, equippableOverrides = setOf("r1"))
            .sections.single { it.kind == InventorySectionKind.GEAR }
            .rows.single()

        // 11 decision 3's rule, now reachable locally: an override buys the *control*, not a
        // promotion to Weapons. This app still does not know what the thing is.
        assertTrue(row.showsEquipControl)
        assertTrue("and the switch stays, because it is the only way back", row.showsEquippableToggle)
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

    // --- delete and move controls (FR-9, 12 decisions 7 and 8) ------------------

    /**
     * The ordinary case, and the one that has to be right on every row of every sheet: a
     * server item offers both.
     */
    @Test
    fun `an unequipped server item offers delete and move`() {
        val row = map().row("c1")!!

        assertTrue(row.showsDeleteControl)
        assertTrue(row.showsMoveControl)
        assertTrue("a DiceCloud delete is a softRemove, so it is reversible", row.deleteIsUndoable)
        assertEquals(R.string.inventory_delete_undoable, row.deleteWarningRes)
    }

    /**
     * Decision 8's fence: `equip` reparents on the server's own schedule, so an equipped item
     * that had also been hand-placed would have two writers of one field.
     *
     * Delete is deliberately *not* fenced the same way — nothing owns an item's existence, so
     * making the player take a thing off before they may destroy it would be a step to
     * discover for no gain.
     */
    @Test
    fun `an equipped item may be deleted but not moved`() {
        val row = map().row("eq1")!!

        assertFalse("equip already owns an equipped item's location", row.showsMoveControl)
        assertTrue("but nothing owns its existence", row.showsDeleteControl)
    }

    /**
     * Decision 7's coin exclusion, as a rule about the **item** rather than about the section
     * it landed in.
     *
     * `InventoryEngine` routes every coin-tagged item into the wallet, so this board — which
     * puts one in `carried` directly — is not one the engine would build. That is the point:
     * the assertion is that the control is absent because the thing is a coin, not because
     * some upstream filter got there first. A player who deletes their gold stack and
     * dismisses the snackbar has lost a character's money.
     */
    @Test
    fun `a coin-tagged item offers neither delete nor move, wherever it lands`() {
        val state = map(
            board = board.copy(
                carried = listOf(item("gp1", "Gold piece", quantity = 109, tags = listOf("gold"))),
            ),
        )
        val row = state.row("gp1")!!

        assertFalse("wallet rows are stepper-managed (12 decision 7)", row.showsDeleteControl)
        assertFalse(row.showsMoveControl)
        assertTrue(row.isCoin)
    }

    /** The tag is read case-insensitively and out of the library list too — `CoinKind`'s rule. */
    @Test
    fun `a coin is recognised from a library tag and from odd casing`() {
        val state = map(
            board = board.copy(
                carried = listOf(
                    item("gp1", "Gold piece", tags = listOf("Gold")),
                    item("sp1", "Silver piece", tags = emptyList()).copy(libraryTags = listOf("silver")),
                    item("t1", "Torch"),
                ),
            ),
        )

        assertTrue(state.row("gp1")!!.isCoin)
        assertTrue(state.row("sp1")!!.isCoin)
        assertFalse("and an ordinary item is not swept up by it", state.row("t1")!!.isCoin)
    }

    /**
     * Decision 7's honest asymmetry: a local delete cannot be undone, and the confirm says so
     * **before** the tap rather than leaving the player to notice a missing UNDO afterwards.
     */
    @Test
    fun `a local item still offers delete, but warns that it is permanent`() {
        val row = map(isLocal = true).row("c1")!!

        assertTrue(row.showsDeleteControl)
        assertFalse("a deleted Room row has no identity left to restore", row.deleteIsUndoable)
        assertEquals(R.string.inventory_delete_permanent, row.deleteWarningRes)
    }

    /** Decision 8: a local character has no containers, so there is nowhere to move to. */
    @Test
    fun `a local item offers no move control at all`() {
        assertFalse(map(isLocal = true).row("c1")!!.showsMoveControl)
        assertFalse(map(isLocal = true).row("eq1")!!.showsMoveControl)
    }

    // --- the move picker's destinations -----------------------------------------

    @Test
    fun `move targets are the carried root first, then every container`() {
        val state = map()

        assertEquals(
            listOf(null, "cont1"),
            state.moveTargets.map { it.containerId },
        )
        assertEquals("Backpack", state.moveTargets.last().name)
    }

    /**
     * The K9 filter drops a container with no displayable contents from the *section* list, and
     * this list deliberately keeps it.
     *
     * An empty pouch is the single most useful thing to move something into. Building the
     * picker from the rendered sections would have hidden exactly the containers a player is
     * trying to fill.
     */
    @Test
    fun `an empty container is a move target even though it draws no section`() {
        val state = map(
            board = board.copy(
                containers = board.containers + InventoryContainer(
                    propertyId = "empty1",
                    name = "Empty Sack",
                    quantity = 1,
                    weightLb = 0.5,
                    rollupWeightLb = null,
                    rollupValueGp = null,
                    contents = emptyList(),
                ),
            ),
        )

        assertTrue(
            "K9: an empty container draws no header",
            state.sections.none { it.containerName == "Empty Sack" },
        )
        assertTrue(
            "but it is still somewhere you can put things",
            state.moveTargets.any { it.containerId == "empty1" },
        )
    }

    /**
     * A picker listing where you already are is a control with a no-op in it: the write-side
     * guard would answer the tap by doing nothing, which is the interaction that looks most
     * like a bug.
     */
    @Test
    fun `the picker leaves out the container the item is already in`() {
        val state = map(
            board = board.copy(
                carried = listOf(item("c1", "Torch", containerId = "cont1")),
            ),
        )
        val row = state.row("c1")!!

        assertEquals(listOf(null), state.moveTargetsFor(row).map { it.containerId })
    }

    /** The same rule from the other side: an item loose in the pack is not offered "Carried". */
    @Test
    fun `an item already in the carried root is not offered the carried root`() {
        val state = map()
        val row = state.row("c1")!!

        assertEquals(null, row.containerId)
        assertEquals(listOf("cont1"), state.moveTargetsFor(row).map { it.containerId })
    }

    /**
     * A local character produces no containers, so the only entry is the carried root — which
     * every local row is already in, leaving the list empty. The control is gated on `isLocal`
     * anyway; this is the data agreeing with the rule rather than a second copy of it.
     */
    @Test
    fun `a local character has nowhere to move anything`() {
        val state = map(
            board = InventoryBoard(carried = listOf(item("c1", "Torch"))),
            isLocal = true,
        )

        assertTrue(state.moveTargetsFor(state.row("c1")!!).isEmpty())
    }

    // --- FR-22 direct-entry targets (15 decisions 5-7) -----------------------

    @Test
    fun `an item key resolves wherever the row lives, with no ceiling`() {
        // Through `row`, so a row inside a container is as reachable as one loose in Gear.
        val target = map().directEntryTarget(DirectEntryKeys.item("in1"))!!
        assertEquals(DirectEntryKind.ITEM, target.kind)
        assertEquals("Rations (1 day)", target.label)
        assertEquals(5, target.current)
        assertNull("decision 7: a quantity has no ceiling", target.max)
    }

    @Test
    fun `a coin key resolves by denomination and labels itself with the abbreviation`() {
        val target = map().directEntryTarget(DirectEntryKeys.coin(CoinKind.GOLD))!!
        assertEquals(DirectEntryKind.COIN, target.kind)
        assertEquals("the denomination, not a property id", CoinKind.GOLD.name, target.propertyId)
        assertEquals(CoinKind.GOLD.abbreviation, target.label)
        assertEquals(109, target.current)
        assertNull("decision 7: coins have no ceiling", target.max)
    }

    /**
     * A denomination the sheet carries **no property for** still resolves — that is the row a
     * typed number creates, and the whole reason the key is a `CoinKind`.
     */
    @Test
    fun `an absent coin row still resolves, because a typed number creates it`() {
        val empty = board.copy(wallet = Wallet(CoinKind.inWalletOrder.map { WalletRow(it, 0, null) }))
        val target = map(board = empty).directEntryTarget(DirectEntryKeys.coin(CoinKind.SILVER))!!
        assertEquals(0, target.current)
    }

    @Test
    fun `a vanished item and an unknown key both resolve to null`() {
        assertNull(map().directEntryTarget(DirectEntryKeys.item("gone")))
        assertNull(map().directEntryTarget("nonsense"))
    }

    // --- FR-24 the search field (15 decisions 14-16) -------------------------

    /** Fifteen distinct gear items, so the threshold and the filter have something to bite on. */
    private fun bigBoard(names: List<String>) = InventoryBoard(
        wallet = wallet(gp = 109),
        carried = names.mapIndexed { index, name -> item("g$index", name) },
        carriedWeightLb = names.size.toDouble(),
    )

    private val fifteen = (1..15).map { "Gear $it" }

    /**
     * Decision 14's threshold, on the boundary in both directions.
     *
     * Fourteen items is a list a phone can scroll; fifteen is where the field earns its row.
     * `FILTER_THRESHOLD` is asserted by name rather than by repeating `15`, so the design's
     * number and the code's stay one token.
     */
    @Test
    fun `the filter field appears at fifteen items and not at fourteen`() {
        assertFalse(map(board = bigBoard(fifteen.take(FILTER_THRESHOLD - 1))).showsFilterField)
        assertTrue(map(board = bigBoard(fifteen)).showsFilterField)
    }

    /** Coins are not items — the count decision 14 asks for is non-coin by construction. */
    @Test
    fun `a purse full of coins does not push a small inventory over the threshold`() {
        val state = map(board = bigBoard(fifteen.take(3)).copy(wallet = wallet(pp = 9, gp = 900)))
        assertEquals(3, state.itemRowCount)
        assertFalse(state.showsFilterField)
    }

    @Test
    fun `a blank query returns the state untouched`() {
        // Identically: a tab with the field on screen and nothing typed is not a different
        // object from one without the field at all.
        val state = map(board = bigBoard(fifteen))
        assertSame(state, state.filteredBy(""))
        assertSame(state, state.filteredBy("   "))
    }

    /** Decision 15: name substring, case-insensitive. */
    @Test
    fun `the filter matches a case-insensitive name substring`() {
        val state = map(board = bigBoard(listOf("Rope, hempen", "Longsword", "Grappling hook")))
        assertEquals(listOf("Rope, hempen"), state.filteredBy("ROPE").allRowNames())
        assertEquals(listOf("Grappling hook"), state.filteredBy("ling ho").allRowNames())
    }

    /**
     * **Decision 15's headline**: a match inside a *collapsed* section renders, and the section
     * comes back expanded for as long as the filter is active.
     */
    @Test
    fun `a match inside a collapsed section surfaces and the section renders expanded`() {
        val collapsed = map(layout = listOf(InventoryLayoutEntry("equipped", collapsed = true)))
        val equippedSection = collapsed.sections.single { it.key == "equipped" }
        assertTrue("precondition: the player shut this section", equippedSection.collapsed)

        val section = collapsed.filteredBy("longsword").sections.single()
        assertEquals("equipped", section.key)
        assertEquals(listOf("Longsword"), section.rows.map { it.name })
        assertFalse("a matching section opens while the filter is active", section.collapsed)
    }

    /**
     * **Decision 15's other half**: a match in a *hidden* section surfaces under its fold target
     * with its real row.
     *
     * Nothing here undoes the hide, and that is the implementation: `toInventoryUiState` has
     * already folded a hidden section's rows into Gear, so the filter finds them there. The
     * assertion is that the row is reachable and named correctly, not that the heading came back.
     */
    @Test
    fun `a match in a hidden section surfaces under its fold target`() {
        val board = InventoryBoard(
            wallet = wallet(),
            carried = listOf(
                item("w1", "Longsword", equipGroup = EquipGroup.WEAPON),
                item("g1", "Torch"),
            ),
            carriedWeightLb = 2.0,
        )
        val folded = map(board = board, layout = listOf(InventoryLayoutEntry("weapons", hidden = true)))
        assertTrue("precondition: no weapons heading", folded.sections.none { it.key == "weapons" })

        val section = folded.filteredBy("sword").sections.single()
        assertEquals("gear", section.key)
        assertEquals(listOf("Longsword"), section.rows.map { it.name })
    }

    /** A section with no match is dropped whole — the tab's standing rule for an empty section. */
    @Test
    fun `sections with no matches are dropped, header and all`() {
        val state = map().filteredBy("torch")
        assertEquals(listOf("Torch"), state.allRowNames())
        assertTrue("no header over nothing", state.sections.none { it.rows.isEmpty() })
    }

    /** Decision 15: the wallet is exempt. Money does not go missing while you search for rope. */
    @Test
    fun `the wallet block survives a filter that matches nothing`() {
        val state = map().filteredBy("nothing matches this")
        assertEquals(0, state.itemRowCount)
        assertTrue(state.blocks.any { it is InventoryBlock.Wallet })
        assertEquals("and it still says what it holds", 109, state.wallet.rows.first { it.coin == CoinKind.GOLD }.quantity)
    }

    /** Decision 16: an empty result is a state the composable can name, not an empty list of blocks. */
    @Test
    fun `a filter matching nothing leaves no item rows`() {
        assertEquals(0, map().filteredBy("zzz").itemRowCount)
    }

    /**
     * **Decision 15's restore rule.** Clearing the filter returns the stored layout *exactly*,
     * and it is exact for a structural reason rather than a careful one: the filter is a pure
     * transform over an already-built state, so the unfiltered value was never rewritten and
     * there is nothing to put back.
     */
    @Test
    fun `clearing the filter restores the stored layout exactly`() {
        val stored = map(
            layout = listOf(
                InventoryLayoutEntry("equipped", collapsed = true),
                InventoryLayoutEntry("weapons", hidden = true),
            ),
        )

        stored.filteredBy("longsword")

        assertEquals(stored, stored.filteredBy(""))
        assertTrue(stored.sections.single { it.key == "equipped" }.collapsed)
    }

    // --- FR-35: sorting inside sections (ledger decisions 1-3, 6) ------------------

    /**
     * A board whose three sections each hold rows that would sort differently — so a criterion
     * applied to only one of them, or to the tab as a whole, fails visibly.
     *
     * The names are deliberately not in alphabetical order in any section, and the container's
     * contents deliberately span the same weight range as Gear's, so a sort that leaked across
     * section boundaries would interleave them and be caught.
     */
    private val sortable = InventoryBoard(
        wallet = wallet(gp = 109, sp = 4),
        equipped = listOf(
            item("eq-staff", "Quarterstaff", weightLb = 4.0, valueGp = 0.2, equipped = true),
            item("eq-amulet", "Amulet", weightLb = 0.1, valueGp = 500.0, equipped = true),
        ),
        containers = listOf(
            InventoryContainer(
                propertyId = "cont1",
                name = "Backpack",
                quantity = 1,
                weightLb = 5.0,
                valueGp = 2.0,
                rollupWeightLb = 12.5,
                rollupValueGp = 8.0,
                contents = listOf(
                    item("in-rope", "Rope", weightLb = 10.0, valueGp = 1.0),
                    item("in-ink", "Ink", weightLb = null, valueGp = 10.0),
                ),
            ),
        ),
        carried = listOf(
            item("c-torch", "Torch", quantity = 3, weightLb = 1.0, valueGp = 0.01),
            item("c-anvil", "Anvil", weightLb = 50.0, valueGp = 5.0),
            item("c-bedroll", "Bedroll", weightLb = 7.0, valueGp = 1.0),
        ),
        carriedWeightLb = 142.0,
        capacityLb = 225,
    )

    private fun sortedNames(
        criterion: InventorySortCriterion,
        direction: InventorySortDirection = InventorySortDirection.ASCENDING,
    ) = map(board = sortable, sort = InventorySort(criterion, direction)).allRowNames()

    /**
     * Under sheet order the tab draws the **source's own order**, section by section.
     *
     * ### What this does and does not prove, corrected 2026-08-31
     *
     * It used to open with `assertEquals(map(board), map(board, sort = DEFAULT))` and claim to be
     * *"the claim the whole feature rests on"*. The independent review was right that it was
     * neither: `map`'s own parameter already defaults to `InventorySort.DEFAULT`, so the two sides
     * were the identical call and the assertion was `f(x) == f(x)`. It could not have failed, and
     * a test that cannot fail is worse than no test because it occupies the place of one.
     *
     * There is no way to say the real thing from here — the signature carries no "no sort at all"
     * value to contrast with, and inventing one would be adding a state to production code so
     * that a test could assert against it. So this now claims only what it can see: that sheet
     * order is the board's order, written out, which is a genuine (if smaller) pin — a
     * `sortedWith` accidentally left running under `DEFAULT` fails it.
     *
     * **The "FR-35 changed nothing" property lives in two other places, and that is where to look
     * if this feature is ever suspected of moving a row it should not have:**
     *  - `InventorySortPlanTest.sheet order returns the very same list` — `assertSame`, which is
     *    the strongest available statement that nothing was re-decided; and
     *  - the golden corpus, where 17 of 18 images came back **byte-identical** after this wave.
     *    Those pictures are the end-to-end witness a unit test cannot be.
     */
    @Test
    fun `sheet order draws the board's own order, section by section`() {
        assertEquals(
            listOf("Quarterstaff", "Amulet", "Rope", "Ink", "Torch", "Anvil", "Bedroll"),
            sortedNames(InventorySortCriterion.DEFAULT),
        )
        // …and the direction is inert under it, which is the one pairing decision 6's disabled
        // control still allows to be stored. See `InventorySort.direction`.
        assertEquals(
            sortedNames(InventorySortCriterion.DEFAULT),
            sortedNames(InventorySortCriterion.DEFAULT, InventorySortDirection.DESCENDING),
        )
    }

    /**
     * Decision 1: the criterion orders rows **within** each section, and never moves a row across
     * one.
     *
     * The three sections are asserted separately and by key, because "sorted" and "sorted within
     * each section" produce the same *set* of names and differ only in where the boundaries fall —
     * a whole-tab sort would put the Amulet (0.1 lb, Equipped) above the Torch (3 lb, Gear) in one
     * flat run, and a row-name list alone would not say which section either landed in.
     */
    @Test
    fun `weight sorts inside each section and inside the container, never across them`() {
        val state = map(board = sortable, sort = InventorySort(InventorySortCriterion.WEIGHT))

        assertEquals(
            listOf("Amulet", "Quarterstaff"),
            state.sections.single { it.key == InventoryLayoutKeys.EQUIPPED }.rows.map { it.name },
        )
        // The container is decision 1's explicit second half. Ink has no weight and therefore
        // sorts as 0, below the 10 lb rope.
        assertEquals(
            listOf("Ink", "Rope"),
            state.sections.single { it.key == InventoryLayoutKeys.container("cont1") }.rows.map { it.name },
        )
        assertEquals(
            listOf("Torch", "Bedroll", "Anvil"),
            state.sections.single { it.key == InventoryLayoutKeys.GEAR }.rows.map { it.name },
        )
        // …and the section ORDER is untouched: that is `InventoryLayoutPlan`'s, and no criterion
        // has anything to say about it (decision 1).
        assertEquals(
            listOf(InventoryLayoutKeys.EQUIPPED, InventoryLayoutKeys.container("cont1"), InventoryLayoutKeys.GEAR),
            state.sections.map { it.key },
        )
    }

    @Test
    fun `name and value sort each section, and the direction reverses them`() {
        assertEquals(
            listOf("Amulet", "Quarterstaff", "Ink", "Rope", "Anvil", "Bedroll", "Torch"),
            sortedNames(InventorySortCriterion.NAME),
        )
        assertEquals(
            listOf("Quarterstaff", "Amulet", "Rope", "Ink", "Torch", "Bedroll", "Anvil"),
            sortedNames(InventorySortCriterion.NAME, InventorySortDirection.DESCENDING),
        )
        // Value is the stack's, so three torches at 0.01 gp come to 0.03 and still sort last.
        assertEquals(
            listOf("Quarterstaff", "Amulet", "Rope", "Ink", "Torch", "Bedroll", "Anvil"),
            sortedNames(InventorySortCriterion.VALUE),
        )
        assertEquals(
            listOf("Amulet", "Quarterstaff", "Ink", "Rope", "Anvil", "Bedroll", "Torch"),
            sortedNames(InventorySortCriterion.VALUE, InventorySortDirection.DESCENDING),
        )
    }

    /**
     * Decision 1's **wallet exemption**, asserted over every criterion and direction.
     *
     * The wallet is exempt by construction — its rows are built by `Wallet.toUiState`, which the
     * sort never reaches — so this is a test that the call graph stays that shape. A future edit
     * that "helpfully" sorted every block would put copper above platinum under Value ascending,
     * and the coin row order is the one thing on this tab a player reads by position.
     */
    @Test
    fun `the wallet is never sorted, whatever the criterion`() {
        InventorySortCriterion.entries.forEach { criterion ->
            InventorySortDirection.entries.forEach { direction ->
                val state = map(board = sortable, sort = InventorySort(criterion, direction))
                assertEquals(
                    "$criterion $direction must not touch the wallet",
                    CoinKind.inWalletOrder,
                    state.wallet.rows.map { it.coin },
                )
            }
        }
    }

    /**
     * Sorting must not move a number. Section weights are summed over the section's items, and a
     * permutation cannot change a sum — so this pins that the header figures are computed off the
     * unsorted list and that no row was lost or duplicated on the way through the comparator.
     */
    @Test
    fun `sorting changes the order and nothing else — same rows, same weights, same totals`() {
        val unsorted = map(board = sortable)

        InventorySortCriterion.entries.forEach { criterion ->
            InventorySortDirection.entries.forEach { direction ->
                val sorted = map(board = sortable, sort = InventorySort(criterion, direction))
                assertEquals(
                    "$criterion $direction must keep every row exactly once",
                    unsorted.allRowNames().sorted(),
                    sorted.allRowNames().sorted(),
                )
                assertEquals(
                    "$criterion $direction must not move a section weight",
                    unsorted.sections.map { it.key to it.weight },
                    sorted.sections.map { it.key to it.weight },
                )
                assertEquals(unsorted.carriedWeight, sorted.carriedWeight)
                assertEquals(unsorted.capacityWeight, sorted.capacityWeight)
            }
        }
    }

    /**
     * 12 decision 3's fold invariant composed with FR-35: rows folded into Gear are sorted
     * *together with* Gear's own.
     *
     * That is the designed answer rather than an accident of ordering — `toInventoryUiState`
     * appends folded rows after Gear's own so an untouched section does not reshuffle when an
     * unrelated one is folded, but once the player has asked for an explicit order they have
     * asked for it over everything in the section. Under sheet order the append order stands,
     * which is the second half of this test.
     */
    @Test
    fun `rows folded into Gear sort with Gear's own, and only when a criterion is set`() {
        val folded = listOf(InventoryLayoutEntry(InventoryLayoutKeys.container("cont1"), hidden = true))

        assertEquals(
            "sheet order keeps the fold's append order",
            listOf("Torch", "Anvil", "Bedroll", "Rope", "Ink"),
            map(board = sortable, layout = folded).sections
                .single { it.key == InventoryLayoutKeys.GEAR }.rows.map { it.name },
        )
        assertEquals(
            listOf("Anvil", "Bedroll", "Ink", "Rope", "Torch"),
            map(board = sortable, layout = folded, sort = InventorySort(InventorySortCriterion.NAME))
                .sections.single { it.key == InventoryLayoutKeys.GEAR }.rows.map { it.name },
        )
    }

    @Test
    fun `the customize sheet is handed the sort the tab was built with`() {
        // The sheet's radio and the order on the tab come off one pass over one board, which is
        // what makes "the control shows what the tab is doing" true by construction rather than
        // by two mappers agreeing. See `InventoryCustomizeState.sort`.
        val sort = InventorySort(InventorySortCriterion.VALUE, InventorySortDirection.DESCENDING)

        assertEquals(sort, map(board = sortable, sort = sort).customize.sort)
        assertEquals(InventorySort.DEFAULT, map(board = sortable).customize.sort)
    }

    // --- FR-35 decision 6 and the spoken sentences ---------------------------------

    @Test
    fun `the direction control is available for every criterion but sheet order`() {
        // Decision 6. The composable draws it *disabled* rather than absent — that choice is
        // argued at `canChooseDirection` — and this is the rule the composable branches on.
        InventorySortCriterion.entries.forEach { criterion ->
            assertEquals(
                "$criterion",
                criterion != InventorySortCriterion.DEFAULT,
                InventoryCustomizeState(sort = InventorySort(criterion)).canChooseDirection,
            )
        }
    }

    /**
     * The direction segment's spoken sentence, and the half that makes decision 6 honest.
     *
     * A segmented control that is merely inert announces "disabled" and nothing else, which tells
     * a screen-reader user that something is unavailable without telling them what would make it
     * available. This is that sentence, and it is why the control could be disabled rather than
     * removed without costing anybody the explanation.
     *
     * The rule pinned here is the **asymmetry**: the unavailable clause appears only while it is
     * true, matching `spokenEquipLabel`'s dropped "Equipped". *Where* the sentence is mounted —
     * on each segment, so focus reaches it — is `InventorySortControlTest`'s claim, and it has to
     * be, because reachability is a property of the composition and not of this string.
     *
     * The case of the fragments is the shipped case: `strings.xml` spells them "Ascending" and
     * "Descending", and this function joins rather than transforms.
     */
    @Test
    fun `a direction segment says which option it is, and what would turn it on`() {
        assertEquals(
            "Sort direction, Descending",
            InventoryCustomizeState(sort = InventorySort(InventorySortCriterion.NAME))
                .spokenDirectionOptionLabel("Sort direction", "Descending", "not used in sheet order"),
        )
        assertEquals(
            "the reason must be spoken while, and only while, it is true",
            "Sort direction, Descending, not used in sheet order",
            InventoryCustomizeState(sort = InventorySort.DEFAULT)
                .spokenDirectionOptionLabel("Sort direction", "Descending", "not used in sheet order"),
        )
        // Each segment names *itself*, not the current selection — so the two differ under one
        // state, which is what lets a user tell the two stops apart.
        val disabled = InventoryCustomizeState(sort = InventorySort.DEFAULT)
        assertEquals(
            "Sort direction, Ascending, not used in sheet order",
            disabled.spokenDirectionOptionLabel("Sort direction", "Ascending", "not used in sheet order"),
        )
    }

    /** Every row the tab would draw, in order — the filter's observable output. */
    private fun InventoryUiState.allRowNames(): List<String> =
        sections.flatMap { section -> section.rows.map { it.name } }
}
