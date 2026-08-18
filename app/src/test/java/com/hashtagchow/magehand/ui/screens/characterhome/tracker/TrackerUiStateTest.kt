package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import java.time.ZoneId

/**
 * Board → UI mapping and status-strip derivation (WP6 acceptance).
 *
 * The numbers here are Sabriel's, from `docs/fixtures/sabriel-2026-08-17.json` as WP4
 * asserted them: 1st-level slots 3/4, 2nd-level 1/2, HP 17/17, "Heroic Inspiration" 0/1,
 * "Gold piece" ×109. Using the real sheet's shape rather than invented numbers is what
 * makes this test and the emulator parity probe assert the same thing from two ends.
 */
class TrackerUiStateTest {

    private val utc = ZoneId.of("UTC")

    private fun slot(id: String, name: String, value: Int, total: Int, level: Int) =
        TrackedResource(
            propertyId = id,
            kind = TrackerKind.SPELL_SLOT,
            name = name,
            value = value,
            total = total,
            reset = ResetRule.LONG_REST,
            spellSlotLevel = level,
        )

    private val sabriel = TrackerBoard(
        hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 17, 17),
        tempHp = TrackedResource("thp1", TrackerKind.TEMP_HP, "Temporary Hit Points", 0, 0),
        slots = listOf(
            slot("slot1", "1st Level", 3, 4, 1),
            slot("slot2", "2nd Level", 1, 2, 2),
        ),
        resources = listOf(
            TrackedResource("res1", TrackerKind.RESOURCE, "Heroic Inspiration", 0, 1),
        ),
        pinnedItems = listOf(
            TrackedResource("item1", TrackerKind.ITEM, "Gold piece", 109, 109, pinned = true),
        ),
        allItems = listOf(
            TrackedResource("item1", TrackerKind.ITEM, "Gold piece", 109, 109, pinned = true),
            TrackedResource("item2", TrackerKind.ITEM, "Potion of Healing", 2, 2),
        ),
        activeToggles = listOf(
            ConditionToggle("tog1", "Load Wizard Spells", enabled = true),
            ConditionToggle("tog2", "Racial ASI Disabler", enabled = false),
        ),
        concentratingOn = "Web",
    )

    private fun map(
        board: TrackerBoard = sabriel,
        connection: ConnectionState = ConnectionState.LIVE,
        lastSyncedAt: Long? = null,
        isShowingSnapshot: Boolean = false,
        accent: String? = null,
    ) = toTrackerUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        lastSyncedAt = lastSyncedAt,
        isShowingSnapshot = isShowingSnapshot,
        accentColor = accent,
        zone = utc,
    )

    // --- board → UI ---------------------------------------------------------

    @Test
    fun `hp block carries current and max straight through`() {
        val hp = map().hp!!
        assertEquals(17, hp.current)
        assertEquals(17, hp.max)
        assertEquals(1f, hp.fraction, 0.0001f)
    }

    @Test
    fun `a zero temp hp does not draw the shield chip`() {
        assertFalse(map().hp!!.hasTempHp)
    }

    @Test
    fun `a nonzero temp hp draws the shield chip`() {
        val board = sabriel.copy(
            tempHp = TrackedResource("thp1", TrackerKind.TEMP_HP, "Temporary Hit Points", 5, 5),
        )
        val hp = map(board).hp!!
        assertTrue(hp.hasTempHp)
        assertEquals(5, hp.tempHp)
    }

    @Test
    fun `hp fraction is clamped so a server overshoot cannot overdraw the bar`() {
        val board = sabriel.copy(
            hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 30, 17),
        )
        assertEquals(1f, map(board).hp!!.fraction, 0.0001f)
    }

    @Test
    fun `hp fraction of a zero-max sheet is zero, not a division by zero`() {
        val board = sabriel.copy(
            hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 0, 0),
        )
        assertEquals(0f, map(board).hp!!.fraction, 0.0001f)
    }

    @Test
    fun `one pip row per spell slot level, in level order`() {
        val slots = map().slots
        assertEquals(listOf("1st Level", "2nd Level"), slots.map { it.label })
        assertEquals(listOf(3 to 4, 1 to 2), slots.map { it.value to it.total })
    }

    @Test
    fun `slot rows carry their reset rule as display text`() {
        assertEquals("Long rest", map().slots.first().resetLabel)
    }

    @Test
    fun `a resource with no reset rule shows no reset label`() {
        assertNull(map().resources.single().resetLabel)
    }

    @Test
    fun `spent is total minus remaining`() {
        assertEquals(1, map().slots.first().spent)
    }

    @Test
    fun `small rows use pips and large ones fall back to a bar`() {
        assertTrue(map().slots.all { it.usePips })
        val big = sabriel.copy(
            resources = listOf(TrackedResource("r", TrackerKind.RESOURCE, "Ki", 12, 20)),
        )
        assertFalse(map(big).resources.single().usePips)
    }

    @Test
    fun `only pinned items become consumable rows`() {
        val consumables = map().consumables
        assertEquals(listOf("Gold piece"), consumables.map { it.name })
        assertEquals(109, consumables.single().quantity)
    }

    @Test
    fun `condition chips keep their on-off state`() {
        assertEquals(
            listOf("Load Wizard Spells" to true, "Racial ASI Disabler" to false),
            map().conditions.map { it.name to it.enabled },
        )
    }

    @Test
    fun `the concentration banner reads the board's concentration source`() {
        assertEquals("Web", map().concentratingOn)
        assertNull(map(sabriel.copy(concentratingOn = null)).concentratingOn)
    }

    @Test
    fun `an empty board is empty and, while not live, loading`() {
        val state = map(TrackerBoard.EMPTY, connection = ConnectionState.CONNECTING)
        assertTrue(state.isEmpty)
        assertTrue(state.isLoading)
    }

    @Test
    fun `a live empty board is empty but no longer loading`() {
        // The distinction that stops "nothing to track" flashing on every open.
        val state = map(TrackerBoard.EMPTY, connection = ConnectionState.LIVE)
        assertTrue(state.isEmpty)
        assertFalse(state.isLoading)
    }

    @Test
    fun `an empty board rendered from a snapshot is not loading either`() {
        val state = map(
            TrackerBoard.EMPTY,
            connection = ConnectionState.OFFLINE,
            isShowingSnapshot = true,
        )
        assertFalse(state.isLoading)
    }

    @Test
    fun `a populated board is never loading`() {
        assertFalse(map(connection = ConnectionState.CONNECTING).isLoading)
    }

    // --- status strip -------------------------------------------------------

    @Test
    fun `every connection state maps to exactly one tone, and back`() {
        val expected = mapOf(
            ConnectionState.LIVE to ConnectionTone.LIVE,
            ConnectionState.CONNECTING to ConnectionTone.RECONNECTING,
            ConnectionState.OFFLINE to ConnectionTone.OFFLINE,
            ConnectionState.AUTH_FAILED to ConnectionTone.SIGNED_OUT,
        )
        assertEquals(expected.size, ConnectionState.entries.size)
        expected.forEach { (state, tone) ->
            assertEquals(tone, map(connection = state).status.tone)
            assertEquals(state, tone.toConnectionState())
        }
    }

    @Test
    fun `lastSyncedAt is rendered as a 24-hour clock time in the given zone`() {
        // 2026-08-17T18:32:00Z — the minute docs/dicecloud-api.md's dev token was minted.
        val state = map(lastSyncedAt = 1_786_991_520_000L)
        assertEquals("18:32", state.status.syncedAt)
    }

    @Test
    fun `a never-synced character shows no time rather than a fake one`() {
        assertNull(map(lastSyncedAt = null).status.syncedAt)
        assertNull(map(lastSyncedAt = 0L).status.syncedAt)
        assertNull(formatSyncedAt(null, utc))
    }

    @Test
    fun `the snapshot banner is independent of the connection tone`() {
        // The case the two-line strip exists for: reconnecting *and* showing stale data.
        val state = map(connection = ConnectionState.CONNECTING, isShowingSnapshot = true)
        assertEquals(ConnectionTone.RECONNECTING, state.status.tone)
        assertTrue(state.status.showingSnapshot)
    }

    @Test
    fun `a live board is not flagged as a snapshot`() {
        assertFalse(map().status.showingSnapshot)
    }

    @Test
    fun `the accent colour is carried through untouched`() {
        assertEquals("#7E57C2", map(accent = "#7E57C2").accentColor)
    }
}
