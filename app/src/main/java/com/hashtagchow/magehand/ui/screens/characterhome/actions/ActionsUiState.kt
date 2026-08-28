package com.hashtagchow.magehand.ui.screens.characterhome.actions

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader
import com.hashtagchow.magehand.core.model.SpellSlotOption
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.core.model.spellSlotOptions

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
 * ### One gesture can write, and it is expressed as a type rather than as a lambda
 *
 * 16 decision 7 was *"nothing here can write"*. FR-28 adds exactly one gesture — Use — and adds it
 * in the shape that keeps 17 decision 2's *"ABSENT, not disabled"* structural: this state still
 * carries no `onX` lambda, and the affordance is [ActionDetailState.use], an
 * [UseTarget]-carrying value that is **`null` for any row the app has decided is not usable**.
 * There is no path from a row to a use that does not go through a non-null one of those, so an
 * unprepared spell has nothing to press rather than something greyed out.
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
    /**
     * The character's live spell-slot rows, for 17 decision 3's picker.
     *
     * The raw [TrackedResource]s rather than a pre-filtered option list, because the filter needs
     * the spell's level and this state does not know which spell the player will open. The
     * derivation is `spellSlotOptions`, called per detail sheet — see [ActionDetailState].
     *
     * These are the tracker's own rows, so the picker and the pips on the Tracker tab are reading
     * one number. A picker fed from anywhere else would be a second opinion about how many slots
     * are left.
     */
    val spellSlots: List<TrackedResource> = emptyList(),
    /**
     * 17 decision 5's single-flight, mirrored from `OpenCharacter.usesInFlight`.
     *
     * Mirrored, not owned: the latch that actually drops a second call lives in `:core:data`, and
     * this is only what makes the button *look* the way it behaves. A `remember` in the sheet
     * would have been a guard that resets on recomposition — see that property's KDoc.
     */
    val usesInFlight: Set<String> = emptySet(),
    /** Whether a tap could reach the server at all. Dims Use rather than swallowing the tap. */
    val canWrite: Boolean = false,
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
 * One row, expanded — the detail sheet (16 decision 4's *"tap → detail sheet"*, grown by 17
 * decision 1 into the surface that answers *can I use this, and what will it cost?*).
 *
 * ### Derived per frame from the list, never remembered
 *
 * The sheet holds a **property id** and this is re-derived from the live sections on every
 * emission — see [ActionsUiState.detailFor]. That is not a performance shrug; it is what makes
 * the three truths true. Cost, uses and usability all move when the sheet moves, and a detail
 * state captured at tap time would keep saying "1 of 1 uses left" through the whole settle window
 * after the use that spent it — which is the very lag 17 decision 1 is written against,
 * reintroduced by the UI after the engine went to the trouble of avoiding it.
 *
 * It also means a row that *disappears* — soft-removed on the server, or filtered out of the
 * sheet — resolves to `null`, so the detail closes instead of showing a ghost.
 *
 * @property use `null` when this row has no Use at all: 17 decision 2's unprepared and
 *   switched-off rows, and any row whose cost or charges the app can see are not there. Absent,
 *   not disabled — see [UseAffordance].
 */
data class ActionDetailState(
    val row: ActionRow,
    val use: UseAffordance?,
) {
    val name: String get() = row.name

    /** 17 decision 1's **Cost**, whichever kind of row this is. */
    val cost: ActionCost
        get() = when (val entry = row) {
            is ActionRow.Spell -> entry.entry.cost
            is ActionRow.Action -> entry.entry.cost
        }

    /** 17 decision 1's **Uses**, or `null` for an unlimited row. Never the server's `usesLeft`. */
    val uses: ActionUses?
        get() = when (val entry = row) {
            is ActionRow.Spell -> entry.entry.uses
            is ActionRow.Action -> entry.entry.uses
        }

    /**
     * The prose, `description` before `summary` (16 decision 4's plain text; no markdown in v1).
     *
     * `description` first because it is the rules text and `summary` is DiceCloud's own one-line
     * gloss of it — a reader who opened the detail sheet asked for the long answer.
     */
    val body: String?
        get() = when (val entry = row) {
            is ActionRow.Spell -> entry.entry.description ?: entry.entry.summary
            is ActionRow.Action -> entry.entry.description ?: entry.entry.summary
        }?.takeIf { it.isNotBlank() }

    /**
     * Why the Use is missing, when it is — 17 decision 2's *"dimmed rows explain why in the
     * detail sheet"*.
     *
     * `null` when a Use is offered, so the sheet renders either a button or a sentence and never
     * both. The order matters and is the order a player would fix them in: preparation first (a
     * thing they choose), then the sheet's own switch, then resources, then charges. Only the
     * first applicable reason is given — a list of four problems is not more helpful than the one
     * standing between the player and the tap.
     */
    val unusableReason: UnusableReason?
        get() {
            if (use != null) return null
            return when (val entry = row) {
                is ActionRow.Spell -> when {
                    entry.entry.showsUnpreparedBadge -> UnusableReason.UNPREPARED
                    entry.entry.inactive -> UnusableReason.INACTIVE
                    !entry.entry.cost.satisfied -> UnusableReason.NO_RESOURCES
                    else -> UnusableReason.NO_USES
                }

                is ActionRow.Action -> when {
                    entry.entry.inactive -> UnusableReason.INACTIVE
                    !entry.entry.cost.satisfied -> UnusableReason.NO_RESOURCES
                    else -> UnusableReason.NO_USES
                }
            }
        }
}

/** The four reasons a row offers no Use. See [ActionDetailState.unusableReason]. */
enum class UnusableReason {
    /** `!prepared && !alwaysPrepared` — 17 decision 2, and probe U2's burnt slot. */
    UNPREPARED,

    /** `inactive: true` — the sheet has switched this off, or an ancestor is disabled. */
    INACTIVE,

    /** A consumed attribute or item the sheet does not hold enough of — client-derived. */
    NO_RESOURCES,

    /** `uses.value − usesUsed` has reached zero — client-derived, never `usesLeft`. */
    NO_USES,
}

/**
 * A Use the player may press, with everything the confirm dialog needs (17 decisions 3, 4 and 5).
 *
 * ### This type existing at all IS the gate
 *
 * [ActionsUiState]'s KDoc states it and this is where it is enforced: an instance can only be
 * built from a non-null [SpellEntry.useTarget] / [ActionEntry.useTarget], and those return `null`
 * for a row that fails 17 decision 2. So there is no value of this type for an unprepared spell,
 * and the composable's `use?.let { … }` is not a politeness — it is the only way to reach a Use
 * button in this package.
 *
 * @property inFlight decision 5's single-flight, for the disabled state. The **guard** is the
 *   latch in `:core:data`; this is what stops the player pressing a button that would be dropped.
 * @property slots decision 3's picker contents — already filtered to slots of a high enough level
 *   with charges left, by `spellSlotOptions`. Empty for an action, for a cantrip, and for a
 *   leveled spell whose caster has nothing left to cast it with.
 * @property canWrite false off-LIVE. The button dims rather than the tap being swallowed, per
 *   04's *"connection state is always visible, never a surprise error dialog"*.
 */
data class UseAffordance(
    val target: UseTarget,
    val inFlight: Boolean = false,
    val slots: List<SpellSlotOption> = emptyList(),
    val canWrite: Boolean = true,
) {
    /** Whether the button takes a tap right now. See [inFlight] and [canWrite]. */
    val enabled: Boolean get() = canWrite && !inFlight

    /**
     * Whether the confirm dialog draws the upcast picker (decision 3).
     *
     * A leveled spell only. Note it is `true` even when [slots] is **empty**: a caster with no
     * level-3-or-higher slots left still gets the picker, showing that it is empty, rather than a
     * dialog that quietly omits the one thing they need to know. See `spellSlotOptions` for what
     * the emptiness means, and [confirmDisabled] for what the dialog does about it — B1
     * [architect ruling] overruled the wave's original call to let `doCastSpell` refuse a
     * slotless cast atomically: an omitted `slotId` is the contract's own "server may auto-pick
     * one" case, which is a burned, unchosen slot with no undo.
     */
    val showsSlotPicker: Boolean get() = (target as? UseTarget.Spell)?.needsSlot == true

    /** Whether the honest ritual checkbox is drawn (decision 3). */
    val showsRitual: Boolean get() = (target as? UseTarget.Spell)?.ritual == true

    /** The slot the dialog opens on: the cheapest legal one, which is what most casts want. */
    val defaultSlotId: String? get() = slots.firstOrNull()?.propertyId

    /**
     * B1 [architect ruling], as a pure function so it is unit-testable without a Compose
     * harness (`:app` has none — see this file's own KDoc). `true` for a leveled, non-ritual
     * spell whose picker has nothing in it: the Use row stays reachable ([enabled] is
     * unaffected), but the confirm dialog's own Confirm must refuse the tap rather than send a
     * `castSpell` with no `slotId` chosen.
     *
     * @param ritual the dialog's own live ritual-checkbox state — a leveled spell ticked ritual
     *   needs no slot at all, so an empty picker is moot for it.
     */
    fun confirmDisabled(ritual: Boolean): Boolean = showsSlotPicker && !ritual && slots.isEmpty()
}

/**
 * The row the detail sheet is open on, re-derived from the live sections (see [ActionDetailState]).
 *
 * `null` for an id that is no longer in the list — a filtered-out row, a soft-removed property, a
 * character that finished loading into a different sheet. The screen closes the sheet on `null`
 * rather than freezing the last frame it saw.
 *
 * ### It searches the UNFILTERED sections deliberately
 *
 * …except it cannot: [ActionsUiState.sections] is what the screen has, and after `withView` it is
 * the filtered set. That is the right behaviour anyway, and worth stating so nobody "fixes" it:
 * typing a query that excludes the open row closes the detail sheet, which is the same thing
 * every other list-plus-detail surface in this app does when the row leaves the list.
 */
fun ActionsUiState.detailFor(propertyId: String?): ActionDetailState? {
    val id = propertyId ?: return null
    val row = sections.asSequence().flatMap { it.rows }.firstOrNull { it.key == id } ?: return null

    val target: UseTarget? = when (row) {
        is ActionRow.Spell -> row.entry.useTarget
        is ActionRow.Action -> row.entry.useTarget
    }

    return ActionDetailState(
        row = row,
        use = target?.let {
            UseAffordance(
                target = it,
                inFlight = id in usesInFlight,
                slots = if (it is UseTarget.Spell && it.needsSlot) {
                    spellSlotOptions(spellSlots, it.level)
                } else {
                    emptyList()
                },
                canWrite = canWrite,
            )
        },
    )
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
    /** FR-28 decision 3: the tracker's own slot rows, for the upcast picker. */
    spellSlots: List<TrackedResource> = emptyList(),
    /** FR-28 decision 5, mirrored from `OpenCharacter.usesInFlight`. */
    usesInFlight: Set<String> = emptySet(),
    canWrite: Boolean = false,
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
        spellSlots = spellSlots,
        usesInFlight = usesInFlight,
        canWrite = canWrite,
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
