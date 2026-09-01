package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventorySort
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion

/**
 * The inventory customize sheet's state (docs/design/12-inventory-layout.md decision 3).
 *
 * ### Why this is built by `toInventoryUiState` and not by a second mapper
 *
 * The tracker's equivalent is its own function over its own board, and it has to be: it is built
 * from `boardIgnoringHidden`, because `TrackerEngine` *filters hidden rows out*, so the rendered
 * board cannot tell you what there is to un-hide.
 *
 * Nothing on this tab is filtered out. Decision 3's invariant is that hiding changes grouping and
 * never visibility, so every item is on the tab in both states and the same board answers both
 * questions. Building the sheet in the same pass as the tab is therefore not a shortcut — it is
 * what makes "the sheet lists the section the tab is drawing" true by construction rather than by
 * two mappers agreeing. A section cannot be named one thing on the tab and another in the sheet,
 * and a folded section cannot go missing from the list that is the only way to get it back.
 */
data class InventoryCustomizeRow(
    /** The stable key this row reorders and persists under. See [InventoryLayoutKeys]. */
    val key: String,
    /**
     * The section's generic title — "Wallet", "Weapons", "Container".
     *
     * A `@StringRes` rather than a resolved string, matching `InventoryUiState`'s rule that
     * sentences are copy and copy is `strings.xml`'s. The composable does the one join with
     * [containerName], which is the same join `InventoryTab` already does for a section header.
     */
    @get:StringRes
    val titleRes: Int,
    /** A container's own name, or `null` for the fixed sections and for an unnamed container. */
    val containerName: String? = null,
    /**
     * The section's weight, already formatted and **without its unit** — the composable adds
     * "lb", because the unit is copy. `null` on the wallet, which prints no weight figure by
     * 10 decision 10.
     */
    val weightLabel: String? = null,
    /**
     * The wallet's coin line — *"2 pp · 15 gp"* — standing in for the weight it does not have.
     *
     * The two nullable detail fields are mutually exclusive by construction, and that is worth a
     * word: a single `detail: String` would have had to bake "lb" into a state class, which is
     * exactly the number/sentence split this tab keeps. Two fields, one of which is always null,
     * is the cheap honest shape.
     */
    val summary: String? = null,
    val hidden: Boolean = false,
    /**
     * Whether this row gets a hide control at all — [InventoryLayoutKeys.isHideable], carried
     * onto the state so the sheet does not re-derive a guardrail and a test can read it.
     */
    val canHide: Boolean = true,
    /**
     * Whether the section is collapsed on the tab (13 decision 3).
     *
     * **Carried and deliberately never drawn.** 13 decision 6 keeps the sheet unchanged: collapse
     * is an in-place gesture on the section itself, not a third control in a list whose job is
     * order and grouping. So why is it here at all?
     *
     * Because [InventoryCustomizeState.resolved] is the *arrangement* every
     * [InventoryLayoutPlan] gesture is computed against, and the plan persists the **whole**
     * arrangement on every gesture. A row that dropped this flag would make `resolved` a lossy
     * copy of what is stored, and `persist` — which lets the edited entries win over the stored
     * ones — would then quietly re-open every collapsed section the first time the player moved
     * an unrelated one. That failure is silent and total, which is the same shape the class KDoc
     * on [InventoryLayoutPlan] describes for a forgotten container.
     *
     * The field's name is therefore doing two jobs: it is what the sheet must not render, and it
     * is what the sheet's state must not lose.
     */
    val collapsed: Boolean = false,
)

data class InventoryCustomizeState(
    /**
     * Every section, folded and unfolded, in the order they are arranged — **including** the ones
     * the tab is not currently drawing a header for. That is the whole difference from
     * [InventoryUiState.blocks]: this is the list the player edits, so a folded section has to be
     * in it or there would be no way back.
     */
    val rows: List<InventoryCustomizeRow> = emptyList(),
    /**
     * How the rows *inside* each section are ordered (FR-35 decision 3).
     *
     * ### Why the sort control lives on this sheet and not on the tab
     *
     * FR-35 decision 3's words are *"the house wrench pattern — sorting is a preference, not a
     * glance"*, and the distinction is the same one FR-24 drew for the search field: the filter is
     * a `rememberSaveable` on the tab because *"where is my rope"* is a question asked and
     * finished in ten seconds, while *"I read this list heaviest first"* is a durable fact about
     * how a player uses their sheet. A durable fact goes behind the wrench with the other durable
     * facts and is stored beside them.
     *
     * It is on this state rather than in its own flow for [rows]' reason: one pass over one board
     * builds the whole sheet, so the criterion the sections were sorted by and the criterion the
     * radio group shows cannot disagree.
     */
    val sort: InventorySort = InventorySort.DEFAULT,
) {
    /** The sections with headers on the tab, in order. The ▲/▼ arrows act on this list. */
    val visible: List<InventoryCustomizeRow> get() = rows.filterNot { it.hidden }

    /** The folded ones — the sheet's "Hidden" group, matching the tracker's. */
    val hidden: List<InventoryCustomizeRow> get() = rows.filter { it.hidden }

    val hasHiddenRows: Boolean get() = hidden.isNotEmpty()

    /**
     * This state as the arrangement it represents — what [InventoryLayoutPlan] edits against.
     *
     * Derived rather than a second field, so the list the plan reasons about is provably the list
     * the player is looking at. A separate copy would be one more thing to keep in step, and the
     * failure mode — a move computed against yesterday's order — is silent.
     */
    val resolved: List<InventoryLayoutEntry>
        get() = rows.map { InventoryLayoutEntry(it.key, it.hidden, it.collapsed) }

    /**
     * Whether the ascending/descending control can be used at all (FR-35 decision 6).
     *
     * False under [InventorySortCriterion.DEFAULT], because a *reversed sheet order* is not a
     * thing the source expresses — DiceCloud's `order` is where the player put their rows in
     * DiceCloud's own UI, and offering to show it upside down is offering a view of the sheet
     * that nothing on the sheet means.
     *
     * ### Disabled, not absent — and the sheet already contains the precedent for both
     *
     * `InventoryCustomizeSheet` makes the opposite call one control away: the ✕ on Equipped and
     * Gear is **absent**, on the argument that *"a disabled control invites the player to work out
     * why it is disabled; an absent one is simply not part of this row"*. The two are not in
     * conflict — they turn on whether the unavailability is *permanent for this thing* or
     * *transient and caused by the player's own current choice*:
     *
     *  - Gear can never be folded. The control is not part of that row, ever, and a player who
     *    saw it appear once would be owed an explanation the sheet cannot give.
     *  - The direction is unavailable **right now, because of the radio they just pressed**, and
     *    it becomes available the instant they press a different one. The same sheet already
     *    treats that case as *disabled*: the ▲/▼ arrows are `enabled = false` at the ends of the
     *    list, not removed, precisely because their availability moves with the player's own
     *    gestures — and a control that vanished and reappeared under the finger would re-lay-out
     *    the rows beneath it mid-tap.
     *
     * Carried on the state rather than re-derived in the composable so the rule is one line a
     * test can call, matching every other "does this control render" question on this tab.
     */
    val canChooseDirection: Boolean get() = sort.criterion != InventorySortCriterion.DEFAULT

    /**
     * What TalkBack says on **one segment** of the direction toggle — *"Sort direction,
     * Descending"*, or, while the toggle is disabled, the reason it cannot be pressed.
     *
     * ### Why this is per-option, and why there is no second sentence for the group
     *
     * Both were originally written as descriptions on the two *containers* — a `Modifier.semantics`
     * on the radio `Column` and one on the `SingleChoiceSegmentedButtonRow`. The 2026-08-31
     * independent review caught that as a real accessibility defect, not a style point: neither
     * container merges its descendants, and both have individually focusable children, so
     * **TalkBack never stops on either node** and neither sentence was ever spoken. The tests
     * passed because `onNodeWithContentDescription` searches the whole tree, reachable or not —
     * exactly the "green test proving nothing" class FR-34 exists to close.
     *
     * The fix is to put the words where the focus actually lands. A `SegmentedButton` is a
     * selectable control that merges its own content, so a description on it is reached by
     * definition — and the test asserts it on the button's **merged** node together with its click
     * action, which is what makes the assertion about reachability rather than about existence.
     *
     * The group sentence is **gone rather than relocated**. Making it reachable would have meant
     * merging the radio group into one node, which would have destroyed the four separate radios a
     * screen-reader user tabs through — a much worse trade. What it was for (naming the group) is
     * already carried by the visible "SORT ITEMS" heading and its subtitle, which TalkBack reads in
     * document order on the way in, and each radio announces its own label, selected state, and
     * "n of 4" from `selectableGroup`. A sentence nobody can hear is worse than no sentence,
     * because it reads in review as a job already done.
     *
     * ### The asymmetry, which is what the test pins
     *
     * The unavailable clause is present **only while the toggle is disabled** — the mirror of
     * `InventoryRowState.spokenEquipLabel` dropping "Equipped". Decision 6 chose disabled over
     * absent (see [canChooseDirection]), and a segmented control that is merely inert announces
     * "disabled" and nothing else: a user is told something is unavailable without being told what
     * would make it available. This sentence is the half of that choice that keeps it honest, and
     * it is why the decision could go that way without costing anybody the explanation.
     *
     * Nothing here names a resource; every fragment is copy the composable resolves.
     *
     * @param title "Sort direction" — what the control is, which the segment's own word does not
     *   say. Every segment repeats it, because a screen reader lands on a segment, not on the row.
     * @param optionLabel **this segment's** word — "Ascending" / "Descending" — and not the
     *   currently selected one. The segment's own visible label, so a translator cannot end up
     *   with a control and a sentence that disagree; the *selected* state is announced separately
     *   by Material and is deliberately not restated here.
     * @param unavailableLabel the sentence naming which choice would turn the control on. Dropped
     *   entirely once it would be false.
     */
    fun spokenDirectionOptionLabel(
        title: String,
        optionLabel: String,
        unavailableLabel: String,
    ): String = listOfNotNull(
        title,
        optionLabel,
        unavailableLabel.takeUnless { canChooseDirection },
    ).joinToString(SPOKEN_SEPARATOR)
}

/**
 * What separates the facts inside a spoken sentence on this sheet.
 *
 * A comma, the same one `InventoryUiState`'s builders use and for the same reason — these strings
 * are read aloud and a comma is the pause a sentence needs. Its twin is `private` to that file, so
 * this is a second declaration rather than a shared constant; the alternative is widening a
 * file-private punctuation choice into API for the sake of one character. The pair is pinned by
 * `InventoryUiStateTest`, which asserts the shape of both files' sentences.
 */
private const val SPOKEN_SEPARATOR = ", "
