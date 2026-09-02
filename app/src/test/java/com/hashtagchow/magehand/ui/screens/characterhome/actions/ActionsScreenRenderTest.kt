package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.DamageLine
import com.hashtagchow.magehand.core.model.DamageRider
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-36 on the surface: the folded headline and the rider chip are on the row, and they are on
 * the node TalkBack stops at.
 *
 * The row is a merging shell (`RowShell`'s `mergeDescendants`), so the assertion goes through
 * the **merged** node's text — a chip that existed but sat outside the merge would pass an
 * `assertExists` and be unreachable in practice, which is BUG-6's class exactly. The detail
 * sheet's itemised riders are asserted after a tap, the way a player reaches them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActionsScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** A Rogue's finesse Rapier from the live capture, as `ActionEngine` now builds it from the capture's shape. */
    private val rapier = ActionEntry(
        propertyId = "a-rapier",
        name = "Rapier",
        type = ActionType.ATTACK,
        attackRoll = 5,
        damage = listOf(
            DamageLine(
                amount = "d8 + 3",
                damageType = "piercing",
                base = "d8",
                riders = listOf(
                    DamageRider("Finesse Modifiers", "add", "3"),
                    DamageRider("Sneak Attack", "add", "2d6"),
                ),
            ),
        ),
    )

    private val state = toActionsUiState(
        creatureId = Sabriel.CREATURE_ID,
        board = ActionBoard(actions = listOf(rapier)),
        canWrite = false,
    )

    @Test
    fun `the folded headline and the rider chip land on the row's merged node`() {
        compose.setMageHandContent { ActionsScreen(state = state, onUse = { _, _, _ -> }) }

        val row = compose.onNodeWithTag("actions:action:a-rapier")
        row.assertIsDisplayed()
        row.assertHasClickAction()
        // The merged node carries the row's whole sentence: name, headline, chip.
        row.assertTextContains("d8 + 3 piercing", substring = true)
        row.assertTextContains("+2d6 Sneak Attack", substring = true)
    }

    @Test
    fun `the detail sheet itemises every rider by name`() {
        compose.setMageHandContent { ActionsScreen(state = state, onUse = { _, _, _ -> }) }

        compose.onNodeWithTag("actions:action:a-rapier").performClick()

        // Scoped to the sheet: the row behind it still carries the same chip text.
        val inSheet = hasAnyAncestor(hasTestTag("actions:detail:a-rapier"))
        compose.onNode(inSheet and hasText("d8 + 3 piercing")).assertIsDisplayed()
        compose.onNode(inSheet and hasText("+3 Finesse Modifiers")).assertIsDisplayed()
        compose.onNode(inSheet and hasText("+2d6 Sneak Attack")).assertIsDisplayed()
    }
}
