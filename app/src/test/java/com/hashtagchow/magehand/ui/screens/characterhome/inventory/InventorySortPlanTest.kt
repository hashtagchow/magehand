package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.core.model.InventoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * FR-35's comparator (ledger decision 2), with no device, no Compose runtime and no store.
 *
 * ### Why the comparator gets its own class
 *
 * `InventoryLayoutTest`'s argument, applied to the second piece of arithmetic on this tab: the
 * awkward part of a sorting feature is the ordering itself, and it is the part no screenshot and
 * no manual pass would catch getting subtly wrong. A tie-break that is not stable makes a list
 * reshuffle on every sync — nobody reports that as a bug, they report that the app "keeps moving
 * my stuff" — and only a test that sorts the *same input twice* can see it.
 *
 * Every case below is stated as the order of names, because that is what the player sees.
 */
class InventorySortPlanTest {

    private fun item(
        name: String,
        weightLb: Double? = 1.0,
        valueGp: Double? = 1.0,
        quantity: Int = 1,
        sortOrder: Int = 0,
        id: String = name.lowercase().replace(' ', '-'),
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = quantity,
        weightLb = weightLb,
        valueGp = valueGp,
        description = null,
        equipped = false,
        sortOrder = sortOrder,
    )

    private fun sort(
        criterion: InventorySortCriterion,
        direction: InventorySortDirection = InventorySortDirection.ASCENDING,
    ) = InventorySort(criterion, direction)

    private fun List<InventoryItem>.names() = map { it.name }

    private fun sortedNames(
        items: List<InventoryItem>,
        criterion: InventorySortCriterion,
        direction: InventorySortDirection = InventorySortDirection.ASCENDING,
    ) = InventorySortPlan.sorted(items, sort(criterion, direction)).names()

    // --- Default: the identity, and it must stay the identity -----------------------

    /**
     * The load-bearing one. Decision 2's Default is *the source's own order*, and the promise
     * every existing golden depends on is that FR-35 changed nothing for a player who never opens
     * the control.
     *
     * `assertSame` and not `assertEquals`: an implementation that sorted by `sortOrder` would
     * produce an equal list here and would still be wrong, because the boards do not promise that
     * `sortOrder` is what they emitted in — `LocalInventoryBoard` orders by `sortIndex` **then
     * label**, and a re-derivation would silently disagree with it on a tie. Identity is the only
     * assertion that says "nothing was re-decided".
     */
    @Test
    fun `sheet order returns the very same list, unsorted and uncopied`() {
        val items = listOf(
            item("Torch", sortOrder = 9),
            item("Anvil", weightLb = 50.0, sortOrder = 1),
            item("Rope", sortOrder = 5),
        )

        assertSame(items, InventorySortPlan.sorted(items, InventorySort.DEFAULT))
        // …including when a direction is stored beside it. Sheet order has no reverse — decision
        // 6 disables the control rather than removing the stored value, so this pairing is
        // reachable and must be inert. See `InventorySort.direction`.
        assertSame(
            items,
            InventorySortPlan.sorted(items, sort(InventorySortCriterion.DEFAULT, InventorySortDirection.DESCENDING)),
        )
    }

    // --- the three real criteria ----------------------------------------------------

    @Test
    fun `name sorts alphabetically, and case is not an ordering`() {
        // A sheet is hand-typed. "arrows" filed after "Torch" because a lower-case `a` sorts above
        // every capital in code-point order is a list a player reads as broken, so the comparison
        // is case-insensitive — see `InventorySortPlan.byName`.
        val items = listOf(item("Torch"), item("arrows"), item("Bedroll"))

        assertEquals(
            listOf("arrows", "Bedroll", "Torch"),
            sortedNames(items, InventorySortCriterion.NAME),
        )
        assertEquals(
            listOf("Torch", "Bedroll", "arrows"),
            sortedNames(items, InventorySortCriterion.NAME, InventorySortDirection.DESCENDING),
        )
    }

    @Test
    fun `weight sorts by what the stack weighs, not by what one unit weighs`() {
        // The number the row prints (`InventoryRowState.stackWeightLabel`) is the stack's. Sorting
        // by the unit would put twenty arrows above a quarterstaff on a list that plainly reads
        // "1 lb" and "4 lb", which is the app ordering by a number nobody can see.
        val items = listOf(
            item("Quarterstaff", weightLb = 4.0),
            item("Arrows", weightLb = 0.05, quantity = 20), // 1 lb the stack
            item("Anvil", weightLb = 50.0),
        )

        assertEquals(
            listOf("Arrows", "Quarterstaff", "Anvil"),
            sortedNames(items, InventorySortCriterion.WEIGHT),
        )
        assertEquals(
            listOf("Anvil", "Quarterstaff", "Arrows"),
            sortedNames(items, InventorySortCriterion.WEIGHT, InventorySortDirection.DESCENDING),
        )
    }

    @Test
    fun `value sorts by what the stack is worth, the same way weight does`() {
        val items = listOf(
            item("Spellbook", valueGp = 50.0),
            item("Copper piece", valueGp = 0.01, quantity = 100), // 1 gp the stack
            item("Diamond", valueGp = 300.0),
        )

        assertEquals(
            listOf("Copper piece", "Spellbook", "Diamond"),
            sortedNames(items, InventorySortCriterion.VALUE),
        )
        assertEquals(
            listOf("Diamond", "Spellbook", "Copper piece"),
            sortedNames(items, InventorySortCriterion.VALUE, InventorySortDirection.DESCENDING),
        )
    }

    // --- absent measurements (decision 2's "sorts as 0") ----------------------------

    /**
     * Decision 2's *"absent weight/value sorts as 0"*, in both directions so the rule is pinned
     * as an ordering rather than as a coincidence at one end.
     *
     * Note what this does **not** claim. An item the sheet gave no weight is not being called
     * weightless — the row still prints an em dash (11 decision 6, K10), and that distinction
     * survives untouched. It is that there is no other number available to sort by, and floating
     * such items to one end regardless of direction would be a third ordering rule nobody asked
     * for and nothing on screen would explain.
     */
    @Test
    fun `an absent weight sorts as zero, at whichever end zero is`() {
        val items = listOf(
            item("Heavy", weightLb = 10.0),
            item("Unweighed", weightLb = null),
            item("Light", weightLb = 0.5),
        )

        assertEquals(
            listOf("Unweighed", "Light", "Heavy"),
            sortedNames(items, InventorySortCriterion.WEIGHT),
        )
        assertEquals(
            listOf("Heavy", "Light", "Unweighed"),
            sortedNames(items, InventorySortCriterion.WEIGHT, InventorySortDirection.DESCENDING),
        )
    }

    @Test
    fun `an absent value sorts as zero, and sits with a genuinely worthless item`() {
        // The two are indistinguishable *to the comparator* and that is correct: zero is the only
        // arithmetic available for an absence. They remain distinguishable on screen, which is
        // where the distinction is load-bearing.
        val items = listOf(
            item("Priced", valueGp = 5.0),
            item("Unpriced", valueGp = null),
            item("Worthless", valueGp = 0.0),
        )

        // Tie between the two zeroes breaks by name — "Unpriced" before "Worthless".
        assertEquals(
            listOf("Unpriced", "Worthless", "Priced"),
            sortedNames(items, InventorySortCriterion.VALUE),
        )
    }

    // --- ties, and the direction rule they turn on ----------------------------------

    @Test
    fun `equal criterion values break by name`() {
        val items = listOf(
            item("Torch", weightLb = 1.0),
            item("Bedroll", weightLb = 1.0),
            item("Rope", weightLb = 1.0),
        )

        assertEquals(
            listOf("Bedroll", "Rope", "Torch"),
            sortedNames(items, InventorySortCriterion.WEIGHT),
        )
    }

    /**
     * The direction applies to the **criterion alone** — tie-breaks stay ascending.
     *
     * "Weight, descending" means heaviest first, and then, among things that weigh the same, A to
     * Z: a player who asked for the heaviest items at the top did not thereby ask for the alphabet
     * to run backwards inside each weight. This is every file manager's column-sort behaviour, and
     * the alternative — reversing the whole comparator — produces tie-breaks that look scrambled
     * for a reason nothing on screen explains.
     *
     * This is the assertion that would fail on a `.reversed()` applied one step too late, which is
     * the easiest possible mis-edit of `InventorySortPlan.comparator`.
     */
    @Test
    fun `descending reverses the criterion and leaves the tie-breaks ascending`() {
        val items = listOf(
            item("Torch", weightLb = 1.0),
            item("Anvil", weightLb = 50.0),
            item("Bedroll", weightLb = 1.0),
        )

        assertEquals(
            listOf("Anvil", "Bedroll", "Torch"),
            sortedNames(items, InventorySortCriterion.WEIGHT, InventorySortDirection.DESCENDING),
        )
    }

    @Test
    fun `a name tie breaks by sheet order, in both directions`() {
        // A sheet is allowed to carry "Torch" twice. The last key is what stops the two from
        // being free to swap places, and it stays ascending under a descending sort for the
        // reason above — so the same two rows keep the same relative order either way round.
        val items = listOf(
            item("Torch", id = "second", sortOrder = 7),
            item("Torch", id = "first", sortOrder = 2),
        )

        assertEquals(
            listOf("first", "second"),
            InventorySortPlan.sorted(items, sort(InventorySortCriterion.NAME)).map { it.propertyId },
        )
        assertEquals(
            listOf("first", "second"),
            InventorySortPlan.sorted(
                items,
                sort(InventorySortCriterion.NAME, InventorySortDirection.DESCENDING),
            ).map { it.propertyId },
        )
    }

    /**
     * Stability, asserted the only way it can be: sort the **result** again and require it not to
     * move.
     *
     * A comparator with an unbroken tie is free to return a different permutation each time it is
     * given a differently-ordered input, and the board is rebuilt on every DDP sync — so an
     * unstable order is a list that shuffles itself while the player is looking at it. The input
     * is deliberately shuffled between the two runs, because re-sorting an already-sorted list is
     * the one case a broken comparator gets right by accident.
     */
    @Test
    fun `the order is total, so a re-sort of a shuffled list is a fixed point`() {
        val items = listOf(
            item("Torch", weightLb = 1.0, sortOrder = 3),
            item("Torch", weightLb = 1.0, sortOrder = 1, id = "torch-b"),
            item("Rope", weightLb = 1.0, sortOrder = 2),
            item("Anvil", weightLb = 50.0, sortOrder = 0),
            item("Unweighed", weightLb = null, sortOrder = 4),
        )

        InventorySortCriterion.entries.forEach { criterion ->
            InventorySortDirection.entries.forEach { direction ->
                val once = InventorySortPlan.sorted(items, sort(criterion, direction))
                val fromReversed = InventorySortPlan.sorted(items.reversed(), sort(criterion, direction))
                val twice = InventorySortPlan.sorted(once, sort(criterion, direction))

                assertEquals(
                    "$criterion $direction must be a fixed point",
                    once.map { it.propertyId },
                    twice.map { it.propertyId },
                )
                if (criterion != InventorySortCriterion.DEFAULT) {
                    // The real claim: the answer does not depend on the order it was handed. Sheet
                    // order is excluded because *depending on the input order is what it is*.
                    assertEquals(
                        "$criterion $direction must not depend on input order",
                        once.map { it.propertyId },
                        fromReversed.map { it.propertyId },
                    )
                }
            }
        }
    }

    // --- degenerate inputs ----------------------------------------------------------

    @Test
    fun `an empty or single-item section is not a special case`() {
        InventorySortCriterion.entries.forEach { criterion ->
            assertEquals(emptyList<String>(), sortedNames(emptyList(), criterion))
            assertEquals(listOf("Torch"), sortedNames(listOf(item("Torch")), criterion))
        }
    }
}
