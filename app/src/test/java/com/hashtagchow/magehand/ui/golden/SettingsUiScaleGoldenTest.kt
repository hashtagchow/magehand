package com.hashtagchow.magehand.ui.golden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.screens.settings.SETTINGS_HORIZONTAL_PADDING
import com.hashtagchow.magehand.ui.screens.settings.UiScaleSetting
import com.hashtagchow.magehand.ui.testing.captureGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-38's stepper at the width and the scale it has to survive (14 addendum 3 as amended,
 * FR-38 ruling 5).
 *
 * ### Why 360 dp and its own class
 *
 * The width is a Robolectric **qualifier**, not a `Modifier.width`, for the reason
 * `NarrowWidthGoldenTest` gives at length: a modifier would prove the control fits when handed
 * 360 dp and say nothing about whether a phone ever hands it 360 dp. A qualifier is per test
 * class, so a second width is a second class — and 360 dp is the width the majority of Android
 * phones report, which makes it the one where a control that does not fit is a defect a majority
 * of users would meet.
 *
 * `ScreensGoldenTest` keeps `SettingsScreen.png` at 411 dp and re-records it for this change. The
 * two are not redundant: that one photographs the whole screen and would catch the control
 * disturbing the rest of the page, these photograph the control alone at the size where its own
 * layout is under pressure, so a regression names itself in the file that changed.
 *
 * ### The 150 % capture is the one that earns its place
 *
 * The stepper's failure mode is not "a chip is missing" — it is the **value clipping between the
 * two buttons**. Two 48 dp targets do not shrink when density grows, but the row does not grow
 * either, so at 150 % the value line has roughly a third less room for text that is half again
 * as large. `100% (Default)` is the longest string it ever shows, which is why the captures below
 * both sit on `Default` rather than on a two-character percentage that would fit anywhere. The
 * 150 % capture is what proved the value cannot stay on one line at that size, and then proved
 * the two-line form reads correctly.
 *
 * That is a *measuring* outcome, so the guard is a picture; `SettingsUiScaleRenderTest` owns
 * everything about this control that a picture cannot see, which on a stepper is most of it.
 * The scale is applied through `ProvideUiScale` — the shipped mechanism — because design 19
 * decision 7 forbids a test-only density hack here, and because a hack would photograph a size
 * the app never actually renders at.
 *
 * ### The 24 dp padding is not decoration
 *
 * Both captures wrap the control in [SETTINGS_HORIZONTAL_PADDING] — the screen's own constant,
 * imported and not copied, which its scrolling `Column` puts on every child. A bare
 * composable would have been handed the **whole** 360 dp device, so it would fit here and clip on
 * the phone — 48 dp narrower is a sixth of the width, and this control's failure mode is running
 * out of width. Photographing it with less room than the app gives it would be the same mistake
 * as `Modifier.width()`, one layer up.
 *
 * Wrapping rather than capturing `SettingsScreen` at 360 dp: the screen needs a Hilt-shaped view
 * model and `Dispatchers.setMain` (see `ScreensGoldenTest`), and it already has a golden at
 * 411 dp. What is wanted here is the control at a second width, not the screen twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiScaleGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the scale stepper on a 360 dp phone`() {
        compose.captureGolden("SettingsUiScale_narrow") { asSettingsScreenPlacesIt() }
    }

    @Test
    fun `the scale stepper on a 360 dp phone at 150 percent`() {
        // The app's own worst case for this control: the widest value string, the least room,
        // through the real provider. The value must read whole between the two buttons.
        compose.captureGolden("SettingsUiScale_150", scale = UiScale.LARGE_150) {
            asSettingsScreenPlacesIt()
        }
    }

    /**
     * The control with exactly the room `SettingsScreen` gives it, and no more.
     *
     * [SETTINGS_HORIZONTAL_PADDING] is imported from the screen rather than copied, so a change
     * to the screen's inset re-records these two captures instead of quietly leaving them
     * photographing a width the app no longer uses.
     */
    @Composable
    private fun asSettingsScreenPlacesIt() {
        Box(Modifier.padding(horizontal = SETTINGS_HORIZONTAL_PADDING)) {
            UiScaleSetting(selected = UiScale.DEFAULT, onSelect = {})
        }
    }
}
