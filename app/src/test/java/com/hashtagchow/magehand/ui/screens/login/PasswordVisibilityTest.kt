package com.hashtagchow.magehand.ui.screens.login

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import com.hashtagchow.magehand.repoFile
import com.hashtagchow.magehand.ui.testing.FakeAccounts
import com.hashtagchow.magehand.ui.testing.MageHandTestSurface
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-33's password reveal toggle — **`PasswordVisibilityPostureTest`, upgraded**
 * (docs/design/19-ui-test-infrastructure.md decision 5: *"reveal password, simulate recreation via
 * StateRestorationTester, assert re-masked"*).
 *
 * ### What the posture test could say, and what this says instead
 *
 * The old file asserted three strings out of `CredentialsScreen.kt`: that the flag was declared
 * with `remember` and not `rememberSaveable`, and that both branches of the
 * `visualTransformation` were spelled. Every one of those is now a *behaviour* this test performs:
 * the field is typed into, the toggle is pressed, the pixels the field lays out are read back, and
 * the composition is destroyed and restored the way a rotation destroys and restores it. The
 * upgrade matters because a source scan passes on code that is spelled correctly and wired
 * wrongly — a toggle bound to the icon and not to the transformation reads exactly right.
 *
 * The one assertion that stayed a scan is the last one, and it is the class of claim design 19
 * decision 5 keeps: *no* dependency on `material-icons-extended` was added. That is about the
 * absence of something from the whole build, which no composition can witness.
 *
 * ### Reading what the field actually draws
 *
 * `assertTextEquals` reads the semantics tree, and a password field's semantics hold the **raw**
 * text — deliberately, because a screen reader on a focused field needs the characters. What the
 * user sees is the *transformed* text, and the only place that exists is the text layout the field
 * measured. [displayedText] pulls it out through `GetTextLayoutResult`, which is the same action
 * an accessibility service uses to ask "what is drawn here". Without it this test would assert
 * that the field holds the password — which is true whether or not it is masked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PasswordVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val secret = "correcthorse"

    /**
     * Never sent anywhere: [FakeAccounts.addAccount] throws, and nothing here submits the form.
     *
     * A field rather than a factory call inside [screen], because a view model minted on every
     * recomposition is a different object each frame — which would quietly reset the very state
     * the restoration test is watching.
     */
    private val viewModel = CredentialsViewModel(FakeAccounts())

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the field masks what is typed until the toggle is pressed`() {
        compose.setMageHandContent { screen() }

        compose.onNodeWithTag("credentials:password").performTextInput(secret)

        // Masked: same length, none of the characters.
        val masked = compose.onNodeWithTag("credentials:password").displayedText()
        assertEquals(secret.length, masked.length)
        assertNotEquals(secret, masked)
        assertFalse("the masked field must not draw the password", masked.contains("horse"))

        compose.onNodeWithTag("credentials:password:visibility").performClick()

        assertEquals(secret, compose.onNodeWithTag("credentials:password").displayedText())
    }

    @Test
    fun `the control announces what pressing it will do, in both states`() {
        // Two strings rather than one, because the label has to say what pressing the control
        // *does* and that flips with the state — a single "Password visibility" leaves a
        // screen-reader user unable to tell which way it is currently set. The visible label is
        // one word (it lives in a text field's trailing slot); this is the spoken one.
        compose.setMageHandContent { screen() }

        compose.onNodeWithTag("credentials:password:visibility")
            .assertContentDescriptionEquals("Show password")
            .performClick()

        compose.onNodeWithTag("credentials:password:visibility")
            .assertContentDescriptionEquals("Hide password")
    }

    /**
     * **The rule this feature is invisible when it breaks.**
     *
     * A revealed password must not survive process death, and neither must the *fact* that it was
     * revealed: saved-instance state is written into a `Bundle` the system persists and a bug
     * report can contain. The old test asserted this by checking that the declaration read
     * `remember` and not `rememberSaveable`; [StateRestorationTester] asserts it by saving the
     * state holders, discarding the composition and rebuilding it from that same `Bundle` — which
     * is what a rotation does — and finding the screen masked again.
     *
     * The password itself goes with it, and that is the safe direction: the worst case is a player
     * re-typing, and the alternative is a screen that comes back from the background with
     * somebody's password on it in a coffee shop.
     */
    @Test
    fun `a revealed password does not survive an activity recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MageHandTestSurface { screen() } }

        compose.onNodeWithTag("credentials:password").performTextInput(secret)
        compose.onNodeWithTag("credentials:password:visibility").performClick()
        assertEquals(secret, compose.onNodeWithTag("credentials:password").displayedText())

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("credentials:password:visibility")
            .assertContentDescriptionEquals("Show password")
        assertEquals(
            "the restored screen must hold no password at all — neither the characters nor the " +
                "fact that they were on screen may reach the saved-instance Bundle",
            "",
            compose.onNodeWithTag("credentials:password").displayedText(),
        )
    }

    /**
     * No icon dependency was added for this.
     *
     * `Visibility`/`VisibilityOff` live in `material-icons-extended`, which this app deliberately
     * does not depend on — `libs.versions.toml` pins `material-icons-core` with that reason
     * written beside it, and the extended set is tens of megabytes of vectors. A one-word text
     * affordance says the same thing for nothing, and this assertion is what stops the next wave
     * from reaching for the glyph without seeing the note.
     *
     * Kept as a build-file scan when everything else in this file became behavioural: it is a
     * claim about the *absence* of a dependency from the whole project, and no composition can
     * witness an absence.
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

    @Composable
    private fun screen() = CredentialsScreen(
        onSignedIn = {},
        onContinueWithoutAccount = {},
        viewModel = viewModel,
    )

    /**
     * What the field *draws*, as opposed to what it holds — see the class KDoc.
     *
     * `GetTextLayoutResult` is a semantics action rather than a property, so it is invoked with a
     * list to fill. One entry, always: the node is a single text field.
     */
    private fun SemanticsNodeInteraction.displayedText(): String {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "the password field exposes no text layout to read"
        }
        action(results)
        return results.single().layoutInput.text.text
    }
}
