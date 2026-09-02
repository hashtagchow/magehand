package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * A Rogue's finesse Rapier from the live capture, as `ActionEngine` now builds it from the
     * capture's shape — through [DamageLine.of], which is the only path production code has
     * (review finding 12: the first cut hand-built a headline no engine could have produced, so
     * the test could have passed a fold that did not exist).
     */
    private val rapier = ActionEntry(
        propertyId = "a-rapier",
        name = "Rapier",
        type = ActionType.ATTACK,
        attackRoll = 5,
        damage = listOf(
            DamageLine.of(
                base = "d8",
                damageType = "piercing",
                riders = listOf(
                    DamageRider("Finesse Modifiers", "add", "3"),
                    DamageRider("Sneak Attack", "add", "2d6"),
                ),
            ),
        ),
    )

    private fun stateOf(vararg actions: ActionEntry) = toActionsUiState(
        creatureId = Sabriel.CREATURE_ID,
        board = ActionBoard(actions = actions.toList()),
        canWrite = false,
    )

    private val state = stateOf(rapier)

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

    /**
     * A non-`add` operation is a chip like any other, and it has to reach the **merged** node —
     * the same reachability BUG-6 and the FR-36 chip's first recording were both about. An
     * operation this build has never seen is the case where being unreachable is worst: the row
     * would silently read as an ordinary hit.
     */
    @Test
    fun `a non-add rider chip reaches the row's merged node`() {
        val odd = ActionEntry(
            propertyId = "a-odd",
            name = "Odd Strike",
            type = ActionType.ATTACK,
            damage = listOf(
                DamageLine.of("d6", "fire", listOf(DamageRider("Doubled", "mul", "2"))),
            ),
        )
        compose.setMageHandContent { ActionsScreen(state = stateOf(odd), onUse = { _, _, _ -> }) }

        val row = compose.onNodeWithTag("actions:action:a-odd")
        row.assertTextContains("d6 fire", substring = true)
        row.assertTextContains("Doubled · mul 2", substring = true)
    }

    /**
     * A rider-less row is **exactly** what it was before FR-36 — asserted as the merged node's
     * whole text, not as a substring, because the regression this guards against is FR-36
     * leaving a stray separator, an empty chip or a trailing space on the ordinary row that most
     * of the list is made of.
     */
    @Test
    fun `a rider-less row's merged text is name, attack bonus and damage line and nothing else`() {
        val axe = ActionEntry(
            propertyId = "a-axe",
            name = "Greataxe",
            type = ActionType.ATTACK,
            attackRoll = 6,
            damage = listOf(DamageLine.of("1d12", "slashing")),
        )
        compose.setMageHandContent { ActionsScreen(state = stateOf(axe), onUse = { _, _, _ -> }) }

        val texts = compose.onNodeWithTag("actions:action:a-axe")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(listOf("Greataxe", "+6 to hit", "1d12 slashing"), texts)
    }

    /**
     * A zero `add` rider reaches the row **not at all** (architect ruling, 2026-09-02): the merged
     * sentence of a Str-10 character's weapon is the sentence of a weapon with no effects on it.
     *
     * Asserted as the whole merged text rather than as an absent substring, because the two ways
     * this can go wrong produce different strings — a fold gives `d6 + 0 bludgeoning`, a chip
     * gives a separate `+0 Ability Modifiers` — and the row's contract is that neither exists.
     */
    @Test
    fun `a zero rider row's merged text is the same as a rider-less row's`() {
        val unarmed = ActionEntry(
            propertyId = "a-zero",
            name = "Unarmed Strike",
            type = ActionType.ATTACK,
            damage = listOf(
                DamageLine.of("d6", "bludgeoning", listOf(DamageRider("Ability Modifiers", "add", "0"))),
            ),
        )
        compose.setMageHandContent {
            ActionsScreen(state = stateOf(unarmed), onUse = { _, _, _ -> })
        }

        val texts = compose.onNodeWithTag("actions:action:a-zero")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(listOf("Unarmed Strike", "d6 bludgeoning"), texts)
        assertTrue(texts.none { it.contains("+0") })
    }

    /**
     * ...and the **detail sheet is where it shows up** (pre-release review M3). The ruling gives
     * a zero rider exactly one surface, and a rule with one surface needs a test on that surface
     * or it is a rule about nothing: without this, `DamageFacts` could iterate `chips` instead of
     * `riders` and every other assertion in this file would still pass while the effect vanished
     * from the app entirely.
     */
    @Test
    fun `the detail sheet is where a zero rider is visible`() {
        val unarmed = ActionEntry(
            propertyId = "a-zero-sheet",
            name = "Unarmed Strike",
            type = ActionType.ATTACK,
            damage = listOf(
                DamageLine.of("d6", "bludgeoning", listOf(DamageRider("Ability Modifiers", "add", "0"))),
            ),
        )
        compose.setMageHandContent {
            ActionsScreen(state = stateOf(unarmed), onUse = { _, _, _ -> })
        }

        compose.onNodeWithTag("actions:action:a-zero-sheet").performClick()

        val inSheet = hasAnyAncestor(hasTestTag("actions:detail:a-zero-sheet"))
        compose.onNode(inSheet and hasText("d6 bludgeoning")).assertIsDisplayed()
        compose.onNode(inSheet and hasText("+0 Ability Modifiers")).assertIsDisplayed()
    }

    /**
     * An unnamed effect chips as its amount alone — no dangling space where the name would be
     * (review finding 7). TalkBack reads the merged sentence, and a trailing separator is heard.
     */
    @Test
    fun `a blank-named rider chips as its amount alone`() {
        val unnamed = ActionEntry(
            propertyId = "a-unnamed",
            name = "Unnamed Rider",
            type = ActionType.ATTACK,
            damage = listOf(
                DamageLine.of("d6", "cold", listOf(DamageRider("", "add", "1d4"))),
            ),
        )
        compose.setMageHandContent {
            ActionsScreen(state = stateOf(unnamed), onUse = { _, _, _ -> })
        }

        val texts = compose.onNodeWithTag("actions:action:a-unnamed")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(listOf("Unnamed Rider", "d6 cold", "+1d4"), texts)
    }

    /**
     * The sheet headlines the server's verbatim `base` and lists every rider under it (review
     * finding 5): with `d8 + 3` at the top *and* `+3 Finesse Modifiers` beneath, the audit view
     * read as `d8 + 3 + 3 + 2d6`. The row keeps the folded headline; only the sheet changed.
     */
    @Test
    fun `the detail sheet headlines the verbatim base and itemises every rider by name`() {
        compose.setMageHandContent { ActionsScreen(state = state, onUse = { _, _, _ -> }) }

        compose.onNodeWithTag("actions:action:a-rapier").performClick()

        // Scoped to the sheet: the row behind it still carries the folded headline.
        val inSheet = hasAnyAncestor(hasTestTag("actions:detail:a-rapier"))
        compose.onNode(inSheet and hasText("d8 piercing")).assertIsDisplayed()
        compose.onNode(inSheet and hasText("+3 Finesse Modifiers")).assertIsDisplayed()
        compose.onNode(inSheet and hasText("+2d6 Sneak Attack")).assertIsDisplayed()
        compose.onAllNodes(inSheet and hasText("d8 + 3 piercing")).assertCountEquals(0)
    }
}
