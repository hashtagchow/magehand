package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.code
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.mainSourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FR-19's screen state, plus the structural claims composition alone would swallow
 * (docs/design/14-large-screen-arc.md decisions 12, 14, 16 and 18).
 *
 * ### Why some of this is read out of the source
 *
 * Some claims are about the *absence* of something from a file, or about *where* a call sits in a
 * Hilt-wired screen. Neither can be witnessed by rendering anything, so they are read out of the
 * source — `UiScaleProviderTest` set the precedent and `PaneSelectionTest` followed it. This file
 * uses it for the claims of FR-19 that live only in the code and whose failure is silent:
 *
 *  1. the editing toggle is **not** persisted anywhere,
 *  2. the entry reads the width gate, once, on the character list,
 *  3. no file in this feature reads the width gate a second time.
 *
 * The claim that used to be second on that list — *"the write controls are rendered under
 * `showsWriteControls` and nothing else"* — is a claim about a composition, and FR-34 turned it
 * into one: `DmCardRenderTest` renders a read-only card and checks that not one control exists on
 * it. That is a stronger statement than any scan for an `if`, on the one screen in this app that
 * can write to five other people's sheets.
 */
class DmViewUiStateTest {

    private fun card(
        id: String,
        granted: Boolean = true,
        editing: Boolean = false,
        denied: Boolean = false,
        available: Boolean = true,
    ) = DmCardUiState(
        creatureId = id,
        name = id,
        availability = if (available) {
            DmCardAvailability.AVAILABLE
        } else {
            DmCardAvailability.NOT_AVAILABLE
        },
        showsWriteControls = dmCardShowsWriteControls(editing, granted, denied, available),
        permissionDenied = denied,
        grantedEditing = granted,
    )

    // ---- the toggle and its banner (decision 14) ----------------------------

    @Test
    fun `the dashboard's default state is not editing`() {
        // Decision 14: "default OFF every time the view opens". The *default value of the state
        // class* is where that starts, before any store, any toggle and any navigation.
        assertFalse(DmViewUiState().editingEnabled)
        assertFalse(DmViewUiState().showsEditingBanner)
    }

    @Test
    fun `the banner is on exactly when editing is`() {
        // Decision 14's "unmistakable persistent banner while ON". Asserted as its own field
        // rather than left as "the toggle happens to be on", because the banner is the only thing
        // between a DM and an accidental write to somebody else's sheet — see the field's KDoc.
        assertTrue(DmViewUiState(editingEnabled = true).showsEditingBanner)
        assertFalse(DmViewUiState(editingEnabled = false).showsEditingBanner)
    }

    @Test
    fun `the toggle is absent when nothing on this table is editable`() {
        // Decision 18's capability gate, applied per table: a DM who is a reader on all six
        // sheets has nothing the toggle could switch on, and a control that provably does
        // nothing is worse than none. Decision 14 uses exactly this posture — "absent, not
        // present-and-broken" — for the server-refuses-everything case.
        assertFalse(DmViewUiState(canEditAnyCard = false).showsEditingToggle)
        assertTrue(DmViewUiState(canEditAnyCard = true).showsEditingToggle)
    }

    @Test
    fun `the toggle's label counts the cards it would affect, while it is still off`() {
        // The number is the whole meaning of the control: turning editing on with one editable
        // card and with five are very different acts, and a TalkBack user has no other way to
        // learn which.
        val state = DmViewUiState(
            cards = listOf(
                card("c1", granted = true),
                card("c2", granted = false),
                card("c3", granted = true, denied = true),
                card("c4", granted = true, available = false),
            ),
            canEditAnyCard = true,
        )

        // c1 only: c2 was never granted, c3 was refused by the server, c4 has nothing to edit.
        assertEquals(1, state.editableCardCount)
    }

    // ---- loading (decision 19's screen-level companion) ---------------------

    @Test
    fun `no cards is a screen-level wait, but a loading card is not`() {
        // The two must not be drawn the same way: a spinner over a grid that already has five
        // good cards on it would hide the table to report on one member.
        assertTrue(DmViewUiState().isLoading)
        assertFalse(
            DmViewUiState(cards = listOf(card("c1", available = false))).isLoading,
        )
    }

    // ---- the picker (decision 16) -------------------------------------------

    @Test
    fun `the picker seeded with a whole party says there is no room left`() {
        val party = (1..DM_VIEW_MAX_MEMBERS).map { CharacterSummary("c$it", "c$it") }
        val state = DmPickerState(candidates = party, selected = party.map { it.creatureId }.toSet())

        assertTrue(state.isFull)
        assertEquals(0, state.remaining)
        assertTrue(state.canConfirm)
    }

    @Test
    fun `remaining never goes negative`() {
        // The sheet prints this number. A stored table over the cap — hand-edited, or written by
        // a later build — would otherwise render "You can add -1 more".
        val over = (1..DM_VIEW_MAX_MEMBERS + 2).map { "c$it" }.toSet()

        assertEquals(0, DmPickerState(selected = over).remaining)
    }

    @Test
    fun `one ticked row cannot open a dashboard`() {
        assertFalse(DmPickerState(selected = setOf("c1")).canConfirm)
        assertTrue(DmPickerState(selected = setOf("c1", "c2")).canConfirm)
    }

    // ---- the structural claims ----------------------------------------------

    @Test
    fun `the editing toggle is not persisted anywhere`() {
        // Decision 14's *"per-session, NOT persisted"*, as a property of the code rather than of
        // this build's behaviour. The failure it prevents is the worst one this feature has: a
        // dashboard that opens *writable* onto five players' sheets because of something the DM
        // did three weeks ago.
        //
        // Read out of the source because a JVM test cannot prove the absence of a write by
        // running one — and because the tempting regression is a one-line "remember the toggle"
        // convenience that no behavioural test would notice.
        val viewModel = dmViewSourceFiles().single { it.name == "DmViewViewModel.kt" }.code()

        assertTrue(
            "the toggle must be a plain in-memory flow",
            viewModel.contains("private val editingEnabled = MutableStateFlow(false)"),
        )
        // `SavedStateHandle` is the one that would look most innocent: it survives process death
        // but not a fresh entry, so a reviewer could read it as "per-session" and be wrong about
        // the case that matters — an app killed in the background and restored with editing on.
        listOf("SavedStateHandle", "DataStore", "dataStore").forEach {
            assertFalse(
                "DmViewViewModel must not reach $it — the editing toggle would survive an entry",
                viewModel.contains(it),
            )
        }
        // It legitimately *reads* two stores — FR-6's `show_toggles` and the membership — and
        // writes neither. A write to either from here would be this screen persisting something,
        // which is the shape the rule is about.
        listOf("appSettingsStore.set", "dmViewStore.setMembers").forEach {
            assertFalse("the DM view must write no store; it only reads ($it)", viewModel.contains(it))
        }
    }

    /**
     * The card must not grow a **second** copy of the write gate.
     *
     * `DmCardRenderTest` proves the gate works — a read-only card renders no control at all — and
     * that is the assertion that used to live here as a source scan. What a render cannot say is
     * that the composable asks the state layer's *one* question rather than re-deriving any of the
     * four conditions behind it, because a card carrying its own second copy would render
     * identically today and drift from `dmCardShowsWriteControls` tomorrow. So the negative
     * survives the upgrade and the positive moved to the composition.
     */
    @Test
    fun `the card composable re-derives none of the write gate for itself`() {
        val source = dmViewSourceFiles().single { it.name == "DmCard.kt" }.code()

        // The availability clause is not a re-derivation: `availability` is decision 19's own
        // dimension, already folded into `dmCardShowsWriteControls` at the state layer — the
        // composable repeats it as the "whatever the toggle says" defense the Q15 render test
        // feeds the illegal combination to. The Q13 grid golden's first recording caught the
        // unguarded version drawing steppers on an unavailable card.
        assertTrue(
            "DmCard.kt no longer gates on availability + showsWriteControls",
            source.contains(
                "if (card.availability == DmCardAvailability.AVAILABLE && card.showsWriteControls) {",
            ),
        )
        // Exactly one call site, so there is no second unguarded path to the same controls.
        assertEquals(
            "CardWriteControls must be composed in exactly one place",
            1,
            Regex("CardWriteControls\\(\\s*\\n\\s*card =").findAll(source).count(),
        )
        listOf("grantedEditing &&", "editingEnabled", "isEditableByMe").forEach {
            assertFalse(
                "DmCard.kt must not re-derive the write gate ($it)",
                source.contains(it),
            )
        }
    }

    @Test
    fun `the entry reads the width gate exactly once, on the character list`() {
        // Decision 12: the entry is absent below EXPANDED. `LocalExpandedWidth` is a
        // `staticCompositionLocalOf` defaulting to false, so a screen that forgot to read it
        // renders the phone path — safe, but silently; a screen that read it *twice* would be the
        // beginning of two different width questions, which is what that local's KDoc argues
        // against. `PaneSelectionTest` makes the same assertion about the two home screens.
        val list = mainSourceFiles().single { it.name == "CharacterListScreen.kt" }.code()

        assertEquals(
            "CharacterListScreen must read LocalExpandedWidth exactly once",
            1,
            Regex("LocalExpandedWidth\\.current").findAll(list).count(),
        )
        // …and it must reach the entry through `canOfferDmView`, not through a bare `if`, so the
        // two halves of decision 12's rule stay in the one function that has a test.
        assertTrue(
            "the entry must be gated through canOfferDmView",
            list.contains("canOfferDmView("),
        )
    }

    @Test
    fun `the dashboard itself does not consult the width gate`() {
        // The gate belongs to the *entry* (decision 12: "on smaller widths the entry is absent").
        // A dashboard that also checked it would blank itself mid-session when a foldable closed
        // or a multi-window divider moved — with six subscriptions still open behind the blank.
        // Whether the layout copes with a narrow window is `dmGridColumns`' job, and it has one.
        dmViewSourceFiles().forEach { file ->
            assertFalse(
                "${file.name} must not read LocalExpandedWidth — see dmGridColumns",
                file.code().contains("LocalExpandedWidth"),
            )
        }
    }

    // --- source access -------------------------------------------------------

    /**
     * 06 step 2 reaches this screen too, and the wiring is the part that can go missing.
     *
     * `captureSnapshots()` shipped with a KDoc, a body and **no caller** — six live mirrors that
     * were never serialized, so a cold re-open of any character the DM had just been looking at
     * started on a spinner. Dead code with a good comment on it reads exactly like wired code;
     * only the absence of a caller distinguishes them, and nothing was looking for one.
     *
     * Asserted here rather than left to review because the same omission is invisible on a
     * device: nothing fails, the next cold start is merely slower than it should be.
     */
    @Test
    fun `the dashboard captures its snapshots when the app backgrounds`() {
        val screen = dmViewSourceFiles().single { it.name == "DmViewScreen.kt" }.code()

        assertTrue(
            "DmViewScreen must observe the lifecycle — 06 step 2 applies to every open mirror",
            screen.contains("LifecycleEventObserver"),
        )
        assertTrue(
            "the hook must fire on ON_STOP, the way CharacterHomeScreen's does",
            screen.contains("Lifecycle.Event.ON_STOP"),
        )
        assertTrue(
            "…and it must call captureSnapshots(), or the view model's function is dead again",
            screen.contains("viewModel.captureSnapshots()"),
        )
        assertTrue(
            "the observer must be removed on dispose, or the screen leaks into the lifecycle",
            screen.contains("removeObserver(observer)"),
        )
    }

    /**
     * The refusal collector is started **before** the session is published to the grid.
     *
     * `OpenCharacter.writeFailures` is hot and has no replay, so a refusal emitted between "this
     * card is on screen and tappable" and "somebody is collecting" is lost outright — and with
     * it both halves of decision 18's response: the server's own sentence, and the card dropping
     * to read-only. Ordering is the entire fix, which is also what makes it easy to swap back
     * while tidying, so it is pinned rather than argued.
     */
    @Test
    fun `refusals are watched before the card can be tapped`() {
        val viewModel = dmViewSourceFiles().single { it.name == "DmViewViewModel.kt" }.code()

        val watch = viewModel.indexOf("watchRefusals(creatureId, opened)")
        val publish = viewModel.indexOf("sessions.value = sessions.value + (creatureId to opened)")

        assertTrue("DmViewViewModel no longer watches refusals per session", watch >= 0)
        assertTrue("DmViewViewModel no longer publishes the opened session", publish >= 0)
        assertTrue(
            "watchRefusals must run before the session is published — writeFailures has no " +
                "replay, so a refusal in that gap is silence, which decision 18 forbids",
            watch < publish,
        )
    }

    /** This feature's own files. */
    private fun dmViewSourceFiles(): List<File> =
        mainSourceFiles().filter { it.path.contains("${File.separatorChar}dmview${File.separatorChar}") }

}
