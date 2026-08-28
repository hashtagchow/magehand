package com.hashtagchow.magehand.ui.screens.characterhome.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader

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
}
