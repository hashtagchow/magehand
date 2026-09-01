package com.hashtagchow.magehand.ui.golden

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryCustomizeSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryLayoutKeys
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryTab
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
 * The inventory tab, in FR-16's two states: every section open, and every section folded shut.
 *
 * ### Why both, when the *rule* already has a test
 *
 * `InventoryLayoutTest` decides which sections collapse and what a stored entry means;
 * `InventoryUiStateTest` decides what each section contains. Neither has ever looked at the
 * result. The pair of pictures below is what makes the collapse *visible*: a header with its rows
 * under it, and the same header with them gone — including that the header keeps its summary line,
 * which is the thing that stops a collapsed section from reading as an empty one.
 *
 * A collapsed golden also catches the specific regression 13 decision 3 is nervous about: the
 * items are still *filed* under the section, so a collapse that quietly moved them into Gear would
 * look almost right here and be wrong everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventoryGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `sections expanded`() {
        compose.captureGolden("InventoryScreen_expanded") {
            InventoryTab(state = Sabriel.inventory())
        }
    }

    @Test
    fun `sections collapsed`() {
        // Every section this character has, folded. The wallet is in the list too: its collapse is
        // deliberately ephemeral rather than persisted (13 decision 3's exception), but it
        // *renders* collapsed the same way, and a golden of "everything shut" that left one block
        // open would be a picture of an inconsistency rather than of the state.
        val collapsed = listOf(
            InventoryLayoutKeys.WALLET,
            InventoryLayoutKeys.EQUIPPED,
            InventoryLayoutKeys.WEAPONS,
            InventoryLayoutKeys.GEAR,
            InventoryLayoutKeys.container("cont-backpack"),
        ).map { InventoryLayoutEntry(key = it, collapsed = true) }

        compose.captureGolden("InventoryScreen_collapsed") {
            InventoryTab(state = Sabriel.inventory(layout = collapsed))
        }
    }

    /**
     * FR-35's customize sheet, in the state a player first opens it in.
     *
     * ### Why the default state and not a sorted one
     *
     * Because this is the picture that carries the most that no other test can see, and three of
     * those things are only true here:
     *
     *  - the **disabled direction toggle** (decision 6). `InventorySortControlTest` asserts it is
     *    disabled and not absent; only a picture says whether a disabled segmented control is
     *    still *legible*, which is the exact defect BUG-3 was — Material's 38 % on this app's
     *    palette made the customize sheet's arrows all but vanish. A new disabled control on the
     *    same sheet is precisely where that would recur.
     *  - the **"Sections" heading**, which FR-35 added because the section list is no longer the
     *    first thing under the subtitle. Whether the sheet now reads as three groups or as one
     *    long run is a question only a picture answers.
     *  - four radio labels and two segment labels at 411 dp — the wrap class the corpus exists
     *    for, on the controls this wave introduced.
     *
     * A sorted variant would show one radio's dot in a different row and an enabled toggle, which
     * `InventorySortControlTest` already pins behaviourally. One golden, per the ledger.
     *
     * `commitGolden` rather than `captureGolden`, because the subject is a `ModalBottomSheet` and
     * `captureScreenRoboImage` has to photograph the sheet's own window — FR-34.md §2's deviation
     * 2, and the same reason `AddItemSheet`'s goldens take this path.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `the customize sheet's sort controls`() {
        compose.setMageHandContent {
            InventoryCustomizeSheet(
                state = Sabriel.inventory().customize,
                onDismiss = {},
                onMove = { _, _ -> },
                onSetHidden = { _, _ -> },
                onReset = {},
                onSetSortCriterion = {},
                onSetSortDirection = {},
            )
        }
        compose.commitGolden("InventoryCustomizeSheet_sort")
    }
}
