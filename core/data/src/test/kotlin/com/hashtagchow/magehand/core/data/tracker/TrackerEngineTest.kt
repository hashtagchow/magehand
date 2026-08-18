package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * The WP4 acceptance bar for discovery, against the committed live capture, plus the
 * synthetic cases the capture cannot exercise (it contains no temp HP, no concentration
 * and no `showUI` toggle).
 */
class TrackerEngineTest {

    private val board by lazy { TrackerEngine.build(Fixtures.sabrielSheet()) }

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
