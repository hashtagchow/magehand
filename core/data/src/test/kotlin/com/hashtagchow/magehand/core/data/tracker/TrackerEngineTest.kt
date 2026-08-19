package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * The WP4 acceptance bar for discovery, against the committed live capture, plus the
 * synthetic cases the capture cannot exercise (it contains no temp HP, no concentration
 * and no `showUI` toggle).
 */
class TrackerEngineTest {

    private val board by lazy { TrackerEngine.build(Fixtures.sabrielSheet()) }

    /** The six ability attributes' names, as DiceCloud spells them. */
    private val ABILITY_NAMES = setOf(
        "Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma",
    )

    // -----------------------------------------------------------------------
    // Acceptance criteria (docs/design/07-build-plan.md WP4)
    // -----------------------------------------------------------------------

    @Test
    fun `first level slots total four`() {
        val first = board.slots.single { it.name == "1st Level" }
        assertEquals(4, first.total)
        assertEquals(3, first.value) // total 4, damage 1
        assertEquals(1, first.spellSlotLevel)
        assertEquals(ResetRule.LONG_REST, first.reset)
        assertEquals(TrackerKind.SPELL_SLOT, first.kind)
    }

    @Test
    fun `second level slots total two`() {
        val second = board.slots.single { it.name == "2nd Level" }
        assertEquals(2, second.total)
        assertEquals(1, second.value)
        assertEquals(2, second.spellSlotLevel)
    }

    @Test
    fun `no death save rows anywhere on the board`() {
        // Death saves are attributeType 'spellSlot' with reset null — the single most
        // important exclusion in 03's discovery rules.
        val everything = board.slots + board.resources + board.allItems +
            listOfNotNull(board.hp, board.tempHp)
        val deathSaves = everything.filter {
            it.name == "Succeeded Saves" || it.name == "Failed Saves"
        }
        assertEquals("death saves leaked onto the board: $deathSaves", 0, deathSaves.size)
        assertTrue(board.activeToggles.none { it.name.contains("Saves") })
    }

    @Test
    fun `heroic inspiration is discovered as a resource`() {
        val resource = board.resources.single { it.name == "Heroic Inspiration" }
        assertEquals(TrackerKind.RESOURCE, resource.kind)
        assertEquals(1, resource.total)
        assertEquals(0, resource.value) // total 1, damage 1 — already spent
        assertEquals("py5chhimM8fsXT28T", resource.propertyId)
    }

    @Test
    fun `gold piece is discovered as an item with quantity 109`() {
        val gold = board.allItems.single { it.name == "Gold piece" }
        assertEquals(TrackerKind.ITEM, gold.kind)
        assertEquals(109, gold.value)
        assertEquals(109, gold.total)
    }

    // -----------------------------------------------------------------------
    // The rest of the discovery rules against live data
    // -----------------------------------------------------------------------

    @Test
    fun `only reachable slot levels are discovered`() {
        // 3rd–9th level are total 0 and inactive; Magic Initiate is a real level-1 slot
        // (and sorts before "1st Level" because its server `order` is lower).
        assertEquals(
            listOf("Magic Initiate", "1st Level", "2nd Level"),
            board.slots.map { it.name },
        )
        assertTrue(board.slots.all { it.total > 0 })
        assertTrue(board.slots.all { it.reset != null })
    }

    @Test
    fun `slots are ordered by level`() {
        val levels = board.slots.map { it.spellSlotLevel }
        assertEquals(levels.sortedBy { it ?: Int.MAX_VALUE }, levels)
    }

    @Test
    fun `hit points come from the variable name, not the attribute type`() {
        val hp = board.hp
        assertNotNull("no HP row discovered", hp)
        hp!!
        assertEquals("Hit Points", hp.name)
        assertEquals(TrackerKind.HIT_POINTS, hp.kind)
        assertEquals(17, hp.total)
        assertEquals(17, hp.value)
        assertEquals("SGw9y6m6TcjyNFgEB", hp.propertyId)
    }

    @Test
    fun `temp hp is found under the live variable name tempHP`() {
        // 03-data-model.md says `tempHitPoints`; the live sheet says `tempHP`.
        // See docs/verification/WP4.md §Deviations.
        val tempHp = board.tempHp
        assertNotNull("no temp HP row discovered", tempHp)
        assertEquals("Temporary Hit Points", tempHp!!.name)
        assertEquals(TrackerKind.TEMP_HP, tempHp.kind)
        assertEquals(0, tempHp.total)
    }

    @Test
    fun `the soft-deleted item is not discovered`() {
        // The one property carrying removed:true. This is also the whole of the
        // 573-vs-572 REST/DDP delta — see docs/verification/WP4.md.
        assertTrue(
            board.allItems.none { it.propertyId == Fixtures.REMOVED_PROPERTY_ID },
        )
        assertTrue(board.allItems.none { it.name.startsWith("Healing-potion ingredients") })
        assertEquals(31, board.allItems.size) // 32 items in the capture, one removed
    }

    @Test
    fun `nothing inactive is discovered`() {
        // Buffs, disabled slots and deactivated features are all inactive:true live.
        assertTrue(board.slots.none { it.total == 0 })
        assertTrue(board.resources.isNotEmpty())
    }

    @Test
    fun `sabriel is not concentrating`() {
        assertNull(board.concentratingOn)
    }

    @Test
    fun `toggles are discovered for display, and the dead ones are still excluded`() {
        // Every live toggle is shown now, not just the condition-free ones: a computed
        // toggle's state is real information at the table. Still excluded are the ones a
        // flip could not affect — removed, or deactivated by an ancestor or another toggle.
        assertTrue(board.activeToggles.isNotEmpty())
        assertTrue(board.activeToggles.none { it.name == "Source of Fear in Sight?" })
        assertTrue(board.activeToggles.none { it.name == "Dead or Stable" })
        assertTrue(
            "the four condition-free toggles must all still be on the board",
            board.activeToggles.map { it.name }
                .containsAll(listOf("Load Wizard Rituals", "Load Wizard Spells", "Racial ASI Disabler")),
        )
    }

    @Test
    fun `not one toggle on the live sheet is flippable, and that is the server's rule`() {
        // The decisive WP7 finding. `flipToggle` refuses any toggle whose document carries
        // neither `enabled` nor `disabled` ("Can't flip a toggle that is computed"), and
        // **none** of Sabriel's 55 toggles carries either. WP4 §6.2's condition-absence
        // fallback offered three of them as flippable; the server rejects all three.
        assertTrue(
            "a chip the server would refuse must not be offered as tappable: " +
                board.activeToggles.filter { it.flippable }.map { it.name },
            board.activeToggles.none { it.flippable },
        )
    }

    @Test
    fun `on the live sheet the default view is 13 of 44 toggles`() {
        // What the feature is *for*, measured on the real capture: 44 discovered toggles,
        // 31 of them switched off. Those 31 are still on the board — the tracker files them
        // behind its "N inactive" expander — but the chip row a player scans mid-combat is
        // 13 long instead of 44. The exact numbers are pinned so a discovery change that
        // quietly re-inflates the list has to come past this test.
        val shown = board.activeToggles.filter { it.shownByDefault }
        assertEquals(44, board.activeToggles.size)
        assertEquals(13, shown.size)
        assertTrue("nothing off may reach the default view unpinned", shown.all { it.enabled })
        assertTrue("Midnight Toggle is off on this sheet", board.activeToggles
            .single { it.name == "Midnight Toggle" }.shownByDefault.not())
    }

    // -----------------------------------------------------------------------
    // Source-agnosticism — the property the whole offline design rests on
    // -----------------------------------------------------------------------

    @Test
    fun `the REST snapshot and the DDP mirror produce the same board`() {
        val fromRest = TrackerEngine.build(Fixtures.sabrielSheet())
        val fromMirror = TrackerEngine.build(
            CreatureSheet.fromMirror(Fixtures.sabrielMirror(), Fixtures.SABRIEL_ID),
        )
        assertEquals(fromRest, fromMirror)
    }

    @Test
    fun `a snapshot body round-trips through the mirror shape`() {
        val original = Fixtures.sabrielSheet()
        val reserialized = CreatureSheet.fromSnapshotJson(original.toSnapshotBody(), Fixtures.SABRIEL_ID)
        assertEquals(TrackerEngine.build(original), TrackerEngine.build(reserialized))
        assertEquals(original.properties.size, reserialized.properties.size)
        assertEquals(Fixtures.SABRIEL_ID, reserialized.creatureId)
    }

    @Test
    fun `the summary reads the creature document`() {
        val summary = Fixtures.sabrielSheet().summary(myUserId = Fixtures.SABRIEL_OWNER)
        assertNotNull(summary)
        assertEquals(Fixtures.SABRIEL_NAME, summary!!.name)
        assertEquals("Chaotic Good", summary.alignment)
        assertTrue(summary.isOwnedByMe)
        assertFalse(Fixtures.sabrielSheet().summary("someone-else")!!.isOwnedByMe)
    }

    // -----------------------------------------------------------------------
    // Override layer (03 §6) — applied last, never mutates server data
    // -----------------------------------------------------------------------

    @Test
    fun `hidden rows disappear and pinned items surface`() {
        val gold = Fixtures.sabrielSheet().let { TrackerEngine.build(it) }
            .allItems.single { it.name == "Gold piece" }
        val potion = TrackerEngine.build(Fixtures.sabrielSheet())
            .allItems.single { it.name == "Potion of Healing" }
        val firstLevel = board.slots.single { it.name == "1st Level" }

        val overridden = TrackerEngine.build(
            Fixtures.sabrielSheet(),
            listOf(
                TrackerOverride(potion.propertyId, pinned = true),
                TrackerOverride(gold.propertyId, hidden = true),
                TrackerOverride(firstLevel.propertyId, hidden = true),
            ),
        )

        assertEquals(listOf("Potion of Healing"), overridden.pinnedItems.map { it.name })
        assertTrue(overridden.pinnedItems.single().pinned)
        assertTrue(overridden.allItems.none { it.name == "Gold piece" })
        assertTrue(overridden.slots.none { it.name == "1st Level" })
        // The server data itself is untouched — a fresh build sees everything again.
        assertEquals(3, TrackerEngine.build(Fixtures.sabrielSheet()).slots.size)
    }

    @Test
    fun `sortIndex reorders ahead of the server order`() {
        val second = board.slots.single { it.name == "2nd Level" }
        val reordered = TrackerEngine.build(
            Fixtures.sabrielSheet(),
            listOf(TrackerOverride(second.propertyId, sortIndex = 0)),
        )
        assertEquals("2nd Level", reordered.slots.first().name)
    }

    // -----------------------------------------------------------------------
    // Synthetic cases the live capture cannot cover
    // -----------------------------------------------------------------------

    private fun sheet(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"c1","name":"Test"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    @Test
    fun `a toggle carrying enabled or disabled is the manual kind`() {
        // The server's own precondition, verified live on the test dummy: setting
        // `enabled: true` is what made `flipToggle` stop throwing "Computed toggle".
        listOf(
            """{"_id":"t1","type":"toggle","name":"Bless","enabled":true}""",
            """{"_id":"t1","type":"toggle","name":"Bless","disabled":true,"inactive":true}""",
        ).forEach { json ->
            assertTrue(json, TrackerEngine.build(sheet(json)).activeToggles.single().flippable)
        }
    }

    @Test
    fun `showUI alone does not make a toggle flippable`() {
        // Tried on the live dummy and it changed nothing — 03 §5's rule is simply not the
        // rule this server implements. Kept as a regression test so nobody re-adopts it.
        val built = sheet("""{"_id":"t1","type":"toggle","name":"Rage","showUI":true}""")
        val toggle = TrackerEngine.build(built).activeToggles.single()
        assertEquals("Rage", toggle.name)
        assertFalse("showUI is not the server's flippability signal", toggle.flippable)
    }

    @Test
    fun `a computed toggle is shown but not flippable`() {
        val built = sheet(
            """{"_id":"t1","type":"toggle","name":"0 HP?","condition":{"value":true},"tags":["combat"]}""",
        )
        val toggle = TrackerEngine.build(built).activeToggles.single()
        assertEquals(listOf("combat"), toggle.tags)
        assertFalse(toggle.flippable)
    }

    @Test
    fun `a toggle that is off is still offered, so it can be turned on`() {
        val built = sheet(
            """{"_id":"t1","type":"toggle","name":"Rage","disabled":true,"inactive":true}""",
        )
        val toggle = TrackerEngine.build(built).activeToggles.single()
        assertFalse(toggle.enabled)
        assertTrue("an off manual toggle must stay flippable, or it can never come back on", toggle.flippable)
    }

    @Test
    fun `concentration is detected on an active buff by tag and by name`() {
        val byTag = sheet(
            """{"_id":"b1","type":"buff","name":"Hex","tags":["concentration"]}""",
        )
        assertEquals("Hex", TrackerEngine.build(byTag).concentratingOn)

        val byName = sheet("""{"_id":"b1","type":"buff","name":"Concentration: Bless"}""")
        assertEquals("Concentration: Bless", TrackerEngine.build(byName).concentratingOn)

        val inactive = sheet(
            """{"_id":"b1","type":"buff","name":"Hex","tags":["concentration"],"inactive":true}""",
        )
        assertNull(TrackerEngine.build(inactive).concentratingOn)
    }

    @Test
    fun `slot level falls back to the leading ordinal in the name`() {
        val built = sheet(
            """{"_id":"s1","type":"attribute","attributeType":"spellSlot","name":"3rd Level",
                "reset":"longRest","total":2,"value":2}""",
        )
        assertEquals(3, TrackerEngine.build(built).slots.single().spellSlotLevel)
    }

    @Test
    fun `value falls back to total minus damage when the server sent no value`() {
        val built = sheet(
            """{"_id":"s1","type":"attribute","attributeType":"spellSlot","name":"1st Level",
                "spellSlotLevel":{"value":1},"reset":"shortRest","total":4,"damage":3}""",
        )
        val slot = TrackerEngine.build(built).slots.single()
        assertEquals(1, slot.value)
        assertEquals(ResetRule.SHORT_REST, slot.reset)
    }

    @Test
    fun `a resource with total zero but a positive value survives`() {
        val built = sheet(
            """{"_id":"r1","type":"attribute","attributeType":"resource","name":"Odd","total":0,"value":2}""",
        )
        assertEquals(1, TrackerEngine.build(built).resources.size)

        val empty = sheet(
            """{"_id":"r1","type":"attribute","attributeType":"resource","name":"Odd","total":0,"value":0}""",
        )
        assertTrue(TrackerEngine.build(empty).resources.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Toggle visibility (active-buffs-only): the engine's half of the rule
    // -----------------------------------------------------------------------

    @Test
    fun `an on toggle is shown by default and an off one is not`() {
        val built = sheet(
            """{"_id":"t1","type":"toggle","name":"Bless","enabled":true}""",
            """{"_id":"t2","type":"toggle","name":"Racial ASI Disabler","disabled":true,"inactive":true}""",
        )
        val board = TrackerEngine.build(built)

        // Both are still *discovered* — the off one has to stay reachable to be turned on.
        assertEquals(listOf("Bless", "Racial ASI Disabler"), board.activeToggles.map { it.name })
        assertEquals(listOf("Bless"), board.activeToggles.filter { it.shownByDefault }.map { it.name })
    }

    @Test
    fun `a pinned toggle is shown by default even while it is off`() {
        // The user override beats the default, in that direction: "always show me this one".
        val built = sheet(
            """{"_id":"t1","type":"toggle","name":"Rage","disabled":true,"inactive":true}""",
        )
        val board = TrackerEngine.build(built, listOf(TrackerOverride("t1", pinned = true)))

        val toggle = board.activeToggles.single()
        assertTrue("the pin must reach the row, not just the prefs table", toggle.pinned)
        assertFalse(toggle.enabled)
        assertTrue("a pinned toggle stays on the main list when it goes off", toggle.shownByDefault)
    }

    @Test
    fun `a hidden toggle is gone even while it is on`() {
        // And in the other direction: hide wins over "it is active", which is why the rule
        // in ConditionToggle.shownByDefault never has to ask about hiding — nothing hidden
        // survives this far.
        val built = sheet("""{"_id":"t1","type":"toggle","name":"Bless","enabled":true}""")
        val board = TrackerEngine.build(built, listOf(TrackerOverride("t1", hidden = true)))
        assertTrue(board.activeToggles.isEmpty())
    }

    @Test
    fun `an unpinned toggle carries no pin, so the default rule is the enabled flag alone`() {
        val built = sheet("""{"_id":"t1","type":"toggle","name":"Bless","enabled":true}""")
        assertFalse(TrackerEngine.build(built).activeToggles.single().pinned)
    }

    // -----------------------------------------------------------------------
    // Defenses — `damageMultiplier`, read off the capture rather than off 03
    // -----------------------------------------------------------------------

    @Test
    fun `a half multiplier is a resistance and the damage types come through`() {
        // The exact shape of the live capture's active multiplier, with an invented name
        // (the real one is a private-capture string — see tools/public-gate.sh).
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"Starlit Aegis",
                "damageTypes":["radiant","necrotic"],"value":0.5,"order":180}""",
        )
        val defense = TrackerEngine.build(built).defenses.single()
        assertEquals(DefenseKind.RESISTANT, defense.kind)
        assertEquals(listOf("radiant", "necrotic"), defense.damageTypes)
        assertEquals("Starlit Aegis", defense.name)
        assertEquals(180, defense.sortOrder)
    }

    @Test
    fun `the multiplier decides the kind, and one and negative decide nothing`() {
        fun kindOf(value: String): DefenseKind? = TrackerEngine.build(
            sheet(
                """{"_id":"d1","type":"damageMultiplier","name":"X",
                    "damageTypes":["fire"],"value":$value}""",
            ),
        ).defenses.singleOrNull()?.kind

        assertEquals(DefenseKind.IMMUNE, kindOf("0"))
        assertEquals(DefenseKind.RESISTANT, kindOf("0.5"))
        assertEquals(DefenseKind.RESISTANT, kindOf("0.25"))
        assertEquals(DefenseKind.VULNERABLE, kindOf("2"))
        assertEquals(DefenseKind.VULNERABLE, kindOf("1.5"))
        // A multiplier that changes nothing is not a defense, and a negative one is a
        // different feature entirely — neither has a word, so neither gets a row.
        assertNull("a 1x multiplier must not read as a defense", kindOf("1"))
        assertNull("a negative multiplier is unsourced; do not guess", kindOf("-1"))
        // And a missing value cannot be read as immunity by accident.
        assertNull(
            TrackerEngine.build(
                sheet("""{"_id":"d1","type":"damageMultiplier","name":"X","damageTypes":["fire"]}"""),
            ).defenses.singleOrNull(),
        )
    }

    @Test
    fun `a fractional multiplier is not truncated into an immunity`() {
        // The bug this whole `decimal()` reader exists to prevent: `number()` would read
        // 0.5 back as 0, turning every resistance in the app into an immunity.
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"X","damageTypes":["fire"],"value":0.5}""",
        )
        assertEquals(DefenseKind.RESISTANT, TrackerEngine.build(built).defenses.single().kind)
    }

    @Test
    fun `an inactive or removed multiplier never reaches the table`() {
        // "an inactive resistance from an unequipped item must not show" — the capture's
        // second multiplier is exactly this, inactive + deactivatedByToggle.
        listOf(
            """{"_id":"d1","type":"damageMultiplier","name":"Grave Ward",
                "damageTypes":["necrotic"],"value":0.5,"inactive":true,"deactivatedByToggle":true}""",
            """{"_id":"d1","type":"damageMultiplier","name":"Gone",
                "damageTypes":["fire"],"value":0,"removed":true}""",
        ).forEach { json ->
            assertTrue(json, TrackerEngine.build(sheet(json)).defenses.isEmpty())
        }
    }

    @Test
    fun `a multiplier with no damage types is not an empty row`() {
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"X","damageTypes":[],"value":0}""",
        )
        assertTrue(TrackerEngine.build(built).defenses.isEmpty())
    }

    @Test
    fun `defenses sort immunities first, then resistances, then vulnerabilities`() {
        // Declaration order of DefenseKind is ascending multiplier, so the board reads
        // best-news-first regardless of the server's `order`.
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"V","damageTypes":["fire"],"value":2,"order":1}""",
            """{"_id":"d2","type":"damageMultiplier","name":"R","damageTypes":["cold"],"value":0.5,"order":2}""",
            """{"_id":"d3","type":"damageMultiplier","name":"I","damageTypes":["poison"],"value":0,"order":3}""",
        )
        assertEquals(
            listOf(DefenseKind.IMMUNE, DefenseKind.RESISTANT, DefenseKind.VULNERABLE),
            TrackerEngine.build(built).defenses.map { it.kind },
        )
    }

    @Test
    fun `a hidden defense obeys the override layer like every other row`() {
        // v1's customize sheet offers no control that can set this, but the repo's rule is
        // that overrides are applied last to everything — so the engine holds up its end.
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"X","damageTypes":["fire"],"value":0.5}""",
        )
        assertTrue(
            TrackerEngine.build(built, listOf(TrackerOverride("d1", hidden = true))).defenses.isEmpty(),
        )
    }

    @Test
    fun `a board with only defenses is not empty`() {
        val built = sheet(
            """{"_id":"d1","type":"damageMultiplier","name":"X","damageTypes":["fire"],"value":0.5}""",
        )
        assertFalse(TrackerEngine.build(built).isEmpty)
    }

    @Test
    fun `the live sheet resists radiant and necrotic, and only from the active feature`() {
        // Capture-coupled (skips without the private fixture). The capture holds two
        // damageMultiplier properties: an active radiant+necrotic 0.5, and a necrotic-only
        // 0.5 that is inactive + deactivatedByToggle. Only the first may reach the board.
        //
        // Asserted on shape rather than on the two feature names, which are private-capture
        // strings (tools/public-gate.sh). `single()` already proves the deactivated one did
        // not leak, and `damageTypes` proves the survivor is the *active* one rather than
        // the necrotic-only one — which is the whole claim.
        val defense = board.defenses.single()
        assertEquals(DefenseKind.RESISTANT, defense.kind)
        assertEquals(listOf("radiant", "necrotic"), defense.damageTypes)
    }

    @Test
    fun `the creature document's denormalized damageMultipliers rollup is not the source`() {
        // It is `{}` on the capture despite an active multiplier existing, which is why
        // discovery reads the properties. Pinned so nobody "simplifies" to the rollup.
        val rollup = Fixtures.sabrielSheet().creature?.get("damageMultipliers")
        assertEquals("{}", rollup.toString())
        assertTrue("discovery must not depend on the empty rollup", board.defenses.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // Rolls (FR-7) — read off the capture rather than off 03, like the defenses
    // -----------------------------------------------------------------------

    @Test
    fun `an ability check reads the modifier, never the score`() {
        // The bug this rule exists to prevent: `value`/`total` on an ability attribute are
        // the 3-20 score, and adding one of those to a d20 would be off by about ten.
        val built = sheet(
            """{"_id":"a1","type":"attribute","attributeType":"ability","name":"Dexterity",
                "variableName":"dexterity","total":13,"value":13,"modifier":1,
                "advantage":0,"order":106}""",
        )
        val roll = TrackerEngine.build(built).rolls.single()
        assertEquals("Dexterity", roll.name)
        assertEquals(1, roll.modifier)
        assertEquals(RollAdvantage.NONE, roll.advantage)
        assertEquals(106, roll.sortOrder)
    }

    @Test
    fun `an ability with no modifier field is not a roll we can answer`() {
        // Deriving it from the score would be this app re-implementing the sheet's own
        // arithmetic, and would silently drop everything the server folded into the real
        // number. No modifier, no row.
        val built = sheet(
            """{"_id":"a1","type":"attribute","attributeType":"ability","name":"Strength",
                "total":8,"value":8}""",
        )
        assertTrue(TrackerEngine.build(built).rolls.isEmpty())
    }

    @Test
    fun `a skill's value is the total, and its ingredients are not re-added`() {
        val built = sheet(
            """{"_id":"s1","type":"skill","skillType":"skill","name":"Acrobatics",
                "variableName":"acrobatics","ability":"dexterity",
                "abilityMod":1,"proficiency":1,"value":5,"order":70}""",
        )
        val roll = TrackerEngine.build(built).rolls.single()
        // 5, not 5 + 1 + 1: the server already totalled it.
        assertEquals(5, roll.modifier)
        assertEquals("Acrobatics", roll.name)
    }

    @Test
    fun `saves and checks are rolls, and proficiency-only kinds are not`() {
        fun namesFor(skillType: String, name: String): List<String> = TrackerEngine.build(
            sheet(
                """{"_id":"s1","type":"skill","skillType":"$skillType","name":"$name",
                    "abilityMod":0,"proficiency":1,"value":2}""",
            ),
        ).rolls.map { it.name }

        // Rolled with a d20…
        assertEquals(listOf("Wisdom Save"), namesFor("save", "Wisdom Save"))
        assertEquals(listOf("Initiative"), namesFor("check", "Initiative"))
        assertEquals(listOf("Tinker's Tools"), namesFor("tool", "Tinker's Tools"))
        assertEquals(listOf("Stealth"), namesFor("skill", "Stealth"))
        // …and the three that are a proficiency you *hold*. "Make a Common check" is not a
        // thing the sheet ever claimed, and the `value` on these is the proficiency bonus
        // that the property type carries whether or not it means anything.
        assertTrue(namesFor("language", "Common").isEmpty())
        assertTrue(namesFor("weapon", "Simple Melee Weapons").isEmpty())
        assertTrue(namesFor("armor", "Light Armor").isEmpty())
        // A kind nobody has seen yet is *offered* rather than dropped — see
        // TrackerEngine.NON_ROLL_SKILL_TYPES for why the filter is an exclusion list.
        assertEquals(listOf("Something New"), namesFor("somethingUnheardOf", "Something New"))
    }

    @Test
    fun `advantage is read from the sign, and its absence is not disadvantage`() {
        fun advantageFor(field: String): RollAdvantage = TrackerEngine.build(
            sheet(
                """{"_id":"s1","type":"skill","skillType":"skill","name":"Stealth",
                    "value":3$field}""",
            ),
        ).rolls.single().advantage

        assertEquals(RollAdvantage.NONE, advantageFor(""))
        assertEquals(RollAdvantage.NONE, advantageFor(""","advantage":0"""))
        assertEquals(RollAdvantage.ADVANTAGE, advantageFor(""","advantage":1"""))
        // A rollup, not a flag: two sources pushing the same way still reads as advantage.
        assertEquals(RollAdvantage.ADVANTAGE, advantageFor(""","advantage":2"""))
        assertEquals(RollAdvantage.DISADVANTAGE, advantageFor(""","advantage":-1"""))
    }

    @Test
    fun `an inactive or removed roll never reaches the dropdown`() {
        // The same blanket rule every other discovery rule uses: a save switched off by a
        // toggle is not something the character can be asked to roll.
        listOf(
            """{"_id":"s1","type":"skill","skillType":"save","name":"Death Save",
                "value":0,"inactive":true,"deactivatedByToggle":true}""",
            """{"_id":"s1","type":"skill","skillType":"skill","name":"Gone",
                "value":3,"removed":true}""",
            """{"_id":"a1","type":"attribute","attributeType":"ability","name":"Strength",
                "modifier":-1,"inactive":true}""",
        ).forEach { json ->
            assertTrue(json, TrackerEngine.build(sheet(json)).rolls.isEmpty())
        }
    }

    @Test
    fun `a nameless roll is dropped rather than offered as a blank line`() {
        listOf(
            """{"_id":"s1","type":"skill","skillType":"skill","value":3}""",
            """{"_id":"s1","type":"skill","skillType":"skill","name":"  ","value":3}""",
            """{"type":"skill","skillType":"skill","name":"No id","value":3}""",
        ).forEach { json ->
            assertTrue(json, TrackerEngine.build(sheet(json)).rolls.isEmpty())
        }
    }

    @Test
    fun `rolls keep the sheet's own order`() {
        val built = sheet(
            """{"_id":"s2","type":"skill","skillType":"skill","name":"Later","value":1,"order":90}""",
            """{"_id":"s1","type":"skill","skillType":"skill","name":"Earlier","value":2,"order":10}""",
            """{"_id":"s3","type":"skill","skillType":"skill","name":"Alpha","value":3,"order":10}""",
        )
        // order first, then name as the tie-breaker — never alphabetical overall.
        assertEquals(
            listOf("Alpha", "Earlier", "Later"),
            TrackerEngine.build(built).rolls.map { it.name },
        )
    }

    @Test
    fun `a hidden roll obeys the override layer like every other row`() {
        val built = sheet(
            """{"_id":"s1","type":"skill","skillType":"skill","name":"Stealth","value":3}""",
        )
        val hidden = TrackerEngine.build(built, listOf(TrackerOverride("s1", hidden = true)))
        assertTrue(hidden.rolls.isEmpty())
    }

    @Test
    fun `a board with only rolls is not empty`() {
        val built = sheet(
            """{"_id":"s1","type":"skill","skillType":"skill","name":"Stealth","value":3}""",
        )
        assertFalse(TrackerEngine.build(built).isEmpty)
    }

    @Test
    fun `the live sheet's rolls are the six abilities plus every rollable skill kind`() {
        // 6 ability checks + 26 skill-type properties that are actually rolled. The capture
        // holds 32 `skill` properties in total; the six that do not appear here are the
        // three language and two weapon proficiencies, and one save that is switched off.
        assertEquals(32, board.rolls.size)
        assertEquals(6, board.rolls.count { it.name in ABILITY_NAMES })
        assertEquals(6, board.rolls.count { it.name.endsWith(" Save") })
        assertTrue(board.rolls.none { it.name == "Death Save" })
        assertTrue(board.rolls.none { it.name == "Common" })
        // Every id is distinct, which is what lets the dropdown key on it.
        assertEquals(board.rolls.size, board.rolls.map { it.id }.toSet().size)
    }

    @Test
    fun `the live sheet's ability checks carry modifiers, not scores`() {
        // Two ends of the range on the capture: an 8 reads −1 and a 14 reads +2. If this
        // ever asserts 8 and 14, the rule has started reading `value`.
        assertEquals(-1, board.rolls.single { it.name == "Strength" }.modifier)
        assertEquals(2, board.rolls.single { it.name == "Intelligence" }.modifier)
    }

    @Test
    fun `no roll on the live sheet carries advantage, because the conditions are off`() {
        // Not a weak assertion about the capture — it is the mechanism working. The capture
        // holds several `operation: "disadvantage"` effects, every one of them belonging to
        // a condition buff that is switched off, so every computed rollup reads zero.
        assertTrue(board.rolls.all { it.advantage == RollAdvantage.NONE })
    }

    @Test
    fun `the live sheet's rolls come out in ascending sheet order`() {
        assertEquals(board.rolls.map { it.sortOrder }.sorted(), board.rolls.map { it.sortOrder })
    }

    @Test
    fun `an empty sheet produces an empty board rather than throwing`() {
        assertTrue(TrackerEngine.build(CreatureSheet.EMPTY).isEmpty)
    }

    @Test
    fun `malformed documents are skipped instead of crashing the board`() {
        val built = CreatureSheet.fromSnapshotJson(
            """{"creatureProperties":[
                 {"_id":"x1","type":"attribute","attributeType":"spellSlot","name":"Weird",
                  "reset":"longRest","total":"4","value":null},
                 {"type":"item","name":"No id","quantity":3}
               ]}""",
        )
        val board = TrackerEngine.build(built)
        // The id-less item cannot be a write target, so it is not offered at all.
        assertTrue(board.allItems.isEmpty())
        // A stringified total still parses.
        assertEquals(4, board.slots.single().total)
    }
}
