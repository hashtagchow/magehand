package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R

/**
 * What the removed connection strip used to say, said once, on demand.
 *
 * Reached only by tapping the tracker's bottom-right dot, which exists only while the
 * sheet is not live ([TrackerUiState.showConnectionIndicator] is the whole rule — the dot
 * is also held back over a cold open that is merely reconnecting). It answers the three
 * questions a player actually has when the tracker stops responding, in the order they
 * ask them:
 *
 *  1. **What is wrong?** — [ConnectionStatus.stateLabelRes] and
 *     [ConnectionStatus.explanationRes], which also say whether waiting will fix it.
 *     `SIGNED_OUT` is the one state that waiting cannot fix, and it says so.
 *  2. **Is what I'm looking at current?** — the sync line. `showingSnapshot` gets the
 *     stronger "this came out of the cache" wording; a merely-reconnecting screen that is
 *     still rendering the live mirror gets the plainer "last synced at". These are
 *     genuinely different situations and the strip conflated them.
 *  3. **Can I do anything?** — "Try reconnecting" where a restart could plausibly help,
 *     and an explicit "retrying happens anyway" note so the button does not read as *the*
 *     mechanism. There is no new reconnection machinery behind it: it calls the
 *     `DdpConnectionManager.restart()` that already existed for the token-refresh path.
 *
 * ### Why it survives going live
 *
 * The dot vanishes the moment the socket comes back, but this sheet does not — it renders
 * the `LIVE` copy instead. A sheet that evaporated mid-read would leave the user unsure
 * whether they had dismissed it or something had gone wrong; showing "Live" answers the
 * question they opened it to ask.
 *
 * ### Accessibility
 *
 * Everything here is real `Text`, in reading order, inside the standard `ModalBottomSheet`
 * — so TalkBack traverses it exactly as it does the history and customize sheets. The dot
 * that opens it carries its own `contentDescription`; see [ConnectionStatus].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TrackerConnectionSheet(
    status: ConnectionStatus,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .testTag("tracker:connection:sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.connection_details_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(status.stateLabelRes),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("tracker:connection:state"),
            )
            Text(
                text = stringResource(status.explanationRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = syncedLine(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tracker:connection:synced"),
            )

            if (status.warnsWritesDisabled) {
                Text(
                    text = stringResource(R.string.tracker_writes_need_live),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("tracker:connection:read-only"),
                )
            }

            if (status.canRetry) {
                Text(
                    text = stringResource(R.string.connection_details_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FilledTonalButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("tracker:connection:retry"),
                ) {
                    Text(stringResource(R.string.connection_details_retry))
                }
            }
        }
    }
}

/**
 * "Is what I'm looking at current?", in one sentence.
 *
 * Split out of the layout so the three cases are visible together — the bug this shape
 * prevents is the never-synced case silently rendering a formatted `null`, which is how
 * the old strip could have shown a confident "synced 00:00" for a sheet that had never
 * loaded. [ConnectionStatus.syncedAt] is already `null` rather than `"00:00"` for that
 * case; this is the second half of the same guarantee.
 */
@Composable
private fun syncedLine(status: ConnectionStatus): String = when {
    status.syncedAt == null -> stringResource(R.string.connection_details_never_synced)
    status.showingSnapshot -> stringResource(R.string.tracker_snapshot_synced, status.syncedAt)
    else -> stringResource(R.string.connection_details_synced, status.syncedAt)
}
