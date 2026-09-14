package com.hashtagchow.magehand.ui.golden

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.BuffChipState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.testing.Sabriel
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import com.hashtagchow.magehand.ui.testing.captureGolden
import com.hashtagchow.magehand.ui.testing.commitGolden
import com.hashtagchow.magehand.ui.testing.setMageHandContent
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
 * [Sabriel] transcribes the live capture — HP 17/17, AC 14, slots 3/4 and 1/2, hit dice 3/3 at
 * "+ 1". The board is
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

    /**
     * FR-53 R8's two: the Conditions section with **one buff** in it, at 100 % and 150 %.
     *
     * ### New images, and the six above are deliberately untouched
     *
     * The default fixture has no buffs, which is R3's *"nothing to show → the section is
     * unchanged"* stated as pixels: every earlier golden in this file is byte-identical after this
     * wave, which is the claim the feature makes about a character who has cast nothing. A buff
     * had to arrive through a new capture rather than by editing the shared fixture, or that claim
     * would have been untestable.
     *
     * ### Why a golden at all, when `TrackerTabRenderTest` already asserts the chip
     *
     * That test proves the chip is composed, speaks and takes taps. What it cannot see is the
     * thing this file exists for: an `InputChip` with a leading icon, a name and a trailing ✕ is
     * the widest chip on the screen, and it sits in a two-per-row grid directly above the toggle
     * chips. Whether the ✕ stays inside the chip at 150 %, whether the name ellipsises before the
     * icon is squeezed, and whether the two chip *shapes* read as one section rather than two are
     * all questions only an image answers — and 150 % is where the 1.9.1 and 1.11.0 spot-checks
     * kept finding the collisions this corpus was built to catch.
     *
     * Light only, matching the scale steps' own note above: the dark theme is orthogonal to
     * layout and is photographed at both extremes by the six existing images, so a dark buff
     * variant would double the corpus for no new claim.
     */
    @Test
    fun `the conditions section with a buff at 100 percent`() =
        captureConditions("TrackerScreen_buff_light_100")

    @Test
    fun `the conditions section with a buff at 150 percent`() =
        captureConditions("TrackerScreen_buff_light_150", scale = UiScale.LARGE_150)

    /**
     * Scrolled to the buff chip before the shutter, unlike the six full-board captures above.
     *
     * The Conditions section is the **last** thing on this board, well below a 891 dp viewport, so
     * a capture of the tab as it opens photographs the HP block and proves nothing about the
     * chips. `commitGolden` is the half of the harness that exists for exactly this — drive the UI
     * first, photograph after — and the scroll is by test tag so a future section inserted above
     * Conditions cannot silently move the picture back off it.
     */
    private fun captureConditions(name: String, scale: UiScale = UiScale.DEFAULT) {
        compose.setMageHandContent(scale = scale) {
            TrackerTab(state = Sabriel.tracker(armorClass = 14, buffs = oneBuff))
        }
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("tracker:buff:buff-shield"))
        compose.commitGolden(name)
    }

    /**
     * One buff, generically named, with a description long enough to be worth a sheet.
     *
     * *Shield* rather than anything off the party's sheets — the capture's own twenty buffs are
     * all templates, so there was no applied one to transcribe, and the store-safety rule would
     * not have allowed a party name if there had been. The description does not appear in these
     * images (it lives behind the chip tap) and is supplied anyway so the fixture is the state the
     * app really holds rather than a thinner one shaped to the photograph.
     */
    private val oneBuff = listOf(
        BuffChipState("buff-shield", "Shield", "+5 bonus to AC until the start of your next turn."),
    )

    private fun capture(
        name: String,
        scale: UiScale = UiScale.DEFAULT,
        darkTheme: Boolean = false,
        buffs: List<BuffChipState> = emptyList(),
    ) = compose.captureGolden(name, scale = scale, darkTheme = darkTheme) {
        // FR-50: the capture's own AC (14), so the corpus photographs the badge rather than the
        // absence of it. `Sabriel.tracker()` defaults to absent — which is what every other
        // consumer of the fixture keeps, and what `TrackerTabRenderTest` asserts draws nothing —
        // so the number is supplied here, at the one place whose job is to show what the screen
        // looks like. Absent AC has no golden of its own on purpose: it is the state the whole
        // corpus was recorded in before this wave, so the previous six images are its record.
        TrackerTab(state = Sabriel.tracker(armorClass = 14, buffs = buffs))
    }
}
