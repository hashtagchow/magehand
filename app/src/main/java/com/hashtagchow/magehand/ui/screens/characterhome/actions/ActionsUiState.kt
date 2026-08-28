package com.hashtagchow.magehand.ui.screens.characterhome.actions

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader

/**
 * The Actions surface's rendered state (docs/design/16-actions-and-feed.md decisions 3–6, FR-26).
 *
 * ### Why this layer exists between [ActionBoard] and the screen
 *
 * `InventoryUiState`'s reason exactly: the board is *what the character has*, this is *what the
 * screen draws* — sectioned, filtered, collapsed. Keeping them apart is what lets the grouping
 * and the search be unit-tested at all, because `:app` has no Compose test harness (see
 * `StartDestinationNavigationTest`), so anything that only exists inside a `@Composable` can only
 * be checked by reading it.
 *
 * ### Nothing here can write
 *
 * 16 decision 7. There is no `onX` lambda on this state and no intent anywhere in this package —
 * compare `InventoryUiState`, which carries an actions bundle because the inventory tab has
 * steppers. The only gesture this surface has is *look at it*, plus collapse and search, both of
 * which are local view state.
 */
data class ActionsUiState(
    val creatureId: String = "",
    /** The `spellList` headers — decision 4's DC and ability modifier. Never a per-spell bonus. */
    val spellLists: List<SpellListHeader> = emptyList(),
    /** Spell-level sections then action-group sections, in that order. See [toActionsUiState]. */
    val sections: List<ActionSection> = emptyList(),
    /** The live filter text (decision 6). Empty means inactive. */
    val query: String = "",
    /**
     * Whether the filter field is drawn at all — decision 6's *"once spells+actions ≥ 15"*.
     *
     * Computed from the **unfiltered** row count and carried on the state rather than recomputed
     * in the composable, so that typing a query which matches two rows does not make the field
     * that is being typed into disappear. That is the same self-erasing-control bug FR-24's
     * threshold has, solved the same way.
     */
    val showsFilter: Boolean = false,
) {
    /** How many rows match right now — the live region reads this (decision 6 / FR-24). */
    val matchCount: Int get() = sections.sumOf { it.rows.size }

    /** True while the player has typed something. */
    val filterActive: Boolean get() = query.isNotBlank()

    /** Decision 9's honest empty state: the character genuinely has nothing to act with. */
    val isEmpty: Boolean get() = sections.isEmpty() && !filterActive

    /** FR-24 decision 16's "No … match" line, which prints the query back. */
    val showsNoMatches: Boolean get() = filterActive && matchCount == 0

    companion object {
        /**
         * Decision 6's threshold: the field appears once the combined list reaches this many rows.
         *
         * Fifteen because that is FR-24's number for the inventory and this is explicitly *"the
         * FR-24 field pattern … same glance semantics"*. A surface that showed a search box at a
         * different size than the tab beside it would be teaching two rules for one gesture.
         */
        const val FILTER_THRESHOLD = 15
    }
}

/**
 * One collapsible section of the list (decision 3: *"All section headers default-collapsible per
 * the standing convention (spell-level sections too)"*).
 *
 * A sealed [title] rather than a `@StringRes` plus a nullable argument, because the three kinds
 * genuinely differ: a cantrip header takes no argument, a level header takes one, and a group
 * header is an enum lookup. Modelling that as one resource id and an `Int?` would make "which
 * sections have an argument" a fact every call site had to remember.
 */
data class ActionSection(
    /**
     * Stable identity for the collapse set and the test tags — `spell:0`, `spell:3`,
     * `group:ATTACKS`.
     *
     * Stable across a filter, deliberately: collapsing a section, searching, and clearing the
     * search must leave that section still collapsed. A key derived from the *rendered* rows
     * would change as the filter narrowed and silently reopen everything.
     */
    val key: String,
    val title: ActionSectionTitle,
    val rows: List<ActionRow>,
    val collapsed: Boolean = false,
)

/** What a section header says. See [ActionSection]. */
sealed interface ActionSectionTitle {
    /** Level 0. "Cantrips", never "Level 0" — see `actions_spell_cantrips` in strings.xml. */
    data object Cantrips : ActionSectionTitle

    /** Levels 1+. */
    data class SpellLevel(val level: Int) : ActionSectionTitle

    /** One of decision 3's five action groups. */
    data class Group(val group: ActionGroup) : ActionSectionTitle
}

/**
 * A row in a section.
 *
 * Sealed over the two entry types rather than flattened into one row type, because 16 decision 4
 * gives them **different content** — and one of those differences is load-bearing: a spell has no
 * hit bonus and an action does. A merged row type would need a nullable `attackRoll` that is
 * always null for spells, which is precisely the field [SpellEntry] refuses to have. The sealed
 * split carries that guarantee up into the UI layer instead of losing it at the boundary.
 */
sealed interface ActionRow {
    val key: String
    val name: String

    data class Spell(val entry: SpellEntry) : ActionRow {
        override val key: String get() = entry.propertyId
        override val name: String get() = entry.name
    }

    data class Action(val entry: ActionEntry) : ActionRow {
        override val key: String get() = entry.propertyId
        override val name: String get() = entry.name
    }
}

/**
 * [ActionBoard] → the screen's state (decisions 3 and 6).
 *
 * ### Order of operations, and why filtering comes last
 *
 * Section, then filter, then collapse. Filtering *within* the built sections rather than over the
 * flat list is what keeps a match's header with it — a player searching "fire" sees
 * *"Level 3 → Fireball"*, not a headerless row they cannot place. Empty sections are then
 * dropped, so the filtered view has no bare headers either.
 *
 * ### An active filter forces every section open
 *
 * Decision 6: *"expands collapsed level sections while active"*. Without it, searching would
 * report "3 results match" over three collapsed headers showing nothing — the control would
 * appear broken at exactly the moment it worked. [collapsedKeys] is not modified, so clearing the
 * query restores the player's collapse state exactly, which is FR-24's *"clearing restores the
 * stored layout exactly"* applied here.
 *
 * @param collapsedKeys the [ActionSection.key]s the player has collapsed. View state, not a
 *   preference: see `ActionsScreen`'s `rememberSaveable`.
 */
fun toActionsUiState(
    creatureId: String,
    board: ActionBoard,
    query: String = "",
    collapsedKeys: Set<String> = emptySet(),
): ActionsUiState {
    val spellSections = board.spells
        // `groupBy` preserves first-encounter order, and the board arrives level-sorted, so the
        // groups come out in level order without a second sort that could disagree with the
        // engine's. Decision 3's stable sort is what makes that true; see `ActionEngine.build`.
        .groupBy { it.level }
        .map { (level, spells) ->
            ActionSection(
                key = "$SPELL_KEY_PREFIX$level",
                title = if (level == 0) {
                    ActionSectionTitle.Cantrips
                } else {
                    ActionSectionTitle.SpellLevel(level)
                },
                rows = spells.map { ActionRow.Spell(it) },
            )
        }

    val actionSections = board.actions
        .groupBy { it.group }
        .map { (group, actions) ->
            ActionSection(
                key = "$GROUP_KEY_PREFIX${group.name}",
                title = ActionSectionTitle.Group(group),
                rows = actions.map { ActionRow.Action(it) },
            )
        }

    return ActionsUiState(
        creatureId = creatureId,
        spellLists = board.spellLists,
        sections = spellSections + actionSections,
        // From the UNFILTERED count, so typing a narrow query cannot hide the field being
        // typed into. See [ActionsUiState.showsFilter].
        showsFilter = board.rowCount >= ActionsUiState.FILTER_THRESHOLD,
    ).withView(query = query, collapsedKeys = collapsedKeys)
}

/**
 * Applies the two **view-local** layers — the search query and the collapse set — to sections
 * that were already built from the board.
 *
 * ### Why this is separate from [toActionsUiState]
 *
 * The two halves have different owners and different lifetimes. The sections come from the
 * character's data and are produced once per board emission in the ViewModel; the query and the
 * collapse set are `rememberSaveable` state belonging to the composable, and change on a keystroke
 * with no new board. Splitting them means a keystroke re-maps a list instead of re-deriving
 * everything from the sheet, and it means `ActionsScreen` can own its view state without the
 * ViewModel growing two fields that are not about the character at all.
 *
 * Idempotent and total: calling it with an empty query and an empty set returns the sections
 * unchanged, which is what makes it safe for [toActionsUiState] to route through it
 * unconditionally rather than branching.
 */
fun ActionsUiState.withView(query: String, collapsedKeys: Set<String>): ActionsUiState {
    val trimmed = query.trim()
    val filtering = trimmed.isNotEmpty()

    val shown = sections
        .map { section ->
            section.copy(
                rows = if (filtering) section.rows.filter { it.matches(trimmed) } else section.rows,
                // Forced open while filtering — see [toActionsUiState]'s KDoc. `collapsedKeys` is
                // read, never written, so the player's state survives the search untouched.
                collapsed = !filtering && section.key in collapsedKeys,
            )
        }
        .filter { it.rows.isNotEmpty() }

    return copy(sections = shown, query = query)
}

/**
 * Decision 6's *"same glance semantics"* as FR-24: a case-insensitive substring of the **name**.
 *
 * Name only, and not the description. FR-24's field is a way to find a row you already know is
 * there, and a description search would surface a spell because the word appears in its rules
 * text — which reads as a false positive to a player who typed a spell name. The detail sheet is
 * where the text lives; the list is where the names are.
 */
private fun ActionRow.matches(query: String): Boolean = name.contains(query, ignoreCase = true)

private const val SPELL_KEY_PREFIX = "spell:"
private const val GROUP_KEY_PREFIX = "group:"
