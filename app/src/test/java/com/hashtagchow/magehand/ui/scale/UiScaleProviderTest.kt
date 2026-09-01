package com.hashtagchow.magehand.ui.scale

import androidx.compose.ui.unit.Density
import com.hashtagchow.magehand.mainSourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.settings.UiScale

/**
 * FR-18's root density provider (docs/design/14-large-screen-arc.md decision 1).
 *
 * ### Two kinds of assertion, and where the third went
 *
 * The arithmetic is a pure function, so it is asserted directly. What remains structural is the
 * *whole-app* half — how many places provide `LocalDensity`, and where the one provider is
 * mounted — which is a claim about absence and placement across every file in the module, and
 * therefore still read out of the source in the manner of `WritePostureTest`'s bytecode scan.
 *
 * The third kind used to live here too: "the provider does not re-scale its own output". FR-34
 * gave `:app` a Compose harness, so that is now **rendered** rather than read —
 * `UiScaleProviderRenderTest` recomposes the provider ten times and measures the density on every
 * frame, which is the actual defect (a composition correct on frame 1 and wrong on frame 10) and
 * is something no scan can see.
 *
 * ### Why compounding is the defect worth its own tests
 *
 * A provider that reads its base *inside* itself — or a second provider nested under the
 * first — re-scales an already-scaled density on every recomposition: 1.25, then 1.5625,
 * then 1.953. Nothing crashes. The app simply grows every time something recomposes, which
 * on a screen with a live DDP feed is continuously, and the bug report reads "the app slowly
 * zooms in", which is nobody's first guess about a settings toggle.
 */
class UiScaleProviderTest {

    /** A plain phone: 2.625 px/dp, no system font scaling. */
    private val phone = Density(density = 2.625f, fontScale = 1.0f)

    @Test
    fun `the default step changes nothing at all`() {
        val scaled = scaledDensity(phone, UiScale.DEFAULT)

        // 1.0 has to be *exactly* neutral: this is what every install renders at until the
        // user opens Settings, so a rounding wobble here would be a wobble everybody sees.
        assertEquals(phone.density, scaled.density, 0f)
        assertEquals(phone.fontScale, scaled.fontScale, 0f)
    }

    @Test
    fun `a step scales both density and font scale, not just the text`() {
        val scaled = scaledDensity(phone, UiScale.LARGE_125)

        // Density is the half that grows touch targets and spacing — 14 decision 1's whole
        // reason for scaling density rather than fontScale alone.
        assertEquals(2.625f * 1.25f, scaled.density, 1e-5f)
        assertEquals(1.25f, scaled.fontScale, 1e-5f)
    }

    @Test
    fun `the app factor multiplies the user's system scale rather than replacing it`() {
        // 14 decision 1, in numbers: a user who already asked Android for larger text, then
        // asks this app for 125%, gets 1.625 — not 1.25, which would have *shrunk* their text.
        val accessible = Density(density = 2.625f, fontScale = 1.3f)

        val scaled = scaledDensity(accessible, UiScale.LARGE_125)

        assertEquals(1.625f, scaled.fontScale, 1e-5f)
        assertEquals(2.625f * 1.25f, scaled.density, 1e-5f)
        assertTrue(
            "the app scale must never render smaller than the system asked for",
            scaled.fontScale >= accessible.fontScale && scaled.density >= accessible.density,
        )
    }

    @Test
    fun `applying a step to an already-scaled density is the defect, and it is visible`() {
        val once = scaledDensity(phone, UiScale.LARGE_125)
        val twice = scaledDensity(once, UiScale.LARGE_125)

        // This is what compounding looks like, stated so the next test's structural claim has
        // something concrete behind it: 1.5625x, not 1.25x, and growing.
        assertEquals(2.625f * 1.5625f, twice.density, 1e-4f)
        assertNotEquals(once.density, twice.density)
    }

    @Test
    fun `exactly one place in the app provides LocalDensity`() {
        // The other half of non-compounding, and the half a future wave is likely to break:
        // 14's FR-17 panes are the obvious place somebody would provide a second density.
        // Nesting two providers compounds the factor exactly as re-reading the base does.
        val providers = mainSourceFiles()
            .filter { it.readText().contains("LocalDensity provides") }
            .map { it.name }

        assertEquals(
            "LocalDensity must be provided in exactly one place (found: $providers)",
            listOf("UiScaleProvider.kt"),
            providers.sorted(),
        )
    }

    @Test
    fun `the single provider is mounted above every screen, at the activity root`() {
        val root = mainSourceFiles().single { it.name == "MainActivity.kt" }.readText()

        // Above `MageHandTheme`, and therefore above the NavHost and above every dialog and
        // bottom sheet, which inherit composition locals from the composition that opens them.
        // `CharacterHomeScreen` re-enters `MageHandTheme` for its accent colour, so a provider
        // placed inside the theme would be entered twice on that one screen.
        val provide = root.indexOf("ProvideUiScale(")
        val theme = root.indexOf("MageHandTheme {")

        assertTrue("the root no longer mounts ProvideUiScale", provide >= 0)
        assertTrue(
            "ProvideUiScale must wrap MageHandTheme, not sit inside it",
            provide < theme,
        )
    }

    @Test
    fun `the factor reaches the sheet WebView's textZoom`() {
        // 14 decision 3. The sheet is a second rendering engine and the failure this feature
        // is most likely to ship with is "everything scaled except the character sheet".
        val sheet = mainSourceFiles().single { it.name == "SheetWebView.kt" }.readText()

        assertTrue(
            "the sheet WebView no longer sets textZoom from the UI scale",
            sheet.contains("textZoom = scale.textZoom"),
        )
        assertTrue(
            "the sheet must read the *live* scale, so a change in Settings reaches a WebView " +
                "that outlives every tab switch",
            sheet.contains("LocalUiScale.current"),
        )
        // And the value it sends is the one the design names.
        assertEquals(150, UiScale.LARGE_150.textZoom)
    }

}
