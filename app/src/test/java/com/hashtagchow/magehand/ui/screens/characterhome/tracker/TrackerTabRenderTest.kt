package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.hashtagchow.magehand.ui.testing.MageHandTestSurface
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **FR-34 layer 1's render exemplar** — the pattern every future conversion copies
 * (docs/design/19-ui-test-infrastructure.md decision 4).
 *
 * ### The seam
 *
 * Decision 2: *"the seam is UiState → composable"*. `TrackerUiStateTest` owns board → UiState and
 * is exhaustive about it; everything below is the half that had no witness at all before this
 * wave — that the composable renders that state, speaks it, and responds to a finger. So the test
 * constructs a [Sabriel] `TrackerUiState` directly and calls `setContent`. No Hilt graph, no
 * ViewModel, no server, no emulator: `./gradlew test` runs it.
 *
 * ### The harness, and why each annotation is here
 *
 * - `RobolectricTestRunner` supplies the Android runtime — chiefly the real `Resources`, so the
 *   assertions below read the shipping `strings.xml` through the same `stringResource` call the
 *   app makes rather than through `ShippedStrings`' off-disk regex (decision 5 retires that
 *   helper progressively, as consumers are touched).
 * - `@Config(sdk = [34])` is the house convention `:core:data` established (WP3). Compose 1.12 and
 *   Roborazzi 1.73 both run on it; nothing here needed a newer platform.
 * - `qualifiers` puts the test on a **411 dp phone**, which is the width `WindowSizeGateTest` calls
 *   the one every layout in this app was designed against. Robolectric's default device is 320 dp
 *   — narrower than any phone the app supports — and asserting the tracker on it would be
 *   asserting a layout nobody has.
 * - `@GraphicsMode(NATIVE)` draws real pixels. Not strictly required to read the semantics tree,
 *   but it is what makes `assertIsDisplayed` mean *displayed* rather than *present*, and it is the
 *   same mode the goldens capture in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackerTabRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // ---- render + semantics -------------------------------------------------

    @Test
    fun `the board's rows, counts and pips are on screen`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        // HP first, because it is the row a player looks at before anything else. Two nodes, not
        // one: the big current and the smaller `/ max` are separately styled and separately
        // tagged, which is exactly the kind of detail a golden shows and a state test cannot.
        //
        // `useUnmergedTree` because FR-22's `directEntry` modifier merges the whole HP number into
        // one clickable node — which is the right thing for a screen reader (one focus stop that
        // says "Hit points, 17 of 17, tap to enter a number") and means the two child `Text`s only
        // exist as themselves in the unmerged tree.
        compose.onNodeWithTag("tracker:hp:current", useUnmergedTree = true).assertTextEquals("17")
        compose.onNodeWithTag("tracker:hp:max", useUnmergedTree = true).assertTextEquals(" / 17")

        // The count node is the WP6 numeric-parity probe's anchor: one string, "value / total",
        // under a `resource-id` the emulator dump can read. Asserting it here is what makes that
        // probe's contract checkable without a device.
        compose.onNodeWithTag("tracker:slot:${Sabriel.firstLevel.propertyId}")
            .assertTextEquals("3 / 4")

        // Four pips for a 4-total row, and the third is the last filled one — `PipRowState.usePips`
        // decides pips-vs-bar and `PipRow` decides which are filled, and only a composition can
        // show that the two agreed.
        compose.onNodeWithTag("tracker:slot:${Sabriel.firstLevel.propertyId}:pip:3").assertIsDisplayed()

        // FR-30 decision 17: a hit-dice row prints the composed label, not its source's raw name.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:hitdie:${Sabriel.hitDice.propertyId}"))
        compose.onNodeWithText("Hit Dice d6").assertIsDisplayed()
    }

    @Test
    fun `the concentration banner names what is being held`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker(concentratingOn = "Bless")) }

        compose.onNodeWithText("Bless", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an offline board states why the controls are dead`() {
        // `canWrite = false` is 06's rule that writes require LIVE, made visible. The note is the
        // last item in the list, so getting to it is itself part of the assertion.
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker(canWrite = false)) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:offline-note"))
        compose.onNodeWithTag("tracker:offline-note").assertIsDisplayed()
    }

    // ---- the TalkBack sentence (the E/P5 item class) ------------------------

    /**
     * FR-20 decision 2's spoken row name, asserted on the **merged** semantics tree.
     *
     * `PipRowState.spokenLabel` is a pure string and `TrackerUiStateTest` pins its composition. What
     * that test cannot show — and what this one does — is that the string reaches the accessibility
     * tree: `PipRow` sets it as a `contentDescription` on the *name* node, and `ResetBadge` clears
     * its own semantics so the fact is not then read a second time as a bare fragment. Both halves
     * are invisible to a JVM test and both are one careless modifier from breaking.
     */
    @Test
    fun `a row speaks its name and its reset rule as one sentence`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNodeWithContentDescription("1st Level, restores on a long rest").assertIsDisplayed()

        // The badge under it is silent, so TalkBack does not say "long rest" twice. Its *visible*
        // text is still there — this asserts the accessibility tree, not the pixels.
        compose.onNodeWithContentDescription("Long rest").assertDoesNotExist()
    }

    @Test
    fun `a pip says which row it spends`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        // The pips are one control with two halves (04 §3: "tap pip = spend, tap empty pip =
        // restore"), and the only thing that tells a screen-reader user which half they are on is
        // this string flipping. The *counts* are the assertion: a 3-of-4 row must offer three
        // spends and one restore, so a row that drew every pip as filled — or that spoke the same
        // sentence on all four — fails here rather than looking plausible.
        compose.onAllNodesWithContentDescription("Spend one 1st Level").assertCountEquals(3)
        compose.onAllNodesWithContentDescription("Restore one 1st Level").assertCountEquals(1)
    }

    // ---- collapse / expand (the FR-16 item class) ---------------------------

    @Test
    fun `the inactive-conditions drawer opens and closes on its header`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:conditions:inactive"))

        // Shut: the chips are not composed at all, which is what "absent, not merely invisible"
        // means and what the golden of this state shows.
        compose.onNodeWithText("Incapacitated").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show 2 inactive conditions").assertIsDisplayed()

        compose.onNodeWithTag("tracker:conditions:inactive").performClick()

        compose.onNodeWithText("Incapacitated").assertIsDisplayed()
        // The spoken label flips with the state — "2 inactive" alone never says it can be opened,
        // let alone which way it is currently set.
        compose.onNodeWithContentDescription("Hide inactive conditions").assertIsDisplayed()

        compose.onNodeWithTag("tracker:conditions:inactive").performClick()
        compose.onNodeWithText("Incapacitated").assertDoesNotExist()
    }

    // ---- rememberSaveable survival (the E2/L10/Q4-rotation item class) ------

    /**
     * The drawer stays open across an Activity recreation.
     *
     * `InactiveConditions` holds `expanded` in a `rememberSaveable` with a stated reason —
     * *"`rememberSaveable` so a rotation mid-combat does not slam the drawer shut"* — and until
     * this wave that reason was checkable only by rotating a phone by hand.
     * `StartDestinationNavigationTest`'s KDoc says so in as many words: *"the device is the proof
     * of restoration, and it is on the sweep as L10"*. [StateRestorationTester] saves the state
     * holders, throws the composition away and rebuilds it from the saved `Bundle`, which is the
     * same mechanism a rotation uses — so the sweep item now has a JVM witness.
     */
    @Test
    fun `the opened drawer survives an activity recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MageHandTestSurface { TrackerTab(state = Sabriel.tracker()) } }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:conditions:inactive"))
        compose.onNodeWithTag("tracker:conditions:inactive").performClick()
        compose.onNodeWithText("Incapacitated").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:conditions:inactive"))
        compose.onNodeWithText("Incapacitated").assertIsDisplayed()
    }
}
