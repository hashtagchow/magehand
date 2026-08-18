package com.hashtagchow.magehand.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError,
)

/**
 * Material 3 theme scaffold.
 *
 * Per docs/design/04-screens-ux.md:
 *  - dark theme **follows the system** (a manual override lands with Settings, WP8);
 *  - dynamic (wallpaper) colour is **off by default**;
 *  - the **per-character accent** seeds the scheme when one is set. WP6 wires that: the
 *    character home screen passes `theme_prefs.accentColor` down, so the tracker, its
 *    pips and its chips take the colour the player picked for that character and every
 *    other screen keeps the app default.
 *
 * @param darkTheme defaults to the system setting.
 * @param accentColor `"#RRGGBB"` from `theme_prefs`, or `null` for the app palette.
 * @param dynamicColor opt-in only; off by default.
 */
@Composable
fun MageHandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accentColor: String? = null,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val colorScheme = remember(baseScheme, accentColor, darkTheme) {
        AccentPalette.parse(accentColor)
            ?.let { baseScheme.seededWith(AccentPalette.roles(it, darkTheme)) }
            ?: baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MageHandTypography,
        content = content,
    )
}

/**
 * Overlays the accent-derived roles onto a scheme.
 *
 * Only the six fills and their on-colours move; `surface`, `background`, `outline` and
 * `error` stay the app's, so a badly chosen accent can make the tracker garish but can
 * never make an error message unreadable.
 */
private fun ColorScheme.seededWith(roles: AccentRoles): ColorScheme = copy(
    primary = Color(roles.primary),
    onPrimary = Color(roles.onPrimary),
    primaryContainer = Color(roles.primaryContainer),
    onPrimaryContainer = Color(roles.onPrimaryContainer),
    secondary = Color(roles.secondary),
    onSecondary = Color(roles.onSecondary),
    secondaryContainer = Color(roles.secondaryContainer),
    onSecondaryContainer = Color(roles.onSecondaryContainer),
    tertiary = Color(roles.tertiary),
    onTertiary = Color(roles.onTertiary),
    tertiaryContainer = Color(roles.tertiaryContainer),
    onTertiaryContainer = Color(roles.onTertiaryContainer),
)
