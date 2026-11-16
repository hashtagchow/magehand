package com.hashtagchow.magehand.ui.golden

import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserFixtures
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ChargeChooserSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.chargeChooserFor
import com.hashtagchow.magehand.ui.testing.captureGolden
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-59 stage 1's charge chooser at 100 % and 150 % (docs/design/22-charge-consumers.md R8).
 *
 * One picture carries all three row shapes R4 defines: a usable consumer with its *"Applies …"*
 * subtitle, a consumer the Actions tab would refuse (disabled, with that tab's reason, and no
 * subtitle because two buffs qualify), and the closing *"Just spend a charge"*.
 *
 * ### The 150 % image is pixel-identical to the 100 % one, and that is a finding, not a mistake
 *
 * Material 3's `ModalBottomSheet` draws in its own window, and that window supplies its own
 * `LocalDensity` — so the app's UI-scale density (`ProvideUiScale`) does not reach the sheet's
 * content. Measured while recording: the sheet's title node is the same size at both steps while
 * a node outside the sheet grows by the factor. Every `ModalBottomSheet` in the app shares this
 * (it is not something this chooser introduced), and it is reported for a ruling rather than
 * patched here. The 150 % golden is kept so the day sheets do scale, this image changes and says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChargeChooserGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the charge chooser at 100 percent`() = capture("ChargeChooser_light_100")

    @Test
    fun `the charge chooser at 150 percent`() = capture("ChargeChooser_light_150", scale = UiScale.LARGE_150)

    /**
     * FR-59 stage 2's long list — twelve consumers — at the smallest height this suite renders.
     * The consumers scroll; *Just spend a charge* is fixed below them and must be in the picture.
     */
    @Test
    @Config(qualifiers = "w411dp-h240dp-xhdpi")
    fun `the charge chooser with twelve consumers on a short screen`() {
        val state = checkNotNull(
            chargeChooserFor(ChargeChooserFixtures.manyConsumersRow(), ChargeChooserFixtures.manyConsumersActions()),
        )
        compose.captureGolden("ChargeChooser_twelve_h240") {
            ChargeChooserSheet(state = state, onUse = {}, onJustSpend = {}, onDismiss = {})
        }
    }

    private fun capture(name: String, scale: UiScale = UiScale.DEFAULT) {
        val row = ChargeChooserFixtures.tracker().resources
            .single { it.propertyId == ChargeChooserFixtures.CHARGE_ROW_ID }
        val state = checkNotNull(chargeChooserFor(row, ChargeChooserFixtures.actions()))
        compose.captureGolden(name, scale = scale) {
            ChargeChooserSheet(state = state, onUse = {}, onJustSpend = {}, onDismiss = {})
        }
    }
}
