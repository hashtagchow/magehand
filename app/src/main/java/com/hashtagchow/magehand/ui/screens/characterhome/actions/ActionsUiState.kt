package com.hashtagchow.magehand.ui.screens.characterhome.actions

import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader
import com.hashtagchow.magehand.core.model.SpellSlotOption
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.UseTarget
import com.hashtagchow.magehand.core.model.WeaponMastery
import com.hashtagchow.magehand.core.model.spellSlotOptions
import com.hashtagchow.magehand.core.model.withoutMarkdownEmphasis

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
    /**
     * Whether a Use on this character can be **undone** — false for a DiceCloud character, true
     * for an on-device one (FR-29, docs/design/18-table-pack.md decision 4).
     *
     * ### One flag, and it exists to delete a sentence rather than to add one
     *
     * The server's confirm dialog ends with *"Can't be undone. Logged to the party's activity feed
     * and any connected integrations."* — probe U4's finding, and the single most important line in
     * that dialog. Decision 4 makes the local dialog *"lighter than the server's — cost +
     * uses-after, **NO no-undo line** (undo exists; saying otherwise would lie)"*.
     *
     * A screen-level flag rather than something read off the [UseTarget], for
     * `InventoryRowState.isLocal`'s reason exactly: reversibility is a property of the *storage
     * behind the character*, not of the row, and every row on one screen has the same answer.
     * `LocalOpenCharacter.useAction` is where the undo actually lives, and its KDoc carries the
     * asymmetry in full.
     *
     * False by default, which is the safe direction: a screen that forgot to set it shows the
     * warning on a use that could have been undone — a needlessly cautious dialog — rather than
     * omitting it from one that could not.
     */
    val usesAreUndoable: Boolean = false,
    /**
     * `ActionBoard.switchedOffRowCount`, carried through — D2.
     *
     * Board-derived, so it survives [withView]'s `copy` and is the **same** number whether or not
     * a search is running: a query narrows what is *listed*, and how many rows the sheet has
     * switched off is not something a query changes.
     */
    val switchedOffRowCount: Int = 0,
    /**
     * Whether the **board** carries any spell row, before any search narrows the sections
     * (NEW-5, ruled to match WebHand).
     *
     * Board-derived for [switchedOffRowCount]'s reason and read by [showsSpellLists]: a query
     * changes which rows are *listed*, and whether this character casts is not something a query
     * changes.
     */
    val hasSpellRows: Boolean = false,
) {
    /** How many rows match right now — the live region reads this (decision 6 / FR-24). */
    val matchCount: Int get() = sections.sumOf { it.rows.size }

    /** True while the player has typed something. */
    val filterActive: Boolean get() = query.isNotBlank()

    /**
     * Decision 9's honest empty state: the character genuinely has nothing to act with.
     *
     * **And nothing switched off either** (D2). A sheet whose every row is unavailable is not a
     * sheet with nothing on it, and telling the player it is would be the app reporting on its own
     * filter rather than on their character — see [showsNoneAvailable], which takes that case.
     */
    val isEmpty: Boolean get() = sections.isEmpty() && !filterActive && switchedOffRowCount == 0

    /**
     * D2's third state: the sheet **has** rows and none of them is available right now.
     *
     * Distinct from [isEmpty] above and from [showsNoMatches] below, and all three have to be
     * distinct because they answer different questions. *"This character has nothing to act
     * with"* is about the sheet; *"no rows match 'fireb'"* is about the query; this one is about
     * the rows' state, and it is the only one of the three the player can fix by turning something
     * back on in DiceCloud.
     *
     * Not shown while filtering: a query with no matches is [showsNoMatches]' sentence, which
     * prints the query back, and two empty-state lines at once would be the screen arguing with
     * itself.
     */
    val showsNoneAvailable: Boolean
        get() = sections.isEmpty() && !filterActive && switchedOffRowCount > 0

    /**
     * Whether the Actions surface **exists** for this character — `ActionBoard.hasAnyRows` at the
     * UI layer (D2).
     *
     * Read by `CharacterHomeUiState.hasActions`, which is what `serverHomeTabs` and
     * `serverPaneSurfaces` key on. Deliberately **not** `sections.isNotEmpty()`, which is what it
     * used to be: FR-55 empties `sections` for a character whose every row is switched off, and
     * that must cost them the rows, not the tab.
     */
    val hasRows: Boolean get() = sections.isNotEmpty() || switchedOffRowCount > 0

    /**
     * Whether the spell-list DC/modifier block draws (D2).
     *
     * A spell list is a header for spells; with no spell section under it, the header is a stat
     * floating over nothing. That is the shape FR-55 makes reachable — every spell on a sheet
     * switched off while its actions stay — and the operator's *"only show what is actually
     * available"* covers a DC nothing can be cast at.
     *
     * **All-or-nothing across lists, not per list**, and that is a real limit rather than a
     * simplification: `ActionEngine` does not record which `spellList` a `SpellEntry` came from
     * (nothing has needed it), so a sheet with two lists — one live, one entirely switched off —
     * still draws both headers. Recorded here rather than papered over; per-list attribution is a
     * discovery change and a new model field, which D2 does not ask for.
     *
     * ### A SEARCH does not take the block away (NEW-5, ruled to match WebHand)
     *
     * Read off [hasSpellRows], which is board-derived, and deliberately **not** off [sections],
     * which `withView` replaces with the query-filtered set before the screen reads this. The
     * first cut read `sections` and so dropped the DC block the moment a player typed *"dagger"*
     * on a caster — a search-behaviour change D2 never asked for, since the old gate was
     * `spellLists.isNotEmpty()` and a query never touched it.
     *
     * The distinction the two readings blur: this block is a **stat about the character**, like
     * the tracker's AC badge, not a header over the rows below it. FR-55 may take it away, because
     * FR-55 changes what the character *has available*; a search may not, because a search changes
     * only what is on screen this second — and a caster who filters to their dagger has not
     * stopped having a spell save DC.
     */
    val showsSpellLists: Boolean get() = spellLists.isNotEmpty() && hasSpellRows

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
     *
     * ### Emphasis is stripped HERE, at render (BUG-25 R2)
     *
     * The string arriving from the engine is now the server's **rendered** description (R1: the
     * wrapper's `value`, not its `text`), and the library writes that with markdown emphasis in
     * it: *"must succeed a \*\*DC 12\*\* Intelligence Saving Throw"*. Decision 4 declines a
     * markdown renderer, so the honest remainder is to take the asterisks off —
     * [withoutMarkdownEmphasis], the same regex `ActionEngine` has run on the mastery block since
     * FR-47, now shared from `:core:model`.
     *
     * **At render and not in the engine**, which is R2's own wording and is the load-bearing half.
     * The engine's job is to say what the sheet says; a `**` that never reached a model object
     * would make the contract export publish a string DiceCloud did not send, and would make
     * "did the server bold this?" unanswerable from anything this app records. Stripping in the
     * one place the characters become pixels keeps the model faithful and the screen readable.
     *
     * A spaced *"2 \* your level"* survives untouched — see the regex's own KDoc for why that
     * case is the reason it is not a bare `\*+`.
     */
    val body: String?
        get() = when (val entry = row) {
            is ActionRow.Spell -> entry.entry.description ?: entry.entry.summary
            is ActionRow.Action -> entry.entry.description ?: entry.entry.summary
        }?.withoutMarkdownEmphasis()?.takeIf { it.isNotBlank() }

    /**
     * FR-49 decision 5's upcast paragraph, or `null` — the catalog's own text, verbatim.
     *
     * A **spell** row only, and written as a cast for [mastery]'s reason in the opposite
     * direction: a `when` over the sealed pair would need an action branch returning `null` that
     * reads as "not yet implemented", and this is "an action does not upcast". `null` on every
     * DiceCloud spell too, because `ActionEngine` does not read the field — see
     * [com.hashtagchow.magehand.core.model.SpellEntry.higherLevels].
     *
     * Blank is normalised to absent here rather than at the sheet, so the composable's
     * `?.let { … }` is the whole of the "no paragraph, no heading" rule.
     */
    val higherLevels: String?
        get() = (row as? ActionRow.Spell)?.entry?.higherLevels?.takeIf { it.isNotBlank() }

    /**
     * FR-47 R7's mastery block — the word and, when the sheet carries one, its rules sentence.
     *
     * An action row only, and `null` for every spell: [WeaponMastery] is a property of a weapon,
     * and [SpellEntry] has no field for one. Written as a cast rather than as a `when` over the
     * sealed pair for that reason — a `when` would need a `null` branch for spells that reads as
     * "not yet implemented", and this is "there is nothing there to show".
     */
    val mastery: WeaponMastery?
        get() = (row as? ActionRow.Action)?.entry?.mastery

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
                    // FR-55: no server sheet produces this branch any more. Kept per R2 —
                    // see `UnusableReason.INACTIVE`.
                    entry.entry.inactive -> UnusableReason.INACTIVE
                    !entry.entry.cost.satisfied -> UnusableReason.NO_RESOURCES
                    else -> UnusableReason.NO_USES
                }

                is ActionRow.Action -> when {
                    // FR-55: no server sheet produces this branch any more. Kept per R2 —
                    // see `UnusableReason.INACTIVE`.
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

    /**
     * `inactive: true` — the sheet has switched this off, or an ancestor is disabled.
     *
     * **Unreachable from a DiceCloud sheet since FR-55**: `ActionEngine.listedRow` drops the row,
     * so no server-built entry carries it. Kept with the field it reads (R2) — `isUsable` is
     * gated on `inactive`, and that gate is what makes the engine's filter safe to rely on rather
     * than the only thing standing in the way.
     */
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
    /**
     * Whether this use can be reversed — [ActionsUiState.usesAreUndoable], carried down so the
     * confirm dialog can read it without the screen threading a second parameter through.
     *
     * Drives exactly one thing: whether `action_use_no_undo` is drawn (18 decision 4). It does
     * **not** touch [enabled] — a use is confirmed before it happens on both paths, because a
     * dialog that appeared only for the irreversible case would teach the player that no dialog
     * means no consequences.
     */
    val undoable: Boolean = false,
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

    /**
     * **What this use will actually spend**, given the dialog's live choices.
     *
     * ### The defect this replaces
     *
     * The 1.17.0 device sweep's incidental observation: the confirm dialog read *"This spends
     * nothing."* on a Fireball cast from a level-5 slot. It was reading [UseTarget.cost], which is
     * the row's `attributesConsumed` / `itemsConsumed` — a spell slot is neither, and neither is
     * the row's own charge. So the one line the dialog gives to *"what is this about to take"* was
     * answering a narrower question than it appeared to, and answering it confidently.
     *
     * A slot cast is the case that matters, because the slot is the whole point of the dialog's
     * picker sitting directly below that sentence — the player had just been asked to choose one.
     *
     * ### Why it takes the two live values
     *
     * The ritual checkbox and the picked slot are the dialog's own state, not this type's: they
     * change between frames while the affordance does not. Passing them keeps this a pure
     * function of `(affordance, choices)` — [confirmDisabled]'s shape, for its reason
     * (`ActionsUiStateTest` can check it with no Compose harness).
     *
     * @param ritual the ritual checkbox's live state. A ritual cast spends no slot, which is what
     *   the checkbox promises and 20 decision 4's `ritualCast` branch does.
     * @param slotId the picker's live choice.
     */
    fun spend(ritual: Boolean, slotId: String?): UseSpend {
        val ritualCast = ritual && showsRitual
        return UseSpend(
            slot = if (!ritualCast && showsSlotPicker) {
                slots.firstOrNull { it.propertyId == slotId }
            } else {
                null
            },
            // Deliberately the same condition the dialog's existing "Uses left afterwards" line
            // already uses, so the two halves cannot say different things about one row.
            uses = target.uses,
            costLines = target.cost.lines,
        )
    }
}

/**
 * The three things a Use can take, as one answer — see [UseAffordance.spend].
 *
 * Kept as a value rather than as three getters on the affordance because [isNothing] is the
 * question the dialog actually asks first, and *"is every one of these absent"* is a rule that has
 * to live in one place or be re-derived wherever somebody prints the sentence.
 *
 * @property slot the spell slot this cast will burn. `null` for an action, a cantrip, a ritual
 *   cast, a spell that spends its own charges, and a picker the player has not answered.
 * @property uses the row's own charges, when it has them; the dialog prints both the spend and the
 *   count left afterwards from this.
 * @property costLines `attributesConsumed` + `itemsConsumed`, the lines this type used to be the
 *   whole of.
 */
data class UseSpend(
    val slot: SpellSlotOption?,
    val uses: ActionUses?,
    val costLines: List<CostLine>,
) {
    /** Whether *"This spends nothing."* is the truth. */
    val isNothing: Boolean get() = slot == null && uses == null && costLines.isEmpty()
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
                undoable = usesAreUndoable,
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
    /** FR-29 decision 4 — see [ActionsUiState.usesAreUndoable]. */
    usesAreUndoable: Boolean = false,
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
        usesAreUndoable = usesAreUndoable,
        switchedOffRowCount = board.switchedOffRowCount,
        hasSpellRows = board.spells.isNotEmpty(),
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
