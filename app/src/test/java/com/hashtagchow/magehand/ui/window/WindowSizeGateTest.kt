package com.hashtagchow.magehand.ui.window

import androidx.window.core.layout.WindowSizeClass
import com.hashtagchow.magehand.core.data.settings.UiScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-17's width gate (docs/design/14-large-screen-arc.md decision 5).
 *
 * `WindowSizeClass` is plain Kotlin with a public constructor over minimum width/height in dp, so
 * the threshold is testable without a device, a Robolectric shadow or a composition. What is
 * *not* testable here is that the class is fed real window metrics — that is
 * `currentWindowAdaptiveInfoV2`'s job and the combined device sweep's to confirm (14 §Acceptance
 * shape, area Q: "gate both directions").
 *
 * ### The defect this is about
 *
 * Getting the gate wrong is invisible in code review and obvious on a device — the wrong half of
 * the population gets the wrong chrome, and neither half crashes. Three specific ways to get it
 * wrong: picking a threshold that is not Material's; asking `== EXPANDED` on a device wide enough
 * to be LARGE; and feeding the size class a density that is not the device's, which is B1 and has
 * a test here in each direction — a scaled-up density demoting a tablet, and (FR-38 ruling 3) a
 * scaled-down one promoting a phone.
 */
class WindowSizeGateTest {

    /** A window [widthDp] wide. Height is fixed tall enough to be irrelevant — see below. */
    private fun window(widthDp: Int, heightDp: Int = 900) = WindowSizeClass(widthDp, heightDp)

    @Test
    fun `a phone in portrait keeps the tab row`() {
        // 360 dp: the width every layout in this app was designed against.
        assertFalse(isExpandedWidth(window(360)))
    }

    @Test
    fun `a phone in landscape keeps the tab row`() {
        // Decision 5 calls this one out by name: "this includes most landscape phones — width
        // MEDIUM". A 640 dp landscape phone is exactly the device somebody would expect panes on
        // and exactly the device the design says must not get them.
        assertFalse(isExpandedWidth(window(640, heightDp = 360)))
    }

    @Test
    fun `just under the Material breakpoint keeps the tab row`() {
        assertFalse(isExpandedWidth(window(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND - 1)))
    }

    @Test
    fun `the Material breakpoint itself is expanded`() {
        // 840 dp is a *lower bound*, inclusive. Off by one here is a tablet that never gets panes.
        assertTrue(isExpandedWidth(window(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)))
    }

    @Test
    fun `a tablet is expanded`() {
        // Pixel Tablet in landscape, the AVD the sweep runs on.
        assertTrue(isExpandedWidth(window(1280, heightDp = 800)))
    }

    @Test
    fun `a window too wide to be EXPANDED is still expanded`() {
        // The bug `== EXPANDED` would ship. Under the V2 breakpoint set a 1600 dp window is
        // EXTRA_LARGE, not EXPANDED — so an equality check would drop a desktop-sized window back
        // to the phone tab row, which is the exact opposite of what decision 5 wants.
        assertTrue(isExpandedWidth(window(1200, heightDp = 900)))
        assertTrue(isExpandedWidth(window(1600, heightDp = 1000)))
    }

    /**
     * ### B1, as arithmetic
     *
     * `currentWindowAdaptiveInfoV2()` does not receive dp. It receives the window's **pixels**
     * and divides by `LocalDensity.current.density` (adaptive 1.3.0). So "which density is in
     * scope where the gate is composed" decides what device class the app believes it is on —
     * and FR-18's `ProvideUiScale` puts `deviceDensity * factor` in scope for everything under
     * it.
     *
     * Below is the same physical window at every app scale step. The hardware does not move; the
     * answer does. That is why `ProvideWindowSizeGate` is mounted *above* `ProvideUiScale` in
     * `MainActivity` — pinned structurally by `PaneSelectionTest`, and explained here so the
     * ordering cannot be re-read as a stylistic preference and quietly swapped back.
     *
     * The cost of getting it wrong is silent: no crash, just a qualifying tablet rendering the
     * phone tab row and no FR-19 DM entry (decision 12 gates that on this same local), for a
     * user whose only sin was turning text size up.
     */
    @Test
    fun `a scaled density shrinks the apparent window past the breakpoint`() {
        // 2100 px at 2.5 (420 dpi) is 840 dp — the Material breakpoint exactly, which is the
        // width where a demotion costs the most and is hardest to notice.
        val widthPx = 2100
        val deviceDensity = 2.5f
        fun apparentDp(scale: UiScale) = (widthPx / (deviceDensity * scale.factor)).toInt()

        assertEquals(
            "the fixture must sit on the breakpoint, or this proves nothing",
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
            apparentDp(UiScale.DEFAULT),
        )
        assertTrue(
            "measured against the device's own density this window is EXPANDED",
            isExpandedWidth(window(apparentDp(UiScale.DEFAULT))),
        )

        // Every step *above* Default, including the smallest of them. The sub-1.0 steps FR-38
        // adds run this arithmetic the other way — they make the window look wider, not
        // narrower — so they are a demotion this test cannot show and are pinned as their own
        // case below.
        UiScale.entries.filter { it.factor > 1.0f }.forEach { scale ->
            assertFalse(
                "at ${scale.key}% the same window measures ${apparentDp(scale)} dp — a gate " +
                    "composed under ProvideUiScale would drop this device to the phone tab row",
                isExpandedWidth(window(apparentDp(scale))),
            )
        }

        // The affected band, which three KDocs now quote: at the top step everything from the
        // breakpoint up to 1259 dp is demoted, and 1260 dp is the first width that survives.
        // Pinned so the number in prose cannot drift away from the number in the enum.
        val top = UiScale.LARGE_150.factor
        assertFalse("1259 dp at 150% must fall under the breakpoint", isExpandedWidth(window((1259 / top).toInt())))
        assertTrue("1260 dp at 150% must survive it", isExpandedWidth(window((1260 / top).toInt())))
    }

    /**
     * ### B1's mirror (14 addendum 3, FR-38 ruling 3)
     *
     * The test above walks the factor *up* and watches a tablet fall out of pane mode. FR-38's
     * sub-1.0 steps run the same arithmetic the other way: dividing by a factor below 1 makes the
     * window measure **wider** in dp than it is, so a scaled gate would promote a device *into*
     * pane mode — two columns and an FR-19 DM entry on hardware that has room for neither.
     *
     * ### Where the promotion actually bites, which is not on a phone
     *
     * A 360 dp phone at 0.7 apparently measures 514 dp, which is still under the 840 dp
     * breakpoint — so a phone is safe by arithmetic, not by contract, and the first version of
     * this test asserted that it stays compact in a way that could not have failed. Ruling 3 asks
     * for the phone case and it is kept below as a single literal, but the case with teeth is a
     * window **just under** the breakpoint: 839 dp is one dp short of panes at Default and sails
     * past it at every step below. That is a large foldable's inner screen, or a split-screen
     * window on a tablet — devices where the promotion produces a two-pane layout in a window
     * that measures 839 dp, which is the defect and not merely the wrong chrome.
     *
     * The gate reads the device's own density by contract (`ProvideWindowSizeGate` above
     * `ProvideUiScale` in `MainActivity`, pinned structurally by `PaneSelectionTest`). What this
     * test shows is what the wrong ordering would have produced, by feeding the gate the scaled
     * widths directly: 932, 1048 and 1198 dp from one 839 dp window.
     */
    @Test
    fun `a shrunken density would widen a sub-breakpoint window into pane mode`() {
        // Ruling 3's literal ask, as one assertion that stands on its own: an ordinary phone is
        // compact, and no arithmetic in this test can make it anything else.
        assertFalse("a 360 dp phone is compact", isExpandedWidth(window(360)))

        // 2517 px at 3.0 (480 dpi) is 839 dp — one dp under the breakpoint, where a promotion
        // costs the most and is hardest to spot.
        val widthPx = 2517
        val deviceDensity = 3.0f
        fun apparentDp(scale: UiScale) = (widthPx / (deviceDensity * scale.factor)).toInt()

        assertEquals(
            "the fixture must sit one dp under the breakpoint, or this proves nothing",
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND - 1,
            apparentDp(UiScale.DEFAULT),
        )
        assertFalse(
            "measured against the device's own density this window keeps the tab row",
            isExpandedWidth(window(apparentDp(UiScale.DEFAULT))),
        )

        // Every step below Default, with the *scaled* width handed to the gate — which is what a
        // gate composed under ProvideUiScale would receive.
        UiScale.entries.filter { it.factor < 1.0f }.forEach { scale ->
            assertTrue(
                "at ${scale.key}% this 839 dp window apparently measures ${apparentDp(scale)} dp " +
                    "— a gate composed under ProvideUiScale would hand it the tablet pane layout, " +
                    "which is decision 5's rule inverted",
                isExpandedWidth(window(apparentDp(scale))),
            )
        }
    }

    @Test
    fun `height does not enter into it`() {
        // Decision 5 gates on *width* only. A short wide window — a freeform window dragged flat,
        // a foldable half-open — is still wide enough for two columns, and a tall narrow one is
        // not, however much vertical room it has.
        assertTrue(isExpandedWidth(window(1000, heightDp = 300)))
        assertFalse(isExpandedWidth(window(400, heightDp = 2000)))
    }
}
