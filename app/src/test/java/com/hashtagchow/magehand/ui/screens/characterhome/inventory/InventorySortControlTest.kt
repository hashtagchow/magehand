package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-35's control, **rendered** — the behavioural half of `InventoryUiStateTest`'s pure claims
 * (design 19 decision 4's exemplar patterns, `RestConfirmDialogTest` and `DmCardRenderTest` as the
 * templates).
 *
 * ### What a composition says here that a unit test cannot
 *
 * `InventorySortPlanTest` proves the comparator orders a list, and `InventoryUiStateTest` proves
 * `toInventoryUiState` puts that order on the state. Neither has ever looked at a screen, so
 * between them they leave the one thing the player actually does entirely untested: **press the
 * radio and watch the rows move.** The first test below is that gesture end to end — a real tap on
 * a real `RadioRow` inside a real `ModalBottomSheet`, whose callback drives the same state the tab
 * recomposes from, and then the rows' **laid-out positions** are read back off the tab.
 *
 * Positions and not a `UiState` field, deliberately: a state assertion would pass just as happily
 * if `InventoryTab` drew its sections from something else, which is exactly the seam design 19
 * exists to cover.
 *
 * ### The other two claims
 *
 * Decision 6's disabled direction (rendered, not derived — the state rule is
 * `InventoryUiStateTest`'s), and the spoken sentences on the merged semantics tree, which is the
 * E/P5 item class and the only place the TalkBack wording is ever observable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InventorySortControlTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The tab and its customize sheet, wired through one piece of state — which is what the two
     * view models do (`CharacterHomeViewModel.setInventorySortCriterion` writes the store, the
     * store re-emits, `toInventoryUiState` rebuilds the tab). The store and the coroutines are
     * the part this test is not about; the wiring from *control* to *rendered order* is.
     */
    private val sort = mutableStateOf(InventorySort.DEFAULT)
    private val sheetOpen = mutableStateOf(true)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TabAndSheet() {
        val state = Sabriel.inventory(sort = sort.value)
        InventoryTab(state = state)
        if (sheetOpen.value) {
            InventoryCustomizeSheet(
                state = state.customize,
                onDismiss = { sheetOpen.value = false },
                onMove = { _, _ -> },
                onSetHidden = { _, _ -> },
                onReset = {},
                onSetSortCriterion = { sort.value = sort.value.copy(criterion = it) },
                onSetSortDirection = { sort.value = sort.value.copy(direction = it) },
            )
        }
    }

    /** Where a row's top edge sits on the tab, in the root's coordinates. */
    private fun rowTop(propertyId: String): Float =
        compose.onNodeWithTag("inventory:row:$propertyId").getUnclippedBoundsInRoot().top.value

    /**
     * Closes the sheet the way its scrim does.
     *
     * A `ModalBottomSheet` renders in its own window over the tab, and the assertions below are
     * about the tab's **layout**, so the sheet comes down first. Driving the real `onDismiss`
     * rather than reaching past it keeps the flow the player's: tap the radio, close the sheet,
     * look at the list.
     */
    private fun closeSheet() {
        compose.runOnIdle { sheetOpen.value = false }
        compose.waitForIdle()
    }

    /**
     * **The gesture, end to end.**
     *
     * `Torch` (3 × 1 lb) is above `Component Pouch` (1 × 2 lb) in Gear's sheet order, and every
     * criterion swaps them — so one pair of positions witnesses the whole path from a tap on a
     * radio to a row moving on screen.
     */
    @Test
    fun `tapping the Name radio flips the rendered order of the Gear rows`() {
        compose.setMageHandContent { TabAndSheet() }

        // Before: the sheet's own order, which is what every build before FR-35 drew.
        closeSheet()
        assertTrue(
            "sheet order puts Torch above Component Pouch",
            rowTop("c-torch") < rowTop("c-component"),
        )

        compose.runOnIdle { sheetOpen.value = true }
        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.NAME.key}")
            .performClick()

        // The control's own state moved…
        assertEquals(InventorySortCriterion.NAME, sort.value.criterion)
        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.NAME.key}")
            .assertIsSelected()

        // …and so did the rows, which is the half no unit test can see.
        closeSheet()
        assertTrue(
            "Component Pouch sorts above Torch by name",
            rowTop("c-component") < rowTop("c-torch"),
        )
    }

    /**
     * The direction is a second, independent control over the same list.
     *
     * Descending by name has to put the pair back the way sheet order happened to have it, and
     * that coincidence is the point of asserting the *criterion* is still Name: a test that only
     * looked at the row positions could not tell "descending worked" from "the sort was
     * cancelled".
     */
    @Test
    fun `the direction toggle reverses the rendered order without changing the criterion`() {
        compose.setMageHandContent { TabAndSheet() }

        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.NAME.key}")
            .performClick()
        compose.onNodeWithTag(
            "inventory:customize:sort:direction:${InventorySortDirection.DESCENDING.key}",
        ).performClick()

        assertEquals(InventorySortCriterion.NAME, sort.value.criterion)
        assertEquals(InventorySortDirection.DESCENDING, sort.value.direction)

        closeSheet()
        assertTrue(
            "descending by name puts Torch back above Component Pouch",
            rowTop("c-torch") < rowTop("c-component"),
        )
    }

    /**
     * Decision 6, **as drawn**: the direction is disabled under sheet order and enabled the
     * instant a criterion is chosen.
     *
     * `assertIsNotEnabled` and not `assertDoesNotExist`, which is the whole of the decision: the
     * control stays in place — see `InventoryCustomizeState.canChooseDirection` for why a
     * transiently unavailable control is disabled here where a permanently inapplicable one (the
     * ✕ on Gear, two rows down this same sheet) is absent.
     */
    @Test
    fun `the direction control is present but disabled under sheet order`() {
        compose.setMageHandContent { TabAndSheet() }

        InventorySortDirection.entries.forEach {
            compose.onNodeWithTag("inventory:customize:sort:direction:${it.key}")
                .assertIsDisplayed()
                .assertIsNotEnabled()
        }

        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.WEIGHT.key}")
            .performClick()

        InventorySortDirection.entries.forEach {
            compose.onNodeWithTag("inventory:customize:sort:direction:${it.key}").assertIsEnabled()
        }
    }

    /**
     * The TalkBack sentence, asserted **where a screen reader would actually land on it**.
     *
     * ### Why this test is shaped the way it is
     *
     * It used to call `onNodeWithContentDescription(...).assertExists()`, and it passed while the
     * feature was broken. The 2026-08-31 review found the sentences sitting on two *non-merging*
     * containers whose children are individually focusable — so TalkBack stopped on the children
     * and never on the node carrying the words. `onNodeWithContentDescription` searches the whole
     * tree without asking whether anything can reach the node it finds, so the assertion was
     * satisfied by a string that was, in practice, never spoken. That is precisely the "green test
     * proving nothing" class design 19 exists to close, and it is worth the paragraph because the
     * failing shape looks identical to the passing one at a glance.
     *
     * So the assertion is now made **through the control's own merged node**: the segment is found
     * by its `testTag`, checked for a click action — which is what makes it a thing focus stops on
     * — and only then read for the description. `assertContentDescriptionEquals` reads the merged
     * tree by default, so what it compares is what a screen reader would compose at that stop.
     *
     * Run against the pre-fix code this fails, which is the property the old version lacked.
     *
     * The words come from real `Resources` (design 19 decision 5's replacement for the
     * `ShippedStrings` disk read); a test that repeated them from a constant would keep passing
     * after somebody edited `strings.xml`.
     */
    @Test
    fun `each direction segment speaks its own state, on a node focus can reach`() {
        compose.setMageHandContent { TabAndSheet() }

        // Disabled: the segment says what the control is, which of the two it is, and — the half
        // decision 6 turns on — what would make it pressable.
        InventorySortDirection.entries.forEach { direction ->
            val label = if (direction == InventorySortDirection.ASCENDING) "Ascending" else "Descending"
            compose.onNodeWithTag("inventory:customize:sort:direction:${direction.key}")
                .assertHasClickAction()
                .assertContentDescriptionEquals(
                    "Sort direction, $label, " +
                        "not used in sheet order, choose Name, Weight or Value first",
                )
        }

        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.WEIGHT.key}")
            .performClick()

        // Enabled: the unavailable clause drops out, which is the asymmetry the state builder
        // exists for. Nothing else about the sentence moves.
        InventorySortDirection.entries.forEach { direction ->
            val label = if (direction == InventorySortDirection.ASCENDING) "Ascending" else "Descending"
            compose.onNodeWithTag("inventory:customize:sort:direction:${direction.key}")
                .assertHasClickAction()
                .assertContentDescriptionEquals("Sort direction, $label")
        }
    }

    /**
     * The group sentence is **gone**, and that is the fix rather than a regression.
     *
     * Making it reachable would have meant merging the radio group into one node, destroying the
     * four separate radios a screen-reader user tabs through. Naming the group is done by the
     * visible "SORT ITEMS" heading instead — which this asserts is really on screen, so the job
     * is provably being done by something.
     */
    @Test
    fun `the radio group is named by a visible heading and each radio speaks for itself`() {
        compose.setMageHandContent { TabAndSheet() }

        compose.onNodeWithText("SORT ITEMS").assertIsDisplayed()

        // No description was relocated onto the group container: a sentence nobody can hear is
        // worse than none, because it reads in review as a job already done.
        compose.onNodeWithTag("inventory:customize:sort", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))

        // Each radio carries its own label and selected state, which is what focus lands on.
        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.NAME.key}")
            .assertHasClickAction()
            .assertTextContains("Name")
    }

    /**
     * The four criteria are all offered — including **Value**, which is the ledger's decision 5
     * data check reaching the screen.
     *
     * The option renders because the field is delivered on both kinds of character: 28 of the 36
     * item/container properties in `docs/fixtures/` carry `value`, and a local row has a `value`
     * column (schema v4) that the add-item form collects. Had it not been, decision 5 required the
     * option to be omitted honestly — so this assertion is the record of which way that went.
     */
    @Test
    fun `all four criteria render, Value included`() {
        compose.setMageHandContent { TabAndSheet() }

        InventorySortCriterion.entries.forEach {
            compose.onNodeWithTag("inventory:customize:sort:${it.key}").assertIsDisplayed()
        }
        compose.onNodeWithTag("inventory:customize:sort:${InventorySortCriterion.DEFAULT.key}")
            .assertIsSelected()
    }
}
