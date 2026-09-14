package com.hashtagchow.magehand.core.data.write

import com.hashtagchow.magehand.core.model.AppliedBuff
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What latency compensation is allowed to change about a board — and, more importantly,
 * what it is **not**.
 *
 * The overlay is the one place in the app that rebuilds a whole [TrackerBoard] from
 * another one, so it is the one place where a board field can go missing without a
 * compiler error: the field-by-field constructor form it used to take defaulted every
 * field it did not name. [TrackerBoard.defenses] was exactly that — added to the board,
 * never added here — so the Defenses section disappeared while any write was in flight
 * and reappeared when it resolved, which reads as a flickering bug rather than as a
 * dropped field. `applyTo` uses `copy` now; these tests are what stops it drifting back.
 */
class OptimisticOverlayTest {

    private val hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 17, 17)
    private val slot = TrackedResource("s1", TrackerKind.SPELL_SLOT, "1st Level", 3, 4)
    private val potion = TrackedResource("i1", TrackerKind.ITEM, "Potion of Healing", 2, 2)
    private val bless = ConditionToggle("t1", "Bless", enabled = false, flippable = true)
    // FR-53's row. Two of them, because the interesting failure is a filter that takes the wrong
    // chip — or all of them — rather than one that takes none.
    private val shieldBuff = AppliedBuff("b1", "Shield", "**+5** to AC")
    private val blessBuff = AppliedBuff("b2", "Bless", null)
    // H1: FR-30's row, 3 of 4 d8s remaining — the fixture the review's three pins share below.
    private val hitDie = TrackedResource("hd1", TrackerKind.HIT_DICE, "Hit Dice", 3, 4, dieSize = "d8")
    // FR-44's row, 2 of 2 uses left. The same class of field as `hitDice` above and the same trap.
    private val ability = TrackedResource("lu1", TrackerKind.LIMITED_USE, "Guiding Bolt", 2, 2)

    private val board = TrackerBoard(
        hp = hp,
        slots = listOf(slot),
        hitDice = listOf(hitDie),
        limitedUses = listOf(ability),
        pinnedItems = listOf(potion),
        allItems = listOf(potion),
        buffs = listOf(shieldBuff, blessBuff),
        activeToggles = listOf(bless),
        defenses = listOf(
            DamageDefense("d1", DefenseKind.RESISTANT, listOf("radiant", "necrotic"), "A Feature", 180),
        ),
        concentratingOn = "Bless",
    )

    /** A non-empty overlay: one delta, one toggle. Enough to take the `copy` path. */
    private val overlay = OptimisticOverlay.of(
        listOf(
            OptimisticChange.ValueDelta("s1", -1),
            OptimisticChange.ToggleTo("t1", enabled = true),
        ),
    )

    // --- the regression this file exists for --------------------------------

    /**
     * The board fields the overlay does not transform must survive it untouched. Asserted
     * on the two that exist today; the point of `copy` is that a third one added tomorrow
     * passes this by construction rather than by someone remembering.
     */
    @Test
    fun `fields the overlay does not transform survive a non-empty overlay`() {
        assertFalse("the overlay under test must not be a no-op", overlay.isEmpty)
        val applied = overlay.applyTo(board)

        assertEquals("defenses must not be dropped while a write is in flight",
            board.defenses, applied.defenses)
        assertEquals("Bless", applied.concentratingOn)
    }

    /**
     * The same claim from the other end: whatever the overlay does to a board, it cannot
     * turn a board that has defenses into one that does not — which is the state the
     * tracker actually rendered before the fix (Defenses section gone, then back).
     */
    @Test
    fun `a board with defenses still has them after every kind of change`() {
        val everything = OptimisticOverlay.of(
            listOf(
                OptimisticChange.ValueDelta("s1", -1),
                OptimisticChange.ValueAbsolute("hp1", 4),
                OptimisticChange.ToggleTo("t1", enabled = true),
                OptimisticChange.None("i1"),
            ),
        )
        val applied = everything.applyTo(board)
        assertEquals(1, applied.defenses.size)
        assertEquals(DefenseKind.RESISTANT, applied.defenses.single().kind)
        assertEquals(listOf("radiant", "necrotic"), applied.defenses.single().damageTypes)
    }

    // --- and the transformations it *is* supposed to make -------------------

    @Test
    fun `an empty overlay is the identity, not a rebuild`() {
        assertTrue(OptimisticOverlay.EMPTY.isEmpty)
        assertSame(board, OptimisticOverlay.EMPTY.applyTo(board))
    }

    @Test
    fun `the rows the overlay does address are still predicted`() {
        val applied = overlay.applyTo(board)
        assertEquals(2, applied.slots.single().value)
        assertTrue(applied.activeToggles.single().enabled)
        // Untouched rows come through unchanged rather than merely equal-looking.
        assertEquals(hp, applied.hp)
        assertEquals(listOf(potion), applied.allItems)
    }

    // --- H1: `hitDice` was missing from `applyTo`'s `copy` — a spend predicted nothing until
    // the write resolved, which is exactly the flicker `TrackerBoard.defenses` regressed on. ---

    /** A hit-die spend has to overlay the same frame it is tapped, like every other pip row. */
    @Test
    fun `a hit-die spend overlays immediately`() {
        val spend = OptimisticOverlay.of(listOf(OptimisticChange.ValueDelta("hd1", -1)))
        val applied = spend.applyTo(board)
        assertEquals(2, applied.hitDice.single().value)
    }

    /** Six rapid spends on 3 remaining floors at 0 — the same clamp every other row gets. */
    @Test
    fun `six rapid hit-die spends on 3 remaining floor at 0, never negative`() {
        val sixSpends = OptimisticOverlay.of(List(6) { OptimisticChange.ValueDelta("hd1", -1) })
        val applied = sixSpends.applyTo(board)
        assertEquals(0, applied.hitDice.single().value)
    }

    // --- FR-44: `limitedUses` is the next list that could have been forgotten in the same way ---

    /**
     * A use spent has to move the pips in the frame it is tapped.
     *
     * The write behind this row is an **absolute** (`update {path:['usesUsed']}`), so its
     * optimistic change is a `ValueAbsolute` rather than the `ValueDelta` every other pip row
     * emits — and the row still has to be in `applyTo`'s `copy` for either kind to land.
     */
    @Test
    fun `a limited-use spend overlays immediately`() {
        val spend = OptimisticOverlay.of(listOf(OptimisticChange.ValueAbsolute("lu1", 1)))
        val applied = spend.applyTo(board)
        assertEquals(1, applied.limitedUses.single().value)
    }

    /** And it is clamped into the row like every other, however the queue got there. */
    @Test
    fun `a limited-use row is clamped to its own total`() {
        val overshoot = OptimisticOverlay.of(listOf(OptimisticChange.ValueAbsolute("lu1", 9)))
        assertEquals(2, overshoot.applyTo(board).limitedUses.single().value)

        val undershoot = OptimisticOverlay.of(listOf(OptimisticChange.ValueDelta("lu1", -9)))
        assertEquals(0, undershoot.applyTo(board).limitedUses.single().value)
    }

    /** A write on some other row leaves this one exactly as it was — not merely equal-looking. */
    @Test
    fun `an unrelated write leaves the limited-use row untouched`() {
        assertEquals(listOf(ability), overlay.applyTo(board).limitedUses)
    }

    /** FR-22 direct entry: a later absolute wins over an earlier delta for the same row. */
    @Test
    fun `a direct-entry absolute on a hit die supersedes a queued spend`() {
        val spendThenSet = OptimisticOverlay.of(
            listOf(
                OptimisticChange.ValueDelta("hd1", -1),
                OptimisticChange.ValueAbsolute("hd1", 4),
            ),
        )
        val applied = spendThenSet.applyTo(board)
        assertEquals(4, applied.hitDice.single().value)
    }

    // --- FR-53 R4: the chip leaves the row immediately -----------------------

    /**
     * The whole of what a turn-off predicts: **that chip**, gone, and nothing else about the
     * board touched.
     *
     * This is the class KDoc's warning arriving on a new list. `buffs` is the field FR-53 adds,
     * and `copy`'s pass-through — which is what makes every other new field safe — is exactly
     * wrong for this one: left to it, the chip would sit on screen for the whole round trip after
     * its ✕ was tapped, which reads as a dead control and earns a second tap.
     */
    @Test
    fun `turning a buff off removes its chip and leaves the rest of the board alone`() {
        val applied = OptimisticOverlay.of(listOf(OptimisticChange.Removed("b1"))).applyTo(board)

        assertEquals(listOf(blessBuff), applied.buffs)
        assertEquals("the other lists are untouched", board.activeToggles, applied.activeToggles)
        assertEquals(board.slots, applied.slots)
        assertEquals(board.hp, applied.hp)
    }

    /**
     * A `Removed` naming nothing on the board changes nothing — the stale-id case, from the
     * overlay's side.
     *
     * `:core:data`'s intent already drops a tap whose id the board does not carry, so this is the
     * second gate rather than the only one; it matters because the overlay is applied to *every*
     * board this session produces, including ones rebuilt after a sync that ended the buff by
     * another route.
     */
    @Test
    fun `a removal for an unknown id changes nothing`() {
        val applied = OptimisticOverlay.of(listOf(OptimisticChange.Removed("nope"))).applyTo(board)
        assertEquals(board.buffs, applied.buffs)
    }

    /** Two removals fold into one set entry, and an empty one is still an empty overlay. */
    @Test
    fun `removals fold and an overlay of none is empty`() {
        val folded = OptimisticOverlay.of(
            listOf(OptimisticChange.Removed("b1"), OptimisticChange.Removed("b1")),
        )
        assertEquals(setOf("b1"), folded.removed)
        assertFalse("a pending removal is not an empty overlay", folded.isEmpty)
        assertTrue(OptimisticOverlay.of(emptyList()).isEmpty)
    }

    /**
     * A removal rides alongside the other three kinds without disturbing them, and an
     * `OptimisticChange.None` for the same property does not resurrect the chip.
     *
     * `None` means *"this op touches the property and we cannot predict the result"*, which is a
     * different statement from "nothing happened to it" — folding it as an erasure would have made
     * any op that declined to predict undo a sibling op that did.
     */
    @Test
    fun `a removal coexists with value and toggle predictions`() {
        val applied = OptimisticOverlay.of(
            listOf(
                OptimisticChange.ValueDelta("s1", -1),
                OptimisticChange.Removed("b2"),
                OptimisticChange.ToggleTo("t1", true),
                OptimisticChange.None("b2"),
            ),
        ).applyTo(board)

        assertEquals(2, applied.slots.single().value)
        assertEquals(listOf(shieldBuff), applied.buffs)
        assertTrue(applied.activeToggles.single().enabled)
    }
}
