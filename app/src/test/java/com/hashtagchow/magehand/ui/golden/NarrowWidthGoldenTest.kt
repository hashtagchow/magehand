package com.hashtagchow.magehand.ui.golden

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.ui.panes.PaneRow
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsScreen
import com.hashtagchow.magehand.ui.screens.characterhome.actions.toActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.AddItemSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryTab
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.testing.Sabriel
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
 * **The narrow-width wrap corpus** (design 19 decision 7): the two 1.9.1/1.11.0 defects that only
 * appear when a composable is genuinely short of room.
 *
 * ### The device, and why it is a `@Config` rather than a `Modifier.width`
 *
 * 320 dp is the narrowest width the app can meet — `minSdk` 30 phones in this class exist, and a
 * split-screen window on any phone is narrower still. Setting it as a Robolectric *qualifier*
 * means the emulated device is that wide, so every layout in the tree measures against it exactly
 * as it would on the hardware. A `Modifier.width(320.dp)` inside the composition would have proven
 * that the subject wraps at 320 dp while saying nothing about whether a phone ever hands it 320 dp
 * — and the sheets below take their width from the *window*, so it would not even have applied.
 *
 * The scale is 150 % on both, because that is the combination that produced the reports: the
 * physical screen does not grow when `ProvideUiScale` scales density, so 320 dp at 1.5× leaves a
 * composable about 213 dp to lay out in. This is the worst case the app supports, photographed.
 *
 * ### The two defects
 *
 *  - **the vertical "Add"** — the add-item sheet's action squeezed toward zero width and its
 *    label broke one character per line. The same defect class as BUG-4, on a `Row` that neither
 *    shrinks nor wraps its children.
 *  - **"Ite/m"** — an inventory row's name broken mid-word when the trailing equip control took
 *    the space it needed.
 *
 * Both are measuring outcomes, so both are pictures. `PaneSelectionTest`'s `FlowRow` scan and
 * `InventoryLayoutTest`'s rules cover the code; these cover the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NarrowWidthGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the add-item sheet on the narrowest phone`() {
        compose.captureGolden("AddItemSheet_narrow", scale = UiScale.LARGE_150) {
            // `isLocal = false`: the DiceCloud path, which is the one with the category chooser
            // above the form and therefore the one with the most to fit.
            AddItemSheet(isLocal = false, onAdd = {}, onDismiss = {})
        }
    }

    /**
     * The same sheet, switched to its **custom item** half — where the reported "Add" squeeze was.
     *
     * Reached by tapping the sheet's own mode switch rather than by a parameter, because there is
     * no parameter: `custom` is `rememberSaveable` state inside the composable, and the only way in
     * is the way a player takes. That this is possible at all is design 19 decision 0's argument
     * for Roborazzi over a layoutlib renderer — a post-interaction golden.
     */
    @Test
    fun `the add-item sheet's custom form on the narrowest phone`() {
        compose.setMageHandContent(scale = UiScale.LARGE_150) {
            AddItemSheet(isLocal = false, onAdd = {}, onDismiss = {})
        }

        compose.onNodeWithTag("inventory:add:mode").performClick()

        compose.commitGolden("AddItemSheet_custom_narrow")
    }

    @Test
    fun `inventory rows on the narrowest phone`() {
        // "Ration Pack (1 day)" beside an Equip button is the shape that ran out of row.
        compose.captureGolden("InventoryRow_narrow", scale = UiScale.LARGE_150) {
            InventoryTab(state = Sabriel.inventory())
        }
    }

    /**
     * **Q17** (docs/verification/FR-34-checklist-map.md, area Q): the other way a window runs out
     * of room — wide and *short*, the ~850×400 dp class a tablet split-screen or resized
     * multi-window hands the app. The method-level `@Config` replaces the class's narrow phone
     * for these two captures only; the corpus is still one file because the claim is the same
     * claim: a composable genuinely short of room, photographed.
     *
     * ### What the two pictures document
     *
     * 850 dp clears the 840 dp pane gate, which is width-only *by design* (14 decision 5), so the
     * checklist item is explicitly a documentation item: "record actual behavior — the accepted
     * cost or a real break". Two panes get ~425 dp each — the supported case, and the picture's
     * job is that nothing clips at 400 dp of height. Three panes get ~283 dp each, **below the
     * 320 dp floor the app is designed against** — that is the accepted cost itself, on film, so
     * the next design pass argues about a picture instead of an estimate.
     *
     * The Sheet pane is the one surface never photographed: it is a WebView, which renders
     * nothing under Robolectric — a golden of it would pin a blank rectangle.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w850dp-h400dp-land-xhdpi")
    fun `two panes in a wide short window`() {
        compose.captureGolden("PaneRow_wideshort_two") {
            PaneRow(panes = listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY)) { paneBody(it) }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w850dp-h400dp-land-xhdpi")
    fun `three panes in a wide short window go below the width floor`() {
        compose.captureGolden("PaneRow_wideshort_three") {
            PaneRow(
                panes = listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.ACTIONS),
            ) { paneBody(it) }
        }
    }

    @Composable
    private fun paneBody(surface: PaneSurface) {
        when (surface) {
            PaneSurface.TRACKER -> TrackerTab(state = Sabriel.tracker())
            PaneSurface.INVENTORY -> InventoryTab(state = Sabriel.inventory())
            PaneSurface.ACTIONS -> ActionsScreen(state = rageBoard, onUse = { _, _, _ -> })
            PaneSurface.SHEET -> Unit
        }
    }

    /** `ScreensGoldenTest`'s Rage fixture, on a board: enough content to show a pane's shape. */
    private val rageBoard = toActionsUiState(
        creatureId = Sabriel.CREATURE_ID,
        board = ActionBoard(
            actions = listOf(
                ActionEntry(
                    propertyId = "a-rage",
                    name = "Rage",
                    type = ActionType.BONUS,
                    cost = ActionCost(
                        attributes = listOf(CostLine("Rage", amount = 1, available = 2)),
                    ),
                    uses = ActionUses(max = 3, used = 1),
                    description = "In a frenzy, you gain advantage on Strength checks and " +
                        "saving throws, and resistance to bludgeoning, piercing and " +
                        "slashing damage.",
                ),
            ),
        ),
        canWrite = true,
    )
}
