package com.hashtagchow.magehand.ui.golden

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.data.local.LocalActionBoard
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionDetailSheet
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsScreen
import com.hashtagchow.magehand.ui.screens.characterhome.actions.AddActionSheet
import com.hashtagchow.magehand.ui.screens.characterhome.actions.detailFor
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState
import com.hashtagchow.magehand.ui.testing.captureGolden
import com.hashtagchow.magehand.ui.testing.commitGolden
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-49's goldens (docs/design/20-local-spells-and-attacks.md), per design 19 decision 7's rule
 * that a wave seeds pictures of what it draws.
 *
 * ### The four, and why each one earns a file
 *
 * - **`ActionDetailSheet_higher_levels`** — decision 5's whole deliverable. The heading, the
 *   paragraph, and their position *directly above the Use block* are a layout claim, which is the
 *   one kind of claim a unit test cannot make: `ActionsUiStateTest` can assert that
 *   `higherLevels` is non-null and a render test can assert the node exists, and neither would
 *   notice the block drifting below the Use button where a player deciding which slot to burn
 *   would have to scroll past the thing they are about to press to read the answer.
 * - **`AddActionSheet_catalog`** — decision 6's catalog half, with the Spell/Attack filter and
 *   the one-line summaries. 319 rows of real SRD prose in a list whose rows are two lines tall is
 *   exactly the shape that wraps badly, and BUG-4's whole class of defect lives there.
 * - **`AddActionSheet_form`** — decision 6's *"a pick pre-fills the form"*, photographed **after
 *   the tap**. This is design 19 decision 0's argument for Roborazzi over a layoutlib renderer
 *   made concrete: the interesting state of this sheet is the one that only exists after an
 *   interaction, and a preview renderer cannot reach it at all. It is also the picture that would
 *   catch the pre-fill silently emptying — a form full of blank boxes looks exactly like a custom
 *   form, and only a picture says which one is on screen.
 * - **`ActionsScreen_local_empty`** — decision 1's empty state with its Add. The tab is now
 *   always present, so this is the **first thing** every new local character sees on it; a
 *   missing button here is a character that cannot be given a spell.
 *
 * ### Store safety (design 19 decision 9)
 *
 * Every string in this corpus is SRD text or an invented character name. No host, no token, no
 * party identifier — and the catalog sheet draws the bundled SRD data, which
 * `tools/public-gate.sh` already scans in its source-root mode.
 *
 * ### Re-recording
 *
 * Design 19 decision 8, unchanged and worth repeating on a wave that adds four: a golden pins
 * whatever the code does today, so `recordRoborazziDebug` requires eyeballing the diffs like a
 * code review — or the golden certifies the bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalSpellsGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Fireball on a level-5 caster: a leveled spell with an upcast paragraph, a slot picker behind
     * the Use button, and every scalar the local model collects.
     *
     * Built through the **real** `LocalActionBoard` rather than by hand-constructing a
     * `SpellEntry`, so the picture is of what the app produces from a row rather than of what a
     * test thinks it produces — the `DamageLine.of` lesson (FR-36 finding 12) applied to a golden.
     */
    @Test
    fun `the action detail sheet on a local spell that upcasts`() {
        val detail = toActionsUiState(
            creatureId = "local-1",
            board = LocalActionBoard.build(listOf(fireball)),
            spellSlots = listOf(slot(level = 3, remaining = 2, total = 3)),
            canWrite = true,
            usesAreUndoable = true,
        ).detailFor("fireball")

        compose.captureGolden("ActionDetailSheet_higher_levels") {
            ActionDetailSheet(state = checkNotNull(detail), onUse = { _, _, _ -> }, onDismiss = {})
        }
    }

    /** Decision 1's empty state: the short line, and the Add that makes the tab worth having. */
    @Test
    fun `the local actions tab before anything has been added`() {
        compose.captureGolden("ActionsScreen_local_empty") {
            ActionsScreen(
                state = toActionsUiState(creatureId = "local-1", board = emptyBoard),
                onUse = { _, _, _ -> },
                onAdd = {},
            )
        }
    }

    /** Decision 6's catalog half, on the default Spell filter. */
    @Test
    fun `the add sheet listing the spell catalog`() {
        compose.captureGolden("AddActionSheet_catalog") {
            AddActionSheet(onAdd = {}, onDismiss = {})
        }
    }

    /**
     * Decision 6's *"a catalog pick pre-fills the custom form"* — captured **after the tap**.
     *
     * `acid-splash` rather than `fireball`: the list is sorted by level then name, so the first
     * cantrip is the first row, and a golden that had to scroll to find its subject would be a
     * golden of a scroll position. What it shows is the point either way — a form whose name,
     * level chip, scalars and description arrived from the catalog and are all editable.
     */
    @Test
    fun `the add sheet after a catalog pick pre-fills the form`() {
        compose.setMageHandContent { AddActionSheet(onAdd = {}, onDismiss = {}) }
        compose.onNodeWithTag("actions:add:catalog:acid-splash").performClick()
        compose.commitGolden("AddActionSheet_form")
    }

    private companion object {
        /**
         * A local Fireball row, as the add sheet would have written it — the SRD's own text,
         * trimmed only where a golden of four paragraphs would be a golden of a scrollbar.
         */
        val fireball = LocalTrackerRow(
            id = "fireball",
            characterId = "local-1",
            kind = LocalRowKind.SPELL,
            label = "Fireball",
            total = 0,
            current = 0,
            reset = null,
            sortIndex = 0,
            description = "A bright streak flashes from your pointing finger to a point you " +
                "choose within range and then blossoms with a low roar into an explosion of " +
                "flame.",
            catalogId = "fireball",
            spellLevel = 3,
            higherLevels = "When you cast this spell using a spell slot of 4th level or higher, " +
                "the damage increases by 1d6 for each slot level above 3rd.",
            castingTime = "1 action",
            range = "150 feet",
            components = "V, S, M (A tiny ball of bat guano and sulfur.)",
            duration = "Instantaneous",
        )

        val emptyBoard = LocalActionBoard.build(emptyList())

        /** A tracker slot row as `LocalTrackerBoard` publishes one, for the picker behind Use. */
        fun slot(level: Int, remaining: Int, total: Int) = TrackedResource(
            propertyId = "slot-$level",
            kind = TrackerKind.SPELL_SLOT,
            name = "$level Level",
            value = remaining,
            total = total,
            spellSlotLevel = level,
        )
    }
}
