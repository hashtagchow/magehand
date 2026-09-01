package com.hashtagchow.magehand.ui.golden

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.panes.HomeTabRow
import com.hashtagchow.magehand.ui.panes.serverHomeTabs
import com.hashtagchow.magehand.ui.testing.captureGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **BUG-4's regression corpus** — the first goldens design 19 decision 7 asks for, and the reason
 * layer 2 exists at all.
 *
 * ### The bug these four pictures make unshippable
 *
 * The operator's phone drew "Inventory" as *"Inventor / y"*. `PrimaryTabRow` gives every tab an
 * equal share of the width, FR-26's Actions tab made that share a quarter, and Material's own
 * `Tab(text = …)` spends 32 dp of it on padding and then lets the label **wrap** into the
 * two-line tab slot. On a 360–411 dp phone the longest label lands a pixel or two over and breaks
 * mid-word — the ugliest possible failure — and every UI-scale step above 100 % made it worse.
 *
 * `PaneSelectionTest` pins `maxLines = 1` and `softWrap = false` as source text, and
 * `HomeTabRowTest` pins that every label is present and dispatches. Neither can see a wrap: a
 * wrapped label is still one string in the semantics tree, still displayed, still tappable. Only a
 * picture shows it, which is the whole argument for this layer.
 *
 * ### Why four, and why these four
 *
 * The two axes that produced the bug: **tab count** (three tabs fit comfortably; four is where the
 * share ran out) and **UI scale** (100 % fits; 150 % is where truncation is the accepted outcome).
 * Every combination is captured, so the diff between the 4-tab pair says exactly what a scale step
 * costs, and the 3-versus-4 pair says exactly what the Actions tab costs.
 *
 * ### The device is short on purpose
 *
 * `h240dp` is not a phone. It is a *frame*: the tab row is 48 dp, and a golden of it on an 891 dp
 * screen would be 95 % background — a picture that is harder for a human to review and a file that
 * is mostly compressed nothing. The width, which is the axis under test, is a real 411 dp phone.
 * Height affects nothing about how `PrimaryTabRow` measures its labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h240dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTabRowGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `three tabs at 100 percent`() = capture("HomeTabRow_3tabs_100", hasActions = false)

    @Test
    fun `three tabs at 150 percent`() =
        capture("HomeTabRow_3tabs_150", hasActions = false, scale = UiScale.LARGE_150)

    /** The four-tab row on a phone: BUG-4's exact geometry. */
    @Test
    fun `four tabs at 100 percent`() = capture("HomeTabRow_4tabs_100", hasActions = true)

    /** …and the same row at the largest scale, where truncation is the accepted outcome. */
    @Test
    fun `four tabs at 150 percent`() =
        capture("HomeTabRow_4tabs_150", hasActions = true, scale = UiScale.LARGE_150)

    private fun capture(
        name: String,
        hasActions: Boolean,
        scale: UiScale = UiScale.DEFAULT,
    ) = compose.captureGolden(name, scale = scale) {
        HomeTabRow(
            tabs = serverHomeTabs(hasActions = hasActions),
            selected = CharacterHomeTab.Tracker,
            onSelect = {},
            titleResId = { it.titleResId },
        )
    }
}
