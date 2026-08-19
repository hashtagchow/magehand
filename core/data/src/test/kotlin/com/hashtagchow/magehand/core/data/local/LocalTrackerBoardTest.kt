package com.hashtagchow.magehand.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind

/**
 * The local board, built from stored rows.
 *
 * Plain JUnit, no Robolectric: [LocalTrackerBoard] is pure, and a board test that needed a
 * database and a coroutine scope to run would be testing the plumbing rather than the rule.
 * That is the same bargain `TrackerEngineTest` makes for the server path.
 *
 * The claim under test is the one 09 decision 5 rests on: **a local character produces the
 * same [TrackerBoard] the tracker already renders** — same types, same fields, same grouping —
 * so the screen needs no branch for it.
 */
class LocalTrackerBoardTest {

    private fun character(
        maxHp: Int = 20,
        currentHp: Int = maxHp,
        level: Int? = 3,
    ) = LocalCharacter(
        id = "local-1",
        name = "Brambles",
        level = level,
        abilities = AbilityScores(strength = 8, dexterity = 14, constitution = 15),
        maxHp = maxHp,
        currentHp = currentHp,
        armorClass = 15,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun row(
        id: String,
        kind: LocalRowKind,
        label: String = "row-$id",
        total: Int = 4,
        current: Int = total,
        reset: ResetRule? = null,
        sortIndex: Int = 0,
    ) = LocalTrackerRow(
        id = id,
        characterId = "local-1",
        kind = kind,
        label = label,
        total = total,
        current = current,
        reset = reset,
        sortIndex = sortIndex,
    )

    @Test
    fun `a missing character renders the empty board rather than a headless one`() {
        val board = LocalTrackerBoard.build(null, listOf(row("r-1", LocalRowKind.SLOT)))

        assertEquals(TrackerBoard.EMPTY, board)
        assertTrue(board.isEmpty)
    }

    @Test
    fun `hit points become the first row, from maxHp and currentHp`() {
        val board = LocalTrackerBoard.build(character(maxHp = 24, currentHp = 9), emptyList())

        with(board.hp!!) {
            assertEquals(TrackerKind.HIT_POINTS, kind)
            assertEquals(LocalTrackerBoard.HP_ROW_ID, propertyId)
            assertEquals(LocalTrackerBoard.HP_ROW_NAME, name)
            assertEquals(9, value)
            assertEquals(24, total)
            assertNull("HP has no reset rule locally", reset)
        }
    }

    @Test
    fun `the HP row keeps its identity across rebuilds`() {
        val first = LocalTrackerBoard.build(character(currentHp = 20), emptyList()).hp!!
        val second = LocalTrackerBoard.build(character(currentHp = 4), emptyList()).hp!!

        assertEquals(
            "a changing id would look like a different row appearing",
            first.propertyId,
            second.propertyId,
        )
    }

    @Test
    fun `each kind lands in its own section`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(
                row("s-1", LocalRowKind.SLOT, label = "1st Level", total = 4, current = 2, sortIndex = 0),
                row("res-1", LocalRowKind.RESOURCE, label = "Rage", total = 3, current = 3, sortIndex = 1),
                row("i-1", LocalRowKind.ITEM, label = "Healing Potion", total = 2, current = 2, sortIndex = 2),
            ),
        )

        assertEquals(listOf("1st Level"), board.slots.map { it.name })
        assertEquals(TrackerKind.SPELL_SLOT, board.slots.single().kind)
        assertEquals(listOf("Rage"), board.resources.map { it.name })
        assertEquals(TrackerKind.RESOURCE, board.resources.single().kind)
        assertEquals(listOf("Healing Potion"), board.allItems.map { it.name })
        assertEquals(TrackerKind.ITEM, board.allItems.single().kind)
    }

    /**
     * The consumables section renders `pinnedItems`, not `allItems` (see `TrackerUiState`).
     * Every local item was typed in by the player, so every one of them belongs there —
     * unlike the server path, which discovers hundreds and pins a handful.
     */
    @Test
    fun `every local item is pinned, so the consumables section shows all of them`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(
                row("i-1", LocalRowKind.ITEM, label = "Potion", total = 2, current = 2, sortIndex = 0),
                row("i-2", LocalRowKind.ITEM, label = "Arrows", total = 20, current = 20, sortIndex = 1),
            ),
        )

        assertEquals(listOf("Potion", "Arrows"), board.pinnedItems.map { it.name })
        assertEquals(board.allItems, board.pinnedItems)
        assertTrue(board.pinnedItems.all { it.pinned })
    }

    @Test
    fun `an item's total tracks its quantity because an item has no ceiling`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(row("i-1", LocalRowKind.ITEM, total = 2, current = 7)),
        )

        with(board.allItems.single()) {
            assertEquals(7, value)
            assertEquals(7, total)
        }
    }

    @Test
    fun `a counted row keeps its own ceiling`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(row("res-1", LocalRowKind.RESOURCE, total = 3, current = 1)),
        )

        with(board.resources.single()) {
            assertEquals(1, value)
            assertEquals(3, total)
        }
    }

    @Test
    fun `rows render in the player's order, with the label as the tie-breaker`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(
                row("s-3", LocalRowKind.SLOT, label = "3rd Level", sortIndex = 2),
                row("s-1", LocalRowKind.SLOT, label = "1st Level", sortIndex = 0),
                row("s-2", LocalRowKind.SLOT, label = "2nd Level", sortIndex = 1),
                row("s-z", LocalRowKind.SLOT, label = "Zebra", sortIndex = 1),
            ),
        )

        assertEquals(
            listOf("1st Level", "2nd Level", "Zebra", "3rd Level"),
            board.slots.map { it.name },
        )
    }

    @Test
    fun `the reset rule survives onto the board so the rest buttons can act on it`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(
                row("a", LocalRowKind.RESOURCE, reset = ResetRule.SHORT_REST, sortIndex = 0),
                row("b", LocalRowKind.RESOURCE, reset = ResetRule.LONG_REST, sortIndex = 1),
                row("c", LocalRowKind.RESOURCE, reset = null, sortIndex = 2),
            ),
        )

        assertEquals(
            listOf(ResetRule.SHORT_REST, ResetRule.LONG_REST, null),
            board.resources.map { it.reset },
        )
    }

    /** 09 decisions 4 and 8, and the out-of-scope list: none of these has a local source. */
    @Test
    fun `a local board carries no toggles, defenses, temp HP or concentration`() {
        val board = LocalTrackerBoard.build(
            character(),
            listOf(
                row("s-1", LocalRowKind.SLOT, sortIndex = 0),
                row("i-1", LocalRowKind.ITEM, sortIndex = 1),
            ),
        )

        assertTrue("no toggles for local characters", board.activeToggles.isEmpty())
        assertTrue("defenses are a discovered-sheet concept", board.defenses.isEmpty())
        assertNull("no temp HP field on the form", board.tempHp)
        assertNull("concentration is toggle-driven, and there are no toggles", board.concentratingOn)
    }

    // -----------------------------------------------------------------------
    // FR-7 — the six ability checks
    // -----------------------------------------------------------------------

    @Test
    fun `the six ability checks are derived from the stored scores`() {
        val board = LocalTrackerBoard.build(character(), emptyList())

        assertEquals(
            listOf("Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma"),
            board.rolls.map { it.name },
        )
        // The fixture character is STR 8, DEX 14, CON 15, and 10 for the rest. `floorDiv`,
        // not truncation: 8 reads −1 (a truncating divide would say 0), and 15 reads +2.
        assertEquals(
            listOf(-1, 2, 2, 0, 0, 0),
            board.rolls.map { it.modifier },
        )
    }

    @Test
    fun `an odd score below ten floors rather than truncating`() {
        // The whole reason `abilityModifier` exists, checked through this path too: a 7 is
        // −2, and an integer divide toward zero would make it −1.
        val board = LocalTrackerBoard.build(
            character().copy(abilities = AbilityScores(strength = 7)),
            emptyList(),
        )
        assertEquals(-2, board.rolls.single { it.name == "Strength" }.modifier)
    }

    @Test
    fun `local rolls carry no advantage and keep sheet order`() {
        val board = LocalTrackerBoard.build(character(), emptyList())

        assertTrue(board.rolls.all { it.advantage == RollAdvantage.NONE })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), board.rolls.map { it.sortOrder })
    }

    @Test
    fun `a check's id is stable across edits, so a remembered selection survives one`() {
        // The id is derived from the ability, never from the score: editing a character in
        // the form must not silently reset the player's dropdown selection.
        val before = LocalTrackerBoard.build(character(), emptyList()).rolls
        val after = LocalTrackerBoard.build(
            character().copy(abilities = AbilityScores(strength = 20, dexterity = 3)),
            emptyList(),
        ).rolls

        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(LocalTrackerBoard.rollId(Ability.STR), before.first().id)
        // Namespaced, so it can never be mistaken for a Meteor property id.
        assertTrue(before.all { it.id.startsWith(LocalTrackerBoard.ROLL_ID_PREFIX) })
    }

    @Test
    fun `a character that has not loaded has no rolls at all`() {
        assertTrue(LocalTrackerBoard.build(null, emptyList()).rolls.isEmpty())
    }

    @Test
    fun `a character with no rows still renders its HP row`() {
        val board = LocalTrackerBoard.build(character(maxHp = 8, currentHp = 8), emptyList())

        assertEquals(8, board.hp?.total)
        assertTrue(board.slots.isEmpty())
        assertTrue(board.resources.isEmpty())
        assertTrue(board.allItems.isEmpty())
        assertEquals("HP alone is not an empty board", false, board.isEmpty)
    }
}
