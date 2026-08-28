package com.hashtagchow.magehand.ui.screens.login

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FR-33's password reveal toggle, pinned as a **posture** rather than as behaviour.
 *
 * ### Why this is a source-reading test and not a state test
 *
 * The brief asks for the rule to be pinned "at the sign-in form's existing seam (UiState if
 * present, else structural)", and there is deliberately no seam to use. `CredentialsUiState`
 * carries `isSubmitting`, `error` and `hint` and **nothing else**: the password never reaches the
 * ViewModel at all, because a `StateFlow` in a `ViewModel` survives configuration changes and can
 * be dumped by tooling (docs/design/05-security.md §"Token & credential handling", and that
 * class's own KDoc). Adding a `passwordVisible` field to that state to make it testable would
 * mean putting the *visibility of a password* into the one object this screen exists to keep
 * credentials out of — trading a real security posture for a convenient assertion.
 *
 * `:app` has no Compose test harness either (`StartDestinationNavigationTest` records why), so the
 * remaining options are "a human checks once, on a device" and this. `UiScaleProviderTest` and
 * `WritePostureTest` set the precedent: where a rule cannot be expressed as a pure function, read
 * it out of the source, because a rule nobody can check is a rule that quietly stops being true.
 *
 * ### The rule
 *
 * The reveal state must be a plain `remember`, **never** `rememberSaveable`. Saveable state is
 * written into the saved-instance `Bundle`, which the system persists and a bug report can
 * contain — so a revealed password must not survive process death, and neither must the fact that
 * it was revealed. That is the same rule the password field itself has followed since WP1, one
 * step further out, and it is the half of this feature that is invisible when it is wrong.
 */
class PasswordVisibilityPostureTest {

    private val source: String by lazy {
        mainSourceFiles().single { it.name == "CredentialsScreen.kt" }.readText()
    }

    @Test
    fun `the reveal state is remembered but never saved`() {
        assertTrue(
            "CredentialsScreen no longer holds a passwordVisible flag",
            source.contains("var passwordVisible by remember {"),
        )
        assertFalse(
            "a revealed password must not survive process death: the saved-instance Bundle is " +
                "persisted by the system and readable from a bug report",
            source.contains("var passwordVisible by rememberSaveable"),
        )
    }

    /**
     * The password itself is still unsaved too.
     *
     * Restated here rather than left to WP1's KDoc because FR-33 is the first change to touch this
     * field since, and the two rules stand or fall together: revealing a password that *is* saved
     * would put the plaintext in the `Bundle` whether or not the toggle's own flag was saved.
     */
    @Test
    fun `the password itself is still a plain remember`() {
        assertTrue(source.contains("""var password by remember { mutableStateOf("") }"""))
        assertFalse(source.contains("var password by rememberSaveable"))
    }

    /**
     * The transformation is genuinely removed when revealed, and genuinely applied otherwise.
     *
     * A toggle that flipped an icon without changing `visualTransformation` would be the exact
     * bug this feature exists to fix, and it looks correct in a screenshot of the *masked* state —
     * which is the state a reviewer sees first.
     */
    @Test
    fun `the toggle drives the visual transformation in both directions`() {
        assertTrue(
            "revealed must mean VisualTransformation.None",
            source.contains("if (passwordVisible) {") && source.contains("VisualTransformation.None"),
        )
        assertTrue(
            "and masked must still mean PasswordVisualTransformation",
            source.contains("PasswordVisualTransformation()"),
        )
    }

    /**
     * Both spoken labels exist and both are used, which is the a11y half of the feature.
     *
     * Two strings rather than one because the label has to say what pressing the control *does*,
     * and that flips with the state — a single "Password visibility" leaves a screen-reader user
     * unable to tell which way it is currently set. The visible label is one word (it lives in a
     * text field's trailing slot); the spoken one is the whole phrase, set as a
     * `contentDescription` on the merged button node where it takes precedence over the child
     * text.
     */
    @Test
    fun `the control announces what pressing it will do`() {
        assertTrue(source.contains("R.string.action_show_password"))
        assertTrue(source.contains("R.string.action_hide_password"))
        assertTrue("the phrase is set as a contentDescription, not left as the one-word label",
            source.contains("contentDescription = showPasswordLabel"))

        val strings = stringsXml()
        listOf(
            "action_show_password",
            "action_hide_password",
            "credentials_password_show",
            "credentials_password_hide",
        ).forEach {
            assertTrue("missing string $it", strings.contains("""name="$it""""))
        }
    }

    /**
     * No icon dependency was added for this.
     *
     * `Visibility`/`VisibilityOff` live in `material-icons-extended`, which this app deliberately
     * does not depend on — `libs.versions.toml` pins `material-icons-core` with that reason
     * written beside it, and the extended set is tens of megabytes of vectors. A one-word text
     * affordance says the same thing for nothing, and this assertion is what stops the next wave
     * from reaching for the glyph without seeing the note.
     */
    @Test
    fun `the app still depends on the core icon set only`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()

        assertFalse(
            "material-icons-extended is ~50 MB of vectors and is deliberately avoided",
            catalog.contains("material-icons-extended"),
        )
        assertTrue(
            "…and the core set is still declared, so the glyphs the app does use keep working",
            catalog.contains("material-icons-core"),
        )
    }

    // --- source access -------------------------------------------------------

    /** `UiScaleProviderTest`'s walk-up, which is the house way to reach a source file. */
    private fun mainSourceFiles(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val root = File(dir, "src/main/java/com/hashtagchow/magehand")
            if (root.isDirectory) {
                return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not find :app sources from ${System.getProperty("user.dir")}")
    }

    private fun stringsXml(): String = File(moduleDir(), "src/main/res/values/strings.xml").readText()

    private fun moduleDir(): File = File(System.getProperty("user.dir") ?: ".").absoluteFile

    private fun repoFile(path: String): File {
        var dir: File? = moduleDir()
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("could not find $path from ${moduleDir()}")
    }
}
