package com.hashtagchow.magehand.ui.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.hashtagchow.magehand.core.data.settings.UiScale
import java.io.File

/**
 * Where the committed goldens live, as the Roborazzi Gradle plugin reports it.
 *
 * `app/build.gradle.kts` sets `roborazzi { outputDir = src/test/snapshots/img }`, and the plugin
 * passes that through to the test JVM as `roborazzi.output.dir`. Reading it back rather than
 * repeating the literal is what stops the build script and the tests from disagreeing about where
 * the corpus is — a disagreement whose symptom is `verifyRoborazziDebug` passing against files
 * nobody committed.
 *
 * ### Why `img/` and not `snapshots/` itself (BUG-12)
 *
 * Gradle caches the record task's output *directory*, so a cache hit restores everything in it as
 * the task last produced it — which reverted the corpus's hand-written `README.md` after every
 * `clean` build. The images are the task's output; the README is not. So the images live in
 * `snapshots/img/` and the README stays in `snapshots/`, one level above anything Gradle owns.
 * The fallback below must therefore name `img/` too: it is only reached when the system property
 * is missing, and a fallback pointing one level up would write goldens back into the README's
 * directory and re-create the bug.
 *
 * ### Why the path is spelled out at each capture at all
 *
 * Roborazzi resolves a *bare* file name against the module directory, not against `outputDir`;
 * `outputDir` is the default only for the no-argument, rule-driven capture, which names its files
 * after the test method. This corpus is named after what it draws
 * (`TrackerScreen_dark_150.png`), because a golden's whole job is to be recognised in a diff by a
 * human — so [captureGolden] joins the two itself and no caller can get it wrong.
 */
private val snapshotDir: String =
    System.getProperty("roborazzi.output.dir") ?: "src/test/snapshots/img"

/**
 * Render [content] in the app's real root composition and commit one golden of the result.
 *
 * ### `captureScreenRoboImage`, not `captureRoboImage`
 *
 * Roborazzi's composable-scoped capture photographs one composition. Half this corpus is bottom
 * sheets and dialogs, which Compose renders in **their own window** — so that call would have
 * produced a picture of the empty screen behind an `AddItemSheet` rather than of the sheet.
 * `captureScreenRoboImage` photographs every window Robolectric has, which is what the user is
 * looking at. Using it for the non-sheet cases too keeps one code path and one framing rule
 * across the whole corpus, so a diff between two goldens is a diff about the app.
 *
 * ### Sizing is the device's, never the composable's
 *
 * There is no `width` parameter here on purpose. The narrow-width cases design 19 decision 7 names
 * — the "Ite/m" row wrap and the vertical "Add" action — are about a composable being genuinely
 * short of room on a real phone, so they are captured by putting the *test class* on a narrow
 * device with `@Config(qualifiers = …)`. A `Modifier.width()` here would have proven that the
 * composable wraps at 320 dp and said nothing about whether a phone ever gives it 320 dp.
 *
 * @param name the file's stem. Convention: `Subject_variant_variant` — subject first so the
 *   directory listing groups by screen, variants in the order the design lists them
 *   (tabs → theme → scale). The `.png` is appended here.
 */
fun ComposeContentTestRule.captureGolden(
    name: String,
    scale: UiScale = UiScale.DEFAULT,
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    setMageHandContent(scale = scale, darkTheme = darkTheme, content = content)
    commitGolden(name)
}

/**
 * The capture half of [captureGolden], for the goldens that need a **tap first**.
 *
 * Roborazzi photographs the same Robolectric environment layer 1 drives, which is design 19
 * decision 0's whole argument for choosing it over a layoutlib renderer: a post-interaction state
 * — a sheet switched to its custom-item form, a section expanded — is reachable here and is not
 * reachable at all from a static preview renderer. Callers `setMageHandContent`, drive the UI with
 * the ordinary Compose finders, then call this.
 */
// `captureScreenRoboImage` is @ExperimentalRoborazziApi — the whole-screen capture is newer than
// the composable-scoped one. Opted in here, at the single call site the corpus has, rather than
// module-wide: the annotation is Roborazzi telling us this signature may move, and one call site
// is the cost of that move. Left as a warning it was the one warning
// `compileDebugUnitTestKotlin` carried (BUG-12's leftover).
@OptIn(ExperimentalRoborazziApi::class)
fun ComposeContentTestRule.commitGolden(name: String) {
    // Sheets animate in, and a golden of a half-open sheet would be a golden of the animation
    // clock rather than of the layout. The rule's own idling is what settles that.
    waitForIdle()
    captureScreenRoboImage(File(snapshotDir, "$name.png").path)
}
