package com.hashtagchow.magehand.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.scale.ProvideUiScale
import com.hashtagchow.magehand.ui.theme.MageHandTheme

/**
 * The composition every FR-34 test renders inside, and the reason there is only one of it.
 *
 * ### It is `MainActivity`'s root, minus the navigation
 *
 * `MainActivity.onCreate` composes `ProvideWindowSizeGate` → [ProvideUiScale] → [MageHandTheme] →
 * a `Surface` at `colorScheme.background`, and every screen in this app is drawn under all four.
 * A test that called `setContent { TrackerTab(...) }` directly would be rendering a composable
 * the app never renders: no theme (so Material's own defaults, not this app's palette), no
 * background surface (so a transparent golden), and — the one that matters most — no scale
 * provider, which is the mechanism FR-18 ships and decision 7 requires the goldens to exercise.
 *
 * The window-size gate is deliberately *not* included. It reads real window metrics, which in
 * Robolectric come from the device qualifier rather than from anything a test controls
 * meaningfully, and every composable these tests render takes its width from the layout it is
 * given rather than from that local. `PaneSelectionTest` and `WindowSizeGateTest` own that gate.
 *
 * ### The scale goes through `ProvideUiScale`, never through a density hack
 *
 * Design 19 decision 7: *"Scale variants compose through `UiScaleProvider` (the shipped
 * mechanism, not a test-only density hack) so goldens exercise the real FR-18 path"*. A test that
 * overrode `LocalDensity` itself would prove that Compose scales — which is not in doubt — and
 * would leave the app's own multiply-the-system-density rule untested at exactly the sizes BUG-4
 * showed up at.
 *
 * @param scale the FR-18 step. [UiScale.DEFAULT] is neutral by construction (its factor is
 *   exactly 1.0), so passing it renders precisely what an un-scaled install renders.
 * @param darkTheme passed explicitly rather than left to `isSystemInDarkTheme()`: a golden that
 *   asked the environment what colour to be would be a golden that changed with the environment.
 */
@Composable
fun MageHandTestSurface(
    scale: UiScale = UiScale.DEFAULT,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    ProvideUiScale(scale) {
        MageHandTheme(darkTheme = darkTheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

/** [MageHandTestSurface] as a one-liner on the rule, which is how every layer-1 test opens. */
fun ComposeContentTestRule.setMageHandContent(
    scale: UiScale = UiScale.DEFAULT,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) = setContent { MageHandTestSurface(scale = scale, darkTheme = darkTheme, content = content) }
