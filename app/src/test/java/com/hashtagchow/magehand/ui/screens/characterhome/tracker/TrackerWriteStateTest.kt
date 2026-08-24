package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.declaredString
import com.hashtagchow.magehand.stringsXml
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import java.time.ZoneId

/**
 * The write half of the board → UI mapping, and the two derivations the tracker gets wrong
 * in ways a screenshot cannot show: **which rows a rest restores**, and **which single
 * history entry may be undone**.
 *
 * Pure, so it runs without a device, a Compose runtime or a clock — same reasoning as
 * `TrackerUiStateTest`, whose fixtures these deliberately mirror.
 */
class TrackerWriteStateTest {

    private val zone = ZoneId.of("Africa/Johannesburg")

    private val shortRestResource = TrackedResource(
        propertyId = "ki",
        kind = TrackerKind.RESOURCE,
        name = "Ki",
        value = 1,
        total = 4,
        reset = ResetRule.SHORT_REST,
    )
    private val longRestSlot = TrackedResource(
        propertyId = "slot1",
        kind = TrackerKind.SPELL_SLOT,
        name = "1st Level",
        value = 3,
        total = 4,
        reset = ResetRule.LONG_REST,
        spellSlotLevel = 1,
    )
    private val noResetResource = TrackedResource(
        propertyId = "charges",
        kind = TrackerKind.RESOURCE,
        name = "Wand charges",
        value = 2,
        total = 7,
        reset = null,
    )

    private fun state(
        canWrite: Boolean = true,
        canUndo: Boolean = false,
        history: List<TrackerWrite> = emptyList(),
        board: TrackerBoard = TrackerBoard(
            slots = listOf(longRestSlot),
            resources = listOf(shortRestResource, noResetResource),
        ),
        /** 09 decision 8. False is a local character — there is no server behind the rest. */
        hasConnection: Boolean = true,
    ) = toTrackerUiState(
        creatureId = "c1",
        board = board,
        connection = if (canWrite) ConnectionState.LIVE else ConnectionState.OFFLINE,
        lastSyncedAt = null,
        isShowingSnapshot = false,
        canWrite = canWrite,
        canUndo = canUndo,
        history = history,
        zone = zone,
        hasConnection = hasConnection,
    )

    private fun write(
        id: Long,
        kind: TrackerWriteKind = TrackerWriteKind.SPEND,
        name: String = "1st Level",
        amount: Int = 1,
        undoable: Boolean = true,
        undone: Boolean = false,
    ) = TrackerWrite(
        id = id,
        kind = kind,
        targetName = name,
        amount = amount,
        at = 1_755_463_920_000L,
        undoable = undoable,
        undone = undone,
    )

    // --- canWrite ---------------------------------------------------------------

    @Test
    fun `writes are only offered while the session is live`() {
        assertTrue(state(canWrite = true).canWrite)
        assertFalse(state(canWrite = false).canWrite)
    }

    @Test
    fun `undo is not offered while the session cannot write`() {
        // The inverse op is still on the stack; it just has nowhere to go. Showing an
        // enabled UNDO that the queue would refuse is the surprise 04 rules out.
        assertFalse(state(canWrite = false, canUndo = true, history = listOf(write(1))).canUndo)
        assertTrue(state(canWrite = true, canUndo = true, history = listOf(write(1))).canUndo)
    }

    // --- the history sheet ------------------------------------------------------

    @Test
    fun `only the newest reversible entry offers undo`() {
        val rows = state(
            history = listOf(write(3), write(2), write(1)),
        ).history

        assertEquals(listOf(true, false, false), rows.map { it.canUndo })
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.id })
    }

    @Test
    fun `an entry that is no longer reversible is skipped over, not silently dropped`() {
        // What a rest leaves behind: everything is still listed, nothing is undoable.
        val rows = state(
            history = listOf(
                write(3, kind = TrackerWriteKind.LONG_REST, undoable = false),
                write(2, undoable = false),
                write(1, undoable = false),
            ),
        ).history

        assertEquals(3, rows.size)
        assertTrue("a rest must leave nothing undoable", rows.none { it.canUndo })
    }

    @Test
    fun `an undone entry stays in the list and says so`() {
        val row = state(history = listOf(write(1, undoable = false, undone = true))).history.single()
        assertTrue(row.undone)
        assertFalse(row.canUndo)
    }

    @Test
    fun `no history entry is undoable while offline`() {
        assertTrue(state(canWrite = false, history = listOf(write(1))).history.none { it.canUndo })
    }

    @Test
    fun `history rows carry the wall-clock time in the injected zone`() {
        assertEquals("22:52", state(history = listOf(write(1))).history.single().at)
    }

    // --- what a rest will reset -------------------------------------------------

    @Test
    fun `a short rest lists only short-rest rows`() {
        val listed = state().rowsRestoredBy(RestKind.SHORT)
        assertEquals(listOf("Ki"), listed.map { it.label })
    }

    @Test
    fun `a long rest lists every row with a reset rule`() {
        val listed = state().rowsRestoredBy(RestKind.LONG)
        assertEquals(listOf("1st Level", "Ki"), listed.map { it.label })
    }

    @Test
    fun `a row with no reset rule is never listed`() {
        // A wand's charges recharge at dawn on their own terms; claiming a rest restores
        // them would be the dialog lying about what it is about to do.
        assertTrue(RestKind.entries.all { kind -> state().rowsRestoredBy(kind).none { it.label == "Wand charges" } })
    }

    @Test
    fun `rows already full are still listed, so the dialog is honest about the outcome`() {
        val full = longRestSlot.copy(value = 4)
        val listed = state(board = TrackerBoard(slots = listOf(full))).rowsRestoredBy(RestKind.LONG)
        assertEquals(1, listed.size)
        assertEquals(4, listed.single().value)
    }

    @Test
    fun `a server character's long rest still carries the hedged hit-points note`() {
        // Unchanged for the DiceCloud path: `creature.methods.rest` really does apply the
        // sheet's own HP and hit-dice rules on top of the rows listed above it.
        assertTrue(state().showsRestHpNote(RestKind.LONG))
    }

    @Test
    fun `a local character's long rest promises no hit points, because it restores none`() {
        // `LocalOpenCharacter.rest` is `current = total` on the qualifying rows and nothing
        // else — `currentHp` is untouched. Showing the note here would have the primary local
        // flow promise hit points that never arrive, in the one dialog whose whole job is to
        // say truthfully what the button does.
        assertFalse(state(hasConnection = false).showsRestHpNote(RestKind.LONG))
    }

    @Test
    fun `a short rest never carries the note, on either kind of character`() {
        // The note is specifically about what a *long* rest does beyond the listed rows.
        assertFalse(state().showsRestHpNote(RestKind.SHORT))
        assertFalse(state(hasConnection = false).showsRestHpNote(RestKind.SHORT))
    }

    // --- the concentration ✕ ----------------------------------------------------

    @Test
    fun `the concentration cross is live when the source is a flippable toggle`() {
        val board = TrackerBoard(
            activeToggles = listOf(
                ConditionToggle("t1", "Concentration: Bless", enabled = true, flippable = true),
            ),
            concentratingOn = "Concentration: Bless",
        )
        assertEquals("t1", state(board = board).concentrationToggleId)
    }

    @Test
    fun `the concentration cross is inert when the source is a computed toggle`() {
        // Shown on the chip row, but `flipToggle` would refuse it, so the ✕ must not offer.
        val board = TrackerBoard(
            activeToggles = listOf(
                ConditionToggle("t1", "Concentration: Bless", enabled = true, flippable = false),
            ),
            concentratingOn = "Concentration: Bless",
        )
        assertNull(state(board = board).concentrationToggleId)
    }

    @Test
    fun `the concentration cross is inert when the source is not on the chip row`() {
        // 03 §5 lets a `buff` drive the banner, and 02 says flipToggle rejects non-toggles.
        val board = TrackerBoard(concentratingOn = "Bless")
        assertNull(state(board = board).concentrationToggleId)
    }

    // --- the strings the snackbar and the sheet show ------------------------------

    @Test
    fun `a coalesced burst describes itself as one write of the summed amount`() {
        assertEquals("Spent 3 × 1st Level", write(1, amount = 3).describe())
    }

    @Test
    fun `every write kind has its own sentence`() {
        val described = TrackerWriteKind.entries.map { kind ->
            write(1, kind = kind, name = "Rage", amount = 2).describe()
        }
        assertEquals(
            "adding a write kind without giving it a sentence would show a stale one",
            TrackerWriteKind.entries.size,
            described.toSet().size,
        )
        assertTrue(described.none { it.isBlank() })
    }

    @Test
    fun `hp writes read as damage and healing, not as spending`() {
        assertEquals("Took 5 damage", write(1, kind = TrackerWriteKind.TAKE_DAMAGE, amount = 5).describe())
        assertEquals("Healed 5", write(1, kind = TrackerWriteKind.HEAL, amount = 5).describe())
    }

    @Test
    fun `a failure prefers the two cases with their own copy over the server's words`() {
        val offline = failure(refusedOffline = true, reason = "Method not allowed")
        val limited = failure(rateLimited = true, reason = "too many")
        assertEquals("Not saved — you're offline", offline.describe())
        assertEquals("Too fast — that one didn't save", limited.describe())
    }

    @Test
    fun `a failure otherwise quotes the server verbatim`() {
        assertEquals("Not saved: Value must be a number", failure(reason = "Value must be a number").describe())
        assertEquals("That didn't save", failure(reason = null).describe())
    }

    // --- FR-20 decision 4: the rest dialog's copy ---------------------------------

    /**
     * The heading over `rowsRestoredBy`'s list says **restored to full**.
     *
     * Asserted against the resource that ships rather than against a literal in the
     * composable, for `InventoryUiStateTest`'s reason: two literals agreeing proves only that
     * they were typed the same day, and `:app` has no Robolectric harness to resolve an id
     * through. The file is what a player reads.
     *
     * Why it matters enough to pin: `creature.methods.rest` clears the qualifying properties'
     * `damage`, so a listed row ends the rest **at its total** and can only move up. "This will
     * reset:" promised something else, and the 2026-08-21 Heroic Inspiration triage is the
     * recorded case of a player believing it.
     */
    @Test
    fun `the rest dialog says the listed rows are restored to full`() {
        assertEquals("Restored to full:", declaredString("tracker_rest_restored_to_full"))
        assertEquals(
            "Nothing on this tracker is restored by this rest.",
            declaredString("tracker_rest_nothing"),
        )
    }

    /**
     * The dialog's titles are the rest, not the button that opened it.
     *
     * `tracker_short_rest` / `tracker_long_rest` stay one word — they are the two side-by-side
     * buttons on the tracker and have to fit — so the dialog owns its own pair. Pinned because
     * the cheap fix is to reuse the button strings again, and "Short" as a heading over
     * "Restored to full:" is a fragment.
     */
    @Test
    fun `the rest dialog has its own titles, distinct from the two buttons`() {
        assertEquals("Short rest", declaredString("tracker_rest_title_short"))
        assertEquals("Long rest", declaredString("tracker_rest_title_long"))
        assertEquals("Short", declaredString("tracker_short_rest"))
        assertEquals("Long", declaredString("tracker_long_rest"))
    }

    /**
     * **No string this dialog shows may use the word "reset" again.**
     *
     * The verb, not one sentence, was the bug: it was in the heading, in the empty-state line
     * *and* in the hit-points note, and fixing two of three would leave the dialog contradicting
     * itself. Stated as a rule over every `tracker_rest_*` string so a fourth one added later
     * cannot quietly reintroduce it.
     */
    @Test
    fun `no string in the rest dialog claims a reset`() {
        val offenders = Regex("""<string name="(tracker_rest_\w+)">(.*?)</string>""")
            .findAll(stringsXml().readText())
            .filter { it.groupValues[2].contains("reset", ignoreCase = true) }
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "a rest restores toward the total; it does not reset — see FR-20 decision 4",
            emptyList<String>(),
            offenders,
        )
    }

    private fun failure(
        reason: String?,
        refusedOffline: Boolean = false,
        rateLimited: Boolean = false,
    ) = TrackerWriteFailure(
        id = 1,
        kind = TrackerWriteKind.SPEND,
        propertyId = "slot1",
        targetName = "1st Level",
        reason = reason,
        refusedOffline = refusedOffline,
        rateLimited = rateLimited,
    )
}
