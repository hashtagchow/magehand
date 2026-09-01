package com.hashtagchow.magehand.ui.screens.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets

/**
 * Screen 1 — sign in (docs/design/04-screens-ux.md §1).
 *
 * Signs in to the official dicecloud.com by default; a collapsed "Advanced"
 * section reveals a custom-server field for self-hosted DiceCloud instances.
 * No server is pre-listed besides the official one (operator directive
 * 2026-08-17: the app ships no private server addresses).
 *
 * The password lives in a plain `remember` (**not** `rememberSaveable`): saveable
 * state is written into the saved-instance `Bundle`, which is persisted by the
 * system and readable from a bug report. docs/design/05-security.md says the
 * password is "never persisted, cleared from memory after the call" — so it never
 * enters saved state, never enters the ViewModel, and is gone on the first
 * configuration change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
    onSignedIn: () -> Unit,
    onContinueWithoutAccount: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CredentialsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var usernameOrEmail by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    /**
     * FR-33: whether the password is currently readable.
     *
     * **A plain `remember`, deliberately not `rememberSaveable`** — the same rule [password]
     * itself follows, one step further out. Saveable state is written into the saved-instance
     * `Bundle`, which the system persists and a bug report can contain; a revealed password must
     * not survive process death, and neither must the *fact* that it was revealed. The field is
     * re-masked on the first configuration change along with the password being cleared, which is
     * the safe direction: the worst case is a player re-typing, and the alternative is a screen
     * that comes back from the background with somebody's password on it in a coffee shop.
     */
    var passwordVisible by remember { mutableStateOf(false) }
    var showCustomServer by rememberSaveable { mutableStateOf(false) }
    var customServer by rememberSaveable { mutableStateOf("") }

    val canSubmit = usernameOrEmail.isNotBlank() && password.isNotEmpty() && !uiState.isSubmitting
    val submit = {
        if (canSubmit) {
            viewModel.signIn(
                serverInput = if (showCustomServer) customServer else "",
                usernameOrEmail = usernameOrEmail,
                password = password,
                onSignedIn = onSignedIn,
            )
        }
    }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_login)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.credentials_headline),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(
                    R.string.credentials_server_line,
                    if (showCustomServer && customServer.isNotBlank()) {
                        customServer.trim()
                    } else {
                        stringResource(R.string.server_official)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = usernameOrEmail,
                onValueChange = {
                    usernameOrEmail = it
                    viewModel.dismissError()
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isSubmitting,
                label = { Text(stringResource(R.string.credentials_username_label)) },
                isError = uiState.error != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )

            // Resolved out here: `semantics {}` is not a `@Composable` scope, so a
            // `stringResource` call inside it would not compile.
            val showPasswordLabel = stringResource(
                if (passwordVisible) R.string.action_hide_password else R.string.action_show_password,
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    viewModel.dismissError()
                },
                // M7: marks the field as a password node for accessibility services and
                // Autofill — structural (nothing on screen changes), and independent of
                // [passwordVisible]: the semantics describe what the field IS, not whether the
                // characters happen to be masked right now.
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { password() }
                    // FR-34's one inert handle on this screen. `PasswordVisibilityTest` has to
                    // reach *this* field and not the username one above it, and the two are
                    // otherwise distinguishable only by their label text, which is copy and would
                    // make the test fail on a rewording rather than on the rule it pins. A test
                    // tag changes nothing on screen and nothing in the accessibility tree the
                    // `password()` call above already established.
                    .testTag("credentials:password"),
                singleLine = true,
                enabled = !uiState.isSubmitting,
                label = { Text(stringResource(R.string.credentials_password_label)) },
                isError = uiState.error != null,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                // FR-33. A text affordance and not an eye icon, and that is a dependency decision
                // rather than a taste one: `Visibility`/`VisibilityOff` live in
                // `material-icons-extended`, which this app deliberately does not depend on
                // (~50 MB of vectors — see `libs.versions.toml`, where icons-core is pinned with
                // that reason). One word in the trailing slot costs nothing and says the same
                // thing.
                trailingIcon = {
                    TextButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !uiState.isSubmitting,
                        // The spoken label is the whole phrase where the visible one is a word —
                        // "Show" alone tells a screen-reader user nothing about what it shows.
                        // Set on the merged button node, where it takes precedence over the
                        // child `Text`, so TalkBack reads the sentence and the eye reads the word.
                        modifier = Modifier
                            .semantics {
                                contentDescription = showPasswordLabel
                            }
                            .testTag("credentials:password:visibility"),
                    ) {
                        Text(
                            stringResource(
                                if (passwordVisible) {
                                    R.string.credentials_password_hide
                                } else {
                                    R.string.credentials_password_show
                                },
                            ),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )

            AnimatedVisibility(visible = showCustomServer) {
                OutlinedTextField(
                    value = customServer,
                    onValueChange = {
                        customServer = it
                        viewModel.dismissError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.isSubmitting,
                    label = { Text(stringResource(R.string.server_custom_label)) },
                    placeholder = { Text(stringResource(R.string.server_custom_placeholder)) },
                    supportingText = { Text(stringResource(R.string.server_https_note)) },
                    isError = uiState.error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            TextButton(
                onClick = {
                    showCustomServer = !showCustomServer
                    if (!showCustomServer) customServer = ""
                    viewModel.dismissError()
                },
                enabled = !uiState.isSubmitting,
            ) {
                Text(
                    stringResource(
                        if (showCustomServer) {
                            R.string.action_use_official_server
                        } else {
                            R.string.action_use_custom_server
                        },
                    ),
                )
            }

            uiState.error?.let { error ->
                // Verbatim from the server / the typed ApiException…
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // …plus the hint that says which field to look at.
                uiState.hint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = submit,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.action_sign_in))
                }
            }

            Text(
                text = stringResource(R.string.credentials_token_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // FR-5 (docs/design/09-local-characters.md decision 3). Below the sign-in button
            // and styled as the lesser affordance, because signing in is still what most
            // people opened this screen to do — but present, because for the rest of them
            // this is the only way in. Signing in later is a Settings tap away, and 09
            // decision 10 guarantees the local characters survive it either way.
            TextButton(
                onClick = onContinueWithoutAccount,
                enabled = !uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_continue_without_account))
            }
            Text(
                text = stringResource(R.string.credentials_local_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
