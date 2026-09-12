package com.hashtagchow.magehand.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scrollable-footer clearance, and that the local editor still asks for it.
 *
 * ### Defect 1, `docs/verification/sweep-1.17.0/summary.md`
 *
 * The 1.17.0 device sweep found the local character editor's kind-chooser footer — six buttons on
 * two `FlowRow` lines since FR-49 — coming to rest inside the gesture-navigation strip at maximum
 * scroll: an accessibility trace with a button's `boundsInScreen` bottom at **2425 on a 2400 px
 * display**, and taps that reached the system instead of the app. The fix is bottom padding
 * *inside* the scroll ([scrollableFooterPadding]), because the scaffold's insets sit outside it
 * and cannot constrain where the scroll's own maximum puts the last row of content.
 *
 * ### Why this is a pure test and a bytecode scan rather than a render
 *
 * There is no render pin for this. `LocalCharacterEditorScreen` takes its view model from
 * `hiltViewModel()`, so composing it needs a Hilt test application — and even with one, Robolectric
 * reports a **zero** navigation-bar inset, which is precisely the device condition the defect does
 * not occur under. A render test would therefore have passed on the broken build.
 *
 * So the rule is pinned where it can be proven — as a pure function on the inset — and the
 * *wiring* is pinned the way `WritePostureTest` pins its own: by reading the compiled class and
 * checking that the screen still names the thing. The remaining claim, that the padding is enough
 * on a real gesture-navigation phone, is DEVICE-CHECKLIST **W9** and is a device item on purpose.
 */
class ScreenInsetsTest {

    /** A device with no navigation-bar inset still gets the clearance; this is the floor. */
    @Test
    fun `a zero inset still leaves the tap margin`() {
        assertEquals(FOOTER_TAP_MARGIN, footerBottomPadding(0.dp))
        assertTrue("the floor must be non-zero or the defect returns", FOOTER_TAP_MARGIN > 0.dp)
    }

    /**
     * The inset is **added to** the margin, not maxed with it.
     *
     * The sweep's failure is a control resting *at* the inset's edge, so clearing the inset
     * exactly is not enough — the edge-swipe detector claims touches that start within a few dp of
     * it. `max(inset, margin)` would have produced 24 dp of padding on a 48 dp gesture bar, which
     * is the broken build with extra steps.
     */
    @Test
    fun `the margin sits above the navigation bar rather than inside it`() {
        assertEquals(48.dp + FOOTER_TAP_MARGIN, footerBottomPadding(48.dp))
        assertTrue(footerBottomPadding(48.dp) > 48.dp)
    }

    /**
     * The local editor still asks for the padding.
     *
     * `WritePostureTest`'s technique and its argument: a Kotlin call survives into the class
     * file's constant pool and a KDoc does not, so this fails if the modifier is deleted or
     * replaced with a literal — which is the way this fix would realistically be lost, in a
     * conflict resolution nobody re-read.
     */
    @Test
    fun `the local character editor still applies the scrollable footer padding`() {
        val classes = editorClassFiles()
        val names = classes.joinToString { it.name }
        // ISO-8859-1 so every byte maps to a character and nothing is replaced: the constant pool
        // is UTF-8-ish but a class file is not text, and a lossy decode can eat the very bytes
        // being looked for. The needle is the **getter**'s name — a Kotlin top-level `val`
        // compiles to `getScrollableFooterPadding` — plus the file class that declares it, so a
        // same-named property somewhere else would not satisfy this.
        val missing = classes.filterNot { file ->
            val text = String(file.readBytes(), Charsets.ISO_8859_1)
            text.contains("getScrollableFooterPadding") &&
                text.contains("com/hashtagchow/magehand/ui/components/ScreenInsetsKt")
        }

        assertTrue(
            "a compiled LocalCharacterEditorScreen class no longer calls ScreenInsetsKt's " +
                "scrollableFooterPadding: ${missing.map { it.name }} (scanned: $names). " +
                "Defect 1: without it the kind-chooser footer rests in the " +
                "gesture-navigation strip at maximum scroll and sheds taps.",
            missing.isEmpty(),
        )
    }

    /**
     * `LocalCharacterEditorScreenKt` and its lambda classes, discovered rather than named.
     *
     * `WritePostureTest.appClassFiles`'s reasoning, unchanged: where the toolchain writes classes
     * under `build/` is not stable, and a scan that finds nothing must **fail** rather than pass
     * quietly.
     */
    private fun editorClassFiles(): List<File> {
        val buildDir = File(System.getProperty("user.dir") ?: ".", "build")
        val sep = File.separatorChar
        val files = buildDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            // The top-level file class only. Its lambdas carry the call too, but which of them
            // does is a compiler detail, and asserting on ALL of them (below) has to name a set
            // that does not shift under an unrelated edit.
            .filter { it.name == "LocalCharacterEditorScreenKt.class" }
            // **Debug outputs only**, and this is load-bearing: `:app` compiles a release variant
            // too, its classes also live under `build/`, and a stale one from before an edit
            // would otherwise answer for the variant this test actually runs against. Only
            // `testDebugUnitTest` runs in this project.
            .filter { it.path.contains("${sep}debug${sep}") }
            .filterNot { it.path.contains("UnitTest") || it.path.contains("androidTest") }
            .toList()

        assertTrue(
            "found no compiled LocalCharacterEditorScreen classes under $buildDir — this test " +
                "cannot prove anything without them",
            files.isNotEmpty(),
        )
        return files
    }
}
