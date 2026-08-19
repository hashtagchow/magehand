package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.local.LocalInventoryBoard
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CoinKind
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        equippableOverrides: Set<String> = emptySet(),
    ) = toInventoryUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        isShowingSnapshot = isShowingSnapshot,
        canWrite = canWrite,
        equippableOverrides = equippableOverrides,
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
        assertEquals(
            listOf(
                InventorySectionKind.EQUIPPED,
                InventorySectionKind.CONTAINER,
                InventorySectionKind.WEAPONS,
                InventorySectionKind.ARMOR,
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
     * `app/src/main/res/values/strings.xml`, found by walking up from the working directory.
     *
     * Gradle runs a unit test with the *module* directory as its working directory, but that is
     * a default rather than a promise and an IDE runner may disagree. Walking up until the path
     * resolves makes the test say what it means — "the strings file this module ships" — instead
     * of encoding one runner's convention.
     */
    private fun stringsXml(): File {
        val relative = "app/src/main/res/values/strings.xml"
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            // Also the case where the working directory already *is* `app/`.
            val fromModule = File(dir, "src/main/res/values/strings.xml")
            if (fromModule.isFile) return fromModule
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative from ${System.getProperty("user.dir")}")
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
            // Gear, not Weapons, even for a row called "Shield": a local character carries no
            // tags at all, so there is nothing to subdivide by. See `LocalInventoryBoard`.
            listOf(InventorySectionKind.EQUIPPED, InventorySectionKind.GEAR),
            state.sections.map { it.kind },
        )
        assertTrue(state.sections.none { it.kind == InventorySectionKind.CONTAINER })
    }

    @Test
    fun `every local row keeps its equip control, because no local row is classified`() {
        val local = LocalInventoryBoard.build(
            localCharacter(),
            listOf(localItem("r1", "Rope", equipped = false)),
        )

        val row = map(local).sections.single { it.kind == InventorySectionKind.GEAR }.rows.single()

        // 11 decision 1's rule reads a tag taxonomy a local character does not have. "No
        // taxonomy" is not "not equippable" — running the rule anyway would strip the control
        // from the whole of a local inventory on the strength of data never collected.
        assertTrue(row.showsEquipControl)
        assertFalse("and so the override switch has nothing to rescue", row.showsEquippableToggle)
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
