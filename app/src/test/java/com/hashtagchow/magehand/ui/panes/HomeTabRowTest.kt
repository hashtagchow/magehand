package com.hashtagchow.magehand.ui.panes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone chrome's tab row, **rendered** — the behavioural half of what `PaneSelectionTest`
 * could only read out of the source (docs/design/19-ui-test-infrastructure.md decision 5:
 * *"PaneSelectionTest's 'phone still composes the tab row' source-scan half → real composition
 * asserting the tab row and its tabs"*).
 *
 * ### What moved here, and what stayed a scan
 *
 * `PaneSelectionTest` still asserts that each home screen composes `HomeTabRow` *inside the
 * non-expanded branch* and `PanePicker` inside the expanded one. That is a claim about where a
 * call sits in a Hilt-wired screen, which no composition of this row can witness, and it is
 * exactly the class of scan design 19 decision 5 keeps.
 *
 * What it can no longer say better than this file is what the row *does*: draws every tab it was
 * given, marks the selected one selected, and dispatches the one that was tapped. Those were
 * BUG-4's and FR-27's real subject and they were device-only until FR-34.
 *
 * ### BUG-4 is a golden, not an assertion here
 *
 * The wrap defect — "Inventory" drawn as "Inventor / y" on a four-tab phone row — is a *measuring*
 * outcome, and the honest witness for it is a picture. `HomeTabRowGoldenTest` captures the 3- and
 * 4-tab rows at 100 % and 150 %, which is the corpus design 19 decision 7 seeds first. This file
 * asserts the labels are present and legible as strings; the goldens assert they are drawn on one
 * line. `PaneSelectionTest` keeps the `maxLines`/`softWrap` scan alongside both, because a rule
 * that a picture happens to satisfy today is still a rule the next edit deletes as noise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTabRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val fourTabs = serverHomeTabs(hasActions = true)
    private val threeTabs = serverHomeTabs(hasActions = false)

    @Test
    fun `every tab it is given is drawn, with its own label`() {
        compose.setMageHandContent {
            HomeTabRow(
                tabs = fourTabs,
                selected = CharacterHomeTab.Tracker,
                onSelect = {},
                titleResId = { it.titleResId },
            )
        }

        listOf("Tracker", "Inventory", "Actions", "Sheet").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `a character with no actions gets three tabs and no gap where the fourth was`() {
        // FR-26's discovery gate reaches the row as a shorter list, not as a disabled tab.
        compose.setMageHandContent {
            HomeTabRow(
                tabs = threeTabs,
                selected = CharacterHomeTab.Tracker,
                onSelect = {},
                titleResId = { it.titleResId },
            )
        }

        compose.onNodeWithText("Actions").assertDoesNotExist()
        listOf("Tracker", "Inventory", "Sheet").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `the selection indicator follows the drawn list, not the enum ordinal`() {
        // The row's own comment: with the Actions tab gated (FR-26) and the row reorderable
        // (FR-27), `ordinal` and "index within the drawn list" stop agreeing — and `ordinal` would
        // put the indicator under the wrong tab. `Sheet` is ordinal 3 and index 2 in a three-tab
        // row, so this composition is the case where the two answers differ.
        compose.setMageHandContent {
            HomeTabRow(
                tabs = threeTabs,
                selected = CharacterHomeTab.Sheet,
                onSelect = {},
                titleResId = { it.titleResId },
            )
        }

        compose.onNodeWithText("Sheet").assertIsSelected()
        compose.onNodeWithText("Tracker").assertIsNotSelected()
        compose.onNodeWithText("Inventory").assertIsNotSelected()
    }

    @Test
    fun `tapping a tab reports that tab`() {
        val selections = mutableListOf<CharacterHomeTab>()
        compose.setMageHandContent {
            HomeTabRow(
                tabs = fourTabs,
                selected = CharacterHomeTab.Tracker,
                onSelect = { selections += it },
                titleResId = { it.titleResId },
            )
        }

        compose.onNodeWithText("Inventory").performClick()
        compose.onNodeWithText("Sheet").performClick()

        assertEquals(listOf(CharacterHomeTab.Inventory, CharacterHomeTab.Sheet), selections)
    }

    @Test
    fun `the labels are still all present at 150 percent`() {
        // FR-18's largest step, through the shipped `ProvideUiScale` path. Truncation is the
        // accepted outcome at this size (BUG-4's ruling: "ellipsis is the insurance, not the
        // plan") — but the *node* must still be there with its full text, because truncation is a
        // drawing decision and an accessibility service reads the string. A label that had
        // genuinely been dropped or replaced would fail here; one that is merely ellipsised
        // passes here and shows its ellipsis in the golden.
        compose.setMageHandContent(scale = UiScale.LARGE_150) {
            HomeTabRow(
                tabs = fourTabs,
                selected = CharacterHomeTab.Tracker,
                onSelect = {},
                titleResId = { it.titleResId },
            )
        }

        listOf("Tracker", "Inventory", "Actions", "Sheet").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
