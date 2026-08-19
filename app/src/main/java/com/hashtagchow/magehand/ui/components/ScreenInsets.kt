package com.hashtagchow.magehand.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable

/**
 * The content window insets that **every** screen-level `Scaffold` in this app must use.
 *
 * WHY THIS EXISTS (BUG-1: "the keyboard hides anything near the bottom of the screen")
 *
 * `MainActivity` calls `enableEdgeToEdge()`, which is `setDecorFitsSystemWindows(window,
 * false)`. From that moment the manifest's `windowSoftInputMode="adjustResize"` stops
 * resizing anything: on API 30+ a non-decor-fitting window is *never* shrunk by the IME.
 * The keyboard is delivered only as `WindowInsets.ime`, and consuming it becomes the
 * app's job. Nothing consumed it, so the IME drew over the bottom of every screen.
 *
 * That also explains the confusing half of the report — that focused fields sometimes
 * *did* scroll, just not far enough. `Modifier.verticalScroll` honours the focused
 * field's `bringIntoView` request against the scroll **viewport**, and the viewport was
 * still full-height, so "scrolled into view" legitimately meant "into the region the
 * keyboard is covering". Shrink the viewport and the existing bringIntoView machinery
 * lands the field above the keyboard on its own; no per-field code is required.
 *
 * WHY `union` AND NOT `add` — this is the trap
 *
 * `WindowInsets.ime`'s bottom already includes the navigation bar, because the IME draws
 * over it. `ScaffoldDefaults.contentWindowInsets.add(WindowInsets.ime)` therefore counts
 * the navigation bar twice and leaves a dead nav-bar-height gap above the keyboard.
 * `union` takes the per-side maximum, which is what "dock the content to the top of
 * whatever is covering it" actually means.
 *
 * Keyboard down, `WindowInsets.ime` is zero and `union` collapses to exactly
 * `ScaffoldDefaults.contentWindowInsets` — so this is inert on every screen until an IME
 * is actually showing, which is why it is applied uniformly rather than screen by screen.
 *
 * WHERE IT DOES *NOT* BELONG
 *
 * Overlay windows own their own insets and must not be given these as well:
 *   - `ModalBottomSheet` — `TrackerCustomizeSheet` already puts `Modifier.imePadding()`
 *     on its `LazyColumn`; adding scaffold insets on top would double-pad it.
 *   - `AlertDialog` — a dialog is its own window and the platform centres it in the
 *     space left over by the IME.
 */
val screenContentWindowInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)
