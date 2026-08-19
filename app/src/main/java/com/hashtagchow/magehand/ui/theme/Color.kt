package com.hashtagchow.magehand.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Default accent seed for the Material 3 scheme.
 *
 * Per docs/design/04-screens-ux.md the scheme is eventually seeded per character
 * (`theme_prefs` accent colour); WP1 ships the fallback seed only.
 */
val MageHandSeed = Color(0xFF6B4FBB)

// Light scheme
val LightPrimary = Color(0xFF5B44A6)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE6DEFF)
val LightOnPrimaryContainer = Color(0xFF1B0E4A)
val LightSecondary = Color(0xFF615B71)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE7DEF8)
val LightOnSecondaryContainer = Color(0xFF1D192B)
val LightTertiary = Color(0xFF7D5260)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFFDFBFF)
val LightOnBackground = Color(0xFF1C1B1F)
val LightSurface = Color(0xFFFDFBFF)
val LightOnSurface = Color(0xFF1C1B1F)
val LightSurfaceVariant = Color(0xFFE5E0EC)
val LightOnSurfaceVariant = Color(0xFF48454E)
val LightOutline = Color(0xFF79767F)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)

// Light surface containers — see the dark block below for why these exist at all.
val LightSurfaceDim = Color(0xFFDDD8E2)
val LightSurfaceBright = Color(0xFFFDFBFF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF7F2FC)
val LightSurfaceContainer = Color(0xFFEDE7F5)
val LightSurfaceContainerHigh = Color(0xFFE7E1EF)
val LightSurfaceContainerHighest = Color(0xFFE1DBE9)

// Dark scheme
val DarkPrimary = Color(0xFFCDBEFF)
val DarkOnPrimary = Color(0xFF2F1B72)
val DarkPrimaryContainer = Color(0xFF45338C)
val DarkOnPrimaryContainer = Color(0xFFE6DEFF)
val DarkSecondary = Color(0xFFCBC2DB)
val DarkOnSecondary = Color(0xFF322D41)
val DarkSecondaryContainer = Color(0xFF494458)
val DarkOnSecondaryContainer = Color(0xFFE7DEF8)
val DarkTertiary = Color(0xFFEFB8C8)
val DarkOnTertiary = Color(0xFF492532)
val DarkBackground = Color(0xFF141218)
val DarkOnBackground = Color(0xFFE6E1E9)
val DarkSurface = Color(0xFF141218)
val DarkOnSurface = Color(0xFFE6E1E9)
val DarkSurfaceVariant = Color(0xFF48454E)
val DarkOnSurfaceVariant = Color(0xFFC9C5D0)
val DarkOutline = Color(0xFF938F99)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)

/**
 * The surface-container ramp — **BUG-2's fix**, and the reason it is a palette change rather
 * than a patch on one menu.
 *
 * ### The defect
 *
 * Material 3 draws every "container floating over the page" — dropdown and exposed-dropdown
 * menus, bottom sheets, cards, navigation surfaces — on the `surfaceContainer*` roles, not on
 * `surface`. This app's schemes named `surface`, `background` and `surfaceVariant` and left
 * the five container roles at Material's **baseline** values, which are computed for
 * Material's own baseline `surface` and not for this one. On the dark scheme that put the
 * Rolls dropdown's menu at `#211F26` over a page at `#141218` — thirteen levels of grey
 * apart, with a shadow that is invisible on a dark background — so the menu read as text
 * floating on nothing. That is the bug the screenshot review caught.
 *
 * ### Why fixing it here fixes it everywhere
 *
 * Nothing in this app names `MenuDefaults.containerColor`. Every menu, sheet and card asks
 * the *scheme* for its container colour, so stating the ramp once in the scheme is the whole
 * fix — and a menu added by a future feature is fixed before it is written. A per-call-site
 * `containerColor =` on the one dropdown that exists today would have been a fix that the
 * second dropdown does not inherit.
 *
 * ### How the values were chosen
 *
 * The ramp is deliberately **wider than Material's baseline**: `DarkSurfaceContainer` sits
 * about 29% above `DarkSurface` in WCAG relative luminance, against the baseline's 14%.
 * Material's own figures assume its own near-black surface and a shadow that lands; at these
 * levels the shadow contributes nothing, so the tonal step has to carry the separation on its
 * own. `SurfacePaletteTest` pins both the direction (containers rise on dark, fall on light)
 * and a floor on that separation, so a future palette edit cannot quietly re-flatten it.
 */
val DarkSurfaceDim = Color(0xFF141218)
val DarkSurfaceBright = Color(0xFF3B383E)
val DarkSurfaceContainerLowest = Color(0xFF0D0C10)
val DarkSurfaceContainerLow = Color(0xFF1C1A21)
val DarkSurfaceContainer = Color(0xFF2B2836)
val DarkSurfaceContainerHigh = Color(0xFF37333F)
val DarkSurfaceContainerHighest = Color(0xFF433E4C)
