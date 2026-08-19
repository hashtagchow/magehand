package com.hashtagchow.magehand.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-destination auto-open's guard (04's "last-used character's Tracker"), as a
 * regression test for the **1.4.0 sweep's rotation defect**.
 *
 * ### The defect, in one sentence
 *
 * `MageHandNavHost` opened the last-used character from a `LaunchedEffect` keyed only on the
 * creature id, so every Activity recreation — a rotation, or a uiMode change — re-ran it and
 * pushed a *duplicate* `CharacterHome` entry on top of the one `rememberNavController` had just
 * restored. The screen looked identical, but every `rememberSaveable` inside it is scoped to the
 * back-stack entry, and the new entry started them all at their defaults. The visible symptom
 * was the selected tab snapping back to Tracker (evidence: `pl-L6-2.png` / `pl-L6-5.png`).
 *
 * ### What this file pins, and what it deliberately does not
 *
 * It pins [shouldOpenInitialCharacter] — the *rule* that the auto-open is once per app start
 * rather than once per composition. That is the part with a decision in it, and it is the part a
 * future edit is most likely to simplify back into "id != null".
 *
 * It does **not** pin that `rememberSaveable` restores the flag across an Activity recreation.
 * `:app` has no Compose or Robolectric harness (see `docs/DEVICE-CHECKLIST.md`), and a JVM test
 * standing in for the Activity's saved-state bundle would be asserting a mock rather than the
 * mechanism. **The device is the proof of restoration**, and it is on the sweep as L10.
 */
class StartDestinationNavigationTest {

    @Test
    fun `a cold start with a last-used character opens it`() {
        assertTrue(shouldOpenInitialCharacter("creature-1", alreadyOpened = false))
    }

    @Test
    fun `a cold start with no last-used character opens nothing`() {
        // First run, or an account that has never opened a character: the user stops at the
        // list, which is the destination the graph already started at.
        assertFalse(shouldOpenInitialCharacter(null, alreadyOpened = false))
    }

    @Test
    fun `a second composition with the flag restored does not navigate again`() {
        // THE regression. The id is still there after a rotation — it lives on a retained
        // ViewModel — so the id alone cannot be the guard. This is the case that used to
        // return true and push a duplicate entry over the restored one.
        assertFalse(
            "an Activity recreation must not re-open the character: the duplicate back-stack " +
                "entry is what reset the selected tab",
            shouldOpenInitialCharacter("creature-1", alreadyOpened = true),
        )
    }

    @Test
    fun `the flag wins over the id, which is what makes it a guard at all`() {
        // Stated as a property rather than as a third example: whatever the id says, a set flag
        // answers false. An edit that reordered the condition into `alreadyOpened || id != null`
        // would pass the two cases above and fail here.
        listOf(null, "", "creature-1", "creature-2").forEach { id ->
            assertFalse("id=$id", shouldOpenInitialCharacter(id, alreadyOpened = true))
        }
    }
}
