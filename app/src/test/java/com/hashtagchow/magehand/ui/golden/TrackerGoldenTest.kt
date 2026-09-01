package com.hashtagchow.magehand.ui.golden

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.captureGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tracker's full board, photographed in the four combinations design 19 decision 7 seeds:
 * light + dark × 100 % + 150 %.
 *
 * ### What a full-board golden is for
 *
 * The screen's individual rules have tests — `TrackerUiStateTest` for the mapping,
 * `TrackerTabRenderTest` for the semantics and the interactions. What none of them can see is the
 * board as a *composition*: the vertical rhythm between sections, whether the reset badge still
 * sits under its row rather than beside it, whether a pip row and a bar row line up, whether the
 * concentration banner truncates a long spell name, and — at 150 % — whether anything on the
 * busiest screen in the app collides. Those are the defects the 1.9.1 and 1.11.0 spot-checks kept
 * finding by eye, and this is the corpus that finds them without an eye.
 *
 * The dark variants go through the real `MageHandTheme`, and the scale variants through the real
 * `ProvideUiScale` (design 19 decision 7 is explicit that a test-only density override would not
 * do). So a change to either mechanism shows up here, on the screen the table actually looks at,
 * rather than only in `SurfacePaletteTest`'s numbers.
 *
 * ### Fixture, not invention
 *
 * [Sabriel] transcribes the live capture — HP 17/17, slots 3/4 and 1/2, hit dice 3/3. The board is
 * live and writable, which is the state a player spends a session in; the read-only posture is
 * asserted behaviourally in `TrackerTabRenderTest` rather than photographed, because what makes it
 * correct is that the controls are *inert*, and a picture cannot show inertness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackerGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `light at 100 percent`() = capture("TrackerScreen_light_100")

    // Q1b: the two intermediate steps, light only — `UiScaleProviderRenderTest` pins that every
    // step *measures* at base × its factor; these pin what 110 % and 125 % *look like*, which is
    // the half no measurement reaches. The dark theme is orthogonal to scale and photographed at
    // the extremes above; a dark variant per step would double the corpus for no new claim.
    @Test
    fun `light at 110 percent`() = capture("TrackerScreen_light_110", scale = UiScale.LARGE_110)

    @Test
    fun `light at 125 percent`() = capture("TrackerScreen_light_125", scale = UiScale.LARGE_125)

    @Test
    fun `light at 150 percent`() = capture("TrackerScreen_light_150", scale = UiScale.LARGE_150)

    @Test
    fun `dark at 100 percent`() = capture("TrackerScreen_dark_100", darkTheme = true)

    @Test
    fun `dark at 150 percent`() =
        capture("TrackerScreen_dark_150", darkTheme = true, scale = UiScale.LARGE_150)

    private fun capture(
        name: String,
        scale: UiScale = UiScale.DEFAULT,
        darkTheme: Boolean = false,
    ) = compose.captureGolden(name, scale = scale, darkTheme = darkTheme) {
        TrackerTab(state = Sabriel.tracker())
    }
}
