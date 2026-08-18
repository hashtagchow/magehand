package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * The tracker customize bottom sheet (docs/design/04-screens-ux.md §5 and §6).
 *
 * "Reorderable list of all discovered rows + hidden section + item picker for pinnable
 * inventory items. Per-character; local only." — which is why this sheet is **fully
 * functional in WP6** while the tracker itself is read-only: nothing here touches the
 * server. Every mutation is a `tracker_prefs` / `theme_prefs` row.
 *
 * The state is built from `OpenCharacter.boardIgnoringHidden`, not from the rendered
 * board: a hidden row is filtered out by `TrackerEngine`, so the rendered board cannot
 * tell you what there is to un-hide.
 *
 * This sheet is deliberately the *complete* list, and it stayed complete when the tracker
 * started hiding switched-off toggles by default
 * ([com.hashtagchow.magehand.core.model.ConditionToggle.shownByDefault]): the two controls
 * here are how a user overrides that default in either direction — pin a condition and it
 * stays on the tracker while it is off, hide one and it never appears however it is
 * switched. Their meaning is unchanged; the `On` / `Off` detail is what makes the first of
 * those findable.
 */

/** The tracker's row groups, in the order 04 §3 lays them out. */
enum class CustomizeSection {
    SPELL_SLOTS,
    RESOURCES,
    CONSUMABLES,
    CONDITIONS,
}

data class CustomizeRow(
    val propertyId: String,
    val name: String,
    /** `"3 / 4"`, `"×109"`, `"On"` — enough to tell two same-named rows apart. */
    val detail: String?,
    val section: CustomizeSection,
    val hidden: Boolean,
    val pinned: Boolean,
)

data class CustomizeSectionState(
    val section: CustomizeSection,
    val rows: List<CustomizeRow>,
)

/** One row of the inventory picker (04 §5). Items are pinned, never hidden. */
data class ItemPickRow(
    val propertyId: String,
    val name: String,
    val quantity: Int,
    val pinned: Boolean,
)

data class TrackerCustomizeState(
    val sections: List<CustomizeSectionState> = emptyList(),
    val hidden: List<CustomizeRow> = emptyList(),
    val items: List<ItemPickRow> = emptyList(),
    val accentColor: String? = null,
    /**
     * Whether **reordering is the only thing this sheet can do**
     * (docs/design/09-local-characters.md decision 8, for a local character).
     *
     * Three of this sheet's four controls have no meaning for a character the player typed in
     * by hand, and `LocalOpenCharacter` documents each as a deliberate no-op: *hide* is what
     * deleting the row in the form does properly; *pin* says nothing, because every local row
     * is on the tracker by construction; and the *accent* lives in `theme_prefs`, which is
     * account-keyed and therefore unreachable without the sentinel account decision 1 forbids.
     *
     * The sheet hides them rather than dimming them. A disabled control invites the user to
     * find out why it is disabled; an absent one is simply not part of this sheet, which is
     * the truth — 09 decision 8 asks for **one** mechanism (`sortIndex`), and this is what one
     * mechanism looks like from the front.
     */
    val reorderOnly: Boolean = false,
) {
    val hasHiddenRows: Boolean get() = hidden.isNotEmpty()
}

/**
 * Builds the sheet's state.
 *
 * @param board must be `boardIgnoringHidden` — see the file comment.
 * @param showToggles FR-6 (docs/design/09-local-characters.md decision 9): when false the
 *   sheet's CONDITIONS section is hidden. The filter is applied to the *whole* row list
 *   before it is split into visible and hidden, which is the difference between hiding the
 *   section and hiding half of it: a condition the user had previously hidden would
 *   otherwise reappear under "Hidden", offering to un-hide a row the tracker will not draw.
 *
 *   Nothing is deleted by this — `tracker_prefs` keeps every condition override, so turning
 *   the switch back on restores exactly the arrangement the user had (09 decision 9: "when
 *   true: exactly the shipped FR-1/2 behavior").
 */
fun toCustomizeState(
    board: TrackerBoard,
    overrides: List<TrackerOverride>,
    accentColor: String?,
    showToggles: Boolean = true,
    reorderOnly: Boolean = false,
): TrackerCustomizeState {
    val byId = overrides.associateBy { it.propertyId }
    fun hidden(id: String) = byId[id]?.hidden == true
    fun pinned(id: String) = byId[id]?.pinned == true

    val all = buildList {
        board.slots.forEach {
            add(
                CustomizeRow(
                    propertyId = it.propertyId,
                    name = it.name,
                    detail = "${it.value} / ${it.total}",
                    section = CustomizeSection.SPELL_SLOTS,
                    hidden = hidden(it.propertyId),
                    pinned = pinned(it.propertyId),
                ),
            )
        }
        board.resources.forEach {
            add(
                CustomizeRow(
                    propertyId = it.propertyId,
                    name = it.name,
                    detail = "${it.value} / ${it.total}",
                    section = CustomizeSection.RESOURCES,
                    hidden = hidden(it.propertyId),
                    pinned = pinned(it.propertyId),
                ),
            )
        }
        // Only pinned items are tracker rows at all; the rest live in the picker below.
        board.allItems.filter { pinned(it.propertyId) }.forEach {
            add(
                CustomizeRow(
                    propertyId = it.propertyId,
                    name = it.name,
                    detail = "×${it.value}",
                    section = CustomizeSection.CONSUMABLES,
                    hidden = hidden(it.propertyId),
                    pinned = true,
                ),
            )
        }
        val toggles = if (showToggles) board.activeToggles else emptyList()
        toggles.forEach {
            add(
                CustomizeRow(
                    propertyId = it.propertyId,
                    name = it.name,
                    detail = if (it.enabled) "On" else "Off",
                    section = CustomizeSection.CONDITIONS,
                    hidden = hidden(it.propertyId),
                    pinned = pinned(it.propertyId),
                ),
            )
        }
    }

    val visible = all.filterNot { it.hidden }
    return TrackerCustomizeState(
        sections = CustomizeSection.entries
            .map { section -> CustomizeSectionState(section, visible.filter { it.section == section }) }
            .filter { it.rows.isNotEmpty() },
        hidden = all.filter { it.hidden },
        items = board.allItems.map {
            ItemPickRow(
                propertyId = it.propertyId,
                name = it.name,
                quantity = it.value,
                pinned = pinned(it.propertyId),
            )
        },
        accentColor = accentColor,
        reorderOnly = reorderOnly,
    )
}

/**
 * Turns a customize gesture into `tracker_prefs` rows.
 *
 * Pure and separate from the composable so the awkward part — reordering — is testable.
 * `TrackerEngine` sorts each section by `sortIndex ?: Int.MAX_VALUE` then by the server's
 * natural order, **within that section only**, so a move re-indexes the whole section
 * `0..n-1` rather than trying to nudge one row's index past its neighbour. Nudging one
 * index is where this kind of code usually breaks: two rows with no explicit index both
 * compare `MAX_VALUE`, so a single-row write cannot express "before that one".
 */
object TrackerOverridePlan {

    fun setHidden(
        current: List<TrackerOverride>,
        propertyId: String,
        hidden: Boolean,
    ): TrackerOverride = current.forId(propertyId).copy(hidden = hidden)

    fun setPinned(
        current: List<TrackerOverride>,
        propertyId: String,
        pinned: Boolean,
    ): TrackerOverride = current.forId(propertyId).copy(pinned = pinned)

    /**
     * Moves [propertyId] by [delta] places inside [sectionOrder] and returns the rows to
     * persist — the whole section, re-indexed.
     *
     * Returns an empty list when the move is a no-op (row absent, or already at the end
     * it is being moved towards), so a bounce off the top of the list is not a write.
     */
    fun reorder(
        current: List<TrackerOverride>,
        sectionOrder: List<String>,
        propertyId: String,
        delta: Int,
    ): List<TrackerOverride> {
        val from = sectionOrder.indexOf(propertyId)
        if (from < 0) return emptyList()
        val to = from + delta
        if (to !in sectionOrder.indices || delta == 0) return emptyList()

        val moved = sectionOrder.toMutableList().apply {
            removeAt(from)
            add(to, propertyId)
        }
        return moved.mapIndexed { index, id ->
            current.forId(id).copy(sortIndex = index)
        }
    }

    private fun List<TrackerOverride>.forId(propertyId: String): TrackerOverride =
        firstOrNull { it.propertyId == propertyId } ?: TrackerOverride(propertyId)
}
