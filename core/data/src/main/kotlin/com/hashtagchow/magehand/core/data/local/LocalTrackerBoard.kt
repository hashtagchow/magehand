package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.toTrackedResource

/**
 * Turns one local character's stored rows into the **same** [TrackerBoard] the tracker
 * screen already renders (docs/design/09-local-characters.md decision 5: the screen is
 * reused, not forked).
 *
 * Pure, for the same reason [com.hashtagchow.magehand.core.data.tracker.TrackerEngine] is
 * pure: no I/O, no coroutines, no clock, same input → same output. That is what lets the
 * board be tested without a database and a coroutine scope, and it is why the two sources —
 * a DiceCloud sheet and a form the player filled in — can be checked against each other at
 * the type the UI consumes rather than at the wire.
 *
 * ### What is deliberately absent from a local board
 *
 * - **Toggles** (`activeToggles`). 09 decision 4: "no toggles for local characters". The
 *   form has no field for one, so there is nothing to render and nothing to flip. FR-6 makes
 *   the section hidden by default anyway, which is what makes this consistent rather than a
 *   special case.
 * - **Defenses**. Read off `damageMultiplier` properties on a DiceCloud sheet; 09's
 *   explicit out-of-scope list keeps them out of 1.1's form.
 * - **Temp HP**. Discovered on a sheet, not offered by the form.
 * - **Concentration**. Property-driven on the server path (an enabled toggle or buff named
 *   "concentration"); with no toggles there is no source for it.
 *
 * Each of those is an *empty* board field rather than a rendering exception, so the tracker
 * makes the section absent by the same rule it already uses for a character that has none.
 */
object LocalTrackerBoard {

    /**
     * @param character `null` while the character's row has not loaded yet, or after it has
     *   been deleted underneath an open tracker — both render [TrackerBoard.EMPTY] rather
     *   than a board with rows and no HP.
     */
    fun build(character: LocalCharacter?, rows: List<LocalTrackerRow>): TrackerBoard {
        if (character == null) return TrackerBoard.EMPTY

        val resources = rows.sortedWith(ROW_ORDER).map { it.toTrackedResource() }
        val items = resources.filter { it.kind == TrackerKind.ITEM }

        return TrackerBoard(
            hp = hitPointsRow(character),
            slots = resources.filter { it.kind == TrackerKind.SPELL_SLOT },
            resources = resources.filter { it.kind == TrackerKind.RESOURCE },
            // Every local item is pinned by construction (see `toTrackedResource`): the
            // player typed it in, so it belongs on the tracker. `allItems` still carries the
            // full list because the customize sheet reads it.
            pinnedItems = items,
            allItems = items,
        )
    }

    /**
     * HP as the tracker's first row, built from the character rather than from a stored row.
     *
     * [HP_ROW_ID] is a fixed id and not a minted one: it is the identity the tracker keys
     * its HP row on, and it must be the same across every rebuild of the board or a rebuild
     * would look like a different row appearing. Nothing writes to it by id — HP writes go
     * to `local_characters.currentHp` — so it cannot collide with a real row id in any query.
     */
    private fun hitPointsRow(character: LocalCharacter): TrackedResource = TrackedResource(
        propertyId = HP_ROW_ID,
        kind = TrackerKind.HIT_POINTS,
        name = HP_ROW_NAME,
        value = character.currentHp,
        total = character.maxHp,
        reset = null,
        sortOrder = 0,
    )

    /** The HP row's stable identity. */
    const val HP_ROW_ID: String = "local:hitPoints"

    /**
     * Matches DiceCloud's own name for the attribute, so the row reads identically whichever
     * kind of character is open — 09 decision 5's "same screen" claim held at the string.
     */
    const val HP_ROW_NAME: String = "Hit Points"

    /**
     * The player's order, then label as the tie-breaker.
     *
     * `sortIndex` plays the part the server's `order` plays on a discovered sheet, and it is
     * the **only** ordering mechanism for local rows (09 decision 8's "ONE mechanism, not
     * two"): `tracker_prefs` is keyed by `(accountId, creatureId, propertyId)` and a local
     * character has no account, so reusing it would need exactly the sentinel account
     * decision 1 forbids.
     */
    private val ROW_ORDER: Comparator<LocalTrackerRow> =
        compareBy<LocalTrackerRow> { it.sortIndex }.thenBy { it.label }
}
