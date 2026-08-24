package com.hashtagchow.magehand.ui.window

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.window.core.layout.WindowSizeClass

/**
 * Whether this window is wide enough for FR-17's multi-pane chrome
 * (docs/design/14-large-screen-arc.md decision 5), readable anywhere under [ProvideWindowSizeGate].
 *
 * A `Boolean` and not the `WindowSizeClass` itself, deliberately. The design has exactly one
 * width question in it — *is this EXPANDED?* — and publishing the size class would invite every
 * future screen to ask its own slightly different one, at which point "medium and compact keep
 * today's tab row untouched" stops being a property of the app and becomes a property of
 * whichever screen last got edited. [isExpandedWidth] is where the threshold lives, once.
 *
 * Defaults to `false` so a composable read outside the provider — a `@Preview`, a test — gets
 * the phone path, which is the one that must never be wrong.
 *
 * `static` for [com.hashtagchow.magehand.ui.scale.LocalUiScale]'s reason: it changes when the
 * window is resized or folded, which is approximately never compared to how often it is read.
 *
 * ### FR-19's seam
 *
 * Decision 12 puts the DM view behind this same gate ("requires the FR-17 width gate; on smaller
 * widths the entry is absent in v1"). It reads this local from the character list — nothing else
 * needs to be built here for that, and nothing about this local is FR-17-specific, which is why
 * it is named for the window rather than for the panes.
 */
val LocalExpandedWidth = staticCompositionLocalOf { false }

/**
 * Decision 5's gate, as a pure function over a [WindowSizeClass] so it can be asserted without a
 * composition (`:app` has no Compose test harness — see `StartDestinationNavigationTest`).
 *
 * ### Why EXPANDED and not "wide enough for three columns"
 *
 * Because the design says EXPANDED, and because the alternative is a number this app would then
 * own. `WIDTH_DP_EXPANDED_LOWER_BOUND` is 840 dp, which is the Material breakpoint every other
 * adaptive app on the device agrees on; a hand-picked threshold would put this app's idea of
 * "tablet" one resize step away from the system's.
 *
 * ### Why `isWidthAtLeastBreakpoint` and not `windowWidthSizeClass == EXPANDED`
 *
 * The equality form is a *bug on the largest screens*, and a quiet one. The V2 breakpoint set
 * adds LARGE (1200 dp) and EXTRA_LARGE (1600 dp) above EXPANDED, so a desktop-sized window is
 * classified LARGE — and `== EXPANDED` is then false, dropping a 1600 dp window back to the
 * phone tab row. *"At least this wide"* is the question decision 5 actually asks, and it is the
 * one that stays right when a future breakpoint set adds another tier above.
 *
 * Note what this deliberately does **not** consider: height, orientation, and whether the device
 * calls itself a tablet. A landscape phone is width MEDIUM and keeps the tab row (decision 5
 * says so explicitly), a folded foldable is compact, and a freeform window dragged narrow on a
 * desktop-mode device is whatever its *window* is — which is the point of measuring the window
 * rather than the screen.
 *
 * ### Why the app scale must not enter into it — and how it can
 *
 * The app scale *should* be irrelevant here: the panes exist because the display is physically
 * large, and a user who asked for bigger text did not ask to be moved back to a phone layout.
 * (It does mean three panes at 150 % are cramped; that is a sweep item — 14 §Acceptance shape,
 * area Q — not a reason to couple two independent settings.)
 *
 * But that is a property of *where this is composed*, not of [currentWindowAdaptiveInfoV2].
 * Adaptive 1.3.0 computes the size class as `window_px / LocalDensity.current.density` — it
 * measures the real window, then converts it with whatever density the composition supplies.
 * FR-18's `ProvideUiScale` supplies `deviceDensity * factor`, so a gate composed underneath it
 * divides by too much and reports a *smaller* window than the one on the desk. At the top step
 * (150 %) every window from 840 dp to 1259 dp falls under the breakpoint, and a device sitting
 * exactly on 840 dp is demoted by the *smallest* step there is — 110 % reads 763 dp. Nothing
 * crashes; pane mode and the FR-19 DM entry simply stop being offered, on a device that
 * qualifies, because of a text-size setting.
 *
 * Hence the standing rule, pinned in `PaneSelectionTest`: **[ProvideWindowSizeGate] is mounted
 * above `ProvideUiScale`, so the density it reads is the device's own.** `WindowSizeGateTest`
 * pins the arithmetic separately, so the reason survives even if the nesting is re-read as
 * arbitrary.
 */
fun isExpandedWidth(sizeClass: WindowSizeClass): Boolean =
    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/**
 * Publishes [LocalExpandedWidth] for [content], recomputed from the **live window metrics**.
 *
 * ### Why this is at the activity root and not inside the character screen
 *
 * Nearly `ProvideUiScale`'s reason: it is the one composition every screen is inside, so the
 * window is measured once rather than once per screen that cares. It must additionally sit
 * *above* `ProvideUiScale` — see [isExpandedWidth]'s scale section for the defect that ordering
 * prevents.
 *
 * ### What "crossing the gate preserves state" does and does not mean
 *
 * Decision 10's guarantee is about the two *selections* — the tab and the pane set — and it
 * holds exactly: `characterHomeChrome` reads both and converts neither, and both are backed by
 * `rememberSaveable`/a store rather than by which chrome is drawn. `PaneSelectionTest` pins it.
 *
 * What is **not** preserved, stated plainly because an earlier version of this KDoc claimed
 * otherwise:
 *
 * - **Rotation recreates the activity.** `AndroidManifest.xml` declares no `configChanges` on
 *   `MainActivity`, so a rotation is a full recreate — the whole composition is torn down and
 *   rebuilt, and anything not in `rememberSaveable` or a `ViewModel` is gone. This is true with
 *   or without FR-17; it is the behaviour every build before 1.7.0 shipped, and it is accepted
 *   for that reason rather than newly introduced here.
 * - **Crossing the gate within one window disposes the branch that was drawn.** The tab body
 *   and the pane column are different subtrees, so a multi-window drag or an unfold across
 *   840 dp loses plain `remember` state inside them: scroll offsets, expansion animations, the
 *   Sheet WebView when it leaves the selection. Accepted: the resize case is rare, decision 9
 *   *wants* the deselected Sheet destroyed, and hoisting scroll and expansion state up past the
 *   gate is a refactor whose blast radius is larger than the defect (14 §Acceptance shape).
 *
 * Neither costs a *selection*, which is what decision 10 is about. They cost view state, and
 * `CharacterHomeScreen`'s KDoc names the same two facts at the screen they are visible on.
 *
 * ### Live re-evaluation
 *
 * [currentWindowAdaptiveInfoV2] observes the window metrics, so a rotation, an unfold or a
 * multi-window drag re-runs this and flips the local mid-session — decision 5's
 * *"evaluated on the real window metrics so foldables/resizes transition live"*. Reading
 * `LocalConfiguration.screenWidthDp` would have been one line shorter and would have measured
 * the wrong thing on the exact devices this feature is for.
 *
 * The `V2` suffix is not a preference: the un-suffixed `currentWindowAdaptiveInfo()` is
 * deprecated in adaptive 1.3.0 precisely because it cannot express the LARGE and EXTRA_LARGE
 * width classes. See [isExpandedWidth] for why that matters even to a feature with one
 * threshold in it.
 */
@Composable
fun ProvideWindowSizeGate(content: @Composable () -> Unit) {
    val expanded = isExpandedWidth(currentWindowAdaptiveInfoV2().windowSizeClass)
    CompositionLocalProvider(LocalExpandedWidth provides expanded, content = content)
}
