package com.hashtagchow.magehand.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG-6**: the Weapon / Armor / Gear chooser's spoken sentence, asserted where a screen reader
 * would actually land on it.
 *
 * ### Why this file exists at all
 *
 * `CategoryChooser` shipped in 1.4.0 with one `contentDescription` on its
 * `SingleChoiceSegmentedButtonRow`, and it was never spoken. The row does not merge its
 * descendants and its three segments are each focusable, so TalkBack stopped on the children every
 * time and the words on the parent were unreachable. Nothing failed: the control worked, the
 * description existed, and any test asking `onNodeWithContentDescription(...).assertExists()`
 * would have passed — that finder searches the whole tree without asking whether focus can reach
 * what it finds.
 *
 * The identical defect was found on FR-35's sort direction by the 2026-08-31 review and fixed
 * there first; this control is the **house precedent that sheet had copied**, so it carried the
 * same bug from six releases earlier. `InventorySortControlTest` is therefore this file's template
 * on purpose, down to the assertion shape.
 *
 * ### The assertion shape, and why `assertExists` is not it
 *
 * Every claim below is made **through the control's own merged node**: the segment is found by its
 * `testTag`, checked for a click action — which is what makes it a thing focus stops on — and only
 * then read for its description. `assertContentDescriptionEquals` reads the merged tree by
 * default, so what it compares is what a screen reader would compose at that stop.
 *
 * Run against the pre-fix code, the first test fails and the second fails; that is the property
 * the absent test never had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CategoryChooserTest {

    @get:Rule
    val compose = createComposeRule()

    /** Both call sites hold the value and hand it back, which is what this stands in for. */
    private val category = mutableStateOf(CatalogCategory.GEAR)

    /**
     * The tag prefix the local editor uses, rather than an invented one: the KDoc's contract is
     * that each segment tags itself `"<prefix>:<stored value>"`, and a test using a made-up prefix
     * would not notice if that scheme changed under the real callers.
     */
    private val prefix = "local:row:0:category"

    private fun setContent() = compose.setMageHandContent {
        CategoryChooser(
            category = category.value,
            onCategory = { category.value = it },
            testTagPrefix = prefix,
        )
    }

    /** The copy, pinned. A constant read from the same `strings.xml` would pin nothing. */
    private val CatalogCategory.spokenNoun: String
        get() = when (this) {
            CatalogCategory.WEAPON -> "Weapon"
            CatalogCategory.ARMOR -> "Armor"
            CatalogCategory.GEAR -> "Gear"
        }

    /**
     * **The fix.** Each segment names the control and itself, on a node focus can reach.
     *
     * The question mark in "What is it?" is the label the visible `Text` above the row draws, and
     * it is deliberately part of the sentence: three bare nouns with no question in front of them
     * are not an answerable prompt, which is the whole reason the row wanted a description in the
     * first place.
     */
    @Test
    fun `each category segment speaks the control and its own noun, on a node focus can reach`() {
        setContent()

        CatalogCategory.entries.forEach { entry ->
            compose.onNodeWithTag("$prefix:${entry.storedValue}")
                .assertHasClickAction()
                .assertContentDescriptionEquals("What is it?, ${entry.spokenNoun}")
        }
    }

    /**
     * The row's sentence is **gone**, and that is the fix rather than a regression.
     *
     * Making it reachable would have meant merging the row into one node, destroying the three
     * separate segments a screen-reader user tabs through — the same trade `InventorySortControlTest`
     * refuses for the radio group. The naming job is done by the visible label instead, which this
     * asserts is really on screen, so it is provably being done by something.
     */
    @Test
    fun `the row carries no description of its own and the visible label does the naming`() {
        setContent()

        compose.onNodeWithText("What is it?").assertIsDisplayed()

        // A sentence nobody can hear is worse than none, because it reads in review as a job
        // already done — which is exactly how this defect survived six releases.
        compose.onNodeWithTag(prefix, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    /**
     * Selected state is the segment's own, which is the half of the announcement this file does
     * *not* compose by hand — `SegmentedButton` says "selected" for itself, and a description that
     * repeated it would have TalkBack say it twice.
     */
    @Test
    fun `exactly one segment is selected and it follows the value`() {
        setContent()

        compose.onNodeWithTag("$prefix:${CatalogCategory.GEAR.storedValue}").assertIsSelected()
        compose.onNodeWithTag("$prefix:${CatalogCategory.WEAPON.storedValue}").assertIsNotSelected()
        compose.onNodeWithTag("$prefix:${CatalogCategory.ARMOR.storedValue}").assertIsNotSelected()

        compose.onNodeWithTag("$prefix:${CatalogCategory.WEAPON.storedValue}").performClick()

        assertEquals(CatalogCategory.WEAPON, category.value)
        compose.onNodeWithTag("$prefix:${CatalogCategory.WEAPON.storedValue}").assertIsSelected()
        compose.onNodeWithTag("$prefix:${CatalogCategory.GEAR.storedValue}").assertIsNotSelected()
    }

    /**
     * The sentence's composition rule, stated once where it can be read without a composition.
     *
     * Cheap, and it is what makes the literals in the tests above a *pin on the copy* rather than
     * a restatement of the helper's own logic.
     */
    @Test
    fun `the spoken sentence is the control's question then the option`() {
        assertEquals("What is it?, Weapon", spokenCategoryOptionLabel("What is it?", "Weapon"))
    }
}
