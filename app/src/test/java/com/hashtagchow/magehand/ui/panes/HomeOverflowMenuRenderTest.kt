package com.hashtagchow.magehand.ui.panes

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-39's move, **rendered**: history is in the overflow menu, and it is the first thing in it.
 *
 * ### Why a render test and not another source assertion
 *
 * `PaneSelectionTest` already reads both home screens' source and fails if a history control
 * reappears on the app bar. That check can only ever say where the item is *not*. Everything
 * FR-39 promises is on the other side — that the menu opens onto a *reachable* item carrying the
 * tag the sweep flows address, saying the word a player is looking for, in the position the
 * ruling chose, and that tapping it opens the sheet and closes the menu. A `DropdownMenuItem`
 * renders into a popup with its own composition, so "the source passes it" and "a tap reaches
 * it" are genuinely different claims — which is BUG-6's whole lesson, told about a menu.
 *
 * ### Order is read off the layout, not assumed from the argument list
 *
 * Ruling 2 puts history **first**, above `customize`, because it is the one action in a menu of
 * destinations. `assertExists` on each item passes for every permutation of them, and so does a
 * list of tags written in the order this file expects — so the sequence here is sorted by each
 * item's actual `positionInRoot.y` before it is compared. That is what catches the regression
 * that is actually likely: not an item going missing, but a later one drawn above this one by
 * someone who read `HomeOverflowMenu`'s parameter list as a rendering order (it is not — the
 * parameters are alphabetical-ish and the `DropdownMenu` body is what orders them).
 *
 * ### The labels come from the resources
 *
 * `SettingsUiScaleRenderTest`'s rule, for its reason: a test asserting the literal `"History"`
 * would keep passing after somebody changed the string, and would then pin a word the app no
 * longer says. The one thing spelled literally here is the **test tag**, and that is deliberate
 * — `tracker:history:open` is a name `tools/sweep/flows` and the ledger both address by hand, so
 * a test that read it from the same constant production code does could not notice a rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeOverflowMenuRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Counted, not flagged, so "exactly once" is provable rather than "at least once". */
    private var historyOpened = 0

    /**
     * Built by the production factory, which is the point: both home screens reach the menu
     * through this function, so a test that hand-rolled a `HomeOverflowCustomize` here would be
     * pinning a label and a tag no screen actually passes.
     */
    private val history = homeOverflowHistory { historyOpened++ }

    private val customize = HomeOverflowCustomize(
        labelRes = R.string.customize_title,
        testTag = "tracker:customize:open",
        onClick = {},
    )

    private val quests = HomeOverflowCustomize(
        labelRes = R.string.quests_title,
        testTag = "quests:open",
        onClick = {},
    )

    /**
     * The menu as a home screen composes it. `settingsLabel` is the DiceCloud screen's; the local
     * screen passes "Edit character" into the same slot and nothing here depends on which, which
     * is that parameter's whole purpose.
     */
    private fun setContent(
        history: HomeOverflowCustomize? = this.history,
        customize: HomeOverflowCustomize? = this.customize,
        quests: HomeOverflowCustomize? = this.quests,
    ) = compose.setMageHandContent {
        HomeOverflowMenu(
            onPaneOrder = {},
            settingsLabel = context.getString(R.string.action_settings),
            onSettings = {},
            history = history,
            customize = customize,
            quests = quests,
        )
    }

    private fun open() = compose.onNodeWithTag("home:overflow:open").performClick()

    @Test
    fun `history is in the open menu, tagged, and says the verb rather than the sheet's heading`() {
        setContent()
        open()

        compose.onNodeWithTag("tracker:history:open").assertIsDisplayed()
        assertEquals(
            context.getString(R.string.tracker_history_action),
            labelOf("tracker:history:open"),
        )
        // Ruling 3's two sentences. A menu item names what you are about to do; the sheet's own
        // heading names what you are looking at. Asserted rather than assumed, because if the
        // two strings were ever collapsed into one every other assertion in this file would go
        // on passing while the menu read "This session".
        assertNotEquals(
            context.getString(R.string.tracker_history_title),
            context.getString(R.string.tracker_history_action),
        )
    }

    /** The ordering ruling, as the only assertion that can carry it: top to bottom, as laid out. */
    @Test
    fun `the open menu reads history, customize, quests, arrange, settings — in that order`() {
        setContent()
        open()

        assertEquals(
            listOf(
                "tracker:history:open",
                "tracker:customize:open",
                "quests:open",
                "panes:order:open",
                "home:overflow:settings",
            ),
            menuItemTagsTopToBottom(),
        )
    }

    /**
     * The gate, and the half of it that is easy to get wrong: with history absent, `customize`
     * and `quests` are still ordered against each other, so a screen on the Inventory tab reads
     * exactly the menu it read before FR-39.
     */
    @Test
    fun `a null history leaves the tag absent and the rest of the menu unchanged`() {
        setContent(history = null)
        open()

        compose.onNodeWithTag("tracker:history:open").assertDoesNotExist()
        assertEquals(
            listOf(
                "tracker:customize:open",
                "quests:open",
                "panes:order:open",
                "home:overflow:settings",
            ),
            menuItemTagsTopToBottom(),
        )
    }

    /**
     * Both halves of a menu item's job, in one test because they are one gesture: the callback
     * fires, once, and the menu goes away. A tap that opened the sheet *behind* a menu still
     * covering it would photograph correctly and be unusable on a phone.
     */
    @Test
    fun `tapping history invokes the callback exactly once and closes the menu`() {
        setContent()
        open()

        compose.onNodeWithTag("tracker:history:open").performClick()

        assertEquals(1, historyOpened)
        assertEquals(emptyList<String>(), menuItemTagsTopToBottom())
        // …and the button that opened it is still there to open it again.
        compose.onNodeWithTag("home:overflow:open").assertIsDisplayed()
    }

    /** Closed, the menu is one button: none of its items exist to be found, tapped or read out. */
    @Test
    fun `a closed menu carries no items at all`() {
        setContent()

        assertEquals(emptyList<String>(), menuItemTagsTopToBottom())
        compose.onNodeWithTag("home:overflow:open").assertIsDisplayed()
    }

    /** The visible label of the node at [testTag], read off the merged semantics tree. */
    private fun labelOf(testTag: String) = compose.onNodeWithTag(testTag)
        .fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString("") { it.text }

    /**
     * Every menu item currently composed, in the order they are drawn down the popup.
     *
     * Candidate tags are listed rather than discovered by walking the popup's children, so an
     * item that lost its tag fails loudly here instead of quietly dropping out of the sequence;
     * the *order* comes from the layout (`positionInRoot.y`), never from this list.
     */
    private fun menuItemTagsTopToBottom(): List<String> = listOf(
        "tracker:history:open",
        "tracker:customize:open",
        "quests:open",
        "panes:order:open",
        "home:overflow:settings",
    ).mapNotNull { tag ->
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            .singleOrNull()
            ?.let { tag to it.positionInRoot.y }
    }.sortedBy { it.second }.map { it.first }
}
