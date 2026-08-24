package com.hashtagchow.magehand.ui.scale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.hashtagchow.magehand.core.data.settings.UiScale

/**
 * The chosen [UiScale], readable anywhere under [ProvideUiScale].
 *
 * Exists for the one consumer that cannot be served by [LocalDensity]: the Sheet tab's
 * WebView renders its own content with its own text sizing, so it needs the *factor*, not a
 * `Density` (docs/design/14-large-screen-arc.md decision 3). Everything drawn by Compose
 * needs nothing from this — it is already measuring at the scaled density.
 *
 * `static` because it changes about as often as the user opens Settings: a static local
 * invalidates every reader rather than tracking them individually, which is the cheaper
 * trade for a value that is read in two places and written approximately never.
 *
 * Defaults to [UiScale.DEFAULT] so a composable read outside the provider (a `@Preview`, a
 * test) gets the un-scaled app rather than an exception.
 */
val LocalUiScale = staticCompositionLocalOf { UiScale.DEFAULT }

/**
 * FR-18 decision 1's density math, as a function, so it can be asserted without a
 * composition (`:app` has no Compose test harness — see `StartDestinationNavigationTest`).
 *
 * Both components are scaled, and that is the decision rather than an implementation
 * detail: scaling `fontScale` alone grows the text inside touch targets and spacing that
 * stay exactly where they were, which is how you get a 150% label in a 100% button. 14
 * decision 1 wants the *layout* bigger, so `density` — the dp→px factor everything in
 * Compose measures through — is what carries it, with `fontScale` alongside so sp text
 * keeps its ratio to the dp box it sits in.
 *
 * @param base **the system's density**, never an already-scaled one. See [ProvideUiScale]
 *   for why that distinction is the whole of this function's contract.
 */
fun scaledDensity(base: Density, scale: UiScale): Density =
    Density(
        density = base.density * scale.factor,
        fontScale = base.fontScale * scale.factor,
    )

/**
 * Wraps [content] so every composable under it — every screen, and every dialog and bottom
 * sheet, which inherit composition locals from the composition that opened them — measures
 * at [scale].
 *
 * ### The base is read here, outside the provider, and that is load-bearing
 *
 * `LocalDensity.current` on this line is whatever the platform handed the Activity: the
 * device's density *including* the user's system font-size and display-size accessibility
 * settings. 14 decision 1 wants the app factor to multiply those, and this is the line where
 * that happens — the user's 1.3 system font scale and a 125% app scale compose to 1.625, and
 * the app scale can never drag the effective scale below what accessibility settings asked
 * for, because every factor is ≥ 1.0.
 *
 * Read it *inside* the `CompositionLocalProvider` instead — or nest a second
 * [ProvideUiScale] anywhere under this one — and the base becomes the already-scaled value,
 * so recomposition compounds: 1.25, then 1.5625, then 1.95. That is the specific defect
 * `UiScaleProviderTest` pins, both by arithmetic and by asserting this app provides
 * `LocalDensity` in exactly one place.
 *
 * ### Why this is not inside `MageHandTheme`
 *
 * `CharacterHomeScreen` re-enters `MageHandTheme` to apply a per-character accent colour, so
 * a provider living in the theme would be entered twice on that screen — the nesting case
 * above, shipped by construction. This sits above the theme, is called once, and the nested
 * theme call re-supplies colours only.
 */
@Composable
fun ProvideUiScale(
    scale: UiScale,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides scaledDensity(base, scale),
        LocalUiScale provides scale,
        content = content,
    )
}
