package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry

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
)

data class InventoryCustomizeState(
    /**
     * Every section, folded and unfolded, in the order they are arranged — **including** the ones
     * the tab is not currently drawing a header for. That is the whole difference from
     * [InventoryUiState.blocks]: this is the list the player edits, so a folded section has to be
     * in it or there would be no way back.
     */
    val rows: List<InventoryCustomizeRow> = emptyList(),
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
        get() = rows.map { InventoryLayoutEntry(it.key, it.hidden) }
}
