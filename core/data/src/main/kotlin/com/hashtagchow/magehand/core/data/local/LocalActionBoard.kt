package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.WeaponMastery

/**
 * Turns one local character's [LocalRowKind.ACTION] rows into the **same** [ActionBoard] the
 * DiceCloud Actions surface already renders (FR-29, docs/design/18-table-pack.md decisions 1–4).
 *
 * Pure, for [LocalTrackerBoard]'s reason exactly: no I/O, no coroutines, no clock, same input →
 * same output. That is what lets the board be checked against the server engine's output at the
 * type the UI consumes rather than at the wire, and it is why 09 decision 5's "reuse the screen,
 * do not fork it" claim can be made about a third surface without a second screen existing.
 *
 * ### 16 decision 1's local exclusion is **retired** here
 *
 * docs/design/16-actions-and-feed.md decision 1 read *"Local characters: no Actions surface in v1
 * (no local model)"*, and that was true of the reason as well as of the conclusion — there was
 * nothing on a local character that could be an action. 18 decision 1 adds the model, so the
 * exclusion has nothing left to stand on and 18 decision 3 retires it in as many words: *"the
 * Actions tab/pane appears for a local character when ≥1 action row exists (same discovery-gating
 * rule)"*.
 *
 * **That gate is itself retired by FR-49** (20 decision 1): the local Actions tab is always
 * present, because it now owns the Add that fills it, so nothing reads [ActionBoard.isEmpty] for
 * a local character any more — see `localPaneSurfaces`. The board being empty is now an *empty
 * state* rather than a missing surface, which is the whole of what changed.
 *
 * ### FR-49: spells and attacks arrive, and one of the exclusions below survives
 *
 * docs/design/20-local-spells-and-attacks.md decisions 1, 2, 8 and 9 add two row kinds —
 * [LocalRowKind.SPELL] and [LocalRowKind.ATTACK] — so this board now emits [ActionBoard.spells]
 * as well, and an attack lands under the **Attacks** group header rather than under Actions. The
 * exclusion that survives is the one below about `spellLists`, and it survives for its own reason
 * rather than by inertia: nothing here can compute a save DC.
 *
 * ### What a local action board deliberately does not have
 *
 * - **Spell lists** ([ActionBoard.spellLists]). The DC and ability modifier a `spellList` header
 *   prints are numbers a *server* computes; there is nothing here to compute them from, and
 *   inventing a save DC would be exactly the class of client arithmetic 16 decision 4 forbids.
 *   20 decision 9 keeps it that way — *"no spell-list header (no DC / ability mod to compute)"*.
 * - **Attack bonuses** ([ActionEntry.attackRoll]) and **damage rollups** ([ActionEntry.damage]).
 *   20 decision 8: a local character records no proficiencies and there is no sheet to resolve a
 *   die against, so an attack's damage and properties are **text** ([ActionEntry.damageText],
 *   [ActionEntry.properties]) and no number is derived from either.
 * - **Preparation.** 20 decision 9: a local spell is *known* by being added, so every one is built
 *   with `alwaysPrepared = true` and [SpellEntry.showsUnpreparedBadge] is false for all of them.
 *   A `prepared` toggle would need a spell list to be prepared *against*.
 * - **`inactive`.** A local row is either there or deleted; there is no ancestor to be switched
 *   off by. So [ActionEntry.isUsable] here reduces to *"charges left and the cost is funded"*,
 *   which is the whole of 17 decision 1 minus the two clauses that need a sheet.
 */
object LocalActionBoard {

    /**
     * Builds the board.
     *
     * @param rows **every** row of the character, not only the action ones: a cost line names
     *   another row by id and has to be able to find it, so the non-action rows are the index
     *   rather than noise to be filtered out first.
     */
    fun build(rows: List<LocalTrackerRow>): ActionBoard {
        val byId = rows.associateBy { it.id }
        val actions = rows
            .filter { it.kind == LocalRowKind.ACTION || it.kind == LocalRowKind.ATTACK }
            .sortedWith(ROW_ORDER)
            .map { it.toActionEntry(byId) }
        // Sorted by **level first**, then by the player's own order within a level — which is the
        // opposite emphasis from `actions` above, and is the server path's rule rather than a new
        // one: `toActionsUiState` groups spells by level and relies on `groupBy`'s
        // first-encounter ordering to emit the sections in level order (16 decision 3's stable
        // sort, see `ActionEngine.build`). A list sorted only by `sortIndex` would produce
        // *"Level 3 · Cantrips · Level 1"* headers for a player who added Fireball first.
        val spells = rows
            .filter { it.kind == LocalRowKind.SPELL }
            .sortedWith(SPELL_ORDER)
            .map { it.toSpellEntry(byId) }
        return ActionBoard(actions = actions, spells = spells)
    }

    /**
     * One action row → the entry the shared surface draws.
     *
     * ### Why every local action carries [ActionType.ACTION]
     *
     * 18 decision 3: *"Grouping: one 'Actions' section (no actionType taxonomy locally)"*. The
     * shared sectioning code (`toActionsUiState`) groups by [ActionEntry.group], so producing one
     * section means producing one group — and the group whose header reads "Actions" is the one
     * [ActionType.ACTION] maps to.
     *
     * The honest alternative, `type = null`, is what the server path uses for an `actionType` it
     * has never heard of, and it files the row under **Other**. That would be wrong twice over
     * here: "Other" is the group defined by *not being* the four named ones, and a local action is
     * not an unclassifiable row — it is the only kind of row this model has. The type is not a
     * claim read off a wire that said nothing; it is the single group the design specifies,
     * carried on the entry so the grouping rule stays in one place instead of being forked for
     * local characters.
     *
     * ### Uses
     *
     * `total == 0` is *unlimited* (see [LocalTrackerRow.total]) and yields a `null`
     * [ActionEntry.uses] — the same value `ActionEngine.usesFor` produces for a server action that
     * states no `uses`, so the two sources agree about the same fact. Otherwise the pair is
     * `max = total`, `used = total − current`: local rows store what is *left*, and [ActionUses]
     * stores what has been *spent*, so the conversion happens once, here.
     *
     * [ActionEntry.usesLeft] / [usesMax] are filled in as well, and that is not a duplicate of
     * [uses]. On the server path those two are the lagging rollup the list row prints while
     * [ActionUses] is the synchronous pair the Use gate reads (see [ActionUses]' own KDoc for the
     * double-spend that split prevents). Locally there is no lag and no second source — one Room
     * column answers both questions — so the two agree by construction, which is the *strongest*
     * form of the rule rather than an exception to it.
     */
    private fun LocalTrackerRow.toActionEntry(byId: Map<String, LocalTrackerRow>): ActionEntry {
        val uses = usesOrNull()
        val isAttack = kind == LocalRowKind.ATTACK
        return ActionEntry(
            propertyId = id,
            name = label,
            // FR-49 decision 1: an ATTACK row is `ActionType.ATTACK`, so it files under the
            // **Attacks** group header rather than under Actions. Everything the paragraph below
            // says about ACTION is unchanged; this is the one kind that names a second group, and
            // it names it because the row *is* an attack rather than because a wire said so.
            type = if (isAttack) ActionType.ATTACK else ActionType.ACTION,
            // Read off the same nullable column FR-8 gave item notes — see the v7 migration's
            // KDoc for why this feature added no second text column.
            description = description?.takeIf { it.isNotBlank() },
            // 20 decision 8's two text facts, on an attack row only. `attackRoll` and `damage`
            // (the rollup list) stay absent — see `ActionEntry.damageText` for the argument, and
            // note that `null` here is not "we could not compute it" but "there is nothing to
            // compute": a local character records no proficiencies.
            damageText = damage?.takeIf { isAttack && it.isNotBlank() },
            properties = properties?.takeIf { isAttack && it.isNotBlank() },
            usesLeft = uses?.remaining,
            usesMax = uses?.max,
            cost = costFrom(byId),
            uses = uses,
            // FR-47's badge, lifted out of the property string rather than stored twice — see
            // [masteryFrom].
            mastery = properties?.takeIf { isAttack }?.let(::masteryFrom),
            sortOrder = sortIndex,
        )
    }

    /**
     * One spell row → the entry the shared surface draws (FR-49, 20 decisions 2, 5 and 9).
     *
     * ### `alwaysPrepared = true`, and that is the whole of "known, not prepared"
     *
     * 20 decision 9: a local spell is known **by being added**, so there is no prepared flag, no
     * spell list to be prepared against, and [SpellEntry.showsUnpreparedBadge] must be false for
     * every one of them. That badge is `!prepared && !alwaysPrepared`, so exactly one of the two
     * fields has to be set — and `alwaysPrepared` is the honest one. `prepared = true` would be
     * this board answering a question the local model never asks (*"has the player prepared it
     * today?"*) with a yes; `alwaysPrepared` says *"this spell needs no preparation"*, which is
     * precisely what the absence of a preparation model means.
     *
     * The consequence is worth stating because it is load-bearing rather than cosmetic: with the
     * badge off, [SpellEntry.isUsable] reduces to *"charges left and the cost is funded"*, so
     * [SpellEntry.useTarget] is non-null and the detail sheet can offer a Cast at all. 17 decision
     * 2's gate is still the gate; a local spell simply passes it.
     *
     * ### Uses versus slots, decided here and read by `UseTarget.Spell.needsSlot`
     *
     * `total == 0` is unlimited (see [LocalTrackerRow.total]) and yields a `null`
     * [SpellEntry.uses] — which is what makes a leveled spell a *slot* cast. A spell with uses is
     * 20 decision 4's **innate** casting: it spends its own charge and no slot, and `needsSlot`
     * follows from `level > 0` together with the absence of uses at the one place that reads it,
     * `LocalOpenCharacter.castSpell`. Nothing here decides what a cast spends; this only publishes
     * the two facts that decide it.
     */
    private fun LocalTrackerRow.toSpellEntry(byId: Map<String, LocalTrackerRow>): SpellEntry {
        val uses = usesOrNull()
        return SpellEntry(
            propertyId = id,
            name = label,
            // A SPELL row's level is required by the form (0..9), so a null here can only be a
            // row written by something that bypassed it. Read as a cantrip, which is the reading
            // that spends nothing — the safe direction for a value that should not exist.
            level = spellLevel ?: 0,
            concentration = concentration,
            ritual = ritual,
            // See the KDoc. Not `prepared`.
            alwaysPrepared = true,
            castingTime = castingTime?.takeIf { it.isNotBlank() },
            range = range?.takeIf { it.isNotBlank() },
            components = components?.takeIf { it.isNotBlank() },
            duration = duration?.takeIf { it.isNotBlank() },
            higherLevels = higherLevels?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            cost = costFrom(byId),
            uses = uses,
            // 20 decision 4's innate case: a local spell with charges spends them *instead of* a
            // slot, which the form collects and a DiceCloud sheet cannot state — see
            // [UseTarget.Spell.spendsOwnUses]. This is the one place in the app that sets it.
            spendsOwnUses = uses != null,
            sortOrder = sortIndex,
        )
    }

    /**
     * The row's uses, or `null` for an unlimited one — [LocalTrackerRow.total]'s `0`.
     *
     * Shared by the three Actions-surface kinds because they share the convention, and named
     * rather than repeated for the reason FR-29's own version gives: local rows store what is
     * *left* and [ActionUses] stores what has been *spent*, so the conversion happens once.
     */
    private fun LocalTrackerRow.usesOrNull(): ActionUses? =
        if (total > 0) ActionUses(max = total, used = (total - current).coerceAtLeast(0)) else null

    /**
     * FR-47's [WeaponMastery] lifted out of an attack row's property string — *"Versatile (1d10),
     * Mastery: Sap"* → `WeaponMastery("Sap")`.
     *
     * ### One source, two renderings
     *
     * 20 decision 2 asks for the mastery to reach *"FR-47's badge path … same regex, same badge
     * text"*, and the row stores it inside [LocalTrackerRow.properties] rather than in a column of
     * its own. That is deliberate: the properties string is a **fact the player can edit**, and a
     * second column would be a copy of one word that the editor would then have to keep in step
     * with the sentence beside it. Reading it back out means the badge and the fact can never
     * disagree, and a player who deletes the mastery from the string deletes the badge — which is
     * what editing a property list should do.
     *
     * [WeaponMastery.text] is always `null` here, and that is the honest answer rather than a gap:
     * the server path's text comes from the sheet's own mastery feature, and the SRD weapon table
     * this catalog is drawn from names the mastery without restating the rule. FR-47 R7 already
     * covers the case in as many words — *"a heading with no body is still true"* — so the detail
     * sheet draws the heading alone.
     *
     * A `Mastery:` with nothing after it produces `null` rather than an empty badge, matching
     * `ActionEngine`'s own rule that a mastery with no word is not one.
     */
    private fun masteryFrom(properties: String): WeaponMastery? {
        val name = MASTERY_ENTRY.find(properties)?.groupValues?.get(1)?.trim().orEmpty()
        return if (name.isEmpty()) null else WeaponMastery(name = name)
    }

    /**
     * 18 decision 1's **cost**: *"a reference to another local row by id + an amount"*.
     *
     * ### Which of [ActionCost]'s two lists a line lands in
     *
     * By the cost row's own kind: an item goes in [ActionCost.items] and a slot or a resource in
     * [ActionCost.attributes]. Nothing downstream distinguishes them — `ActionCost.lines` is the
     * concatenation and the UI renders both identically — so this is a naming decision rather than
     * a behavioural one, and it is made this way because the server path's split is exactly the
     * same distinction (`attributesConsumed` versus `itemsConsumed`). Putting a local "Arrows: 2"
     * under `attributes` would have made the two sources describe the same cost differently for no
     * reason a reader could recover.
     *
     * A cost naming an [LocalRowKind.ACTION] cannot occur — 18 decision 2 fences chaining out and
     * `LocalCharacterForm.validate` refuses it — but a cost naming a **deleted** row very much can
     * (there is no `FOREIGN KEY`; see [LocalTrackerRow.costRowId]). That line is dropped entirely
     * rather than rendered with a `null` available: the line's whole content is a *name*, and a
     * row that is gone has none. Dropping it also lands the action on the permissive side of
     * [CostLine.satisfied]'s asymmetry — the Use stays offered — which is the same direction the
     * server path errs in for a cost it cannot resolve, and for the same stated reason.
     */
    private fun LocalTrackerRow.costFrom(byId: Map<String, LocalTrackerRow>): ActionCost {
        val rowId = costRowId ?: return ActionCost.FREE
        val amount = costAmount ?: return ActionCost.FREE
        val costRow = byId[rowId] ?: return ActionCost.FREE
        val line = CostLine(name = costRow.label, amount = amount, available = costRow.current)
        return if (costRow.kind == LocalRowKind.ITEM) {
            ActionCost(items = listOf(line))
        } else {
            ActionCost(attributes = listOf(line))
        }
    }

    /**
     * The player's order, then label — [LocalTrackerBoard]'s `ROW_ORDER`, restated for the one
     * list that board does not produce.
     *
     * Not shared with it: that comparator is private to a board whose ordering rule is 09 decision
     * 8's *"ONE mechanism"* for the tracker, and this is the same rule reached independently for a
     * different surface. Two callers of one `sortIndex` is the point; one comparator reaching
     * across two files to say so is not worth the coupling.
     */
    private val ROW_ORDER: Comparator<LocalTrackerRow> =
        compareBy<LocalTrackerRow> { it.sortIndex }.thenBy { it.label }

    /**
     * [ROW_ORDER] with the spell's level in front of it — see [build] for why the emphasis is the
     * other way round here.
     *
     * A level-less row (impossible through the form; see [toSpellEntry]) sorts as a cantrip, which
     * is where [toSpellEntry] reads it, so the list and the section headers agree about a row
     * neither of them should ever see.
     */
    private val SPELL_ORDER: Comparator<LocalTrackerRow> =
        compareBy<LocalTrackerRow> { it.spellLevel ?: 0 }
            .thenBy { it.sortIndex }
            .thenBy { it.label }

    /**
     * `Mastery: Sap` anywhere in a property string, case-insensitively, up to the next comma.
     *
     * Bounded by `,` rather than by end-of-string so that *"Mastery: Sap, Heavy"* yields `Sap` and
     * not `Sap, Heavy` — a property list is a comma-separated sentence and the mastery is one term
     * of it, wherever the player put it.
     */
    private val MASTERY_ENTRY = Regex("""Mastery\s*:\s*([^,]*)""", RegexOption.IGNORE_CASE)
}
