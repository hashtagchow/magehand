package com.hashtagchow.magehand.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.RadioRow
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets

/**
 * Screen 6 — settings and accounts (docs/design/04-screens-ux.md §6).
 *
 * WP5 delivers the multi-server account switcher and sign-out. Per-character
 * accent colour, portrait override and "about" are WP8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingSignOutOf by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_accounts),
                style = MaterialTheme.typography.titleMedium,
            )

            uiState.accounts.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioRow(
                        selected = account.id == uiState.activeAccountId,
                        title = account.username,
                        subtitle = account.serverUrl.removePrefix("https://"),
                        onClick = { viewModel.switchTo(account.id) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmingSignOutOf = account.id }) {
                        Text(stringResource(R.string.action_sign_out))
                    }
                }
            }

            if (uiState.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = onSignedOut,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_add_account))
            }

            Text(
                text = stringResource(R.string.settings_signout_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_tracker),
                style = MaterialTheme.typography.titleMedium,
            )

            // FR-6 (docs/design/09-local-characters.md decision 9). The whole row is the
            // target, not just the thumb: a 24 dp switch on a settings list is the smallest
            // thing on the screen and the label is the part people aim at.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowToggles(!uiState.showToggles) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_show_toggles),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_show_toggles_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.showToggles,
                    onCheckedChange = viewModel::setShowToggles,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .testTag("settings:show-toggles"),
                )
            }
        }
    }

    confirmingSignOutOf?.let { accountId ->
        // "no destructive action without undo or confirm" (04, UX principles).
        AlertDialog(
            onDismissRequest = { confirmingSignOutOf = null },
            title = { Text(stringResource(R.string.signout_dialog_title)) },
            text = { Text(stringResource(R.string.signout_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOutOf = null
                    viewModel.signOut(accountId, onSignedOut)
                }) {
                    Text(stringResource(R.string.action_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOutOf = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
