package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R

/**
 * 04 §3's "persistent undo history sheet [that] holds the session's ops".
 *
 * ### What "session" means here, precisely
 *
 * The list lives on the `WriteQueue`, which lives on the `CreatureSession`, which is
 * created when the character opens and closed when it is popped. So the history is
 * per-character and dies with the screen — deliberately, because an inverse op is only
 * meaningful against the sheet state it was captured from. Persisting it across a process
 * restart would mean offering to "restore 1 × 1st Level" against a sheet three sessions of
 * play later, which is worse than not offering it at all.
 *
 * ### Why only the top row can be undone
 *
 * Because the ops are not independent. Undo is a stack of *inverse ops*, and the inverse of
 * "spend 2" is only "restore 2" while nothing since has set that row absolutely, or rested,
 * or spent it to zero. Offering an UNDO button on every row would be offering arithmetic
 * that is right for the newest one and quietly wrong further down. A rest clears the stack
 * outright for the same reason — every earlier row greys out the moment one is taken.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TrackerHistorySheet(
    rows: List<HistoryRowState>,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.tracker_history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.tracker_history_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.tracker_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 24.dp)
                        .testTag("tracker:history:empty"),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(rows, key = { it.id }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .testTag("tracker:history:${row.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodyLarge,
                                // Struck through rather than removed: "I undid that" is
                                // part of what happened this session, and a row that
                                // vanishes on undo makes the list look like it lied.
                                textDecoration = if (row.undone) TextDecoration.LineThrough else null,
                                color = if (row.undone) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = row.at,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.canUndo) {
                            TextButton(
                                onClick = onUndo,
                                modifier = Modifier.testTag("tracker:history:undo"),
                            ) { Text(stringResource(R.string.action_undo)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
