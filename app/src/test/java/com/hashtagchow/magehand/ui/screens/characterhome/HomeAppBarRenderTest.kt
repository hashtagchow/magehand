package com.hashtagchow.magehand.ui.screens.characterhome

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-43's compact rule, **rendered** — the two spellings of Short and Long, and the fact that
 * only their coat changes.
 *
 * ### Why a render test beside the two goldens
 *
 * `HomeAppBar_320_150.png` and `HomeAppBar_360_100.png` are what a human looks at, and they are
 * the only honest answer to "does the back arrow still draw under the S of Short" (BUG-17, which
 * no assertion found and a picture did). What a picture cannot say is whether the *contract* held
 * across the threshold: same test tag, same `enabled`, same callback, same spoken word. Those are
 * four claims a golden would keep certifying green while a sweep step addressed a tag that had
 * quietly moved, so they are asserted here.
 *
 * ### The widths are qualifiers, not modifiers
 *
 * `NarrowWidthGoldenTest`'s rule, for its reason: the compact branch reads the width the *layout*
 * hands the bar, and `ProvideUiScale` multiplies density — so 320 dp of hardware at 150 % is
 * ~213 effective dp, and only a real device qualifier plus the real scale provider produces that
 * number. A `Modifier.width(213.dp)` would prove the branch works and say nothing about whether a
 * phone ever reaches it.
 *
 * The class sits at 360 dp × 100 % (360 dp — above `HOME_APP_BAR_COMPACT_WIDTH`, and the common
 * phone); the compact tests carry their own 320 dp qualifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppBarRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Counted, not flagged: "exactly once" is a claim a boolean cannot make. */
    private var shortRests = 0
    private var longRests = 0

    private fun setBar(
        scale: UiScale = UiScale.DEFAULT,
        trackerShowing: Boolean = true,
        trackerCanWrite: Boolean = true,
    ) = compose.setMageHandContent(scale = scale) {
        HomeAppBar(
            title = "Sabriel",
            onBack = {},
            trackerShowing = trackerShowing,
            inventoryShowing = false,
            trackerCanWrite = trackerCanWrite,
            inventoryCanWrite = false,
            onShortRest = { shortRests++ },
            onLongRest = { longRests++ },
            onAddItem = {},
            overflow = {},
        )
    }

    // ------------------------------------------------------- compact (BUG-17)

    /**
     * 320 dp × 150 % — the combination BUG-17 was photographed at, and the narrowest the app
     * supports. The buttons keep everything except their text.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun `below the threshold the rest actions are icons that keep their tags and say more`() {
        setBar(scale = UiScale.LARGE_150)

        // The text is gone from the bar entirely — not merely from the button. `onNodeWithText`
        // reads the Text semantics and never a contentDescription, so this cannot be satisfied
        // by the icons' own labels.
        compose.onNodeWithText(context.getString(R.string.tracker_short_rest)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tracker_long_rest)).assertDoesNotExist()

        // ...and what the glyph SAYS is the fuller phrase, not the word that left (review L5).
        // "Short" reads acceptably only beside a visible "Long"; a glyph whose entire spoken
        // name is "Short" is a worse sentence than the text button ever was, so compact borrows
        // the rest dialog's own titles.
        compose.onNodeWithTag("tracker:rest:short")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertContentDescriptionEquals(context.getString(R.string.tracker_rest_title_short))
        compose.onNodeWithTag("tracker:rest:long")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertContentDescriptionEquals(context.getString(R.string.tracker_rest_title_long))
    }

    /**
     * Review NIT-2: `enabled` is one of the things that must NOT change across the threshold. A
     * read-only DiceCloud character's rest buttons are inert, and an icon that looks live and
     * does nothing is the worse half of that bug.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun `a compact rest action is disabled when the tracker cannot be written`() {
        setBar(scale = UiScale.LARGE_150, trackerCanWrite = false)

        compose.onNodeWithTag("tracker:rest:short").assertIsNotEnabled()
        compose.onNodeWithTag("tracker:rest:long").assertIsNotEnabled()
    }

    /**
     * **Review L1: the rule measured where the bar actually ships.**
     *
     * Every other case here composes `HomeAppBar` directly under the harness's `fillMaxSize`
     * box, which hands it the whole window. In both home screens it is a `Scaffold`'s `topBar`
     * slot instead — and a fit rule is only as good as the constraints it is measured against,
     * so "compact at 320 dp" proved through a `Box` is not yet a claim about the app. `Scaffold`
     * measures its top bar with the window's width and loose height; this asserts the icon
     * branch through that path, so a future `Scaffold` change that padded or wrapped the slot
     * would fail here rather than silently in production.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun `the compact rule holds through a Scaffold's topBar slot, where the bar ships`() {
        compose.setMageHandContent(scale = UiScale.LARGE_150) {
            Scaffold(
                topBar = {
                    HomeAppBar(
                        title = "Sabriel",
                        onBack = {},
                        trackerShowing = true,
                        inventoryShowing = false,
                        trackerCanWrite = true,
                        inventoryCanWrite = false,
                        onShortRest = {},
                        onLongRest = {},
                        onAddItem = {},
                        overflow = {},
                    )
                },
            ) { Spacer(Modifier.padding(it)) }
        }

        compose.onNodeWithText(context.getString(R.string.tracker_short_rest)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tracker_long_rest)).assertDoesNotExist()
        compose.onNodeWithTag("tracker:rest:short")
            .assertContentDescriptionEquals(context.getString(R.string.tracker_rest_title_short))
        compose.onNodeWithTag("tracker:rest:long")
            .assertContentDescriptionEquals(context.getString(R.string.tracker_rest_title_long))
    }

    /**
     * The half that makes the tags worth asserting: an icon that is reachable but wired to
     * nothing would pass every assertion above.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun `a compact rest action still reaches its callback, once`() {
        setBar(scale = UiScale.LARGE_150)

        compose.onNodeWithTag("tracker:rest:short").performClick()
        compose.onNodeWithTag("tracker:rest:long").performClick()

        assertEquals("short rest fired exactly once", 1, shortRests)
        assertEquals("long rest fired exactly once", 1, longRests)
    }

    // ---------------------------------------------------------- text (today)

    /**
     * 360 dp × 100 %, which is every phone at the default scale: **nothing changes**. Ruling 1's
     * other half — the fix is a fit rule, so the width where the bar was never short of room
     * renders exactly what 1.14.1 rendered.
     */
    @Test
    fun `at or above the threshold the rest actions are still text buttons`() {
        setBar()

        compose.onNodeWithTag("tracker:rest:short")
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.tracker_short_rest))
        compose.onNodeWithTag("tracker:rest:long")
            .assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.tracker_long_rest))
    }

    // ------------------------------------------------------------- the gate

    /**
     * The rest actions belong to one tab, and the compact rule does not change which. Asserted at
     * both widths because a branch is exactly where a gate gets lost.
     */
    @Test
    fun `with the tracker hidden there is no rest action at all`() {
        setBar(trackerShowing = false)

        compose.onNodeWithTag("tracker:rest:short").assertDoesNotExist()
        compose.onNodeWithTag("tracker:rest:long").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun `with the tracker hidden there is no rest action when compact either`() {
        setBar(scale = UiScale.LARGE_150, trackerShowing = false)

        compose.onNodeWithTag("tracker:rest:short").assertDoesNotExist()
        compose.onNodeWithTag("tracker:rest:long").assertDoesNotExist()
    }
}
