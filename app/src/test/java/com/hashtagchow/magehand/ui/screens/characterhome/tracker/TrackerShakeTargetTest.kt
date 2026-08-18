package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.TrackerBoard

/**
 * The rollback shake's one addressing rule: *if the row it is aimed at is inside the shut
 * "N inactive" drawer, open the drawer*.
 *
 * This file exists because the failure mode is silent. A shake that targets a chip which
 * is not composed does not crash, does not log and does not look different in a
 * screenshot — the user simply gets a snackbar saying something did not save, with nothing
 * on screen saying which condition it was. Only an animation is missing, and only if you
 * knew to expect it. So the decision is a pure function ([shakeIsHiddenByExpander]) and
 * lives here rather than inside a `LaunchedEffect` that only an instrumented test could
 * reach.
 */
class TrackerShakeTargetTest {

    private val bless = ConditionChipState("bless", "Bless", enabled = false, canFlip = true)
    private val rage = ConditionChipState("rage", "Rage", enabled = false, canFlip = true)
    private val inactive = listOf(bless, rage)

    @Test
    fun `a shake aimed into a shut drawer opens it`() {
        assertTrue(
            shakeIsHiddenByExpander(ShakeSignal("bless", token = 1L), inactive, expanded = false),
        )
    }

    /**
     * The drawer is already open, so the chip is already composed and its own `shakeOn`
     * will fire. Re-opening an open drawer is not a no-op in Compose — it would be a state
     * write on every recomposition of this branch — so the rule has to say no here.
     */
    @Test
    fun `an open drawer needs no help`() {
        assertFalse(
            shakeIsHiddenByExpander(ShakeSignal("bless", token = 1L), inactive, expanded = true),
        )
    }

    /**
     * The common case, and the one that must not open anything: a rolled-back HP change or
     * spell slot has nothing to do with the conditions section, and a drawer that sprang
     * open every time an unrelated write failed would be worse than the missing shake.
     */
    @Test
    fun `a shake aimed at a row outside the drawer leaves it shut`() {
        assertFalse(
            shakeIsHiddenByExpander(ShakeSignal("hp1", token = 1L), inactive, expanded = false),
        )
    }

    /**
     * `TrackerWriteFailure.propertyId` is nullable because a rest belongs to no single row.
     * Such a failure shakes nothing anywhere, so it must not be read as "matches the first
     * chip" or as "matches a chip with a null id".
     */
    @Test
    fun `a failure that belongs to no row opens nothing`() {
        assertFalse(shakeIsHiddenByExpander(ShakeSignal(null, token = 1L), inactive, expanded = false))
        assertFalse(shakeIsHiddenByExpander(null, inactive, expanded = false))
    }

    @Test
    fun `an empty drawer cannot be hiding anything`() {
        assertFalse(
            shakeIsHiddenByExpander(ShakeSignal("bless", token = 1L), emptyList(), expanded = false),
        )
    }

    /**
     * The scenario in one assertion, using the real splitter rather than a hand-built list:
     * an unpinned, switched-off toggle is exactly what lands in the drawer, and it is
     * exactly what a failed "turn Bless on" rolls back to.
     */
    @Test
    fun `the drawer the tracker actually builds is the one this rule is asked about`() {
        val state = toTrackerUiState(
            creatureId = "FakeCreature23456",
            board = TrackerBoard(
                activeToggles = listOf(
                    ConditionToggle(
                        propertyId = "bless",
                        name = "Bless",
                        enabled = false,
                        flippable = true,
                    ),
                ),
            ),
            connection = ConnectionState.LIVE,
            lastSyncedAt = null,
            isShowingSnapshot = false,
        )
        assertTrue("an off, unpinned toggle belongs in the drawer", state.conditions.isEmpty())
        assertTrue(
            shakeIsHiddenByExpander(
                ShakeSignal("bless", token = 7L),
                state.inactiveConditions,
                expanded = false,
            ),
        )
    }
}
