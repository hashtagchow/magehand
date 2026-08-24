package com.hashtagchow.magehand.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.settings.UiScale
import java.io.File

/**
 * FR-18's settings control (docs/design/14-large-screen-arc.md decisions 2-4), as far as a
 * module with no Compose test harness can assert it: the state the control renders from, the
 * label each step gets, and the strings themselves. The *rendering* — segmented buttons,
 * TalkBack announcements, tap targets at 150% — is checklist area Q on the device sweep,
 * which is where 14's acceptance shape puts it.
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

        // Decision 4: "segmented buttons labeled with percentages". The percentages are the
        // decision — a label reading "Large" would hide that this multiplies the system scale.
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
