package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.ui.components.DirectEntryKeys
import com.hashtagchow.magehand.ui.components.DirectEntryKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.DeathSaves
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.RollModifier
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.toTrackedResource
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
        selectedRollId: String? = null,
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
        selectedRollId = selectedRollId,
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

    // --- FR-7: the Rolls section --------------------------------------------

    /** A server-shaped roll list: an ability check, a save and a skill, in sheet order. */
    private val rolls = listOf(
        RollModifier("r1", "Dexterity", 3, sortOrder = 10),
        RollModifier("r2", "Wisdom Save", -1, sortOrder = 20),
        RollModifier("r3", "Stealth", 5, RollAdvantage.ADVANTAGE, sortOrder = 30),
        RollModifier("r4", "Perception", 0, RollAdvantage.DISADVANTAGE, sortOrder = 40),
    )

    private val withRolls = sabriel.copy(rolls = rolls)

    @Test
    fun `the section is absent for a character whose data expresses no rolls`() {
        // Absent, not empty: the screen renders the header and the control only when this is
        // true, exactly as it does for defenses.
        assertFalse(map().rolls.isPresent)
        assertTrue(map().rolls.options.isEmpty())
        assertNull(map().rolls.selected)
    }

    @Test
    fun `every roll is offered, in the board's order`() {
        val picker = map(board = withRolls).rolls

        assertTrue(picker.isPresent)
        assertEquals(
            listOf("Dexterity", "Wisdom Save", "Stealth", "Perception"),
            picker.options.map { it.name },
        )
        assertEquals(listOf("r1", "r2", "r3", "r4"), picker.options.map { it.id })
    }

    @Test
    fun `nothing selected renders no display at all`() {
        // The placeholder case: the dropdown is there, the read-out is not.
        assertNull(map(board = withRolls).rolls.selected)
    }

    @Test
    fun `the selected roll's modifier is always signed`() {
        assertEquals("+3", map(board = withRolls, selectedRollId = "r1").rolls.selected?.modifier)
        // U+2212 MINUS SIGN, not a hyphen.
        assertEquals("\u22121", map(board = withRolls, selectedRollId = "r2").rolls.selected?.modifier)
        // "+0", never a bare "0" — that reads as a missing value on a character sheet.
        assertEquals("+0", map(board = withRolls, selectedRollId = "r4").rolls.selected?.modifier)
    }

    @Test
    fun `advantage is named when the data expresses one, and silent when it does not`() {
        assertEquals("Advantage", map(board = withRolls, selectedRollId = "r3").rolls.selected?.advantageLabel)
        assertEquals(
            "Disadvantage",
            map(board = withRolls, selectedRollId = "r4").rolls.selected?.advantageLabel,
        )
        // Null rather than "Normal": a word on every roll teaches the eye to skip the field.
        assertNull(map(board = withRolls, selectedRollId = "r1").rolls.selected?.advantageLabel)
    }

    @Test
    fun `a remembered id that no longer names a roll reads as no selection`() {
        // The state a persisted selection can outlive its board into — a feature switched
        // off, a sheet re-authored. Placeholder, not a crash and not a wrong number.
        val picker = map(board = withRolls, selectedRollId = "gone").rolls

        assertTrue("the dropdown must still be offered", picker.isPresent)
        assertNull(picker.selected)
    }

    @Test
    fun `the spoken form is the whole row, so TalkBack reads a fact`() {
        val selected = map(board = withRolls, selectedRollId = "r3").rolls.selected!!
        assertEquals("Stealth, +5, Advantage", selected.spoken)

        val plain = map(board = withRolls, selectedRollId = "r1").rolls.selected!!
        assertEquals("Dexterity, +3", plain.spoken)
    }

    @Test
    fun `a board with only rolls is not loading`() {
        // The local-character shape at its most minimal, and the trap it avoids: a board that
        // is nothing but six ability checks is real content, so the spinner must give way.
        val onlyRolls = TrackerBoard(rolls = rolls)

        assertFalse(map(board = onlyRolls).isEmpty)
        assertFalse(map(board = onlyRolls, connection = ConnectionState.OFFLINE).isLoading)
    }

    @Test
    fun `a local character's six checks map exactly like a server character's rolls`() {
        // 09 decision 5's "same screen" claim, at this feature: the mapping is source-blind,
        // so the only difference is what the board carried.
        val local = TrackerBoard(
            rolls = listOf(
                RollModifier("local:check:STR", "Strength", -1, sortOrder = 0),
                RollModifier("local:check:DEX", "Dexterity", 2, sortOrder = 1),
            ),
        )
        val picker = map(board = local, selectedRollId = "local:check:STR").rolls

        assertEquals(listOf("Strength", "Dexterity"), picker.options.map { it.name })
        assertEquals("\u22121", picker.selected?.modifier)
        assertNull("no local source expresses advantage", picker.selected?.advantageLabel)
    }

    @Test
    fun `the signed formatter is the one both screens use`() {
        // FR-7's read-out and the local reference strip must never disagree about "+0" or
        // about which character a minus sign is.
        assertEquals("+0", formatSignedModifier(0))
        assertEquals("+3", formatSignedModifier(3))
        assertEquals("\u22122", formatSignedModifier(-2))
    }

    // --- FR-20: the reset badge and the spoken row ------------------------------------

    /**
     * A resource row carrying the given rule, as the *server* delivers one \u2014 `TrackerEngine`
     * has already parsed the property's `reset` field into [ResetRule] by this point, so the
     * board is where the two sources meet.
     */
    private fun serverRow(reset: ResetRule?) = TrackedResource(
        propertyId = "res1",
        kind = TrackerKind.RESOURCE,
        name = "Rage",
        value = 1,
        total = 3,
        reset = reset,
    )

    /**
     * The same row as a **local** one, built from the stored `resetRule` string rather than
     * from a [ResetRule] handed in.
     *
     * Deliberately started at the entity and not at `LocalTrackerRow`: FR-20 decision 3 is
     * "no schema", and the claim worth pinning is that the column the app *already* has
     * reaches the badge. Starting a row's life as a Kotlin enum would skip the only step that
     * could go wrong, which is the parse.
     */
    private fun localRow(storedResetRule: String) = LocalTrackerRowEntity(
        id = "local-row-1",
        characterId = "local-1",
        kind = "resource",
        label = "Rage",
        total = 3,
        current = 1,
        resetRule = storedResetRule,
        sortIndex = 0,
        // `!!` on the mapping too, as of FR-29: `toTrackedResource` is nullable now because an
        // ACTION row is not a tracker row. A `resource` row is one, so a null here would be a
        // genuine failure of the mapping and this fixture should say so loudly.
    ).toDomain()!!.toTrackedResource()!!

    private fun badgeOf(row: TrackedResource): String? =
        map(board = TrackerBoard(resources = listOf(row))).resources.single().resetLabel

    @Test
    fun `a server row's reset rule reaches the badge, in both directions`() {
        assertEquals("Long rest", badgeOf(serverRow(ResetRule.LONG_REST)))
        assertEquals("Short rest", badgeOf(serverRow(ResetRule.SHORT_REST)))
    }

    @Test
    fun `a server row with no reset rule carries no badge`() {
        // The third state, and the common one. FR-20 decision 1 keeps the badge to rows a rest
        // actually touches \u2014 a wand whose charges come back at dawn must say nothing here
        // rather than say "Never", or the two rows that matter stop standing out.
        assertNull(badgeOf(serverRow(null)))
    }

    @Test
    fun `a local row's stored resetRule reaches the same badge as a server row's`() {
        // 09 decision 5's "same screen" claim at this feature: `local_tracker_rows.resetRule`
        // stores DiceCloud's own wire strings, so the two sources cannot render different
        // words for the same rule.
        assertEquals("Long rest", badgeOf(localRow("longRest")))
        assertEquals("Short rest", badgeOf(localRow("shortRest")))
    }

    @Test
    fun `a local row stored as none carries no badge`() {
        // `LocalTrackerRowEntity.RESET_NONE` \u2014 the player's deliberate "no reset" in the form,
        // which must land on the same absence a server row's missing `reset` does.
        assertNull(badgeOf(localRow(LocalTrackerRowEntity.RESET_NONE)))
    }

    @Test
    fun `an unrecognised reset value carries no badge, from either source`() {
        // Tolerant, not strict. DiceCloud can grow a reset rule this build has never heard of
        // (and a sheet author can put anything in the field). `ResetRule.fromWire` answers
        // `null` for all of it, which lands on "no badge" \u2014 the row simply says nothing about
        // rests, which is honest. The alternative \u2014 printing the raw wire value \u2014 would put
        // "dawn" on a row under a heading the rest dialog cannot act on.
        assertNull(badgeOf(serverRow(ResetRule.fromWire("dawn"))))
        assertNull(badgeOf(localRow("dawn")))
        assertNull(badgeOf(localRow("")))
    }

    private fun spokenLabelOf(row: TrackedResource): String =
        map(board = TrackerBoard(resources = listOf(row))).resources.single().spokenLabel

    @Test
    fun `the spoken row name appends the reset rule as a verb phrase`() {
        // FR-20 decision 2. "restores on", not the badge's noun: a sentence has to say what the
        // rest does to the row, because there is no layout left to imply it \u2014 and "restores",
        // not "resets", for decision 4's reason.
        assertEquals("Rage, restores on a long rest", spokenLabelOf(serverRow(ResetRule.LONG_REST)))
        assertEquals("Rage, restores on a short rest", spokenLabelOf(localRow("shortRest")))
    }

    @Test
    fun `the spoken row name is just the name when there is no rule`() {
        // No trailing clause, and no "no reset rule" either \u2014 the sentence ends, exactly as the
        // badge is absent. Same rule as `RollDisplayState.spoken` dropping a null advantage.
        assertEquals("Rage", spokenLabelOf(serverRow(null)))
        assertEquals("Rage", spokenLabelOf(localRow("dawn")))
    }

    // --- FR-22 direct entry targets (15 decisions 5-7) -----------------------

    /**
     * The HP key resolves with the ceiling decision 7 gives it, and with a **blank label** —
     * the one target whose name is copy rather than something off the sheet, so the composable
     * fills it in.
     */
    @Test
    fun `the hit points key resolves with a ceiling and no label`() {
        val target = map().directEntryTarget(DirectEntryKeys.HIT_POINTS)!!
        assertEquals(DirectEntryKind.HIT_POINTS, target.kind)
        assertEquals("hp1", target.propertyId)
        assertEquals(17, target.current)
        assertEquals(17, target.max)
        assertEquals("HP names itself from strings.xml", "", target.label)
    }

    /** Decision 7: a pip row's ceiling is its total, and its label is the row's own name. */
    @Test
    fun `a slot key resolves with the row's total as the ceiling`() {
        val target = map().directEntryTarget(DirectEntryKeys.resource("slot1"))!!
        assertEquals(DirectEntryKind.RESOURCE, target.kind)
        assertEquals("1st Level", target.label)
        assertEquals(3, target.current)
        assertEquals(4, target.max)
    }

    @Test
    fun `a resource key resolves through the same prefix as a slot`() {
        // Both are pip rows and both go to `spend`/`restore`; the composable does not have to
        // know which section a row came from.
        val target = map().directEntryTarget(DirectEntryKeys.resource("res1"))!!
        assertEquals(DirectEntryKind.RESOURCE, target.kind)
        assertEquals(1, target.max)
    }

    /**
     * Decision 7: an item has no ceiling. A `null` here is what removes the field's range line.
     *
     * Resolved against `consumables` — the **pinned** items, which are the rows the tracker
     * actually draws — and not against the whole item list. An unpinned item has no number on
     * this tab to long-press, and offering to edit one would be a control with no surface.
     */
    @Test
    fun `a consumable key resolves with no ceiling`() {
        val target = map().directEntryTarget(DirectEntryKeys.item("item1"))!!
        assertEquals(DirectEntryKind.ITEM, target.kind)
        assertEquals("Gold piece", target.label)
        assertEquals(109, target.current)
        assertNull("an item has no maximum", target.max)
    }

    @Test
    fun `an item the tracker does not draw has no direct-entry target`() {
        // `item2` is in `allItems` and not pinned, so it has no row on this tab.
        assertNull(map().directEntryTarget(DirectEntryKeys.item("item2")))
    }

    /**
     * A key naming a row the board no longer has resolves to nothing, which is what closes an
     * open dialog rather than leaving it editing a ghost.
     */
    @Test
    fun `a key naming a vanished row resolves to null`() {
        assertNull(map().directEntryTarget(DirectEntryKeys.resource("gone")))
        assertNull(map().directEntryTarget(DirectEntryKeys.item("gone")))
    }

    @Test
    fun `an unrecognised key resolves to null rather than throwing`() {
        // A key read back out of a `Bundle` written by a newer install has to be inert.
        assertNull(map().directEntryTarget("something:else"))
    }

    @Test
    fun `the hit points key resolves to null on a character with no hp row`() {
        assertNull(map(board = TrackerBoard()).directEntryTarget(DirectEntryKeys.HIT_POINTS))
    }

    // --- FR-23 the death-save trigger (15 decision 18) -----------------------

    private val downed = sabriel.copy(
        hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 0, 17),
        deathSaves = DeathSaves("ds1", "ds2", successes = 1, failures = 2),
    )

    /** Both halves of decision 18's trigger, together. */
    @Test
    fun `the death save block renders at zero hit points with a discovered pair`() {
        val saves = map(board = downed).deathSaves!!
        assertEquals(1, saves.successes)
        assertEquals(2, saves.failures)
    }

    /**
     * The gate is the **HP row the screen draws**, so the block goes away the instant a heal is
     * tapped rather than a round trip later — see `TrackerUiState.deathSaves` for why that gate
     * is here and not at discovery.
     */
    @Test
    fun `a character above zero shows no block even with marks on the sheet`() {
        val up = downed.copy(hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 1, 17))
        assertNull(map(board = up).deathSaves)
    }

    /** Decision 18: no pair, no block. The Dummy's case. */
    @Test
    fun `a downed character with no discovered pair shows no block`() {
        assertNull(map(board = downed.copy(deathSaves = null)).deathSaves)
    }

    /**
     * A hidden HP row means no block, and that is deliberate: with no HP row on the board there
     * is no `value === 0` to read, and reaching around the override layer would be this app
     * overruling the one screen it lets the player arrange.
     */
    @Test
    fun `a hidden hp row leaves the block off`() {
        assertNull(map(board = downed.copy(hp = null)).deathSaves)
    }

    /**
     * The **counts survive the gate closing**, which is what decision 20's "shows its marks
     * honestly" depends on: a heal takes the block off screen and the marks are still on the
     * sheet until someone clears them.
     */
    @Test
    fun `the discovered pair stays on the state after the block stops rendering`() {
        val up = downed.copy(hp = TrackedResource("hp1", TrackerKind.HIT_POINTS, "Hit Points", 4, 17))
        val state = map(board = up)
        assertNull(state.deathSaves)
        assertEquals("the marks are still on the sheet", 2, state.deathSavePair?.failures)
    }

    /** Stable and dead are derivations, never a flag off the sheet (decision 19). */
    @Test
    fun `stable and dead are counted rather than read`() {
        val stable = downed.copy(deathSaves = DeathSaves("ds1", "ds2", successes = 3, failures = 1))
        assertTrue(map(board = stable).deathSaves!!.isStable)
        assertFalse(map(board = stable).deathSaves!!.isDead)

        val dead = downed.copy(deathSaves = DeathSaves("ds1", "ds2", successes = 0, failures = 3))
        assertTrue(map(board = dead).deathSaves!!.isDead)
    }

    // --- FR-30: hit dice (docs/design/18-table-pack.md decisions 17-19) --------

    private fun hitDie(id: String, size: String, value: Int, total: Int) = TrackedResource(
        propertyId = id,
        kind = TrackerKind.HIT_DICE,
        name = "Hit Dice",
        value = value,
        total = total,
        // The fact decision 17 records: the property carries no reset field. Every assertion in
        // this group depends on it, so the fixture states it rather than relying on the default.
        reset = null,
        dieSize = size,
    )

    private val multiclass = sabriel.copy(
        hitDice = listOf(hitDie("hd8", "d8", value = 3, total = 5), hitDie("hd10", "d10", value = 1, total = 2)),
    )

    @Test
    fun `hit-dice rows reach their own section with the die size intact`() {
        val rows = map(board = multiclass).hitDice

        assertEquals(listOf("hd8", "hd10"), rows.map { it.propertyId })
        assertEquals(listOf("d8", "d10"), rows.map { it.dieSize })
        assertEquals(listOf(3, 1), rows.map { it.value })
        assertEquals("no reset field means no badge", listOf(null, null), rows.map { it.resetLabel })
    }

    /**
     * **Decision 19's pin: hit dice must never appear in the rest dialog's restore list.**
     *
     * *"The SERVER restores half … the app predicts NOTHING; the rest dialog's restore list stays
     * reset-rule-driven (hit dice have no reset rule and would be wrong in it)."*
     *
     * Two independent guards, and this asserts the stronger one. `rowsRestoredBy` reads
     * `slots + resources`, so a hit-dice row is out because it is in neither list — not merely
     * because it happens to carry no reset rule. The weaker guard is checked too, in the second
     * assertion: even a row that somehow acquired a long-rest rule stays out, because the *list*
     * is what excludes it.
     *
     * The failure this prevents is a dialog that promises a restoration and then a server that
     * performs a different one — half the dice, highest first, scaled by
     * `hitDiceResetMultiplier`. A player reading "Hit Dice d8 — 3 / 5" in a "restored to full"
     * list and getting 4 back would reasonably file it as a bug in the app.
     */
    @Test
    fun `a long rest's restore list never names a hit-dice row`() {
        val state = map(board = multiclass)

        val restored = state.rowsRestoredBy(RestKind.LONG).map { it.propertyId }
        assertFalse("hd8" in restored)
        assertFalse("hd10" in restored)
        assertTrue("the rows the sheet DOES restore are still listed", "slot1" in restored)

        // The second guard, in isolation: the list membership excludes them even if the reset
        // rule somehow did not.
        val misTagged = sabriel.copy(
            hitDice = listOf(hitDie("hd8", "d8", 3, 5).copy(reset = ResetRule.LONG_REST)),
        )
        assertFalse("hd8" in map(board = misTagged).rowsRestoredBy(RestKind.LONG).map { it.propertyId })
    }

    /**
     * Decision 17: *"FR-22 direct entry applies"*.
     *
     * The key prefix names the *shape* of the target — a counted row with a ceiling — rather than
     * the section it was drawn in, which is why hit dice join the resource lookup instead of
     * getting a fourth prefix. The ceiling matters: a player typing 9 into a 5-die row must be
     * clamped, and the clamp is `max`.
     */
    @Test
    fun `direct entry resolves a hit-dice row against its own ceiling`() {
        val target = map(board = multiclass).directEntryTarget(DirectEntryKeys.resource("hd8"))!!

        assertEquals(DirectEntryKind.RESOURCE, target.kind)
        assertEquals("hd8", target.propertyId)
        assertEquals(3, target.current)
        assertEquals(5, target.max)
    }

    /** A character with hit dice and nothing else is not an empty board. */
    @Test
    fun `hit dice alone make the board non-empty`() {
        val onlyDice = TrackerBoard(hitDice = listOf(hitDie("hd8", "d8", 3, 5)))

        assertFalse(map(board = onlyDice).isEmpty)
    }
}
