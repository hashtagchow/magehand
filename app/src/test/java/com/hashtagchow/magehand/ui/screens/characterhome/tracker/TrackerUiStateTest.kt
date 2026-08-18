package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import java.time.ZoneId

/**
 * Board → UI mapping and connection derivation (WP6 acceptance).
 *
 * The *presentation* half — dot vs no dot, and what the details sheet says — lives in
 * `ConnectionStatusTest`.
 *
 * The numbers here are the live capture's (see `Fixtures.kt`), as WP4
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
        /** FR-6. True here so every pre-FR-6 assertion keeps asserting FR-1/2's behaviour. */
        showToggles: Boolean = true,
        hasConnection: Boolean = true,
    ) = toTrackerUiState(
        creatureId = "FakeCreature23456",
        board = board,
        connection = connection,
        lastSyncedAt = lastSyncedAt,
        isShowingSnapshot = isShowingSnapshot,
        accentColor = accent,
        zone = utc,
        showToggles = showToggles,
        hasConnection = hasConnection,
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

    // --- toggle visibility (active buffs only) ------------------------------

    @Test
    fun `only the toggles that are on are chips by default`() {
        // The default view is "what is running on me right now". "Racial ASI Disabler" is
        // off, so it is not in the way of that question.
        assertEquals(
            listOf("Load Wizard Spells" to true),
            map().conditions.map { it.name to it.enabled },
        )
    }

    @Test
    fun `the toggles that are off are counted behind the expander, not dropped`() {
        // Turning a buff *on* is a tap on an off chip, so "hidden by default" has to mean
        // one tap away, not gone.
        assertEquals(listOf("Racial ASI Disabler"), map().inactiveConditions.map { it.name })
        assertTrue(map().inactiveConditions.none { it.enabled })
    }

    @Test
    fun `a pinned toggle stays a chip while it is off`() {
        // The user override in the "always show" direction. `pinned` reaches the row from
        // `tracker_prefs` via TrackerEngine; here it is already on the board.
        val board = sabriel.copy(
            activeToggles = listOf(
                ConditionToggle("tog2", "Racial ASI Disabler", enabled = false, pinned = true),
            ),
        )
        assertEquals(listOf("Racial ASI Disabler"), map(board).conditions.map { it.name })
        assertTrue(map(board).inactiveConditions.isEmpty())
    }

    @Test
    fun `a hidden toggle is absent from both lists even when it is on`() {
        // Hiding is enforced upstream: TrackerEngine's override layer never puts a hidden
        // row on the board, so an on-and-hidden toggle cannot arrive here at all. This
        // pins the consequence — the expander is not a back door into hidden rows.
        val board = sabriel.copy(
            activeToggles = listOf(ConditionToggle("tog1", "Load Wizard Spells", enabled = true)),
        )
        val hiddenByEngine = board.copy(activeToggles = emptyList())
        assertTrue(map(hiddenByEngine).conditions.isEmpty())
        assertTrue(map(hiddenByEngine).inactiveConditions.isEmpty())
    }

    @Test
    fun `a board of nothing but off toggles is not an empty board`() {
        // Otherwise the screen would render "Nothing to track yet" over an expander holding
        // every buff the character could raise.
        val board = TrackerBoard(
            activeToggles = listOf(ConditionToggle("tog2", "Rage", enabled = false)),
        )
        val state = map(board)
        assertTrue(state.conditions.isEmpty())
        assertEquals(1, state.inactiveConditions.size)
        assertFalse(state.isEmpty)
        assertFalse(state.isLoading)
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

    // --- connection status ---------------------------------------------------

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
        // The case the details sheet exists for: reconnecting *and* showing stale data.
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

    // --- Defenses -----------------------------------------------------------

    private fun defense(id: String, kind: DefenseKind, vararg types: String) =
        DamageDefense(propertyId = id, kind = kind, damageTypes = types.toList(), name = id)

    @Test
    fun `a character with no defenses gets no defenses section`() {
        // The section is absent, not empty — the whole reason the screen checks isNotEmpty.
        assertTrue(map().defenses.isEmpty())
    }

    @Test
    fun `defenses render display-cased, alphabetical and one row per kind`() {
        val state = map(
            board = sabriel.copy(
                defenses = listOf(
                    defense("d1", DefenseKind.RESISTANT, "radiant", "necrotic"),
                    defense("d2", DefenseKind.IMMUNE, "poison"),
                ),
            ),
        )
        // Row order is the board's order, untouched — sorting defenses is the engine's
        // job, and this list is deliberately handed over in the "wrong" one to prove the
        // mapper does not quietly re-sort behind it.
        assertEquals(listOf(DefenseKind.RESISTANT, DefenseKind.IMMUNE), state.defenses.map { it.kind })
        assertEquals("Necrotic, Radiant", state.defenses.first().text)
        assertEquals("Resistant", state.defenses.first().label)
        assertEquals("Immune", state.defenses.last().label)
        assertEquals("Poison", state.defenses.last().text)
    }

    @Test
    fun `two features granting the same kind merge into one row`() {
        // Three sources of fire resistance are one fact at the table, not three lines.
        val state = map(
            board = sabriel.copy(
                defenses = listOf(
                    defense("d1", DefenseKind.RESISTANT, "fire"),
                    defense("d2", DefenseKind.RESISTANT, "poison"),
                ),
            ),
        )
        val row = state.defenses.single()
        assertEquals(DefenseKind.RESISTANT, row.kind)
        assertEquals("Fire, Poison", row.text)
    }

    @Test
    fun `the same damage type from two features is printed once, whatever its casing`() {
        // The wire strings are whatever the sheet's author typed.
        val state = map(
            board = sabriel.copy(
                defenses = listOf(
                    defense("d1", DefenseKind.RESISTANT, "Fire"),
                    defense("d2", DefenseKind.RESISTANT, "fire", "cold"),
                ),
            ),
        )
        assertEquals("Cold, Fire", state.defenses.single().text)
    }

    @Test
    fun `every defense kind has a label`() {
        assertEquals(
            listOf("Immune", "Resistant", "Vulnerable"),
            DefenseKind.entries.map { it.label() },
        )
    }

    @Test
    fun `a board carrying only defenses is neither empty nor loading`() {
        // Otherwise the tab would render its "nothing to track" state over a character
        // whose only tracker content is the reference section.
        // CONNECTING, so isLoading would fire if isEmpty were still true — the assertion
        // is worthless against the LIVE default.
        val state = map(
            board = TrackerBoard(defenses = listOf(defense("d1", DefenseKind.IMMUNE, "poison"))),
            connection = ConnectionState.CONNECTING,
        )
        assertFalse(state.isEmpty)
        assertFalse(state.isLoading)
    }

    // --- FR-6: the toggles switch (09 decision 9) ---------------------------

    @Test
    fun `with toggles off, the conditions section is absent from the tracker entirely`() {
        val state = map(showToggles = false)

        // Both lists, not just the visible one: the screen renders the header when *either*
        // is non-empty, so leaving the inactive drawer populated would keep the section on
        // screen with nothing but "1 inactive" in it.
        assertTrue(state.conditions.isEmpty())
        assertTrue(state.inactiveConditions.isEmpty())
    }

    @Test
    fun `with toggles on, the conditions section is exactly what FR-1 and FR-2 shipped`() {
        val on = map(showToggles = true)

        assertEquals(listOf("Load Wizard Spells"), on.conditions.map { it.name })
        assertEquals(listOf("Racial ASI Disabler"), on.inactiveConditions.map { it.name })
    }

    @Test
    fun `toggles off hides the conditions but touches nothing else on the board`() {
        val off = map(showToggles = false)
        val on = map(showToggles = true)

        // The gate is a filter on one board field, and this is what says so: everything the
        // player actually plays with is byte-identical with the switch either way.
        assertEquals(on.hp, off.hp)
        assertEquals(on.slots, off.slots)
        assertEquals(on.resources, off.resources)
        assertEquals(on.consumables, off.consumables)
        assertEquals(on.defenses, off.defenses)
    }

    @Test
    fun `a board of nothing but toggles is empty once they are hidden`() {
        val board = TrackerBoard(
            activeToggles = listOf(ConditionToggle("tog1", "Bless", enabled = true)),
        )

        assertFalse(map(board = board, showToggles = true).isEmpty)
        assertTrue(map(board = board, showToggles = false).isEmpty)
    }

    @Test
    fun `the concentration cross survives the toggles switch, in both positions`() {
        val board = sabriel.copy(
            activeToggles = listOf(ConditionToggle("tog3", "Web", enabled = true, flippable = true)),
            concentratingOn = "Web",
        )

        // 09 decision 9: "the concentration banner is property-driven and unaffected". Both
        // halves of the banner, not just its text — the ✕ issues `flipToggle(propertyId)`, a
        // write against a *property*, and whether a chip for that property is being drawn is
        // not one of its inputs.
        //
        // This test previously pinned the opposite, and what it pinned was a trap: with the
        // switch off the banner still read "Concentrating: Web" while its ✕ was inert, so a
        // player who had turned toggles off could not drop concentration from the tracker at
        // all. (Architect ruling, 2026-08-18: the design stands.)
        assertEquals("Web", map(board = board, showToggles = false).concentratingOn)
        assertEquals("tog3", map(board = board, showToggles = false).concentrationToggleId)
        assertEquals("tog3", map(board = board, showToggles = true).concentrationToggleId)

        // …and the chip row really is empty in that state, which is what makes the point:
        // the ✕ is armed against something the conditions section is not showing.
        assertTrue(map(board = board, showToggles = false).conditions.isEmpty())
    }

    @Test
    fun `the switch cannot arm a cross the board itself leaves dead`() {
        // The narrowing that is real and survives: WP7's. A banner driven by a `buff`, or by
        // a computed toggle `flipToggle` would refuse, has no ✕ either way — hiding the chips
        // is not what disarms it, and un-hiding them is not what arms it.
        val buff = sabriel.copy(activeToggles = emptyList(), concentratingOn = "Bless")
        val computed = sabriel.copy(
            activeToggles = listOf(ConditionToggle("tog4", "Web", enabled = true, flippable = false)),
            concentratingOn = "Web",
        )

        listOf(true, false).forEach { showToggles ->
            assertNull(map(board = buff, showToggles = showToggles).concentrationToggleId)
            assertNull(map(board = computed, showToggles = showToggles).concentrationToggleId)
        }
    }

    // --- 09 decision 8: a local character never shows a connection dot -------

    @Test
    fun `a character with no connection never floats the dot, whatever the tone says`() {
        // Every tone, including the two that are terminal-until-acted-on and therefore
        // escape the loading suppression. The point is that none of them can reach the dot.
        ConnectionState.entries.forEach { connection ->
            val state = map(connection = connection, hasConnection = false)
            assertFalse(
                "a local character showed the connection dot for $connection",
                state.showConnectionIndicator,
            )
        }
    }

    @Test
    fun `an empty local board still shows no dot, where a server character would`() {
        // The trap this pins: a cold-open local character is `isLoading == false` only
        // because its tone is LIVE. If anything ever moved that tone, `isTerminalUntilActedOn`
        // would put a permanent red mark on a character with no server.
        val empty = TrackerBoard.EMPTY

        assertTrue(
            map(board = empty, connection = ConnectionState.OFFLINE).showConnectionIndicator,
        )
        assertFalse(
            map(board = empty, connection = ConnectionState.OFFLINE, hasConnection = false)
                .showConnectionIndicator,
        )
    }

    @Test
    fun `a server character is unaffected by the local suppression`() {
        // hasConnection defaults to true, so every existing caller keeps the old rule.
        assertTrue(map(connection = ConnectionState.OFFLINE).showConnectionIndicator)
        assertFalse(map(connection = ConnectionState.LIVE).showConnectionIndicator)
    }
}
