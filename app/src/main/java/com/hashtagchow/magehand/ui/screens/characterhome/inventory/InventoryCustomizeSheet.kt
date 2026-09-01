package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.InventorySortCriterion
import com.hashtagchow.magehand.core.data.settings.InventorySortDirection
import com.hashtagchow.magehand.ui.components.RadioRow
import com.hashtagchow.magehand.ui.theme.hyphenated
import com.hashtagchow.magehand.ui.theme.mageHandIconButtonColors

/**
 * The inventory customize sheet (docs/design/12-inventory-layout.md decision 3).
 *
 * ### Why it looks exactly like the tracker's
 *
 * Because it is the same job, and decision 3 says so: *"the same wrench → sheet affordance as the
 * tracker"*. A `ModalBottomSheet` opened from a `Build` icon in the app bar, a title with a Reset
 * beside it, rows carrying ▲/▼ and ✕ at 48 dp, and a "Hidden" group at the bottom whose rows
 * offer "Show". Five of those strings are literally `TrackerCustomizeSheet`'s
 * (`customize_move_up`, `customize_move_down`, `customize_hide`, `customize_show`,
 * `customize_hidden`, `customize_reset`), which is the point rather than a saving: a player who
 * has arranged their tracker already knows how to arrange this, and a second dialect for the
 * same four gestures would be two things to learn.
 *
 * Arrows rather than drag-to-reorder, for `TrackerCustomizeSheet`'s two reasons unchanged: drag
 * inside a `ModalBottomSheet` fights the sheet's own drag gesture, and 04's "everything reachable
 * one-handed" makes a 48 dp arrow reachable with a thumb at a table where a long-press-then-drag
 * is not.
 *
 * ### The one thing that is deliberately different
 *
 * **What hiding means.** On the tracker a hidden row is gone from the tracker; here a hidden
 * section's items move into Gear and stay on the tab (decision 3's invariant). The subtitle says
 * so, and every folded row in the Hidden list repeats it, because "where did my weapons go" is a
 * question that has to be answered on the screen that caused it rather than by the player
 * scrolling the tab to find out. The wallet is the one exception and says its own line.
 *
 * There is no item picker and no accent picker: the first is a tracker concept (an item is a
 * tracker row only if pinned, which is not a thing an inventory has) and the second belongs to
 * one sheet, not to every sheet in the app.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun InventoryCustomizeSheet(
    state: InventoryCustomizeState,
    onDismiss: () -> Unit,
    onMove: (key: String, delta: Int) -> Unit,
    onSetHidden: (key: String, hidden: Boolean) -> Unit,
    onReset: () -> Unit,
    // Required, no `= {}`. A defaulted callback on a control that writes a persisted preference is
    // a silent no-op waiting for a call site to forget it — the 2026-08-31 review's point, and the
    // same argument `onMove`/`onSetHidden` above already embody by never having had defaults.
    onSetSortCriterion: (InventorySortCriterion) -> Unit,
    onSetSortDirection: (InventorySortDirection) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory:customize"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.inventory_customize_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // Always offered, unlike the tracker's — which hides Reset in reorder-only
                    // mode because there `sortIndex` *is* the order and a reset would scramble
                    // it. Here the arrangement is one stored key with a documented default
                    // (12 decision 1), so resetting is exactly "delete that key" and means the
                    // same thing for a local character as for a DiceCloud one.
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.testTag("inventory:customize:reset"),
                    ) {
                        Text(stringResource(R.string.customize_reset))
                    }
                }
                Text(
                    text = stringResource(R.string.inventory_customize_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            // FR-35. Above the section list rather than below it, because a player who opened
            // this sheet to sort should not have to scroll past however many backpacks their
            // sheet has to find the control — and because the list below is long and variable
            // while this block is four rows and a toggle, always.
            item("sort") {
                SortControls(
                    state = state,
                    onSetSortCriterion = onSetSortCriterion,
                    onSetSortDirection = onSetSortDirection,
                )
            }

            // The section list gained a heading when the sort block moved in above it. Before
            // FR-35 the rows sat directly under the subtitle and needed none; a list with
            // something else above it does, or the first container reads as part of the sort
            // control. Same style as the "Hidden" heading below, so the sheet has one vocabulary
            // for "here starts a group".
            item("sections-header") {
                GroupHeading(stringResource(R.string.inventory_customize_sections))
            }

            items(state.visible, key = { "section-${it.key}" }) { row ->
                val index = state.visible.indexOf(row)
                InventoryCustomizeRowItem(
                    row = row,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.visible.lastIndex,
                    onMoveUp = { onMove(row.key, -1) },
                    onMoveDown = { onMove(row.key, 1) },
                    // `null` rather than a disabled button on Equipped and Gear. A disabled
                    // control invites the player to work out why it is disabled; an absent one
                    // is simply not part of this row, which is the truth — see
                    // `InventoryLayoutKeys.isHideable`. Same choice, same argument, as the
                    // tracker sheet's reorder-only mode.
                    onHide = if (row.canHide) {
                        { onSetHidden(row.key, true) }
                    } else {
                        null
                    },
                )
            }

            if (state.hasHiddenRows) {
                item("hidden-header") {
                    GroupHeading(stringResource(R.string.customize_hidden))
                }
                items(state.hidden, key = { "hidden-${it.key}" }) { row ->
                    HiddenSectionItem(row = row, onShow = { onSetHidden(row.key, false) })
                }
            }
        }
    }
}

/**
 * A divider and a small caps heading — the sheet's one way of saying "here starts a group".
 *
 * Extracted at FR-35 rather than copied: there are three of these now (Sort, Sections, Hidden),
 * and three copies of a padding block is how two of them end up 4 dp apart from the third.
 */
@Composable
private fun GroupHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

/**
 * FR-35's two controls: what to sort by, and which way round.
 *
 * ### Radios for the criterion, a segmented pair for the direction
 *
 * Both are "pick exactly one", and they are drawn differently on purpose. The criterion is
 * **four** options whose labels are nouns of different lengths ("Sheet order" against "Name"), and
 * four segments of a segmented row at 150 % UI scale on a 320 dp phone is the exact geometry BUG-4
 * is about — the tab row's four labels are what broke there. A vertical radio list cannot have
 * that bug: each label gets the width of the sheet.
 *
 * The direction is **two** options, short, and mutually exhaustive, which is precisely
 * `CategoryChooser`'s case — *"a segmented control says 'pick one of these two' in its shape,
 * before any label is read, and it cannot render the state where none is selected"*. Reusing that
 * vocabulary is also what makes the pair read as one setting with a modifier rather than as two
 * unrelated lists.
 *
 * ### What the disabled direction is doing here
 *
 * Decision 6, argued in full at [InventoryCustomizeState.canChooseDirection]: under sheet order
 * the toggle is **disabled, not removed**, because its unavailability is transient and caused by
 * the radio the player just pressed — the same situation the ▲/▼ arrows are in at the ends of the
 * section list, which this sheet already draws as disabled. It keeps the theme's raised disabled
 * tint (BUG-3) for the same reason those arrows do.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun SortControls(
    state: InventoryCustomizeState,
    onSetSortCriterion: (InventorySortCriterion) -> Unit,
    onSetSortDirection: (InventorySortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.inventory_sort_title)
    val directionTitle = stringResource(R.string.inventory_sort_direction)
    val unavailable = stringResource(R.string.inventory_sort_direction_unavailable)

    Column(modifier) {
        GroupHeading(title)
        Text(
            text = stringResource(R.string.inventory_sort_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                // `selectableGroup` is what makes the four rows one radio group to a screen
                // reader ("2 of 4") rather than four unrelated selectable rows.
                //
                // There is deliberately **no `contentDescription` here**. It carried one until the
                // 2026-08-31 review: this Column does not merge its descendants and its children
                // are each focusable, so TalkBack never stopped on it and the sentence was never
                // spoken. Naming the group is done by the visible heading above instead — see
                // `InventoryCustomizeState.spokenDirectionOptionLabel` for the whole argument.
                .selectableGroup()
                .testTag("inventory:customize:sort"),
        ) {
            InventorySortCriterion.entries.forEach { criterion ->
                RadioRow(
                    selected = state.sort.criterion == criterion,
                    title = stringResource(criterion.labelRes),
                    onClick = { onSetSortCriterion(criterion) },
                    modifier = Modifier.testTag("inventory:customize:sort:${criterion.key}"),
                )
            }
        }

        // No `contentDescription` on the row, for the Column's reason above: it does not merge and
        // its segments are focusable, so nothing spoken here was ever reached. The sentence lives
        // on each segment instead, which is where the focus lands.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
                .testTag("inventory:customize:sort:direction"),
        ) {
            InventorySortDirection.entries.forEachIndexed { index, direction ->
                val optionLabel = stringResource(direction.labelRes)
                SegmentedButton(
                    selected = state.sort.direction == direction,
                    onClick = { onSetSortDirection(direction) },
                    enabled = state.canChooseDirection,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = InventorySortDirection.entries.size,
                    ),
                    label = { Text(optionLabel) },
                    modifier = Modifier
                        // On the button, not on the row: a `SegmentedButton` is a selectable
                        // control that merges its own content, so this is reached by construction.
                        // It names the control ("Sort direction"), which the segment's one word
                        // does not, and adds why it is unavailable while it is.
                        .semantics {
                            contentDescription = state.spokenDirectionOptionLabel(
                                title = directionTitle,
                                optionLabel = optionLabel,
                                unavailableLabel = unavailable,
                            )
                        }
                        .testTag("inventory:customize:sort:direction:${direction.key}"),
                )
            }
        }
    }
}

/**
 * The four criteria's words, in `strings.xml` because they are copy.
 *
 * Deliberately **not** reusing `inventory_weight` / `inventory_value`: those are the `"%1$s lb"`
 * and `"%1$s gp"` number formats this tab prints amounts with, and these are the names of two
 * things to sort by. A translator is entitled to render a unit and a criterion differently — the
 * same split `CategoryChooser.labelRes` already draws against the section headings.
 */
private val InventorySortCriterion.labelRes: Int
    @StringRes get() = when (this) {
        InventorySortCriterion.DEFAULT -> R.string.inventory_sort_default
        InventorySortCriterion.NAME -> R.string.inventory_sort_name
        InventorySortCriterion.WEIGHT -> R.string.inventory_sort_weight
        InventorySortCriterion.VALUE -> R.string.inventory_sort_value
    }

private val InventorySortDirection.labelRes: Int
    @StringRes get() = when (this) {
        InventorySortDirection.ASCENDING -> R.string.inventory_sort_ascending
        InventorySortDirection.DESCENDING -> R.string.inventory_sort_descending
    }

/**
 * One arrangeable section: its name, what it weighs, and the three controls.
 *
 * @param onHide `null` on Equipped and Gear — the two guardrails. See the call site.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InventoryCustomizeRowItem(
    row: InventoryCustomizeRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title = row.title()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 8.dp)
            .testTag("inventory:customize:${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                // Two lines, matching the tracker sheet's: a container is named by the player and
                // "Handy Haversack (outer pocket)" ellipsizes to the same prefix as its sibling.
                // Hyphenated per GOLDEN-1's ruling (2026-08-30) — the name is the player's, the
                // slot is narrow beside three 48 dp controls, and this is that defect class.
                // Guarded by checklist item L11, never by a golden; see `TextStyle.hyphenated`.
                style = MaterialTheme.typography.bodyLarge.hyphenated(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // BUG-3's raised disabled tint, from the theme: both arrows are disabled on the first
        // and last row of the list, and Material's 38% default made them all but vanish on dark.
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            colors = mageHandIconButtonColors(),
            modifier = Modifier
                .size(48.dp)
                .testTag("inventory:customize:${row.key}:up"),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.customize_move_up, title),
            )
        }
        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            colors = mageHandIconButtonColors(),
            modifier = Modifier
                .size(48.dp)
                .testTag("inventory:customize:${row.key}:down"),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.customize_move_down, title),
            )
        }
        onHide?.let { hide ->
            IconButton(
                onClick = hide,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("inventory:customize:${row.key}:hide"),
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.customize_hide, title),
                )
            }
        }
    }
}

/**
 * A folded section, and the line saying what folding did to it.
 *
 * The explanation is on **every** hidden row rather than once above the group, because the two
 * cases genuinely differ: a folded Weapons section's items are in Gear, and a folded Wallet's
 * coins are not on the tab at all. One shared caption would have had to be vague enough to cover
 * both, which is how a reassurance stops reassuring.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HiddenSectionItem(
    row: InventoryCustomizeRow,
    onShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 8.dp)
            .testTag("inventory:customize:hidden:${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title(),
                // GOLDEN-1's ruling, as on the visible row above — same name, same slot.
                style = MaterialTheme.typography.bodyLarge.hyphenated(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (row.key == InventoryLayoutKeys.WALLET) {
                        R.string.inventory_customize_wallet_hidden
                    } else {
                        R.string.inventory_customize_folded
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onShow,
            modifier = Modifier.testTag("inventory:customize:hidden:${row.key}:show"),
        ) {
            Text(stringResource(R.string.customize_show))
        }
    }
}

/**
 * The row's heading — its container's own name, or the generic title.
 *
 * The same join `InventoryTab`'s section headers do, so a container renamed on the sheet is
 * renamed in both places at once and an unnamed one falls back to "Container" in both.
 */
@Composable
private fun InventoryCustomizeRow.title(): String =
    containerName ?: stringResource(titleRes)

/**
 * The row's second line: what the section weighs, or — for the wallet, which prints no weight
 * figure by 10 decision 10 — what is in it.
 *
 * The unit is added here rather than baked into the state, per `InventoryUiState`'s rule that
 * numbers are formatted in the state and copy lives in `strings.xml`.
 */
@Composable
private fun InventoryCustomizeRow.detail(): String? =
    weightLabel?.let { stringResource(R.string.inventory_weight, it) } ?: summary
