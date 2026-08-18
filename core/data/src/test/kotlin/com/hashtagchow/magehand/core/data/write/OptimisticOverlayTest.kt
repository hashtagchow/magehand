package com.hashtagchow.magehand.core.data.write

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

    private val board = TrackerBoard(
        hp = hp,
        slots = listOf(slot),
        pinnedItems = listOf(potion),
        allItems = listOf(potion),
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
}
