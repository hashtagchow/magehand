package com.hashtagchow.magehand.ui.screens.characterhome.inventory

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
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
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
                    Column {
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                        Text(
                            text = stringResource(R.string.customize_hidden).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 24.dp,
                                end = 24.dp,
                                top = 12.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                }
                items(state.hidden, key = { "hidden-${it.key}" }) { row ->
                    HiddenSectionItem(row = row, onShow = { onSetHidden(row.key, false) })
                }
            }
        }
    }
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
                style = MaterialTheme.typography.bodyLarge,
                // Two lines, matching the tracker sheet's: a container is named by the player and
                // "Handy Haversack (outer pocket)" ellipsizes to the same prefix as its sibling.
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
                style = MaterialTheme.typography.bodyLarge,
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
