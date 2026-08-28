package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow

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
 * rule)"*. The gate is [ActionBoard.isEmpty], which is the same one `serverPaneSurfaces` reads.
 *
 * ### What a local action board deliberately does not have
 *
 * - **Spells** ([ActionBoard.spells]). The local model has no spell, no level and no preparation
 *   — 09's out-of-scope list keeps them out — so there are no spell sections and no upcast picker.
 * - **Spell lists** ([ActionBoard.spellLists]). The DC and ability modifier a `spellList` header
 *   prints are numbers a *server* computes; there is nothing here to compute them from, and
 *   inventing a save DC would be exactly the class of client arithmetic 16 decision 4 forbids.
 * - **Damage, attack bonuses, casting times, ranges.** All rollups off a sheet this character
 *   does not have. An action row is a label, some prose, a cost and a count.
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
            .filter { it.kind == LocalRowKind.ACTION }
            .sortedWith(ROW_ORDER)
            .map { it.toActionEntry(byId) }
        return ActionBoard(actions = actions)
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
        val uses = if (total > 0) ActionUses(max = total, used = (total - current).coerceAtLeast(0)) else null
        return ActionEntry(
            propertyId = id,
            name = label,
            type = ActionType.ACTION,
            // Read off the same nullable column FR-8 gave item notes — see the v7 migration's
            // KDoc for why this feature added no second text column.
            description = description?.takeIf { it.isNotBlank() },
            usesLeft = uses?.remaining,
            usesMax = uses?.max,
            cost = costFrom(byId),
            uses = uses,
            sortOrder = sortIndex,
        )
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
}
