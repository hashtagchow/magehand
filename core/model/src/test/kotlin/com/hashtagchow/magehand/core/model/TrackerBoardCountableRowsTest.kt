package com.hashtagchow.magehand.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **BUG-20's pin**: a discovered row must be a tappable row.
 *
 * ### The defect, and why a test rather than a third patch
 *
 * `CharacterHomeViewModel.withRow` and `DmViewViewModel.withRow` each resolved a tapped
 * `propertyId` against a hand-written sum of the board's lists, and a third copy sat in
 * `LocalCharacterHomeViewModel`. Nothing held those sums to [TrackerBoard]'s actual field list,
 * so a new list shipped twice as a row that renders, draws its pips, speaks its name and does
 * **nothing** when tapped — FR-30 for hit dice, FR-44 for limited uses. Each was patched by
 * adding the list to the sums, which is the same fix twice; the ledger's verdict is *"third time
 * is the pattern, not the fix"*.
 *
 * [TrackerBoard.allCountableRows] is the fix and this class is what makes it one. The assertion
 * below walks **every [TrackerKind]** and fails if any kind cannot be reached through the sum, so
 * a new kind is a red test at the moment the list is declared rather than a bug report about a
 * pip that does not respond. That is the difference between a rule and a habit.
 *
 * ### Why it lives in `:core:model`
 *
 * Because the sum does. The two view models can only disagree with each other while the sum is
 * written down twice; moved onto the type, "are the two lookups the same?" stops being a question
 * a test could even ask, and the only remaining question — "does the sum cover the board?" — is
 * answerable here, with no Android, no view model and no Compose runtime.
 */
class TrackerBoardCountableRowsTest {

    private fun row(id: String, kind: TrackerKind) = TrackedResource(
        propertyId = id,
        kind = kind,
        name = id,
        value = 1,
        total = 2,
    )

    /**
     * A board carrying one row of **every** kind, filed the way discovery files them.
     *
     * Written from the enum rather than from a literal list so that adding a kind changes what
     * this fixture contains: the `when` is exhaustive, so a new constant fails to compile here
     * and the author has to say which list it belongs in — which is precisely the moment the two
     * old `withRow` copies used to be forgotten.
     */
    private fun boardWithEveryKind(): TrackerBoard {
        var board = TrackerBoard()
        for (kind in TrackerKind.entries) {
            val one = row("row-${kind.name}", kind)
            board = when (kind) {
                TrackerKind.SPELL_SLOT -> board.copy(slots = board.slots + one)
                TrackerKind.RESOURCE -> board.copy(resources = board.resources + one)
                TrackerKind.LIMITED_USE -> board.copy(limitedUses = board.limitedUses + one)
                TrackerKind.HIT_DICE -> board.copy(hitDice = board.hitDice + one)
                TrackerKind.ITEM -> board.copy(allItems = board.allItems + one, pinnedItems = listOf(one))
                TrackerKind.HIT_POINTS -> board.copy(hp = one)
                TrackerKind.TEMP_HP -> board.copy(tempHp = one)
            }
        }
        return board
    }

    /** [boardWithEveryKind]'s per-kind filing, for one kind at a time. */
    private fun boardWithOnly(kind: TrackerKind): TrackerBoard {
        val one = row("row-${kind.name}", kind)
        return when (kind) {
            TrackerKind.SPELL_SLOT -> TrackerBoard(slots = listOf(one))
            TrackerKind.RESOURCE -> TrackerBoard(resources = listOf(one))
            TrackerKind.LIMITED_USE -> TrackerBoard(limitedUses = listOf(one))
            TrackerKind.HIT_DICE -> TrackerBoard(hitDice = listOf(one))
            TrackerKind.ITEM -> TrackerBoard(allItems = listOf(one), pinnedItems = listOf(one))
            TrackerKind.HIT_POINTS -> TrackerBoard(hp = one)
            TrackerKind.TEMP_HP -> TrackerBoard(tempHp = one)
        }
    }

    /**
     * **The pin.** Every kind is reachable, by the id a tap hands back.
     *
     * `TrackerKind.entries` rather than a written-out list is the whole mechanism: this cannot
     * pass while a kind exists that discovery can produce and the lookup cannot resolve.
     */
    @Test
    fun `every tracker kind is reachable through the board's own lookup`() {
        val board = boardWithEveryKind()

        for (kind in TrackerKind.entries) {
            val id = "row-${kind.name}"
            assertNotNull(
                "$kind is discoverable but not tappable — see TrackerBoard.allCountableRows " +
                    "(BUG-20). A row that renders and does nothing when tapped is the symptom.",
                board.countableRow(id),
            )
            assertEquals(kind, board.countableRow(id)!!.kind)
        }
    }

    /**
     * An id nobody on the board carries resolves to `null`, which is what makes dropping a stale
     * tap the *lookup's* job rather than every caller's.
     */
    @Test
    fun `an unknown property id resolves to nothing`() {
        assertNull(boardWithEveryKind().countableRow("row-that-was-deleted"))
    }

    /**
     * A pinned item appears **once**.
     *
     * `pinnedItems` is a subset of `allItems` by construction, so summing both would put the same
     * row in the list twice. Harmless for a `firstOrNull`, and exactly the kind of thing that
     * stops being harmless the first time somebody folds, counts or maps over this list.
     */
    @Test
    fun `a pinned item is not listed twice`() {
        val board = boardWithEveryKind()

        assertEquals(1, board.allCountableRows.count { it.propertyId == "row-ITEM" })
    }

    /**
     * An empty board sums to an empty list rather than to a list of nulls — the `listOfNotNull`
     * half of the rule, which is what lets a caller iterate without a null check.
     */
    @Test
    fun `an empty board has no countable rows`() {
        assertTrue(TrackerBoard.EMPTY.allCountableRows.isEmpty())
    }

    /**
     * **[TrackerBoard.isEmpty] agrees with [TrackerBoard.allCountableRows]** — the re-check's
     * second pin.
     *
     * `isEmpty` was rewritten to derive from the sum (MEDIUM-4) rather than hand-enumerate the
     * same six fields a second time, and until now nothing in `:core:model` said so: the only
     * coverage was indirect, through `ContractExportTest`'s fixture round-trip, which asserts a
     * whole board's JSON and would report a change here as a golden diff rather than as this
     * rule breaking.
     *
     * The two named cases are the ones a re-hand-written predicate drops first, because they are
     * the two the *original* one already had trouble with: `tempHp` is the field nothing on any
     * screen can currently tap (which is why it was nearly left out of the sum), and
     * `limitedUses` is one of the two lists whose omission from a hand-written sum *was* BUG-20.
     * The loop after them is the general rule, so a new [TrackerKind] cannot make a board look
     * empty when it is not.
     */
    @Test
    fun `a board is empty only when it has no countable rows and nothing else to draw`() {
        assertTrue("a board with nothing on it is empty", TrackerBoard.EMPTY.isEmpty)

        assertFalse(
            "a sheet whose only tracked value is temporary hit points still has a block to draw",
            TrackerBoard(tempHp = row("thp", TrackerKind.TEMP_HP)).isEmpty,
        )
        assertFalse(
            "a character whose only rows are limited-use abilities still has a section to draw",
            TrackerBoard(limitedUses = listOf(row("lu", TrackerKind.LIMITED_USE))).isEmpty,
        )

        // The general rule: one row of any kind, and there is a screen worth rendering.
        for (kind in TrackerKind.entries) {
            val board = boardWithOnly(kind)
            assertFalse(
                "$kind alone must not read as an empty board — isEmpty and allCountableRows " +
                    "have drifted apart (see TrackerBoard.isEmpty)",
                board.isEmpty,
            )
            assertEquals(1, board.allCountableRows.size)
        }
    }

    /**
     * The three lists that are **not** countable rows each keep a board non-empty on their own.
     *
     * `isEmpty` names them separately from the sum, and that is deliberate rather than left over:
     * a character with only condition chips, only a defenses line or only a rolls dropdown has a
     * screen worth rendering, and folding them into `allCountableRows` — which is what a reader
     * simplifying this might try — would both make those boards read as empty *and* put three
     * un-tappable things into a tapped-row lookup.
     */
    @Test
    fun `toggles, defenses and rolls each keep a board non-empty without any countable row`() {
        assertFalse(
            TrackerBoard(activeToggles = listOf(ConditionToggle("t1", "Rage", enabled = true))).isEmpty,
        )
        assertFalse(
            TrackerBoard(
                defenses = listOf(
                    DamageDefense("d1", DefenseKind.RESISTANT, listOf("Fire"), "Resistance"),
                ),
            ).isEmpty,
        )
        assertFalse(TrackerBoard(rolls = listOf(RollModifier("r1", "Stealth", 5))).isEmpty)
    }

    /**
     * **Armour class is not in the sum**, and cannot be: it is an `Int?` (18 decision 22).
     *
     * Stated as a test because it is a claim about the *type*, and the type is the guarantee — a
     * future edit that made AC a one-of-one `TrackedResource` to "reuse the row machinery" would
     * make it tappable, and a tappable armour class is a control the server has nothing to point
     * at. `TrackerBoard.armorClass` carries the argument.
     */
    @Test
    fun `armor class is carried as a number and never as a countable row`() {
        val board = boardWithEveryKind().copy(armorClass = 14)

        assertEquals(14, board.armorClass)
        assertEquals(TrackerKind.entries.size, board.allCountableRows.size)
    }
}
