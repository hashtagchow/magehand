package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertHasNoClickAction
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
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.ui.testing.MageHandTestSurface
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
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
        // FR-51 adds the modifier to that same label — the fixture carries the capture's own
        // `constitutionMod: 1`, so the shipped board reads "Hit Dice d6 + 1".
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:hitdie:${Sabriel.hitDice.propertyId}"))
        compose.onNodeWithText("Hit Dice d6 + 1").assertIsDisplayed()
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

    // ---- FR-44: limited-use abilities ---------------------------------------

    /**
     * The section renders under its own header, with the sheet's own name on the row.
     *
     * Built by `copy` onto the shared fixture rather than added to `Sabriel.tracker()` itself, on
     * purpose: that fixture is what the six committed `TrackerScreen_*.png` goldens photograph,
     * and R5 fences this wave to at most one golden moving. The composition under test is
     * otherwise identical to the one the goldens capture.
     */
    @Test
    fun `a limited-use row renders under its own header with the sheet's name`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(limitedUses = listOf(guidingBolt))) }

        // `SectionHeader` upper-cases its copy, so the assertion is on what the screen says
        // rather than on what `strings.xml` stores — and `assertExists`, because scrolling the row
        // into view is what pushes its own header off the top.
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("tracker:limiteduse:${guidingBolt.propertyId}"))

        compose.onNodeWithText("LIMITED USE").assertExists()
        compose.onNodeWithTag("tracker:limiteduse:${guidingBolt.propertyId}").assertTextEquals("1 / 2")
    }

    /**
     * The row speaks as one sentence and its pips say which half they are, exactly as a slot's do.
     *
     * The point is that nothing about the *row* is new — the write underneath it is a different
     * DDP method entirely, and a player must not be able to tell. A 1-of-2 row offers one spend
     * and one restore; a row that drew both pips as filled would look plausible and fail here.
     */
    @Test
    fun `a limited-use row speaks its reset rule and its pips`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(limitedUses = listOf(guidingBolt))) }

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("tracker:limiteduse:${guidingBolt.propertyId}"))

        compose.onNodeWithContentDescription("Guiding Bolt (Star Map), restores on a long rest")
            .assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Spend one Guiding Bolt (Star Map)").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Restore one Guiding Bolt (Star Map)").assertCountEquals(1)
    }

    /**
     * An empty list means **no header**, not an empty section — which is the shipped fixture's own
     * case (a character with no limited-use ability) and, by the same code path, what R3's switch
     * produces when it is off, because the gate empties the list in `toTrackerUiState` rather than
     * hiding anything here. This test exercises the first of those; `TrackerUiStateTest` owns the
     * switch itself, at both values.
     */
    @Test
    fun `no limited-use rows means no header`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNodeWithText("LIMITED USE").assertDoesNotExist()
    }

    /** FR-44 R1's row, 1 of 2 uses left on a long rest — the party sheet's headline case. */
    private val guidingBolt = PipRowState(
        propertyId = "lu-guiding-bolt",
        label = "Guiding Bolt (Star Map)",
        reset = ResetRule.LONG_REST,
        value = 1,
        total = 2,
        pinned = false,
        kind = TrackerKind.LIMITED_USE,
    )

    // ---- FR-50: armour class on the HP block --------------------------------

    /**
     * The number is drawn, and it is **spoken in words**.
     *
     * `TrackerUiStateTest` pins that the board's AC reaches `HpState`; what only a composition can
     * show is that the badge then draws it and that the sentence reaches the accessibility tree.
     * "AC" is right in print — it is what every sheet at the table prints and the block has no
     * room for two words — and wrong read aloud, where it is two letters rather than a fact. Both
     * strings are asserted because the split between them is the decision.
     */
    @Test
    fun `the hp block draws the armor class and speaks it in words`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker(armorClass = 14)) }

        compose.onNodeWithTag("tracker:hp:ac", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("AC 14").assertIsDisplayed()
        compose.onNodeWithContentDescription("Armor class 14", substring = true).assertIsDisplayed()
    }

    /**
     * **R3's no-click assertion.** The badge is information, not a control.
     *
     * 18 decision 23 — the "0 HP?" chip's reasoning: a number a player reads is not a number a
     * player taps, and a tap target here would invite a gesture with nothing behind it (AC is a
     * server computation with no mutator at all). The node carries no click action of its own,
     * and it is not a focus stop of its own either: it sits inside the HP pad's merged node, so
     * TalkBack reaches it as part of the block's own sentence rather than as one more swipe.
     *
     * `useUnmergedTree` for both reasons at once — the tag and the semantics are on the badge
     * itself, and in the merged tree they belong to the pad.
     */
    @Test
    fun `the armor class is not a tap target`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker(armorClass = 14)) }

        compose.onNodeWithTag("tracker:hp:ac", useUnmergedTree = true).assertHasNoClickAction()
    }

    /**
     * Absent AC draws **nothing** — not a placeholder, not an empty row, not a zero.
     *
     * R3: the block is pixel-identical to a build without the feature. A test cannot assert
     * "pixel-identical" (the goldens do that), but it can assert the composable is not there at
     * all, which is the structural half and the one that would fail first.
     */
    @Test
    fun `a character with no armor class draws no badge`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNodeWithTag("tracker:hp:ac", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("AC", substring = true).assertDoesNotExist()
    }

    // ---- FR-51: the hit-die modifier ----------------------------------------

    /**
     * *"Hit Dice d6 + 1"* — the composed label, and the spoken form that says "plus".
     *
     * The two strings differ on purpose and that is the whole of R3's *"never a bare glyph"*:
     * TalkBack reads U+2212 as nothing at all, so a screen reader on the visible label would
     * announce *"Hit Dice d6 1"* — a different number, and a wrong one.
     */
    @Test
    fun `a hit-dice row prints and speaks its modifier`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("tracker:hitdie:${Sabriel.hitDice.propertyId}"))

        compose.onNodeWithText("Hit Dice d6 + 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hit Dice d6 plus 1").assertIsDisplayed()
    }

    /**
     * `+ 3` — the ordinary case, and the one the FR was written for.
     *
     * Three tests rather than a loop, because a Compose rule may `setContent` exactly once and
     * the thing under test *is* a composition. The three are the three forms R3 names.
     */
    @Test
    fun `a positive modifier prints with a plus`() = assertModifier(
        modifier = 3,
        visible = "Hit Dice d8 + 3",
        spoken = "Hit Dice d8 plus 3",
    )

    /**
     * **`+ 0` prints** (18 decision 25) — the case a reasonable implementation suppresses.
     *
     * Suppressing it sends the player to the abilities list for a number the app already holds,
     * which is the errand this FR exists to remove. The reset badge's "no *Never*" rule describes
     * an absence and does not reach a value; `PipRowState.dieModifier` carries the argument.
     */
    @Test
    fun `a zero modifier prints rather than being suppressed`() = assertModifier(
        modifier = 0,
        visible = "Hit Dice d8 + 0",
        spoken = "Hit Dice d8 plus 0",
    )

    /**
     * `− 1` with a **U+2212**, written here as the escape.
     *
     * Deliberately the escape and not the character: a hyphen and a minus sign are one pixel
     * apart in a diff and worlds apart on a line beside a die size, where a hyphen reads as a
     * range. This assertion cannot pass against the wrong one; an eye reviewing the source can.
     */
    @Test
    fun `a negative modifier prints a true minus sign`() = assertModifier(
        modifier = -1,
        visible = "Hit Dice d8 \u2212 1",
        spoken = "Hit Dice d8 minus 1",
    )

    /**
     * **No modifier means today's label**, unchanged — the app never invents a `+ 0`.
     *
     * Decision 25's other half, and the reason `PipRowState.dieModifier` is an `Int?` rather than
     * an `Int`: this row and the `+ 0` row above are different facts and must read differently.
     */
    @Test
    fun `a hit-dice row with no modifier keeps the label FR-30 shipped`() = assertModifier(
        modifier = null,
        visible = "Hit Dice d8",
        spoken = "Hit Dice d8",
    )

    /**
     * **MEDIUM-1**: every spoken string on a `− 1` row says "minus", and none of them says the
     * glyph.
     *
     * The name node was right from the first cut; the *count* node and the *pips* were not — they
     * were handed the visible label, so the row announced itself correctly and then said
     * "Hit Dice d8 − 1, 3 of 5, tap to enter a number" one swipe later, which TalkBack reads as
     * "Hit Dice d8 1". A different and wrong number, in the same composable, from the ruling the
     * wave itself wrote the fix for.
     *
     * All three nodes are asserted in one test because the defect is precisely that they can
     * disagree: pinning the name alone is what let this ship.
     */
    @Test
    fun `every spoken string on a negative-modifier row says minus, never the glyph`() {
        val row = Sabriel.hitDice.copy(dieSize = "d8", dieModifier = -1, value = 3, total = 5)
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(hitDice = listOf(row))) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:hitdie:${row.propertyId}"))

        // 1 — the name node.
        compose.onNodeWithContentDescription("Hit Dice d8 minus 1").assertIsDisplayed()
        // 2 — the count node's direct-entry sentence.
        compose.onNodeWithContentDescription("Hit Dice d8 minus 1, 3 of 5, tap to enter a number")
            .assertIsDisplayed()
        // 3 — the pips, both halves. Three filled of five, so three spends and two restores.
        compose.onAllNodesWithContentDescription("Spend one Hit Dice d8 minus 1").assertCountEquals(3)
        compose.onAllNodesWithContentDescription("Restore one Hit Dice d8 minus 1").assertCountEquals(2)

        // And nothing anywhere speaks the glyph. `\u2212` is written as the escape so this cannot
        // pass against a hyphen that looks the same in a diff.
        compose.onAllNodesWithContentDescription("Hit Dice d8 \u2212 1", substring = true)
            .assertCountEquals(0)
    }

    /**
     * The FR-20 reset clause is spoken **once**, on the name node, and not repeated by the count.
     *
     * This is the constraint that stopped MEDIUM-1's fix from being "hand `spoken` to both nodes":
     * `spokenLabel` appends "restores on a long rest", and a count node carrying it would have
     * TalkBack say the rule twice on every slot and resource row on the screen — the duplication
     * `ResetBadge`'s `clearAndSetSemantics` already exists to prevent one line lower.
     */
    @Test
    fun `the count node speaks the row's name without repeating its reset rule`() {
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker()) }

        compose.onNodeWithContentDescription("1st Level, restores on a long rest").assertIsDisplayed()
        compose.onNodeWithContentDescription("1st Level, 3 of 4, tap to enter a number").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("restores on a long rest, 3 of 4", substring = true)
            .assertCountEquals(0)
    }

    /**
     * **LOW-7** — the **bar row's** steppers say "minus" too, and now something checks it.
     *
     * Above [PipRowState.MAX_PIPS] (8) a row stops drawing pips and draws a bar with a pair of
     * steppers instead, and those steppers speak the row's name through the same
     * `tracker_spend_one` / `tracker_restore_one` strings the pips do. MEDIUM-1's fix reached
     * them; nothing held them there. Putting `label` back on both `contentDescription`s left the
     * **entire** `:app` suite green, because no test in it had ever composed a hit-dice row with
     * `total >= 9` — so the one branch of the four that the fix's own comment singles out as
     * reachable ("a 9th-level multiclass character") was the one branch with no witness.
     *
     * A 9-die row is the whole fixture: same negative modifier as the pip case above, one more
     * die. The stepper tag is asserted first, because if `MAX_PIPS` ever grows this test would
     * otherwise quietly go back to testing the pip branch a sibling already covers and stop
     * covering this one at all.
     */
    @Test
    fun `the bar row's steppers say minus on a negative-modifier row above MAX_PIPS`() {
        val row = Sabriel.hitDice.copy(dieSize = "d8", dieModifier = -1, value = 6, total = 9)
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(hitDice = listOf(row))) }

        val tag = "tracker:hitdie:${row.propertyId}"
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))

        // This is the bar row and not the pip row — 9 > MAX_PIPS. If this fails, the rest of the
        // test is asserting the wrong branch.
        compose.onNodeWithTag("$tag:minus").assertIsDisplayed()
        compose.onNodeWithTag("$tag:plus").assertIsDisplayed()

        compose.onNodeWithContentDescription("Spend one Hit Dice d8 minus 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Restore one Hit Dice d8 minus 1").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Hit Dice d8 \u2212 1", substring = true)
            .assertCountEquals(0)
    }

    /** One composition, one hit-dice row, one pair of assertions. See the four callers. */
    private fun assertModifier(modifier: Int?, visible: String, spoken: String) {
        val row = Sabriel.hitDice.copy(dieSize = "d8", dieModifier = modifier)
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(hitDice = listOf(row))) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:hitdie:${row.propertyId}"))

        compose.onNodeWithText(visible).assertIsDisplayed()
        compose.onNodeWithContentDescription(spoken).assertIsDisplayed()
    }

    // ---- FR-51 R4: the 1.15.0 sweep's long-name LOW -------------------------

    /**
     * **A long row name never touches its count**, which is the whole of the sweep's LOW.
     *
     * The name is `weight(1f)` with an end ellipsis, so before this change it took *all* the
     * remaining width and the count began at the pixel the ellipsis ended:
     * "Guiding Bolt (Star Ma…3 / 4", with nothing to tell the eye where the name stopped. The fix
     * is a fixed arrangement gap, and the fix is only correct because `weight` measures **after**
     * the arrangement's spacing — a `Spacer` between the two would have been measured after the
     * weighted child had already claimed everything, and changed nothing at all.
     *
     * So the assertion is on the **geometry**, not on the text: the name's right edge and the
     * count's left edge, with the gap between them. A golden shows this to an eye; this fails a
     * build. The name is deliberately long enough to be truncated at 411 dp, because a gap that
     * only exists when the name is short is the bug.
     */
    @Test
    fun `a long row name keeps a gap before its count`() {
        val long = PipRowState(
            propertyId = "res-long",
            label = "Channel Divinity: Preserve Life (Circle of the Stars)",
            reset = ResetRule.LONG_REST,
            value = 3,
            total = 4,
            pinned = false,
            kind = TrackerKind.RESOURCE,
        )
        compose.setMageHandContent { TrackerTab(state = Sabriel.tracker().copy(resources = listOf(long))) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tracker:resource:${long.propertyId}"))

        // The count is intact — the gap must come out of the NAME's budget, never the count's.
        compose.onNodeWithTag("tracker:resource:${long.propertyId}").assertTextEquals("3 / 4")

        val name = compose.onNodeWithContentDescription(long.spokenLabel).getUnclippedBoundsInRoot()
        val count = compose.onNodeWithTag("tracker:resource:${long.propertyId}").getUnclippedBoundsInRoot()

        assertTrue(
            "the name must be truncated for this to be testing anything: name ended at ${name.right}",
            name.right < count.left,
        )
        assertTrue(
            "a long name ran into its count — the 1.15.0 sweep LOW, back. Gap was " +
                "${count.left - name.right}, expected at least 8.dp.",
            count.left - name.right >= 8.dp,
        )
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
