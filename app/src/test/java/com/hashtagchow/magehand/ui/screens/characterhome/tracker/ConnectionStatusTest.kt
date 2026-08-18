package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import java.time.ZoneId

/**
 * The connection **presentation** rule, pinned: *quiet when healthy, quiet while a redial
 * is in flight, one dot otherwise*.
 *
 * This file exists because the rule is the feature. The old status strip could only be
 * got wrong by rendering the wrong words; the dot can be got wrong by existing at all —
 * a regression that put it back on screen during a healthy session, or flashed it over
 * the spinner on every cold open, would be a red mark that means nothing, and no compiler
 * catches that. So the mapping lives on [ConnectionStatus] / [TrackerUiState] (plain
 * data, no Compose, no device) and is asserted here rather than being trusted to a
 * composable that only an instrumented test could see.
 *
 * Every assertion below is over [ConnectionState.entries] rather than a hand-listed set,
 * so a fifth connection state cannot be added without this test failing until someone has
 * decided what the dot and the sheet say about it.
 */
class ConnectionStatusTest {

    private val utc = ZoneId.of("UTC")

    /**
     * A board with one row on it — i.e. a tracker the user is actually *looking at*.
     * Almost every assertion here needs this, because an empty board plus a non-`LIVE`
     * connection is by definition [TrackerUiState.isLoading], which is its own case.
     */
    private val loadedBoard = TrackerBoard(
        hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 17, 17),
    )

    /** Every tone, reached the way production reaches it: through the real mapper. */
    private fun stateFor(
        connection: ConnectionState,
        board: TrackerBoard = loadedBoard,
        lastSyncedAt: Long? = 1_786_991_520_000L,
        isShowingSnapshot: Boolean = false,
    ): TrackerUiState = toTrackerUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        lastSyncedAt = lastSyncedAt,
        isShowingSnapshot = isShowingSnapshot,
        zone = utc,
    )

    private fun statusFor(
        connection: ConnectionState,
        lastSyncedAt: Long? = 1_786_991_520_000L,
        isShowingSnapshot: Boolean = false,
    ): ConnectionStatus = stateFor(
        connection = connection,
        lastSyncedAt = lastSyncedAt,
        isShowingSnapshot = isShowingSnapshot,
    ).status

    private val notLive = ConnectionState.entries.filter { it != ConnectionState.LIVE }

    // --- rule 1: live is silent ---------------------------------------------

    @Test
    fun `a live sheet shows no indicator at all`() {
        val state = stateFor(ConnectionState.LIVE)
        assertTrue(state.status.isLive)
        assertFalse(state.status.isWorthMentioning)
        assertFalse(state.showConnectionIndicator)
    }

    @Test
    fun `a live sheet does not warn about writes, and offers no retry`() {
        val status = statusFor(ConnectionState.LIVE)
        assertFalse(status.warnsWritesDisabled)
        assertFalse(status.canRetry)
    }

    /**
     * The freshness of the data does not resurrect the dot. A live sheet that is still
     * rendering the Room snapshot for a few hundred milliseconds is *not* a connection
     * problem, and flashing a red dot on every open would train users to ignore it.
     */
    @Test
    fun `a live sheet stays silent even while it is still showing the snapshot`() {
        val state = stateFor(ConnectionState.LIVE, isShowingSnapshot = true)
        assertTrue(state.status.showingSnapshot)
        assertFalse(state.showConnectionIndicator)
    }

    // --- rule 2: while loading, the dot depends on whether waiting can help --

    /**
     * The cold-open case. `CONNECTING` with nothing discovered is the spinner, and the
     * spinner already says what it is doing; a red dot on top of it would be the first
     * thing a user ever saw the dot do, in a session where nothing was wrong — and, in the
     * overwhelmingly common case, one that resolves itself a second later.
     */
    @Test
    fun `a loading tracker suppresses the dot for a transient state`() {
        val state = stateFor(ConnectionState.CONNECTING, board = TrackerBoard())
        assertTrue("CONNECTING with an empty board should be loading", state.isLoading)
        assertTrue("CONNECTING is still worth mentioning", state.status.isWorthMentioning)
        assertFalse("a redial in flight is what the spinner already says",
            state.status.isTerminalUntilActedOn)
        assertFalse(state.showConnectionIndicator)
    }

    /**
     * The half the "quiet while loading" rule used to get wrong.
     *
     * `OFFLINE` and `SIGNED_OUT` are not stages of loading — they are the end of it. A
     * character with no cached snapshot is `isEmpty`, so it is `isLoading` by definition,
     * and a rejected token means nothing will ever arrive to end that. Suppressing the dot
     * there left an indefinite spinner with no dot, no sheet and no explanation: the one
     * screen state where the user most needs to be told to go and sign in was the one
     * state that said nothing at all.
     */
    @Test
    fun `a loading tracker still shows the dot for a state waiting cannot fix`() {
        listOf(ConnectionState.OFFLINE, ConnectionState.AUTH_FAILED).forEach { connection ->
            val state = stateFor(connection, board = TrackerBoard())
            assertTrue("$connection with an empty board should be loading", state.isLoading)
            assertTrue("$connection is terminal until the user acts",
                state.status.isTerminalUntilActedOn)
            assertTrue("$connection must offer the route to the sheet",
                state.showConnectionIndicator)
        }
    }

    /**
     * The split above is a partition, not two hand-listed sets: every not-live state is on
     * exactly one side of it, so a fifth connection state cannot be added without someone
     * deciding which half it belongs to.
     */
    @Test
    fun `every not-live state is either transient or terminal, and the dot follows`() {
        assertEquals(3, notLive.size)
        notLive.forEach { connection ->
            val state = stateFor(connection, board = TrackerBoard())
            assertTrue("$connection with an empty board should be loading", state.isLoading)
            assertEquals(
                "$connection's dot while loading must follow its tone",
                state.status.isTerminalUntilActedOn,
                state.showConnectionIndicator,
            )
        }
    }

    /**
     * The other half of the same rule: a board *does* exist once a snapshot is on screen,
     * so an offline sheet rendering cached rows is not "loading" and does get its dot.
     */
    @Test
    fun `a snapshot on screen is a board, so the dot comes back`() {
        val state = stateFor(
            ConnectionState.OFFLINE,
            board = TrackerBoard(),
            isShowingSnapshot = true,
        )
        assertFalse(state.isLoading)
        assertTrue(state.showConnectionIndicator)
    }

    // --- rule 3: every other state shows exactly one dot ---------------------

    @Test
    fun `every not-live state shows the indicator once the board has loaded`() {
        assertEquals(3, notLive.size)
        notLive.forEach { connection ->
            val state = stateFor(connection)
            assertFalse("$connection should not read as live", state.status.isLive)
            assertFalse("$connection should not be loading", state.isLoading)
            assertTrue("$connection should show the dot", state.showConnectionIndicator)
        }
    }

    @Test
    fun `each state's indicator description names that state and nothing else`() {
        val expected = mapOf(
            ConnectionState.LIVE to R.string.connection_indicator_live,
            ConnectionState.CONNECTING to R.string.connection_indicator_reconnecting,
            ConnectionState.OFFLINE to R.string.connection_indicator_offline,
            ConnectionState.AUTH_FAILED to R.string.connection_indicator_signed_out,
        )
        assertEquals(ConnectionState.entries.size, expected.size)
        expected.forEach { (state, res) ->
            assertEquals("$state", res, statusFor(state).indicatorDescriptionRes)
        }
    }

    /**
     * A shared description would make three different problems read identically to
     * TalkBack — "not connected" for a socket blip, a dead network and a rejected token
     * alike — and the third one needs the user to go and sign in.
     */
    @Test
    fun `no two states share a description, a label or an explanation`() {
        val statuses = ConnectionState.entries.map { statusFor(it) }
        assertEquals(4, statuses.map { it.indicatorDescriptionRes }.toSet().size)
        assertEquals(4, statuses.map { it.stateLabelRes }.toSet().size)
        assertEquals(4, statuses.map { it.explanationRes }.toSet().size)
    }

    @Test
    fun `the sheet's state label and explanation are pinned per state`() {
        assertEquals(R.string.connection_state_offline, statusFor(ConnectionState.OFFLINE).stateLabelRes)
        assertEquals(
            R.string.connection_details_offline,
            statusFor(ConnectionState.OFFLINE).explanationRes,
        )
        assertEquals(
            R.string.connection_state_signed_out,
            statusFor(ConnectionState.AUTH_FAILED).stateLabelRes,
        )
        assertEquals(
            R.string.connection_details_signed_out,
            statusFor(ConnectionState.AUTH_FAILED).explanationRes,
        )
    }

    // --- rule 4: what the sheet may offer -----------------------------------

    /**
     * `restart()` redials with the token it already has, so it is the right button for a
     * socket that is down and the wrong button for a token the server has refused.
     */
    @Test
    fun `retry is offered exactly where a restart could plausibly help`() {
        assertTrue(statusFor(ConnectionState.CONNECTING).canRetry)
        assertTrue(statusFor(ConnectionState.OFFLINE).canRetry)
        assertFalse(statusFor(ConnectionState.AUTH_FAILED).canRetry)
        assertFalse(statusFor(ConnectionState.LIVE).canRetry)
    }

    /**
     * Everything the sheet renders has to keep working on a loading tracker, whichever way
     * the dot went: the affordance and the contents are independent questions, and a
     * `CONNECTING` sheet the user opened before the load finished must not go blank.
     */
    @Test
    fun `a loading tracker still has a complete sheet to render`() {
        val status = stateFor(ConnectionState.OFFLINE, board = TrackerBoard()).status
        assertEquals(R.string.connection_state_offline, status.stateLabelRes)
        assertTrue(status.canRetry)
        assertTrue(status.warnsWritesDisabled)
    }

    // --- rule 5: the read-only fact survives the strip's removal ------------

    @Test
    fun `every not-live state still says writes are unavailable`() {
        notLive.forEach { state ->
            assertTrue("$state must warn about writes", statusFor(state).warnsWritesDisabled)
        }
    }

    // --- what the sheet says about freshness ---------------------------------

    @Test
    fun `the snapshot timestamp reaches the sheet`() {
        // 2026-08-17T18:32:00Z, the same fixture minute TrackerUiStateTest uses.
        assertEquals("18:32", statusFor(ConnectionState.OFFLINE).syncedAt)
    }

    @Test
    fun `a never-synced sheet carries no time rather than a fabricated one`() {
        assertNull(statusFor(ConnectionState.OFFLINE, lastSyncedAt = null).syncedAt)
        assertNull(statusFor(ConnectionState.OFFLINE, lastSyncedAt = 0L).syncedAt)
    }

    @Test
    fun `showingSnapshot stays independent of the tone`() {
        val state = stateFor(ConnectionState.CONNECTING, isShowingSnapshot = true)
        assertEquals(ConnectionTone.RECONNECTING, state.status.tone)
        assertTrue(state.status.showingSnapshot)
        assertTrue(state.showConnectionIndicator)
    }
}
