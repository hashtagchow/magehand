package com.hashtagchow.magehand.ui.screens.characterhome.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.UseTarget

/**
 * The Actions surface's sectioning, search and collapse (16 decisions 3, 6 and 9).
 *
 * `:app` has no Compose test harness, so this is where the surface's behaviour is actually
 * asserted — everything below would otherwise exist only as a branch inside a `@Composable` that
 * nothing could reach.
 */
class ActionsUiStateTest {

    private fun spell(name: String, level: Int, order: Int = 0) =
        SpellEntry(propertyId = "s-$name", name = name, level = level, sortOrder = order)

    private fun action(name: String, type: ActionType, order: Int = 0) =
        ActionEntry(propertyId = "a-$name", name = name, type = type, sortOrder = order)

    /** A board already ordered the way `ActionEngine` orders one. */
    private val board = ActionBoard(
        spells = listOf(
            spell("Light", 0),
            spell("Mage Hand", 0),
            spell("Magic Missile", 1),
            spell("Fireball", 3),
        ),
        actions = listOf(
            action("Dagger", ActionType.ATTACK),
            action("Dash", ActionType.ACTION),
            action("Second Wind", ActionType.BONUS),
            action("Shield", ActionType.REACTION),
            action("Speak", ActionType.FREE),
        ),
        spellLists = listOf(SpellListHeader(propertyId = "l1", name = "Wizard", dc = 15, abilityMod = 4)),
    )

    private fun state(query: String = "", collapsed: Set<String> = emptySet()) =
        toActionsUiState("c1", board, query = query, collapsedKeys = collapsed)

    // -----------------------------------------------------------------------
    // Decision 3 — sections
    // -----------------------------------------------------------------------

    @Test
    fun `spell levels become sections with cantrips first and actions follow`() {
        val sections = state().sections

        assertEquals(
            listOf("spell:0", "spell:1", "spell:3", "group:ATTACKS", "group:ACTIONS",
                "group:BONUS", "group:REACTIONS", "group:OTHER"),
            sections.map { it.key },
        )
        assertEquals(
            "level 0 is 'Cantrips', never 'Level 0'",
            ActionSectionTitle.Cantrips,
            sections.first().title,
        )
        assertEquals(ActionSectionTitle.SpellLevel(1), sections[1].title)
        assertEquals(ActionSectionTitle.Group(ActionGroup.ATTACKS), sections[3].title)
    }

    @Test
    fun `a section keeps the board's row order`() {
        assertEquals(
            listOf("Light", "Mage Hand"),
            state().sections.first().rows.map { it.name },
        )
    }

    /** Levels with no spells produce no empty header. */
    @Test
    fun `absent spell levels produce no sections`() {
        val keys = state().sections.map { it.key }
        assertFalse("spell:2" in keys)
        assertFalse("spell:4" in keys)
    }

    // -----------------------------------------------------------------------
    // Collapse
    // -----------------------------------------------------------------------

    @Test
    fun `a collapsed section keeps its rows and is only marked collapsed`() {
        val section = state(collapsed = setOf("spell:0")).sections.first()

        assertTrue(section.collapsed)
        assertEquals(
            "collapse decides whether the rows are DRAWN, not whether they exist — the match " +
                "count and the header's own summary both still need them",
            2,
            section.rows.size,
        )
    }

    @Test
    fun `collapsing one section leaves the others open`() {
        val sections = state(collapsed = setOf("spell:1")).sections
        assertEquals(
            listOf(false, true, false, false, false, false, false, false),
            sections.map { it.collapsed },
        )
    }

    // -----------------------------------------------------------------------
    // Decision 6 — search
    // -----------------------------------------------------------------------

    @Test
    fun `the filter keeps matching rows with their headers`() {
        val shown = state(query = "ma")

        assertEquals(
            "a match keeps the section that explains where it sits",
            listOf("spell:0", "spell:1"),
            shown.sections.map { it.key },
        )
        assertEquals(listOf("Mage Hand"), shown.sections[0].rows.map { it.name })
        assertEquals(listOf("Magic Missile"), shown.sections[1].rows.map { it.name })
        assertEquals(2, shown.matchCount)
    }

    @Test
    fun `the filter is case insensitive and matches a substring`() {
        assertEquals(1, state(query = "FIREB").matchCount)
        assertEquals(1, state(query = "ireba").matchCount)
    }

    @Test
    fun `the filter searches actions as well as spells`() {
        // "sh" matches Da*sh* and *Sh*ield — a substring, not a prefix, and across two groups.
        val shown = state(query = "sh")

        assertEquals(listOf("group:ACTIONS", "group:REACTIONS"), shown.sections.map { it.key })
        assertEquals(listOf("Dash", "Shield"), shown.sections.flatMap { it.rows.map { r -> r.name } })
        assertEquals(0, shown.sections.count { it.key.startsWith("spell:") })
    }

    /**
     * Decision 6: an active filter **expands** collapsed sections.
     *
     * Without this, searching would report matches under headers showing nothing — the control
     * appearing broken at the moment it worked.
     */
    @Test
    fun `an active filter forces collapsed sections open`() {
        val shown = state(query = "ma", collapsed = setOf("spell:0", "spell:1"))
        assertTrue("no section stays folded while a search is running",
            shown.sections.none { it.collapsed })
    }

    /**
     * ...and clearing the query restores the collapse state exactly, because the filter never
     * wrote to it.
     */
    @Test
    fun `clearing the query restores the collapse state exactly`() {
        val collapsed = setOf("spell:0", "group:ATTACKS")
        val before = state(collapsed = collapsed)
        val during = state(query = "ma", collapsed = collapsed)
        val after = state(collapsed = collapsed)

        assertTrue(during.sections.none { it.collapsed })
        assertEquals(before, after)
    }

    @Test
    fun `a query that matches nothing yields the honest no-match state`() {
        val shown = state(query = "zzz")

        assertTrue(shown.showsNoMatches)
        assertEquals(0, shown.matchCount)
        assertFalse(
            "an empty RESULT is not an empty CHARACTER — the two states say different things " +
                "and offer different fixes",
            shown.isEmpty,
        )
        assertEquals("the query is carried back so the message can print it", "zzz", shown.query)
    }

    @Test
    fun `a blank query is not an active filter`() {
        val shown = state(query = "   ")
        assertFalse(shown.filterActive)
        assertEquals(9, shown.matchCount)
    }

    // -----------------------------------------------------------------------
    // Decision 6 — the threshold
    // -----------------------------------------------------------------------

    @Test
    fun `the filter field appears only once the combined list reaches fifteen`() {
        fun boardOf(rows: Int) = ActionBoard(
            spells = (1..rows).map { spell("S$it", 1, it) },
        )

        assertFalse(toActionsUiState("c1", boardOf(14)).showsFilter)
        assertTrue(toActionsUiState("c1", boardOf(15)).showsFilter)
        assertEquals(15, ActionsUiState.FILTER_THRESHOLD)
    }

    /**
     * The threshold reads the **unfiltered** count.
     *
     * Otherwise typing a query that matches two rows would drop the row count under fifteen and
     * remove the field being typed into.
     */
    @Test
    fun `a narrow query does not remove the field being typed into`() {
        val big = ActionBoard(spells = (1..20).map { spell("Spell$it", 1, it) })
        val shown = toActionsUiState("c1", big, query = "Spell1")

        assertTrue(shown.matchCount < ActionsUiState.FILTER_THRESHOLD)
        assertTrue("the field survives its own success", shown.showsFilter)
    }

    // -----------------------------------------------------------------------
    // Decision 9 / decision 1 — the honest empty state
    // -----------------------------------------------------------------------

    @Test
    fun `an empty board is the empty state`() {
        val shown = toActionsUiState("c1", ActionBoard.EMPTY)

        assertTrue(shown.isEmpty)
        assertFalse(shown.showsNoMatches)
        assertTrue(shown.sections.isEmpty())
    }

    /** The spell-list header rides through untouched — it is not a section and not a row. */
    @Test
    fun `spell list headers are carried and are not rows`() {
        val shown = state()
        assertEquals(listOf("Wizard"), shown.spellLists.map { it.name })
        assertEquals(15, shown.spellLists.single().dc)
        assertEquals(9, shown.matchCount)
    }

    // -----------------------------------------------------------------------
    // withView
    // -----------------------------------------------------------------------

    /**
     * `withView` is what the screen calls on every keystroke; `toActionsUiState` routes through
     * it. Both paths must agree, or the ViewModel and the composable would disagree about the
     * same query.
     */
    @Test
    fun `withView and toActionsUiState agree`() {
        val fromMapper = toActionsUiState("c1", board, query = "ma", collapsedKeys = setOf("spell:3"))
        val fromView = toActionsUiState("c1", board).withView("ma", setOf("spell:3"))
        assertEquals(fromMapper, fromView)
    }

    @Test
    fun `withView with nothing applied is the identity`() {
        val base = toActionsUiState("c1", board)
        assertEquals(base, base.withView("", emptySet()))
    }

    // =======================================================================
    // FR-28 — the detail sheet and the Use affordance
    // (docs/design/17-use-action.md decisions 1, 2, 3 and 5)
    // =======================================================================

    /**
     * A prepared, funded row resolves to a detail state carrying a Use — and the target is
     * `UseTarget`, not an id.
     *
     * The type is the assertion. Everything downstream of this — the button, the dialog, the
     * ViewModel intent — takes a `UseTarget`, so a row that produced one has passed 17 decision
     * 2's gate by construction rather than by a check somebody remembered to write.
     */
    @Test
    fun `a usable row's detail carries a use target`() {
        val entry = ActionEntry(
            propertyId = "a1",
            name = "Rage",
            type = ActionType.BONUS,
            cost = ActionCost(attributes = listOf(CostLine("Rage", amount = 1, available = 3))),
            uses = ActionUses(max = 3, used = 1),
        )
        val detail = toActionsUiState("c1", ActionBoard(actions = listOf(entry)), canWrite = true)
            .detailFor("a1")

        assertNotNull(detail)
        assertEquals("Rage", detail!!.name)
        assertEquals(2, detail.uses?.remaining)
        assertEquals(1, detail.cost.attributes.single().amount)
        assertNotNull(detail.use)
        assertTrue(detail.use!!.enabled)
        assertNull("a row with a Use explains nothing — it offers one", detail.unusableReason)
    }

    /**
     * **THE STRUCTURAL TRAP** (decision 2): an unprepared or switched-off row exposes NO use
     * path through this seam.
     *
     * `use == null` is the whole claim. The composable draws the button inside a `?.let`, so
     * there is nothing to press; and because [UseAffordance] can only be built from a non-null
     * `useTarget`, there is no way for a later edit to this file to produce one for these rows
     * either. What the sheet gets instead is a *sentence*, which decision 2 asks for in as many
     * words — a missing button explains nothing.
     */
    @Test
    fun `unprepared and switched-off rows offer no use, and say why`() {
        val board = ActionBoard(
            spells = listOf(
                SpellEntry(propertyId = "s-unprepared", name = "Bless", level = 1),
                SpellEntry(propertyId = "s-inactive", name = "Shield", level = 1, prepared = true, inactive = true),
            ),
            actions = listOf(
                ActionEntry(propertyId = "a-inactive", name = "Rage", type = ActionType.BONUS, inactive = true),
                ActionEntry(
                    propertyId = "a-broke",
                    name = "Volley",
                    type = ActionType.ACTION,
                    cost = ActionCost(items = listOf(CostLine("Arrows", amount = 3, available = 0))),
                ),
                ActionEntry(
                    propertyId = "a-spent",
                    name = "Second Wind",
                    type = ActionType.BONUS,
                    // The lagging rollup still claims one is left; the pair says otherwise.
                    usesLeft = 1,
                    uses = ActionUses(max = 1, used = 1),
                ),
            ),
        )
        val state = toActionsUiState("c1", board, canWrite = true)

        val expected = mapOf(
            "s-unprepared" to UnusableReason.UNPREPARED,
            "s-inactive" to UnusableReason.INACTIVE,
            "a-inactive" to UnusableReason.INACTIVE,
            "a-broke" to UnusableReason.NO_RESOURCES,
            "a-spent" to UnusableReason.NO_USES,
        )
        for ((id, reason) in expected) {
            val detail = state.detailFor(id)!!
            assertNull("$id must expose no use path at all", detail.use)
            assertEquals("$id must say why", reason, detail.unusableReason)
        }
    }

    /**
     * Decision 3's picker, derived at the detail sheet from the live tracker rows.
     *
     * The depleted level-4 slot and the too-small level-1 slot are both absent — the trap
     * `UseTargetTest` pins on the derivation, re-checked here on the path the UI actually takes,
     * because a state that forgot to pass `spellSlots` through would have an empty picker and no
     * test would have noticed.
     */
    @Test
    fun `the detail sheet's slot picker offers only legal slots`() {
        val spell = SpellEntry(propertyId = "s1", name = "Fireball", level = 3, prepared = true)
        val state = toActionsUiState(
            "c1",
            ActionBoard(spells = listOf(spell)),
            spellSlots = listOf(
                slotRow("l1", level = 1, remaining = 4),
                slotRow("l3", level = 3, remaining = 2),
                slotRow("l4", level = 4, remaining = 0),
                slotRow("l5", level = 5, remaining = 1),
            ),
            canWrite = true,
        )

        val use = state.detailFor("s1")!!.use!!
        assertTrue("a leveled spell draws the picker", use.showsSlotPicker)
        assertEquals(listOf("l3", "l5"), use.slots.map { it.propertyId })
        assertEquals("the dialog opens on the cheapest legal slot", "l3", use.defaultSlotId)
    }

    /** A cantrip skips the picker entirely — there is no upcast decision to make. */
    @Test
    fun `a cantrip draws no slot picker`() {
        val state = toActionsUiState(
            "c1",
            ActionBoard(spells = listOf(SpellEntry(propertyId = "s0", name = "Light", level = 0, prepared = true))),
            spellSlots = listOf(slotRow("l1", level = 1, remaining = 4)),
            canWrite = true,
        )
        val use = state.detailFor("s0")!!.use!!
        assertFalse(use.showsSlotPicker)
        assertTrue(use.slots.isEmpty())
    }

    /**
     * A leveled spell with nothing left to cast it with still offers the Use, with an empty
     * picker — the row is not gated (B1 [architect ruling]: it is the dialog's own Confirm that
     * refuses, not this affordance; see [confirmDisabled below]).
     */
    @Test
    fun `a spell with no slots left still offers the use, with an empty picker`() {
        val state = toActionsUiState(
            "c1",
            ActionBoard(spells = listOf(SpellEntry(propertyId = "s1", name = "Fireball", level = 3, prepared = true))),
            spellSlots = listOf(slotRow("l1", level = 1, remaining = 4)),
            canWrite = true,
        )
        val use = state.detailFor("s1")!!.use!!
        assertNotNull(use)
        assertTrue(use.showsSlotPicker)
        assertTrue(use.slots.isEmpty())
        assertNull(use.defaultSlotId)
    }

    /**
     * B1 [architect ruling]: `confirmDisabled` is the one place this rule lives, pure so it is
     * testable without the Compose harness `:app` does not have. Four cases: empty picker blocks
     * (unless ritual, which needs no slot at all), and every other combination does not,
     * matching the PIN's own words — "non-empty unchanged".
     */
    @Test
    fun `confirmDisabled is true only for an empty picker on a non-ritual leveled spell`() {
        val emptyPicker = state("s1", level = 3, ritual = false, slotsLeft = false)
        val nonEmptyPicker = state("s1", level = 3, ritual = false, slotsLeft = true)
        val emptyButRitual = state("s1", level = 3, ritual = true, slotsLeft = false)
        val cantrip = state("s0", level = 0, ritual = false, slotsLeft = false)

        assertTrue("empty picker, not ritual: Confirm must be disabled", emptyPicker.confirmDisabled(false))
        assertFalse("slots present: Confirm is unaffected", nonEmptyPicker.confirmDisabled(false))
        assertFalse("ritual needs no slot: an empty picker is moot", emptyButRitual.confirmDisabled(true))
        assertFalse("a cantrip draws no picker at all", cantrip.confirmDisabled(false))
    }

    private fun state(id: String, level: Int, ritual: Boolean, slotsLeft: Boolean): UseAffordance {
        val spell = SpellEntry(propertyId = id, name = "Spell", level = level, prepared = true, ritual = ritual)
        val board = toActionsUiState(
            "c1",
            ActionBoard(spells = listOf(spell)),
            spellSlots = if (slotsLeft) listOf(slotRow("l$level", level = level, remaining = 1)) else emptyList(),
            canWrite = true,
        )
        return board.detailFor(id)!!.use!!
    }

    /** The ritual checkbox is drawn only where the sheet marks the spell as one. */
    @Test
    fun `the ritual checkbox follows the sheet's own flag`() {
        fun useFor(ritual: Boolean) = toActionsUiState(
            "c1",
            ActionBoard(spells = listOf(SpellEntry("s1", "Detect Magic", level = 1, ritual = ritual, prepared = true))),
            canWrite = true,
        ).detailFor("s1")!!.use!!

        assertTrue(useFor(ritual = true).showsRitual)
        assertFalse(useFor(ritual = false).showsRitual)
    }

    /**
     * Decision 5's single-flight and 04's offline rule, both expressed as `enabled` rather than
     * as absence.
     *
     * The distinction is the point: "not right now" is a disabled control, "not on this row, in
     * this state" is an absent one. Conflating them is how a player ends up tapping a greyed
     * button waiting for it to start working.
     */
    @Test
    fun `a use in flight or offline is disabled but still present`() {
        val board = ActionBoard(actions = listOf(ActionEntry("a1", "Dash", ActionType.ACTION)))

        val busy = toActionsUiState("c1", board, usesInFlight = setOf("a1"), canWrite = true)
            .detailFor("a1")!!.use!!
        assertTrue("in flight is a disabled button, not a missing one", busy.inFlight)
        assertFalse(busy.enabled)

        val offline = toActionsUiState("c1", board, canWrite = false).detailFor("a1")!!.use!!
        assertFalse(offline.canWrite)
        assertFalse(offline.enabled)

        val ready = toActionsUiState("c1", board, canWrite = true).detailFor("a1")!!.use!!
        assertTrue(ready.enabled)
    }

    /** The latch is per row: one busy use does not disable every other row's button. */
    @Test
    fun `only the row that is in flight is disabled`() {
        val state = toActionsUiState(
            "c1",
            ActionBoard(
                actions = listOf(
                    ActionEntry("a1", "Rage", ActionType.BONUS),
                    ActionEntry("a2", "Dash", ActionType.ACTION),
                ),
            ),
            usesInFlight = setOf("a1"),
            canWrite = true,
        )
        assertFalse(state.detailFor("a1")!!.use!!.enabled)
        assertTrue(state.detailFor("a2")!!.use!!.enabled)
    }

    /**
     * A row that has left the list resolves to `null`, and the screen closes the sheet on it.
     *
     * Reachable two ways: a filter that no longer matches the open row, and a property the server
     * soft-removed while the sheet was up. Freezing the last frame instead would leave a Use
     * button on a row that is no longer on the character.
     */
    @Test
    fun `a row that leaves the list has no detail`() {
        val state = toActionsUiState("c1", board, query = "fireball")
        assertNull("a filtered-out row closes its own detail", state.detailFor("a-Dash"))
        assertNull(state.detailFor(null))
        assertNull(state.detailFor("nothing-like-this"))
    }

    /** The body prefers the rules text over DiceCloud's own one-line gloss of it. */
    @Test
    fun `the detail body prefers description over summary`() {
        val withBoth = ActionEntry("a1", "Dash", ActionType.ACTION, description = "long", summary = "short")
        val summaryOnly = ActionEntry("a2", "Dodge", ActionType.ACTION, summary = "short")
        val neither = ActionEntry("a3", "Hide", ActionType.ACTION, description = "   ")
        val state = toActionsUiState("c1", ActionBoard(actions = listOf(withBoth, summaryOnly, neither)))

        assertEquals("long", state.detailFor("a1")!!.body)
        assertEquals("short", state.detailFor("a2")!!.body)
        assertNull("blank is absent, never an empty paragraph", state.detailFor("a3")!!.body)
    }

    // -----------------------------------------------------------------------
    // FR-29 decision 4 — the confirm dialog's one difference between the two paths
    // -----------------------------------------------------------------------

    /**
     * *"Confirm dialog: lighter than the server's — cost + uses-after, **NO no-undo line** (undo
     * exists; saying otherwise would lie)."*
     *
     * The flag reaches the affordance, which is where `ActionDetailSheet` reads it to decide
     * whether to draw `action_use_no_undo`. Both directions, because the default is the one that
     * ships to a DiceCloud character and getting it backwards would either warn falsely on a local
     * use or — far worse — omit the warning from a `doAction` that posts to a party feed and a
     * Discord webhook with no inverse of any kind (probe U4).
     *
     * It deliberately does **not** touch `enabled`: a use is confirmed before it happens on both
     * paths, because a dialog that appeared only for the irreversible case would teach the player
     * that no dialog means no consequences.
     */
    @Test
    fun `a local character's use is marked undoable and a DiceCloud one is not`() {
        val entry = ActionEntry("a1", "Enter Rage", ActionType.ACTION)
        val actions = ActionBoard(actions = listOf(entry))

        val server = toActionsUiState("c1", actions, canWrite = true).detailFor("a1")!!.use!!
        assertFalse("the server path warns, and must keep warning", server.undoable)
        assertTrue(server.enabled)

        val local = toActionsUiState("c1", actions, canWrite = true, usesAreUndoable = true)
            .detailFor("a1")!!.use!!
        assertTrue(local.undoable)
        assertTrue("the flag is about the warning line, not about the button", local.enabled)
    }

    /** The default is the cautious direction — a screen that forgets the flag over-warns. */
    @Test
    fun `undoable defaults to false`() {
        assertFalse(ActionsUiState().usesAreUndoable)
        assertFalse(UseAffordance(target = UseTarget.Action("a", "A", ActionCost.FREE, null)).undoable)
    }

    /** A slot row as the tracker board hands one up. See `spellSlotOptions`. */
    private fun slotRow(id: String, level: Int, remaining: Int, total: Int = 4) = TrackedResource(
        propertyId = id,
        kind = TrackerKind.SPELL_SLOT,
        name = "Level $level",
        value = remaining,
        total = total,
        spellSlotLevel = level,
    )
}
