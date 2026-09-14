package com.hashtagchow.magehand.ui.screens.dmview

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.BuffChipState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.setMageHandContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-19's card, **rendered** — the behavioural half of `DmViewUiStateTest`'s structural claim
 * (docs/design/19-ui-test-infrastructure.md decision 5: *"DmViewUiStateTest's structural assertion
 * → behavioural render"*).
 *
 * ### The claim, and why it is worth a composition
 *
 * 14 decision 14: write controls appear only on cards where `owner == me || writers.contains(me)`,
 * and decision 18 takes them away again when the server refuses. All four conditions are resolved
 * at the state layer into one boolean — `dmCardShowsWriteControls` — and `DmViewUiStateTest` pins
 * every one of them. What that test could only assert by *reading `DmCard.kt`* is that the
 * composable asks that one question and re-derives none of it.
 *
 * A render says it directly and says it better: a read-only card is checked for the **absence of
 * every control**, which is what "absent, not disabled" means and is the property a source scan
 * approximates by looking for an `if`. The stakes are the reason it earns a real composition —
 * this is the one screen in the app that can write to five other people's sheets.
 *
 * `DmViewUiStateTest` keeps the rest of its scans. They are absence claims about *other files*
 * (the view model must reach no store; no dmview file may read the width gate), which no
 * composition can witness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DmCardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(
        showsWriteControls: Boolean,
        writeControlsEnabled: Boolean = showsWriteControls,
        permissionDenied: Boolean = false,
        /**
         * Defaulted to the state every pre-Q15 test in this file was written against, so those
         * tests read exactly as they did — the availability dimension is Q15's and nothing else's.
         */
        availability: DmCardAvailability = DmCardAvailability.AVAILABLE,
        /**
         * FR-50 R5. Absent by default for [Sabriel.tracker]'s reason — every test written before
         * this wave asserts the card it was written against, and the `DmCard.png` golden shows
         * the badge because its own fixture supplies one.
         */
        armorClass: Int? = null,
        /** FR-53 R5. Empty by default, for [armorClass]'s reason: every earlier test is unmoved. */
        buffs: List<BuffChipState> = emptyList(),
    ) = DmCardUiState(
        creatureId = Sabriel.CREATURE_ID,
        name = "Sabriel",
        monogram = "S",
        availability = availability,
        hp = HpState(propertyId = "hp", current = 11, max = 17, tempHp = 0, armorClass = armorClass),
        slots = listOf(Sabriel.firstLevel),
        buffs = buffs,
        showsWriteControls = showsWriteControls,
        writeControlsEnabled = writeControlsEnabled,
        permissionDenied = permissionDenied,
        grantedEditing = true,
    )

    @Test
    fun `a read-only card draws the facts and not one control`() {
        compose.setMageHandContent { dmCard(card(showsWriteControls = false)) }

        // The read half is all there…
        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}").assertIsDisplayed()

        // …and the write half is *absent*, not merely disabled. A disabled stepper on somebody
        // else's sheet invites the tap; a missing one cannot be tapped at all.
        //
        // The four controls are found by their spoken labels rather than by test tag, and that is
        // not a convenience: the card's root is a clickable `Card`, so it merges its descendants,
        // and `dm:hp:<id>` is a tag on a plain `Row` *inside* that merge — invisible in the merged
        // tree by construction. The controls survive the merge because each is itself clickable,
        // which is the same reason `spokenLabel`'s KDoc gives for keeping them outside the card's
        // one spoken sentence. Asserting on what a screen reader can reach is asserting on what a
        // finger can reach.
        listOf("Take damage", "Heal", "Spend one 1st Level", "Restore one 1st Level").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
    }

    @Test
    fun `an editable card draws the controls, and they reach the right intents`() {
        val spends = mutableListOf<String>()
        val hpDeltas = mutableListOf<Int>()

        compose.setMageHandContent {
            dmCard(
                card = card(showsWriteControls = true),
                onSpend = { spends += it },
                onChangeHitPoints = { hpDeltas += it },
            )
        }

        listOf("Take damage", "Heal", "Spend one 1st Level", "Restore one 1st Level").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed()
        }

        compose.onNodeWithContentDescription("Spend one 1st Level").performClick()
        compose.onNodeWithContentDescription("Take damage").performClick()

        assertEquals(listOf(Sabriel.firstLevel.propertyId), spends)
        assertEquals(listOf(-1), hpDeltas)
    }

    @Test
    fun `a refused card says why it went read-only`() {
        // Decision 18's second half. The server's refusal drops `showsWriteControls`, and without
        // this sentence the controls would simply have stopped being there — which reads as the
        // app losing them rather than as the server saying no.
        compose.setMageHandContent {
            dmCard(card(showsWriteControls = false, permissionDenied = true))
        }

        compose.onNodeWithContentDescription("Take damage").assertDoesNotExist()
        compose.onNodeWithText("The server refused that edit", substring = true).assertExists()
    }

    /**
     * **Q14**, converted — the card's one summary sentence, asserted **where a screen reader would
     * actually land on it** (docs/verification/FR-34-checklist-map.md, area Q).
     *
     * ### Why this is not `DmCardUiStateTest`'s job, and not `assertExists`
     *
     * `DmCardUiState.spokenLabel` is a pure function with its own exhaustive test: which fragments,
     * in what order, and which are dropped when absent. What that test cannot see is whether the
     * sentence it composes ever reaches a node TalkBack stops on — and this card is built out of
     * exactly the two ingredients that make that go wrong: a `clearAndSetSemantics` region holding
     * the words, inside a merging `Card` that also owns the tap.
     *
     * BUG-6 and the FR-35 review finding are the same class in two other files: a
     * `contentDescription` on a container nothing focuses, passing an `assertExists` for six
     * releases. So this asserts through the card's **merged node** — found by tag, checked for the
     * click action that makes it a focus stop, and only then read for its words.
     *
     * `assertContentDescriptionContains` and not `...Equals`: the full sentence is the pure
     * function's contract and is pinned there, while what this file is entitled to claim is that
     * the composed sentence arrives on a reachable node. Pinning the whole string here would
     * duplicate `DmCardUiStateTest` and break on every copy edit for no added guarantee.
     */
    @Test
    fun `the card's summary sentence lands on a node focus can reach`() {
        compose.setMageHandContent { dmCard(card(showsWriteControls = false)) }

        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertHasClickAction()
            .assertContentDescriptionContains("Sabriel, 11 of 17 hit points", substring = true)
    }

    /**
     * **Q14**'s second half: the merge must not swallow the controls.
     *
     * A card that spoke its summary *and* absorbed its steppers would be a card a screen-reader
     * user can hear and cannot operate — the trade `DmCardUiState.spokenLabel`'s KDoc names in
     * as many words. The read half is one sentence; each control is its own stop.
     *
     * The sibling test above already proves the sentence is on the card, and
     * `an editable card draws the controls…` already clicks the controls; what neither says on its
     * own is that **both** are true at once, which is the property the merge boundary exists for.
     */
    @Test
    fun `the summary merge leaves every write control separately reachable`() {
        compose.setMageHandContent { dmCard(card(showsWriteControls = true)) }

        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertContentDescriptionContains("Sabriel", substring = true)

        listOf("Take damage", "Heal", "Spend one 1st Level", "Restore one 1st Level").forEach {
            compose.onNodeWithContentDescription(it).assertHasClickAction()
        }
    }

    /**
     * **FR-50 R5**: the DM card draws the same badge the tracker's HP block does, beside its bar,
     * and folds the same sentence into the card's one spoken summary.
     *
     * ### Two claims, and the second is the one a state test cannot make
     *
     * That the number is *drawn* is the badge; that it is *heard* is the merge. The read half of
     * this card is `clearAndSetSemantics`, which silences every descendant — so the badge's own
     * `contentDescription` is dropped on the floor here, and the fragment has to come through
     * `DmCardUiState.spokenLabel` instead. That asymmetry with the tracker (where the badge speaks
     * for itself into the HP pad's merge) is invisible to both state tests and is exactly what
     * would break silently: the card would draw an AC that TalkBack never mentions.
     *
     * The badge is found through the unmerged tree because its tag lives inside the card's merge,
     * for the reason the read-only test above gives about `dm:hp:<id>`.
     */
    @Test
    fun `a card with an armor class draws the badge and speaks it in the summary`() {
        compose.setMageHandContent { dmCard(card(showsWriteControls = false, armorClass = 16)) }

        // `useUnmergedTree` for the text as well as the tag: the card's read half is
        // `clearAndSetSemantics`, so in the MERGED tree this card exposes one sentence and no
        // child text at all. That is the whole reason the spoken fragment has to travel through
        // `spokenLabel` — asserted on the next line.
        compose.onNodeWithTag("tracker:hp:ac", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("AC 16", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertContentDescriptionContains("Armor class 16", substring = true)
    }

    /** A sheet with no AC draws no badge and spends no clause on saying so. */
    @Test
    fun `a card with no armor class draws no badge`() {
        compose.setMageHandContent { dmCard(card(showsWriteControls = false)) }

        compose.onNodeWithTag("tracker:hp:ac", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("AC", substring = true, useUnmergedTree = true).assertDoesNotExist()
        // And the sentence goes straight from the hit points to the slots — an absent fact is a
        // dropped fragment, never "no armour class".
        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertContentDescriptionContains("11 of 17 hit points, 1 spell slot spent", substring = true)
    }

    /**
     * **Q15**, converted: a creature the subscription readied with nothing for renders an explicit
     * *"Not available"* card — never an empty tracker.
     *
     * ### Why the checklist called this un-exercisable and why it is not
     *
     * `docs/DEVICE-CHECKLIST.md` marks the row NOT EXERCISABLE because the admin test account can
     * see every creature on the server, so no live sheet ever reaches this state. That is a
     * limitation of the *account*, not of the state — decision 19's card is a `DmCardAvailability`
     * value, and a fixture carrying it sidesteps the account entirely. Same move the map records
     * for I3.
     *
     * ### What decision 19 actually promises
     *
     * *"The card says so in as many words and draws no tracker at all — never an HP bar, never an
     * empty pip row, and never a write control, **whatever the toggle says**."* So the fixture sets
     * `showsWriteControls = true` deliberately: the state layer should never produce that
     * combination, and the card must be safe if it ever does. A test that passed `false` here would
     * be asserting the toggle, not the availability.
     *
     * The spoken sentence is asserted in full, unlike the available card's, because this branch of
     * `spokenLabel` is a two-element list with nothing optional in it: a card the app cannot show
     * must not read as a healthy character with no problems.
     *
     * ### Why the words are asserted on the merged node and nowhere else
     *
     * The card's entire read half sits inside `spokenAs` — `clearAndSetSemantics` — so no text
     * inside it exists as a semantics node at all. `onNodeWithText("Not available")` can never
     * match, and `onNodeWithText("11/17").assertDoesNotExist()` would pass just as happily on a
     * card that DID draw the tracker. (The first authored draft of this test made both mistakes;
     * it had never run.) The one witness semantics offers is the merged sentence, and
     * exactly-equals is what makes it carry both halves at once: an available card's sentence
     * contains its HP fragment, so equality with the two-element sentence proves the words are
     * there *and* the tracker's facts are not. The pixel half — that nothing tracker-shaped is
     * drawn — is a picture, and the Q13 grid golden includes an unavailable card for exactly
     * that reason.
     */
    @Test
    fun `an unavailable card says so and draws no tracker, whatever the toggle says`() {
        compose.setMageHandContent {
            dmCard(card(showsWriteControls = true, availability = DmCardAvailability.NOT_AVAILABLE))
        }

        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertHasClickAction()
            .assertContentDescriptionEquals("Sabriel, Not available")

        // The write controls live OUTSIDE the cleared region — that is what keeps them reachable
        // on an available card — so their absence here is a meaningful assertion, and the
        // fixture's toggle is ON, which is the "whatever the toggle says" half.
        listOf("Take damage", "Heal", "Spend one 1st Level", "Restore one 1st Level").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
    }

    // ---- FR-53 R5: the card's buff chips ------------------------------------

    private val shield = BuffChipState("b1", "Shield", "+5 to AC")

    /**
     * A read-only card **names** the buff and offers no way to end it.
     *
     * The name is asserted through the card's **one spoken sentence** rather than through the
     * chip's test tag, and that is this file's standing rule rather than a workaround: the read
     * half of a card is `clearAndSetSemantics`, so every tag and description inside it is gone
     * from the tree by construction (the same reason `dm:hp:<id>` is unreachable). That sentence
     * is therefore all a screen-reader user gets from the read half — a chip row that draws two
     * shapes and speaks one of them would be BUG-6's defect on a new surface.
     *
     * The absence half is the capability rule: "you may not edit this character" is absence, not
     * a dimmed control. The ✕ is outside the cleared region when it exists (every write control
     * is), so its non-existence here is a meaningful assertion.
     */
    @Test
    fun `a read-only card names the buff and offers no cross`() {
        compose.setMageHandContent {
            dmCard(card(showsWriteControls = false, buffs = listOf(shield)))
        }

        compose.onNodeWithTag("dm:card:${Sabriel.CREATURE_ID}")
            .assertContentDescriptionContains("Shield", substring = true)
        compose.onNodeWithContentDescription("Turn off Shield").assertDoesNotExist()
    }

    /** With the gate open the ✕ is there, speaks, and fires the turn-off for this card. */
    @Test
    fun `a writable card's buff cross fires the turn-off`() {
        val turnedOff = mutableListOf<String>()
        compose.setMageHandContent {
            DmCard(
                card = card(showsWriteControls = true, buffs = listOf(shield)),
                onClick = {},
                onSpend = {},
                onRestore = {},
                onChangeHitPoints = {},
                onToggleCondition = {},
                onTurnOffBuff = { turnedOff += it },
            )
        }

        compose.onNodeWithContentDescription("Turn off Shield").assertIsDisplayed()
        compose.onNodeWithTag("dm:buff:${Sabriel.CREATURE_ID}:b1:off").performClick()
        assertEquals(listOf("b1"), turnedOff)
    }

    @Composable
    private fun dmCard(
        card: DmCardUiState,
        onSpend: (String) -> Unit = {},
        onChangeHitPoints: (Int) -> Unit = {},
    ) = DmCard(
        card = card,
        onClick = {},
        onSpend = onSpend,
        onRestore = {},
        onChangeHitPoints = onChangeHitPoints,
        onToggleCondition = {},
        onTurnOffBuff = {},
    )
}
