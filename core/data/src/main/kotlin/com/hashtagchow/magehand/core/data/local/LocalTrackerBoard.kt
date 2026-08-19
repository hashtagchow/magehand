package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.RollModifier
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
 * - **Skills and saving throws.** The *rolls* list is not empty here — see [abilityChecks] —
 *   but it holds the six ability checks and nothing else, because the six scores are the only
 *   thing the form captures. A skill list would need proficiencies, which is a form field and
 *   a schema column, not a rendering decision.
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
            rolls = abilityChecks(character),
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

    /**
     * The six ability checks, derived from the stored scores.
     *
     * ### Why six, and only six
     *
     * A DiceCloud sheet expresses skills and saves as their own properties, computed by the
     * server from proficiencies the form has no field for. A local character has six numbers
     * and nothing else, so the six checks are the complete, honest answer — anything more
     * would mean this app inventing a proficiency list, which is a form change and a schema
     * change, not a rendering decision. Skills and saves for local characters are deliberately
     * out of scope here for the same reason toggles and defenses are (see the class KDoc).
     *
     * ### Where the number comes from
     *
     * [com.hashtagchow.magehand.core.model.abilityModifier], through `AbilityScores.modifier`
     * — the rule itself, not a re-derivation of it. That function's `floorDiv` is why a score
     * of 7 reads −2 and not −1, and this is the second consumer of it rather than a second
     * copy: the reference strip on the same screen prints the same six numbers, and the two
     * disagreeing would be visible on one glance.
     *
     * No advantage: nothing in the local model expresses one. `RollAdvantage.NONE` is not a
     * default standing in for missing data — there is no data to be missing, because there is
     * no local concept of a condition that could grant it.
     *
     * [ROLL_ID_PREFIX] gives each check a fixed id for the same reason [HP_ROW_ID] is fixed:
     * it is the identity the remembered dropdown selection points at, so it has to survive
     * every rebuild of the board *and* every edit of the character through the form. Derived
     * from the enum constant rather than from the score, so changing a score in the form does
     * not silently reset the player's selection.
     */
    private fun abilityChecks(character: LocalCharacter): List<RollModifier> =
        character.abilities.inSheetOrder.mapIndexed { index, (ability, _) ->
            RollModifier(
                id = rollId(ability),
                name = ability.fullName,
                modifier = character.abilities.modifier(ability),
                advantage = RollAdvantage.NONE,
                // Sheet order (STR → CHA), which is the order `inSheetOrder` already yields
                // and the order every 5e sheet prints. The server path's equivalent is the
                // sheet's own `order` field; this is the local sheet's.
                sortOrder = index,
            )
        }

    /** The stable id of one local ability check — see [abilityChecks]. */
    fun rollId(ability: Ability): String = "$ROLL_ID_PREFIX${ability.name}"

    /** Namespaced like [HP_ROW_ID], so a local id can never be mistaken for a Meteor one. */
    const val ROLL_ID_PREFIX: String = "local:check:"

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
