package com.hashtagchow.magehand.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * FR-29's local Actions board (docs/design/18-table-pack.md decisions 1–4).
 *
 * ### What "reused, not forked" has to mean at this layer
 *
 * 09 decision 5's claim about the tracker, extended to a third surface: the local path produces
 * the **same** `ActionBoard` the DiceCloud engine produces, so the same `toActionsUiState`, the
 * same sectioning, the same detail sheet and the same `UseTarget` gate all apply with no local
 * branch anywhere above this file. These tests are therefore mostly about the *domain* values —
 * because if those are right, everything above is already tested by FR-26's and FR-28's suites.
 *
 * ### The two structural claims
 *
 * An action row is **not** a tracker row (decision 1's model), and the fence against cost chaining
 * (decision 2) holds at the board even though the form is where it is enforced. Both are asserted
 * here rather than left to the screens.
 */
class LocalActionBoardTest {

    private val characterId = "local-1"

    private fun row(
        id: String,
        kind: LocalRowKind,
        label: String,
        total: Int = 1,
        current: Int = total,
        sortIndex: Int = 0,
        reset: ResetRule? = null,
        description: String? = null,
        costRowId: String? = null,
        costAmount: Int? = null,
    ) = LocalTrackerRow(
        id = id,
        characterId = characterId,
        kind = kind,
        label = label,
        total = total,
        current = current,
        reset = reset,
        sortIndex = sortIndex,
        description = description,
        costRowId = costRowId,
        costAmount = costAmount,
    )

    private fun rage(current: Int = 2) =
        row("rage", LocalRowKind.RESOURCE, "Rage", total = 3, current = current, reset = ResetRule.LONG_REST)

    private fun arrows(current: Int = 12) =
        row("arrows", LocalRowKind.ITEM, "Arrows", total = current, current = current, sortIndex = 1)

    // --- decision 1: the model ----------------------------------------------

    @Test
    fun `an action row becomes an entry with its label, description and uses`() {
        val board = LocalActionBoard.build(
            listOf(
                row(
                    "act",
                    LocalRowKind.ACTION,
                    "Second Wind",
                    total = 2,
                    current = 1,
                    description = "Regain 1d10 + level hit points.",
                    sortIndex = 5,
                ),
            ),
        )

        val entry = board.actions.single()
        assertEquals("act", entry.propertyId)
        assertEquals("Second Wind", entry.name)
        assertEquals("Regain 1d10 + level hit points.", entry.description)
        assertEquals(2, entry.uses?.max)
        assertEquals("local rows store what is LEFT; ActionUses stores what was SPENT", 1, entry.uses?.used)
        assertEquals(1, entry.uses?.remaining)
        assertEquals(5, entry.sortOrder)
    }

    /**
     * `total == 0` is **unlimited**, and the distinction from "exhausted" is the whole point.
     *
     * An `ActionUses(max = 0)` would report `isExhausted`, which would hide the Use button on
     * every unconditional action a player types — the most common kind. `null` is what the server
     * path publishes for an action that states no `uses`, so both sources say the same thing about
     * the same fact.
     */
    @Test
    fun `zero uses means unlimited, not exhausted`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Shove", total = 0, current = 0)))
            .actions.single()

        assertNull(entry.uses)
        assertNull(entry.usesLeft)
        assertNull(entry.usesMax)
        assertTrue("an unlimited action is always usable", entry.isUsable)
        assertNotNull(entry.useTarget)
    }

    /**
     * `usesLeft` / `usesMax` agree with `uses` **by construction** here.
     *
     * On the server path those two are the lagging rollup the list row prints, while `ActionUses`
     * is the synchronous pair the Use gate reads — the split that stops probe U3's double-spend.
     * Locally there is one Room column answering both questions, so agreement is not a coincidence
     * to be maintained but a property of there being one source. Asserted so a future edit that
     * introduced a second source would have to face the question.
     */
    @Test
    fun `the display counts and the gate counts cannot disagree locally`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Rage", total = 3, current = 1)))
            .actions.single()

        assertEquals(entry.uses?.remaining, entry.usesLeft)
        assertEquals(entry.uses?.max, entry.usesMax)
    }

    // --- decision 1: the cost -----------------------------------------------

    @Test
    fun `a cost naming a resource joins against that row's remaining count`() {
        val board = LocalActionBoard.build(
            listOf(
                rage(current = 2),
                row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "rage", costAmount = 1),
            ),
        )

        val cost = board.actions.single().cost
        assertFalse(cost.isFree)
        with(cost.attributes.single()) {
            assertEquals("Rage", name)
            assertEquals(1, amount)
            assertEquals(2, available)
            assertTrue(satisfied)
        }
        assertTrue("an item cost belongs on the other list", cost.items.isEmpty())
    }

    /**
     * An **item** cost lands in `ActionCost.items`, matching the server path's own split.
     *
     * Nothing downstream distinguishes them — `lines` is the concatenation and the UI draws both
     * identically — so this is a naming decision. It is made this way because the server's split
     * is exactly the same distinction (`attributesConsumed` versus `itemsConsumed`), and having
     * the two sources describe one cost differently would be a difference a reader could not
     * recover the reason for.
     */
    @Test
    fun `a cost naming an item lands on the items list`() {
        val board = LocalActionBoard.build(
            listOf(
                arrows(current = 12),
                row("act", LocalRowKind.ACTION, "Volley", total = 0, costRowId = "arrows", costAmount = 3),
            ),
        )

        val cost = board.actions.single().cost
        assertTrue(cost.attributes.isEmpty())
        assertEquals("Arrows", cost.items.single().name)
        assertEquals(3, cost.items.single().amount)
        assertEquals(12, cost.items.single().available)
    }

    @Test
    fun `an action with no cost is free`() {
        val board = LocalActionBoard.build(
            listOf(row("act", LocalRowKind.ACTION, "Dodge", total = 0)),
        )

        assertTrue(board.actions.single().cost.isFree)
    }

    /**
     * An underfunded cost makes the action **unusable**, and unusable means the Use is *absent*.
     *
     * `useTarget` returning null is 17 decision 2's "ABSENT — not disabled" expressed as a type,
     * and it is the gate the whole Use path goes through. The detail sheet then renders the reason
     * instead of a dead button — decision 4's *"Insufficient cost → Use absent with the reason in
     * the sheet (mirror the server surface's honesty)"*.
     */
    @Test
    fun `an underfunded cost removes the use`() {
        val board = LocalActionBoard.build(
            listOf(
                rage(current = 0),
                row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "rage", costAmount = 1),
            ),
        )

        val entry = board.actions.single()
        assertFalse(entry.cost.satisfied)
        assertFalse(entry.isUsable)
        assertNull("no target means no button, which is the gate", entry.useTarget)
    }

    @Test
    fun `an exhausted action removes the use`() {
        val entry = LocalActionBoard
            .build(listOf(row("act", LocalRowKind.ACTION, "Second Wind", total = 1, current = 0)))
            .actions.single()

        assertTrue(entry.uses!!.isExhausted)
        assertNull(entry.useTarget)
    }

    /**
     * A cost naming a row that **no longer exists** is permitted, not refused.
     *
     * `costRowId` carries no `FOREIGN KEY` on purpose — a cascade would delete an action when the
     * resource it spends is deleted, which is not what deleting a resource means — so a dangling
     * reference is a live possibility rather than an impossible state. The line is dropped, which
     * lands the action on the permissive side of `CostLine.satisfied`'s asymmetry: the app has not
     * *evaluated* the cost as zero, it has failed to evaluate it at all, and erring the other way
     * would make the row permanently unusable with no explanation the player could act on.
     */
    @Test
    fun `a cost naming a deleted row is dropped rather than blocking the use`() {
        val board = LocalActionBoard.build(
            listOf(row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, costRowId = "gone", costAmount = 1)),
        )

        val entry = board.actions.single()
        assertTrue(entry.cost.isFree)
        assertTrue(entry.isUsable)
    }

    /** Half a cost is no cost — the same normalisation the entity mapping already applies. */
    @Test
    fun `half a cost is no cost`() {
        val amountOnly = LocalActionBoard
            .build(listOf(row("a", LocalRowKind.ACTION, "X", total = 0, costAmount = 2)))
            .actions.single()
        assertTrue(amountOnly.cost.isFree)

        val rowOnly = LocalActionBoard
            .build(listOf(rage(), row("b", LocalRowKind.ACTION, "Y", total = 0, costRowId = "rage")))
            .actions.single()
        assertTrue(rowOnly.cost.isFree)
    }

    // --- decision 3: grouping and gating ------------------------------------

    /**
     * Decision 3: *"one 'Actions' section (no actionType taxonomy locally)"*.
     *
     * The shared sectioning code groups by `ActionEntry.group`, so producing one section means
     * producing one group — and the group whose header reads "Actions" is [ActionType.ACTION]'s.
     * The honest-looking alternative, a null type, files the row under **Other**, which is the
     * group defined by *not being* one of the four named ones. A local action is not an
     * unclassifiable row; it is the only kind of row this model has.
     */
    @Test
    fun `every local action is in the one Actions group`() {
        val board = LocalActionBoard.build(
            listOf(
                row("a", LocalRowKind.ACTION, "Dodge", total = 0, sortIndex = 0),
                row("b", LocalRowKind.ACTION, "Dash", total = 0, sortIndex = 1),
            ),
        )

        assertEquals(listOf(ActionType.ACTION, ActionType.ACTION), board.actions.map { it.type })
        assertEquals(setOf(ActionGroup.ACTIONS), board.actions.map { it.group }.toSet())
    }

    /** Decision 3's discovery gate is `ActionBoard.isEmpty`, the same one the server surface uses. */
    @Test
    fun `a character with no action rows has an empty board`() {
        val board = LocalActionBoard.build(listOf(rage(), arrows()))

        assertTrue(board.isEmpty)
        assertEquals(0, board.rowCount)
    }

    /** The player's order, then label — `sortIndex`, 09 decision 8's one mechanism. */
    @Test
    fun `actions render in the player's own order`() {
        val board = LocalActionBoard.build(
            listOf(
                row("c", LocalRowKind.ACTION, "Third", total = 0, sortIndex = 2),
                row("a", LocalRowKind.ACTION, "First", total = 0, sortIndex = 0),
                row("b", LocalRowKind.ACTION, "Second", total = 0, sortIndex = 1),
            ),
        )

        assertEquals(listOf("First", "Second", "Third"), board.actions.map { it.name })
    }

    /**
     * No spells, ever — and therefore no upcast picker and no spell-list header.
     *
     * 18 decision 1 gives local characters actions and deliberately not spells. This is what makes
     * `LocalCharacterHomeViewModel.use`'s `UseTarget.Spell` branch unreachable rather than merely
     * unused, and it is why the local Actions surface needs no slot list threaded into it.
     */
    @Test
    fun `a local board carries no spells and no spell lists`() {
        val board = LocalActionBoard.build(
            listOf(rage(), row("act", LocalRowKind.ACTION, "Enter Rage", total = 0)),
        )

        assertTrue(board.spells.isEmpty())
        assertTrue(board.spellLists.isEmpty())
        assertEquals(1, board.rowCount)
    }

    // --- decision 1: an action is not a tracker row --------------------------

    /**
     * The structural half of decision 1: an action row cannot reach the tracker.
     *
     * `LocalRowKind.ACTION` maps to no `TrackerKind`, so `toTrackedResource` returns null and
     * `LocalTrackerBoard` drops it — which means "an action never appears among the slots,
     * resources or items" is a property of the type rather than four filters somebody has to
     * remember. Asserted from the board a player actually sees.
     */
    @Test
    fun `an action row does not appear on the tracker board`() {
        val rows = listOf(
            rage(),
            arrows(),
            row("act", LocalRowKind.ACTION, "Enter Rage", total = 0, sortIndex = 2),
        )

        val tracker = LocalTrackerBoard.build(
            character = com.hashtagchow.magehand.core.model.LocalCharacter(
                id = characterId,
                name = "Brambles",
                level = 3,
                abilities = com.hashtagchow.magehand.core.model.AbilityScores.DEFAULTS,
                maxHp = 30,
                currentHp = 30,
                armorClass = 15,
                createdAt = 1,
                updatedAt = 1,
            ),
            rows = rows,
        )

        assertEquals(listOf("rage"), tracker.resources.map { it.propertyId })
        assertEquals(listOf("arrows"), tracker.allItems.map { it.propertyId })
        assertTrue(tracker.slots.isEmpty())
        assertFalse("act" in (tracker.resources + tracker.allItems + tracker.slots).map { it.propertyId })
        // …and the action IS on the other board, so this is a routing assertion rather than a
        // "the row was dropped" one.
        assertEquals(listOf("act"), LocalActionBoard.build(rows).actions.map { it.propertyId })
    }
}
