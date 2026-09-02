package com.hashtagchow.magehand.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-38's control, **rendered** (14 addendum 3 as amended by the operator; FR-38 ruling 6).
 *
 * ### What a stepper needs proving that a chip row did not
 *
 * A row of chips is wrong in ways a picture catches: one missing, one clipped, the wrong one
 * highlighted. A stepper is wrong in ways a picture cannot catch at all, because its two buttons
 * look identical at every value and the thing that changes is *what they do*. Off-by-one in the
 * step arithmetic, a `−` that fires at the floor, a description still naming the destination it
 * had two taps ago — all of those photograph perfectly.
 *
 * So the assertions here are about behaviour and about the **spoken** sentence, and the spoken
 * sentence is checked against the real string resources rather than against literals invented in
 * this file. A test that asserted `"Smaller, to 90%"` as a hard-coded string would keep passing
 * after somebody changed the strings and would then be pinning a sentence the app no longer says.
 *
 * ### The ends
 *
 * `assertIsNotEnabled` rather than `assertDoesNotExist`, deliberately, and that is the operator's
 * amendment read literally: a disabled button is still focusable, still announces itself, and
 * still says why it will not move. A control whose node count changed with its value would make
 * a screen-reader user's map of the screen depend on the setting's state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsUiScaleRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val smaller get() = compose.onNodeWithTag("settings:ui-scale:smaller")
    private val larger get() = compose.onNodeWithTag("settings:ui-scale:larger")

    /** Every step this render's `onSelect` was handed, in order. */
    private val chosen = mutableListOf<UiScale>()

    private val selection = mutableStateOf(UiScale.DEFAULT)

    /**
     * One composition per test — `setContent` may only be called once on the rule, and the value
     * is state rather than a constant so a test can move it the way the store does.
     *
     * @param follow when true the control's own callback advances [selection], which is
     *   `MainViewModel` → DataStore → `collectAsStateWithLifecycle` collapsed to its observable
     *   behaviour. The stepper holds no state of its own, so walking the ladder requires it.
     */
    private fun render(selected: UiScale, follow: Boolean = false) {
        selection.value = selected
        compose.setMageHandContent {
            UiScaleSetting(
                selected = selection.value,
                onSelect = {
                    chosen += it
                    if (follow) selection.value = it
                },
            )
        }
    }

    /** The app's own words for a step, so no assertion below hard-codes a sentence. */
    private fun label(scale: UiScale) = context.getString(uiScaleLabel(scale))

    private fun towardsSmaller(destination: UiScale) =
        context.getString(R.string.settings_ui_scale_smaller, label(destination))

    private fun towardsLarger(destination: UiScale) =
        context.getString(R.string.settings_ui_scale_larger, label(destination))

    @Test
    fun `the value line shows the step it is given, and follows when that changes`() {
        render(UiScale.LARGE_110)
        compose.onNodeWithText("110%").assertIsDisplayed()

        // The same defect FR-6's switch avoided: a control holding its own value would show a
        // tap immediately and then disagree with the size the app is actually drawn at. Moving
        // the state under it and watching the line follow is what pins that it does not.
        compose.runOnIdle { selection.value = UiScale.DEFAULT }

        // `Default` is the one step whose label does not say how big it is, so the value line
        // says it: a user reading "Default" cannot tell whether they are above or below it.
        compose.onNodeWithText(context.getString(R.string.settings_ui_scale_default_value))
            .assertIsDisplayed()
    }

    @Test
    fun `both buttons are tappable and each names the step it would land on`() {
        render(UiScale.DEFAULT)

        // The destination, not the button — "Smaller, to 90%", never "Smaller". A stepper is the
        // control where those two come apart: the glyph never changes and what it does always
        // does, so only the destination tells a user who cannot see the value whether the tap is
        // worth making.
        smaller.assertHasClickAction().assertContentDescriptionEquals(towardsSmaller(UiScale.SMALL_90))
        larger.assertHasClickAction().assertContentDescriptionEquals(towardsLarger(UiScale.LARGE_110))
    }

    @Test
    fun `the spoken destination follows the current step rather than being fixed`() {
        // The defect this catches is a description computed once, or computed from the wrong
        // end: at 125% the neighbours are 110 and 150, and a stepper still saying "to 90%" would
        // be indistinguishable from a correct one in any golden.
        render(UiScale.LARGE_125)

        smaller.assertContentDescriptionEquals(towardsSmaller(UiScale.LARGE_110))
        larger.assertContentDescriptionEquals(towardsLarger(UiScale.LARGE_150))
    }

    @Test
    fun `the value node speaks the group and the step, not the typographic line`() {
        render(UiScale.DEFAULT)

        // "100% (Default)" is a *typographic* line; the brackets are punctuation a screen reader
        // either announces or swallows depending on verbosity, and neither is the sentence.
        // Focus landing here by swipe gets no heading, so the description carries the group's
        // own name with it.
        compose.onNodeWithText(context.getString(R.string.settings_ui_scale_default_value))
            .assertContentDescriptionEquals(
                context.getString(R.string.settings_ui_scale_value_description_default),
            )
    }

    @Test
    fun `a non-default value speaks its percentage under the group name`() {
        render(UiScale.SMALL_80)

        compose.onNodeWithText("80%").assertContentDescriptionEquals(
            context.getString(
                R.string.settings_ui_scale_value_description,
                label(UiScale.SMALL_80),
            ),
        )
    }

    @Test
    fun `the group name is never a description of its own, only part of the value's sentence`() {
        render(UiScale.DEFAULT)

        // BUG-6, as a **negative**. The chip row this replaced inherited a
        // `contentDescription = "UI size"` on its parent from the segmented row before it, which
        // was safe there only because that row does not merge its descendants. On a plain `Row`
        // the same call collapses the three focusable nodes into one announcement naming none of
        // them — so the assertion is that the group name exists *only* inside the value's
        // sentence, and never as a description in its own right.
        val groupName = context.getString(R.string.settings_ui_scale)

        assertEquals(
            "no node may carry the bare group name as its description — the heading names the " +
                "section, and the value node carries it as part of a sentence",
            0,
            compose.onAllNodesWithContentDescription(groupName).fetchSemanticsNodes().size,
        )

        // The heading still says it, as *text*, and — the half that actually makes the section
        // jumpable — carries `heading()` semantics. Asserting only the text would pass on a
        // plain `Text`, and a screen-reader user navigating by heading would then walk every
        // control in Settings to reach this one.
        compose.onNodeWithText(groupName)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

        // And the sentence that does carry it is on the value, not on anything above it.
        compose.onNodeWithText(context.getString(R.string.settings_ui_scale_default_value))
            .assertContentDescriptionEquals(
                context.getString(R.string.settings_ui_scale_value_description_default),
            )
    }

    @Test
    fun `the three nodes are laid out minus, value, plus, left to right`() {
        render(UiScale.DEFAULT)

        // Order is not cosmetic here. TalkBack traverses in layout order, so this *is* the
        // sentence a screen-reader user hears: "Smaller, to 90%" → "UI size, 100%, default" →
        // "Larger, to 110%". Swap the two buttons and every individual assertion in this file
        // still passes while the control reads backwards — the `−` announcing itself after the
        // value it no longer precedes. It is also the operator's amendment stated as geometry:
        // "Default centred, with buttons on either side".
        val minusLeft = smaller.fetchSemanticsNode().boundsInRoot.left
        val valueLeft = compose
            .onNodeWithText(context.getString(R.string.settings_ui_scale_default_value))
            .fetchSemanticsNode().boundsInRoot.left
        val plusLeft = larger.fetchSemanticsNode().boundsInRoot.left

        assertTrue(
            "the value must start to the right of the − button " +
                "(− at $minusLeft, value at $valueLeft)",
            minusLeft < valueLeft,
        )
        assertTrue(
            "the + button must start to the right of the value " +
                "(value at $valueLeft, + at $plusLeft)",
            valueLeft < plusLeft,
        )
    }

    @Test
    fun `plus and minus each move exactly one position from Default`() {
        render(UiScale.DEFAULT)

        larger.performClick()
        smaller.performClick()

        // One position per tap, in `UiScale.entries` order. Both directions in one test because
        // the failure worth catching is a stepper that moves by two, or that moves the same way
        // whichever button is tapped — neither shows up if only one direction is exercised.
        assertEquals(listOf(UiScale.LARGE_110, UiScale.SMALL_90), chosen)
    }

    @Test
    fun `at the floor the minus is disabled and the plus still works`() {
        render(UiScale.SMALL_70)

        // Present and focusable, and it says why it will not move. Removing it would change the
        // control's node count with its value.
        smaller
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(
                context.getString(R.string.settings_ui_scale_smaller_limit),
            )

        larger.assertIsEnabled().performClick()
        assertEquals(listOf(UiScale.SMALL_80), chosen)
    }

    @Test
    fun `at the ceiling the plus is disabled and the minus still works`() {
        render(UiScale.LARGE_150)

        larger
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(
                context.getString(R.string.settings_ui_scale_larger_limit),
            )

        smaller.assertIsEnabled().performClick()
        assertEquals(listOf(UiScale.LARGE_125), chosen)
    }

    @Test
    fun `stepping up walks the whole ladder and stops at the top`() {
        // The control follows its own callback here, so this is the ladder as a user climbs it.
        // `UiScaleProviderRenderTest` proves each factor renders; this proves the control can
        // actually *reach* each factor, which is the half that breaks silently if a button is
        // built from a fixed index rather than from the current entry.
        render(UiScale.entries.first(), follow = true)

        repeat(UiScale.entries.size - 1) { larger.performClick() }

        assertEquals(UiScale.entries.drop(1), chosen)

        // And it stops. One more tap at the ceiling must add nothing — a stepper that ran off
        // the end would either crash on the index or silently wrap back to the floor.
        larger.assertIsNotEnabled()
        assertEquals(UiScale.entries.size - 1, chosen.size)
    }

}
