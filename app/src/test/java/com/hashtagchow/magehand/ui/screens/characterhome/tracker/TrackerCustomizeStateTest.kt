package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/** The customize sheet's state build and its override planner (04 §5). */
class TrackerCustomizeStateTest {

    private fun slot(id: String, name: String, level: Int) = TrackedResource(
        propertyId = id,
        kind = TrackerKind.SPELL_SLOT,
        name = name,
        value = 1,
        total = 2,
        spellSlotLevel = level,
    )

    private val board = TrackerBoard(
        slots = listOf(slot("s1", "1st Level", 1), slot("s2", "2nd Level", 2), slot("s3", "3rd Level", 3)),
        resources = listOf(TrackedResource("r1", TrackerKind.RESOURCE, "Heroic Inspiration", 0, 1)),
        allItems = listOf(
            TrackedResource("i1", TrackerKind.ITEM, "Gold piece", 109, 109),
            TrackedResource("i2", TrackerKind.ITEM, "Potion of Healing", 2, 2),
        ),
        activeToggles = listOf(
            ConditionToggle("t1", "Load Wizard Spells", enabled = true),
            // Off, and therefore the only kind of row the conditions pin exists for: the
            // tracker files it behind the "N inactive" expander until it is pinned.
            ConditionToggle("t2", "Bless", enabled = false, flippable = true),
        ),
    )

    // --- state build --------------------------------------------------------

    @Test
    fun `sections appear in the order the tracker lays them out`() {
        val state = toCustomizeState(board, overrides = emptyList(), accentColor = null)
        assertEquals(
            listOf(
                CustomizeSection.SPELL_SLOTS,
                CustomizeSection.RESOURCES,
                CustomizeSection.CONDITIONS,
            ),
            state.sections.map { it.section },
        )
    }

    @Test
    fun `an unpinned item is only in the picker, never a tracker row`() {
        val state = toCustomizeState(board, overrides = emptyList(), accentColor = null)
        assertTrue(state.sections.none { it.section == CustomizeSection.CONSUMABLES })
        assertEquals(listOf("Gold piece", "Potion of Healing"), state.items.map { it.name })
        assertTrue(state.items.none { it.pinned })
    }

    @Test
    fun `pinning an item promotes it into the consumables section`() {
        val state = toCustomizeState(
            board,
            overrides = listOf(TrackerOverride("i2", pinned = true)),
            accentColor = null,
        )
        val consumables = state.sections.single { it.section == CustomizeSection.CONSUMABLES }
        assertEquals(listOf("Potion of Healing"), consumables.rows.map { it.name })
        assertTrue(state.items.single { it.propertyId == "i2" }.pinned)
    }

    @Test
    fun `a hidden row leaves its section and joins the hidden list`() {
        val state = toCustomizeState(
            board,
            overrides = listOf(TrackerOverride("s2", hidden = true)),
            accentColor = null,
        )
        val slots = state.sections.single { it.section == CustomizeSection.SPELL_SLOTS }
        assertEquals(listOf("1st Level", "3rd Level"), slots.rows.map { it.name })
        assertEquals(listOf("2nd Level"), state.hidden.map { it.name })
        assertTrue(state.hasHiddenRows)
    }

    @Test
    fun `rows carry a detail line so two same-named rows are distinguishable`() {
        val state = toCustomizeState(board, overrides = emptyList(), accentColor = null)
        assertEquals(
            "1 / 2",
            state.sections.first().rows.first().detail,
        )
    }

    // --- the conditions pin (the sheet's only control for `shownByDefault`) ---

    /**
     * The sheet lists every discovered toggle, on or off, and each carries the pin state
     * the row's control has to render. Without this the checkbox would have nothing
     * truthful to draw itself from.
     */
    @Test
    fun `condition rows carry their pin, on or off`() {
        val state = toCustomizeState(
            board,
            overrides = listOf(TrackerOverride("t2", pinned = true)),
            accentColor = null,
        )
        val conditions = state.sections.single { it.section == CustomizeSection.CONDITIONS }
        assertEquals(listOf("Load Wizard Spells", "Bless"), conditions.rows.map { it.name })
        assertEquals("Off", conditions.rows.single { it.propertyId == "t2" }.detail)
        assertTrue(conditions.rows.single { it.propertyId == "t2" }.pinned)
        assertFalse(conditions.rows.single { it.propertyId == "t1" }.pinned)
    }

    /**
     * The whole point of the control, end to end and through the real engine: pinning an
     * **off** condition in the customize sheet promotes it out of the "N inactive"
     * expander and onto the tracker's main chip row, and un-pinning puts it back.
     *
     * This is the claim that had no way of being true before WP8's review — the override
     * was readable, persistable and described in three KDocs, and the only control that
     * could write one was the item picker, which never lists a condition. Asserted across
     * all three layers (planner → engine → UI state) because each of them was individually
     * correct while the feature as a whole did not exist.
     */
    @Test
    fun `pinning an off condition moves it out of the inactive expander, and un-pinning returns it`() {
        val sheet = CreatureSheet.fromSnapshotJson(TOGGLE_SHEET)

        fun conditionsFor(overrides: List<TrackerOverride>): TrackerUiState =
            toTrackerUiState(
                creatureId = "FakeCreature23456",
                board = TrackerEngine.build(sheet, overrides),
                connection = ConnectionState.LIVE,
                lastSyncedAt = null,
                isShowingSnapshot = false,
            )

        // Default: off means filed away.
        val before = conditionsFor(emptyList())
        assertEquals(listOf("Bless"), before.conditions.map { it.name })
        assertEquals(listOf("Load Wizard Spells"), before.inactiveConditions.map { it.name })

        // What the sheet's checkbox does — the same planner call the item picker makes.
        val pinned = listOf(TrackerOverridePlan.setPinned(emptyList(), "t2", pinned = true))
        assertTrue(pinned.single().pinned)
        val after = conditionsFor(pinned)
        assertEquals(listOf("Bless", "Load Wizard Spells"), after.conditions.map { it.name })
        assertTrue(after.inactiveConditions.isEmpty())

        // And back. Un-checking is a write, not a delete, so the row persists unpinned.
        val unpinned = listOf(TrackerOverridePlan.setPinned(pinned, "t2", pinned = false))
        assertFalse(unpinned.single().pinned)
        val restored = conditionsFor(unpinned)
        assertEquals(listOf("Bless"), restored.conditions.map { it.name })
        assertEquals(listOf("Load Wizard Spells"), restored.inactiveConditions.map { it.name })
    }

    /**
     * Pinning is not hiding's opposite and must not become it: a pinned condition that is
     * also hidden stays hidden, because the engine drops hidden rows before
     * `shownByDefault` is ever consulted.
     */
    @Test
    fun `a hidden condition stays hidden even when it is pinned`() {
        val sheet = CreatureSheet.fromSnapshotJson(TOGGLE_SHEET)
        val overrides = listOf(TrackerOverride("t2", pinned = true, hidden = true))
        val board = TrackerEngine.build(sheet, overrides)
        assertTrue(board.activeToggles.none { it.propertyId == "t2" })
    }

    // --- the planner --------------------------------------------------------

    @Test
    fun `hiding a row with no existing override creates one`() {
        val override = TrackerOverridePlan.setHidden(emptyList(), "s1", hidden = true)
        assertEquals(TrackerOverride("s1", hidden = true), override)
    }

    @Test
    fun `hiding preserves a pin that is already set`() {
        val existing = listOf(TrackerOverride("i1", pinned = true, sortIndex = 3))
        val override = TrackerOverridePlan.setHidden(existing, "i1", hidden = true)
        assertTrue(override.pinned)
        assertTrue(override.hidden)
        assertEquals(3, override.sortIndex)
    }

    @Test
    fun `pinning preserves an existing sort index`() {
        val existing = listOf(TrackerOverride("i1", sortIndex = 2))
        assertEquals(2, TrackerOverridePlan.setPinned(existing, "i1", pinned = true).sortIndex)
    }

    @Test
    fun `a move re-indexes the whole section, not just the moved row`() {
        val rows = TrackerOverridePlan.reorder(
            current = emptyList(),
            sectionOrder = listOf("s1", "s2", "s3"),
            propertyId = "s3",
            delta = -1,
        )
        assertEquals(listOf("s1", "s3", "s2"), rows.map { it.propertyId })
        assertEquals(listOf(0, 1, 2), rows.map { it.sortIndex })
    }

    @Test
    fun `a move off the top of the list is not a write`() {
        assertTrue(
            TrackerOverridePlan.reorder(emptyList(), listOf("s1", "s2"), "s1", delta = -1).isEmpty(),
        )
        assertTrue(
            TrackerOverridePlan.reorder(emptyList(), listOf("s1", "s2"), "s2", delta = 1).isEmpty(),
        )
        assertTrue(
            TrackerOverridePlan.reorder(emptyList(), listOf("s1", "s2"), "nope", delta = 1).isEmpty(),
        )
    }

    @Test
    fun `a move preserves the pin and hide flags of every row it re-indexes`() {
        val current = listOf(
            TrackerOverride("s1", pinned = true),
            TrackerOverride("s2", hidden = true),
        )
        val rows = TrackerOverridePlan.reorder(current, listOf("s1", "s2", "s3"), "s1", delta = 2)
        assertEquals(listOf("s2", "s3", "s1"), rows.map { it.propertyId })
        assertTrue(rows.single { it.propertyId == "s1" }.pinned)
        assertTrue(rows.single { it.propertyId == "s2" }.hidden)
        assertFalse(rows.single { it.propertyId == "s3" }.pinned)
    }

    /**
     * The end-to-end claim the customize sheet actually makes: a plan produced here,
     * fed back through the real `TrackerEngine`, reorders the real board.
     *
     * Without this the two halves could each be "correct" and still disagree — the
     * planner writes indices, the engine reads them, and nothing else checks they mean
     * the same thing.
     */
    @Test
    fun `a planned reorder is what the engine renders`() {
        val sheet = CreatureSheet.fromSnapshotJson(SLOT_SHEET)
        val natural = TrackerEngine.build(sheet).slots.map { it.name }
        assertEquals(listOf("1st Level", "2nd Level", "3rd Level"), natural)

        val ids = TrackerEngine.build(sheet).slots.map { it.propertyId }
        val plan = TrackerOverridePlan.reorder(emptyList(), ids, ids.last(), delta = -2)

        val reordered = TrackerEngine.build(sheet, plan).slots.map { it.name }
        assertEquals(listOf("3rd Level", "1st Level", "2nd Level"), reordered)
    }

    @Test
    fun `a planned hide is what the engine drops`() {
        val sheet = CreatureSheet.fromSnapshotJson(SLOT_SHEET)
        val second = TrackerEngine.build(sheet).slots[1]
        val plan = listOf(TrackerOverridePlan.setHidden(emptyList(), second.propertyId, true))

        assertEquals(
            listOf("1st Level", "3rd Level"),
            TrackerEngine.build(sheet, plan).slots.map { it.name },
        )
    }

    private companion object {
        val SLOT_SHEET = """
            {"creatures":[{"_id":"c1","name":"Test"}],
             "creatureProperties":[
               {"_id":"s1","type":"attribute","attributeType":"spellSlot","name":"1st Level",
                "value":3,"total":4,"reset":"longRest","spellSlotLevel":1,"order":10},
               {"_id":"s2","type":"attribute","attributeType":"spellSlot","name":"2nd Level",
                "value":1,"total":2,"reset":"longRest","spellSlotLevel":2,"order":11},
               {"_id":"s3","type":"attribute","attributeType":"spellSlot","name":"3rd Level",
                "value":1,"total":1,"reset":"longRest","spellSlotLevel":3,"order":12}
             ],
             "creatureVariables":[]}
        """.trimIndent()

        /** One on, one off — the two halves `ConditionToggle.shownByDefault` splits. */
        val TOGGLE_SHEET = """
            {"creatures":[{"_id":"c1","name":"Test"}],
             "creatureProperties":[
               {"_id":"t1","type":"toggle","name":"Bless","enabled":true,"order":10},
               {"_id":"t2","type":"toggle","name":"Load Wizard Spells","disabled":true,
                "inactive":true,"order":11}
             ],
             "creatureVariables":[]}
        """.trimIndent()
    }
}
