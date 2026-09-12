package com.hashtagchow.magehand.ui.screens.settings

/**
 * The app's version, as the Settings **About** section prints it (BUG-24).
 *
 * ### Why this is a parameter and not a `BuildConfig` read
 *
 * `AboutSection` used to read `BuildConfig.VERSION_NAME` / `VERSION_CODE` itself. That is one line
 * of a composable, but it is also a hard-wired dependency on a value that changes at **every
 * release** — and `ScreensGoldenTest` photographs that section. The picture therefore asserted the
 * build number, so the 29 → 30 bump alone turned `verifyRoborazziDebug` red; that task is step 3
 * of `tools/magehand-release.sh`, so the release gate failed by construction on a bump that had
 * broken nothing, and the runner had to re-record a golden mid-release. BUG-24 is that defect.
 *
 * Injecting the value fixes it at the only place it can be fixed: the screen takes the version
 * from its caller, production supplies it from `BuildConfig` (`MageHandNavHost`), and the golden
 * supplies a constant. The golden then pins what a golden is for — the section's layout and the
 * attribution sentences — and stops pinning a number that is guaranteed to move.
 *
 * ### Deliberately a plain value
 *
 * No Hilt binding, no provider interface, no `BuildConfig` read of its own: one data class and one
 * call site. Anything larger would be scaffolding around two strings. A future second consumer can
 * lift the `BuildConfig` read out of the nav host into a module without touching this type.
 *
 * @param name the marketing version, e.g. `"1.17.0"` (the debug build appends `-debug`).
 * @param code the monotonic Play version code, e.g. `30`.
 */
data class AppVersion(val name: String, val code: Int)
