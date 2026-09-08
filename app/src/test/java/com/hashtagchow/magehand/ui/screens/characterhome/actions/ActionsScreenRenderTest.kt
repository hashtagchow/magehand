package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.DamageLine
import com.hashtagchow.magehand.core.model.DamageRider
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.WeaponMastery
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

    // ---- BUG-7: the five state badges are inside the sentence too --------------

    /**
     * BUG-7, on the surface it was found on.
     *
     * The badges were `AssistChip(enabled = false)` — a clickable `Surface`, so a merging node of
     * its own that `RowShell`'s `mergeDescendants` does not absorb. Every one of them was drawn
     * and none of them was *said*: a screen-reader user got "Fireball" where a sighted player got
     * "Fireball, Concentration, Ritual, Unprepared, Switched off on the sheet". That is worse
     * than the FR-36 rider this file's first test is about, because these five are the whole
     * reason a row is dim, and dimness is the other channel a screen reader also cannot see.
     *
     * All four spell badges at once, because the four are independent (decision 5: *"the two
     * states below can coexist and both show"*) and the failure the `AssistChip` had was
     * per-composable — one badge fixed and three left behind would pass any single-label check.
     * Asserted as the whole merged text rather than by substring, so the **order** the row speaks
     * them in is pinned as well: this is one sentence, and a sentence has a word order.
     */
    @Test
    fun `every spell badge lands on the row's merged node, in the order the row draws them`() {
        val fireball = SpellEntry(
            propertyId = "s-fireball",
            name = "Fireball",
            level = 3,
            concentration = true,
            ritual = true,
            // `prepared` and `alwaysPrepared` both false — `showsUnpreparedBadge` is derived from
            // the FIELDS, never from `inactive` (decision 5), so the two badges below are two
            // independent statements and this row deliberately makes both.
            prepared = false,
            inactive = true,
            castingTime = "1 action",
            range = "150 feet",
        )
        compose.setMageHandContent {
            ActionsScreen(
                state = toActionsUiState(
                    creatureId = Sabriel.CREATURE_ID,
                    board = ActionBoard(spells = listOf(fireball)),
                    canWrite = false,
                ),
                onUse = { _, _, _ -> },
            )
        }

        val texts = compose.onNodeWithTag("actions:spell:s-fireball")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(
            listOf(
                "Fireball",
                "Concentration",
                "Ritual",
                "Unprepared",
                "Switched off on the sheet",
                "1 action · 150 feet",
            ),
            texts,
        )
    }

    /**
     * The other two badges, on the other row type — and the pair that carries BUG-7's cost most
     * plainly. `ActionEntryRow` dims for **two independent reasons** and its own comment says why
     * they are words rather than a colour: *"'greyed out' alone does not tell a player which of
     * the two to fix"*. Outside the merged node they were not words either, to the one user who
     * cannot see the grey — so the row said nothing at all about why it could not be used.
     */
    @Test
    fun `both action badges land on the row's merged node, in the order the row draws them`() {
        val smite = ActionEntry(
            propertyId = "a-smite",
            name = "Divine Smite",
            type = ActionType.ACTION,
            insufficientResources = true,
            inactive = true,
        )
        compose.setMageHandContent { ActionsScreen(state = stateOf(smite), onUse = { _, _, _ -> }) }

        val texts = compose.onNodeWithTag("actions:action:a-smite")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(
            listOf("Divine Smite", "Not enough resources", "Switched off on the sheet"),
            texts,
        )
    }

    // ---- FR-47: the weapon-mastery badge and the detail sheet's block -----------

    /** A chosen mastery with its rules sentence, as `ActionEngine` hands one to the surface. */
    private val battleaxe = ActionEntry(
        propertyId = "a-mastery",
        name = "Battleaxe",
        type = ActionType.ATTACK,
        attackRoll = 6,
        mastery = WeaponMastery("Topple", "The target makes a save or falls prone."),
        damage = listOf(DamageLine.of("1d12", "slashing")),
    )

    /**
     * FR-47 R3, asserted the way BUG-6 and BUG-7 taught this file to: through the **merged** node.
     *
     * A badge that existed outside the row's merging shell would draw correctly and be unreachable
     * as part of the sentence TalkBack speaks — the exact defect the five state badges had. Every
     * badge is on at once so the ruling's *"after the state badges and before the uses line"* is
     * pinned as an order and not just as presence: this is one sentence, and a sentence has a word
     * order.
     *
     * The rules text is deliberately **not** on the row. R7 puts it behind the row tap; a paragraph
     * on a list row would be read out in full on every scroll stop.
     */
    @Test
    fun `the mastery badge lands on the row's merged node, after the state badges`() {
        val dimmed = battleaxe.copy(
            propertyId = "a-mastery-dim",
            usesLeft = 2,
            usesMax = 3,
            insufficientResources = true,
            inactive = true,
        )
        compose.setMageHandContent { ActionsScreen(state = stateOf(dimmed), onUse = { _, _, _ -> }) }

        val texts = compose.onNodeWithTag("actions:action:a-mastery-dim")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(
            listOf(
                "Battleaxe",
                "+6 to hit",
                "Not enough resources",
                "Switched off on the sheet",
                "Mastery: Topple",
                "2 / 3 uses",
                "1d12 slashing",
            ),
            texts,
        )
        assertTrue(texts.none { it.contains("falls prone") })
    }

    /** A row with no mastery is exactly the row it was before FR-47 — no stray chip, no gap. */
    @Test
    fun `a row without a mastery gains nothing`() {
        compose.setMageHandContent {
            ActionsScreen(state = stateOf(battleaxe.copy(mastery = null)), onUse = { _, _, _ -> })
        }

        val texts = compose.onNodeWithTag("actions:action:a-mastery")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(listOf("Battleaxe", "+6 to hit", "1d12 slashing"), texts)
    }

    /**
     * R7's whole point: the tap that already opened the detail sheet now answers *"what does
     * Topple do?"*. The heading repeats the badge and the sentence is the server's own text.
     */
    @Test
    fun `tapping the row shows the mastery's rules text in the detail sheet`() {
        compose.setMageHandContent { ActionsScreen(state = stateOf(battleaxe), onUse = { _, _, _ -> }) }

        compose.onNodeWithTag("actions:action:a-mastery").performClick()

        compose.onNode(
            hasAnyAncestor(hasTestTag("actions:detail:a-mastery")) and
                hasTestTag("actions:detail:mastery"),
        ).assertIsDisplayed()
        assertEquals(
            listOf("Mastery: Topple", "The target makes a save or falls prone."),
            masteryBlockTexts(),
        )
    }

    /**
     * The block's own words, in order.
     *
     * Read off the **children** rather than off the block itself, because — unlike `RowShell` —
     * this one deliberately does not merge: it is a heading plus a paragraph in a scrolling sheet,
     * where a reader wants to stop at each, not a list row that has to be one sentence.
     */
    private fun masteryBlockTexts(): List<String> =
        compose.onNodeWithTag("actions:detail:mastery")
            .fetchSemanticsNode()
            .children
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }

    /**
     * R7: *"Bullet absent → `text = null`, the block shows the heading alone; a heading with no
     * body is still true."* Nothing is drawn in the gap — no *"no description"* placeholder, which
     * would be this app reporting on the sheet's completeness rather than on the weapon.
     */
    @Test
    fun `a mastery with no rules text shows the heading alone`() {
        val wordOnly = battleaxe.copy(propertyId = "a-word-only", mastery = WeaponMastery("Topple"))
        compose.setMageHandContent { ActionsScreen(state = stateOf(wordOnly), onUse = { _, _, _ -> }) }

        compose.onNodeWithTag("actions:action:a-word-only").performClick()

        assertEquals(listOf("Mastery: Topple"), masteryBlockTexts())
    }

    /** No mastery, no block — not an empty one, and not a heading with nothing under it. */
    @Test
    fun `the detail sheet has no mastery block for a row without one`() {
        compose.setMageHandContent {
            ActionsScreen(state = stateOf(battleaxe.copy(mastery = null)), onUse = { _, _, _ -> })
        }

        compose.onNodeWithTag("actions:action:a-mastery").performClick()

        compose.onAllNodesWithTag("actions:detail:mastery").assertCountEquals(0)
    }
}
