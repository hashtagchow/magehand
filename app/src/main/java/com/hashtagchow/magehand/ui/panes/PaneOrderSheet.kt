package com.hashtagchow.magehand.ui.panes

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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.ui.theme.mageHandIconButtonColors

/**
 * FR-27 decision 2's reorder sheet: *"the house customize-sheet arrow pattern (wrench → sheet,
 * up/down arrows), listing the surfaces that exist for the character"*.
 *
 * ### Why it looks exactly like the other two customize sheets
 *
 * Because it is the same job, and the FR says so. `TrackerCustomizeSheet` and
 * `InventoryCustomizeSheet` already made this argument twice: a `ModalBottomSheet` opened from an
 * app-bar icon, a title with a Reset beside it, rows carrying ▲/▼ at 48 dp. Three of its strings
 * are literally theirs (`customize_move_up`, `customize_move_down`, `customize_reset`) — which is
 * the point rather than a saving, since a player who has arranged their tracker and their
 * inventory already knows how to arrange this.
 *
 * Arrows rather than drag-to-reorder, for `TrackerCustomizeSheet`'s two reasons unchanged: drag
 * inside a `ModalBottomSheet` fights the sheet's own drag gesture, and 04's *"everything reachable
 * one-handed"* makes a 48 dp arrow reachable with a thumb at a table where a long-press-then-drag
 * is not. Decision 6's a11y clause — *"arrows follow the tracker customize sheet's spoken
 * pattern"* — is the same sentence from the other direction, and it is satisfied by reusing the
 * two `%1$s`-formatted strings rather than by describing the arrows again: TalkBack says "Move
 * Inventory up", naming the row, exactly as it does on the other two sheets.
 *
 * ### The three things that are deliberately different
 *
 * - **No hide control.** On the other two sheets ✕ folds a row away. Here "is it showing" is the
 *   pane picker's question and decision 2 leaves the picker's select/deselect *unchanged*, so a
 *   second control for it would be two ways to say one thing that could disagree. There is also
 *   nothing to hide a **tab** from: the tab row draws every surface the character has, always.
 * - **No hidden group** at the bottom, for the same reason: nothing here can be hidden.
 * - **Every row is listed, whether or not it is open as a pane.** That is decision 2's *"the
 *   surfaces that exist for the character"* read literally, and it is what makes the sheet mean
 *   the same thing on a phone (where panes are not a concept) and on a tablet.
 *
 * ### Reset
 *
 * Always offered, like the inventory sheet's and unlike the tracker's reorder-only mode: the
 * arrangement is one stored key with a documented default, so resetting is exactly "delete that
 * key". Decision 3 makes that restore the default **order and the default set** in one write —
 * both facts live in the one key. The subtitle does not spell that out; the wide-screen sentence
 * is what tells a player the two are the same preference.
 *
 * @param order every surface this character has, in the player's current order — from
 *   `resolvePaneLayout`, so a surface the character does not have right now is not listed and a
 *   surface they have never arranged is already in its default place.
 * @param onMove the arrow, as `(surface, delta)`. `-1` and `1`; the plan (`movePane`) refuses a
 *   bounce off either end, and the buttons are disabled there as well — the same belt-and-braces
 *   the other two sheets use, and for `InventoryLayoutPlan.move`'s reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneOrderSheet(
    order: List<PaneSurface>,
    onDismiss: () -> Unit,
    onMove: (PaneSurface, Int) -> Unit,
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
                .testTag("panes:order"),
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
                        text = stringResource(R.string.panes_order_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.testTag("panes:order:reset"),
                    ) {
                        Text(stringResource(R.string.customize_reset))
                    }
                }
                Text(
                    text = stringResource(R.string.panes_order_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            items(order, key = { "surface-${it.key}" }) { surface ->
                val index = order.indexOf(surface)
                PaneOrderRowItem(
                    surface = surface,
                    canMoveUp = index > 0,
                    canMoveDown = index < order.lastIndex,
                    onMoveUp = { onMove(surface, -1) },
                    onMoveDown = { onMove(surface, 1) },
                )
            }
        }
    }
}

/** One arrangeable surface: its label — the tab's own — and the two arrows. */
@Composable
private fun PaneOrderRowItem(
    surface: PaneSurface,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The tab row's own string, via `PaneSurface.titleResId` — deliberately not a second
    // vocabulary, for that property's reason: this sheet arranges the things the tab row draws.
    val title = stringResource(surface.titleResId)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 8.dp)
            .testTag("panes:order:${surface.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            // One line, unlike the other two sheets' two: those carry player-minted names
            // ("Handy Haversack (outer pocket)", "Racial ASI Disabler") that ellipsize to the
            // same prefix as a sibling. These four are app-owned words of one line each.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // BUG-3's raised disabled tint, from the theme: both arrows are disabled on the first and
        // last row, and Material's 38 % default made them all but vanish on the dark scheme.
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            colors = mageHandIconButtonColors(),
            modifier = Modifier
                .size(48.dp)
                .testTag("panes:order:${surface.key}:up"),
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
                .testTag("panes:order:${surface.key}:down"),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.customize_move_down, title),
            )
        }
    }
}
