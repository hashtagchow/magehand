package com.hashtagchow.magehand.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

/**
 * The bottom padding a screen must put **inside** its scrolling content when the last thing in
 * that content is something the player taps.
 *
 * WHY THIS EXISTS (Defect 1, `docs/verification/sweep-1.17.0/summary.md`)
 *
 * FR-49 added two buttons to the local editor's kind-chooser footer, which took it from four
 * buttons (one `FlowRow` line) to six (two). Scrolled to the absolute end, the second line came to
 * rest against the bottom of the screen — the sweep's accessibility trace recorded a button with
 * `boundsInScreen` bottom **2425 on a 2400 px display**, and taps Maestro computed as on-screen
 * did not reach the button at all, because that strip belongs to the system's gesture navigation.
 * No error, no row added; the player taps *Add spell* and nothing happens.
 *
 * WHY THE SCAFFOLD'S OWN INSETS WERE NOT ENOUGH
 *
 * [screenContentWindowInsets] is applied by the `Scaffold` and arrives as `innerPadding`, which
 * the editor puts **outside** its `verticalScroll` — correct, and it is what stops the content
 * starting under the status bar. What it cannot do is guarantee a resting position: the scroll's
 * own maximum puts the last pixel of content at the last pixel of the viewport, and a system
 * gesture region is not a rectangle content merely has to avoid *drawing* in — it is a region
 * whose touches the app does not receive. So the guarantee has to be padding the scroll itself
 * carries, which is what this is.
 *
 * `navigationBars` rather than `systemBars`: the top half is already handled and re-adding it
 * inside the scroll would push the first field down for no reason. The margin on top of it is
 * because the gesture strip is not the whole story — a touch that *starts* within a few dp of the
 * edge can be claimed by the system's edge-swipe detector before the app sees it, so a control
 * resting exactly at the inset's edge is still a control that sheds taps.
 *
 * Zero-inset devices (three-button navigation on some OEM builds, and Robolectric) get
 * [FOOTER_TAP_MARGIN] alone, which is the floor this is allowed to reach.
 */
val scrollableFooterPadding: Dp
    @Composable get() = footerBottomPadding(
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

/**
 * [scrollableFooterPadding]'s rule, as a pure function so it can be pinned without a device.
 *
 * @param navigationBarBottom the bottom inset the navigation bar claims, in dp.
 */
internal fun footerBottomPadding(navigationBarBottom: Dp): Dp =
    navigationBarBottom + FOOTER_TAP_MARGIN

/**
 * The clearance between a tappable footer and the navigation bar.
 *
 * Not a design token and not tuned: it is one 24 dp step of the spacing this app already uses,
 * chosen to be comfortably larger than the edge-swipe slop and small enough that it reads as the
 * end of a form rather than as a gap. The number matters much less than its being non-zero.
 */
internal val FOOTER_TAP_MARGIN = 24.dp
