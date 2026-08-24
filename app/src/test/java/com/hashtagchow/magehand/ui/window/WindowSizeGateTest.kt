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
 * to be LARGE; and feeding the size class a density that is not the device's, which is B1 and the
 * last test here.
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

        // Every step the user can actually choose, including the smallest.
        UiScale.entries.filter { it != UiScale.DEFAULT }.forEach { scale ->
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

    @Test
    fun `height does not enter into it`() {
        // Decision 5 gates on *width* only. A short wide window — a freeform window dragged flat,
        // a foldable half-open — is still wide enough for two columns, and a tall narrow one is
        // not, however much vertical room it has.
        assertTrue(isExpandedWidth(window(1000, heightDp = 300)))
        assertFalse(isExpandedWidth(window(400, heightDp = 2000)))
    }
}
