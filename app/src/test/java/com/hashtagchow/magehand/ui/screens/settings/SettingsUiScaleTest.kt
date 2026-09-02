package com.hashtagchow.magehand.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.settings.UiScale
import java.io.File

/**
 * FR-18's settings control (docs/design/14-large-screen-arc.md decisions 2-4, addendum 3), as far
 * as a JUnit test with no composition can assert it: the state the control renders from, the label
 * each step gets, and the strings themselves.
 *
 * The *rendering* moved. When this file was written, "does TalkBack reach every option" was a
 * device-sweep item; `SettingsUiScaleRenderTest` now owns it in a real composition, which is what
 * FR-38 ruling 6 asks for — the chips' reachability is a claim about a semantics tree, and a
 * semantics tree is something a JVM test can have. Tap targets at 150% on real hardware stay on
 * the sweep (checklist Q2), because whether a thumb can hit 48 dp is not something any test here
 * can answer.
 */
class SettingsUiScaleTest {

    @Test
    fun `the settings state carries the chosen scale`() {
        // The control is driven by stored state rather than by a local `remember`, which is
        // what makes the highlighted option and the size on screen the same fact.
        assertEquals(UiScale.LARGE_125, SettingsUiState(uiScale = UiScale.LARGE_125).uiScale)
    }

    @Test
    fun `the frame before the first read highlights the size the app is drawn at`() {
        // Same defect FR-6's switch avoided: a state seeded with anything else would highlight
        // an option the app is not rendering at, for the frame before DataStore answers.
        assertEquals(UiScale.DEFAULT, SettingsUiState().uiScale)
    }

    @Test
    fun `every step has its own label, and no two share one`() {
        val labels = UiScale.entries.map { uiScaleLabel(it) }

        assertEquals("a step is missing its label", UiScale.entries.size, labels.size)
        assertEquals("two steps share a label", labels.size, labels.toSet().size)
        assertTrue("a label resolved to no resource at all", labels.all { it != 0 })
    }

    @Test
    fun `the labels and the description are the words 14 decisions 3 and 4 specify`() {
        val strings = stringsXml()

        // Decision 4: options "labeled with percentages". The percentages are the decision — a
        // label reading "Large" would hide that this multiplies the system scale. FR-38 adds the
        // three below 100%, which have to read as percentages for the same reason: "Small" would
        // say nothing about how much smaller.
        assertTrue(strings.contains(">70%<"))
        assertTrue(strings.contains(">80%<"))
        assertTrue(strings.contains(">90%<"))
        assertTrue(strings.contains(">Default<"))
        assertTrue(strings.contains(">110%<"))
        assertTrue(strings.contains(">125%<"))
        assertTrue(strings.contains(">150%<"))

        // Decision 3's sentence, verbatim: the sheet is a WebView and can only scale its text,
        // and saying so is what keeps that from being discovered as a bug.
        assertTrue(
            "the UI-size description no longer tells the truth about the character sheet",
            strings.contains(
                "Applies across the app; the character sheet scales its text.",
            ),
        )

        // Decision 4's group label, which is also what TalkBack reads for the row.
        assertTrue(strings.contains(">UI size<"))
    }

    /** `app/src/main/res/values/strings.xml`, found by walking up from the module directory. */
    private fun stringsXml(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val fromRoot = File(dir, "app/src/main/res/values/strings.xml")
            if (fromRoot.isFile) return fromRoot.readText()
            val fromModule = File(dir, "src/main/res/values/strings.xml")
            if (fromModule.isFile) return fromModule.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not find strings.xml from ${System.getProperty("user.dir")}")
    }
}
