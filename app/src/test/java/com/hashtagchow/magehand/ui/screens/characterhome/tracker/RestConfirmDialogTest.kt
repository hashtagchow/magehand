package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **FR-34 layer 1's dialog exemplar** (docs/design/19-ui-test-infrastructure.md decision 4):
 * *"rest-confirm dialog wording ('restored to full', FR-20's ride-along) asserted through real
 * composition"*.
 *
 * ### Why a dialog is its own pattern
 *
 * A dialog is a **separate window**. It does not sit in the host composition's view hierarchy, it
 * inherits composition locals but not modifiers, and `testTagsAsResourceId` has to be re-declared
 * inside it — `HpNumberPadDialog` and this one both carry that re-declaration with a comment
 * saying why. A render test that only ever asserted on inline content would never touch that
 * machinery, so this file proves once that the semantics tree reaches into a dialog window at all.
 *
 * ### What is asserted, and what `TrackerUiStateTest` already owns
 *
 * `rowsRestoredBy` is a pure function with its own exhaustive test — which rows a rest of each
 * kind puts back is decided there and is not re-litigated here. What this adds is the part that
 * only exists once the dialog is drawn: that the heading really is FR-20 decision 4's *"Restored
 * to full"* rather than 04's original "will reset" (which is the wording that shipped and caused
 * the 2026-08-21 Heroic Inspiration triage), that the not-undoable warning is on screen beside it,
 * and that the rows named in the list are the ones the function chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestConfirmDialogTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a long rest promises the rows will be restored to full, and says it cannot be undone`() {
        compose.setMageHandContent {
            RestConfirmDialog(
                kind = RestKind.LONG,
                state = Sabriel.tracker(),
                onConfirm = {},
                onDismiss = {},
            )
        }

        // FR-20 decision 4's verb. "Restores", not "resets": `creature.methods.rest` clears the
        // qualifying properties' `damage`, so a row can only move *up* — and "reset" is the word a
        // player read as "returns to some rule-defined starting value" when a mis-configured row
        // landed on 0.
        compose.onNodeWithText("Restored to full:").assertIsDisplayed()

        // The rows, at their *current* values: the heading says where they are going and the
        // numbers say where they are now.
        compose.onNodeWithTag("tracker:rest:row:${Sabriel.firstLevel.propertyId}")
            .assertIsDisplayed()
        compose.onNodeWithText("•  1st Level  —  3 / 4").assertIsDisplayed()
        // Already full, and still listed — seeing "1 / 1" is how a player learns the rest gains
        // them nothing here.
        compose.onNodeWithText("•  Magic Initiate  —  1 / 1").assertIsDisplayed()

        // Heroic Inspiration carries no reset rule, so no rest touches it. Its *absence* from this
        // list is the whole of FR-20's answer to the triage that started it.
        compose.onNodeWithTag("tracker:rest:row:${Sabriel.heroicInspiration.propertyId}")
            .assertDoesNotExist()

        // Not-undoable is stated in the dialog *and* enforced in the type system (`WriteOp.Rest`'s
        // `inverse` is null). This is the half a user can see.
        compose.onNodeWithTag("tracker:rest:warning").assertIsDisplayed()
        compose.onNodeWithText("A rest cannot be undone.").assertIsDisplayed()
    }

    @Test
    fun `a short rest lists only what a short rest restores`() {
        compose.setMageHandContent {
            RestConfirmDialog(
                kind = RestKind.SHORT,
                state = Sabriel.tracker(),
                onConfirm = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag("tracker:rest:row:${Sabriel.secondWind.propertyId}").assertIsDisplayed()
        // "A short rest silently restored my long-rest slots" is a bug you only find at the table.
        compose.onNodeWithTag("tracker:rest:row:${Sabriel.firstLevel.propertyId}").assertDoesNotExist()
    }

    @Test
    fun `the long-rest note about the server's own rules is absent on a local character`() {
        // 09 decision 7, corrected 2026-08-28: a local long rest heals to max unconditionally, so
        // the hedge about what *the server* also restores has nothing to say. `hasConnection` is
        // the gate, and a local character is the only thing that turns it off.
        compose.setMageHandContent {
            RestConfirmDialog(
                kind = RestKind.LONG,
                state = Sabriel.tracker().copy(hasConnection = false),
                onConfirm = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("The server also applies", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the long-rest note about the server's own rules is present on a server character`() {
        // The positive half of the test above (FR-34 review finding 5): without it, rewording
        // the string leaves both tests green while the hedge silently vanishes for everyone.
        compose.setMageHandContent {
            RestConfirmDialog(
                kind = RestKind.LONG,
                state = Sabriel.tracker(),
                onConfirm = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("The server also applies", substring = true).assertIsDisplayed()
    }

    @Test
    fun `confirming dispatches once and closes the dialog`() {
        var confirms = 0
        var dismisses = 0
        compose.setMageHandContent {
            RestConfirmDialog(
                kind = RestKind.LONG,
                state = Sabriel.tracker(),
                onConfirm = { confirms++ },
                onDismiss = { dismisses++ },
            )
        }

        compose.onNodeWithTag("tracker:rest:confirm").performClick()

        // Both, in that order: the button calls `onConfirm(); onDismiss()`, and a dialog that
        // dispatched a *non-undoable* rest without closing itself would be one tap from a second.
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
    }
}
