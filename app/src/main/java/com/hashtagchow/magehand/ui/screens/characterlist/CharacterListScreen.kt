package com.hashtagchow.magehand.ui.screens.characterlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.ui.screens.dmview.DM_VIEW_MAX_MEMBERS
import com.hashtagchow.magehand.ui.screens.dmview.DM_VIEW_MIN_MEMBERS
import com.hashtagchow.magehand.ui.screens.dmview.DmPickerState
import com.hashtagchow.magehand.ui.screens.dmview.canOfferDmView
import com.hashtagchow.magehand.ui.window.LocalExpandedWidth

/**
 * Screen 2 — character list (docs/design/04-screens-ux.md §2).
 *
 * Renders whatever is cached immediately and reconciles when the live
 * `characterList` subscription lands ("Never block on network"). The connection
 * chip is always visible, so a stale list is never mistaken for a live one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListScreen(
    onCharacterClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onNewCharacterClick: () -> Unit,
    onLocalCharacterClick: (String) -> Unit,
    onNewLocalCharacterClick: () -> Unit,
    onDmViewClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dmPicker by viewModel.dmPicker.collectAsStateWithLifecycle()
    var creatorMenuOpen by rememberSaveable { mutableStateOf(false) }

    // FR-19 decisions 11 and 12, joined here — this is the app's ONE width question, read from
    // the local `WindowSizeGate` publishes at the activity root, and `canOfferDmView` is the one
    // place the two halves of the entry rule meet. See `LocalExpandedWidth`.
    val offersDmView = canOfferDmView(
        serverCharacterCount = uiState.characters.size,
        expandedWidth = LocalExpandedWidth.current,
    )

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.title_character_list))
                        uiState.accountLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // Decision 12: on smaller widths the entry is **absent**, not disabled. A
                    // greyed-out button would teach the user the app has a feature they cannot
                    // have, rather than that their window is small.
                    if (offersDmView) {
                        TextButton(
                            onClick = viewModel::openDmPicker,
                            modifier = Modifier.testTag("list:dm-view"),
                        ) {
                            Text(stringResource(R.string.dm_view_open))
                        }
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 04 §2's FAB opened the PWA's own creator, full stop. FR-5 gives it a second
            // possible meaning, so it now asks which — but only when both answers exist.
            // Signed out there is no DiceCloud creator to offer, and a menu with one item
            // is a tap the user pays for nothing (09 decision 3: the signed-out list is
            // "local + the create affordance", not local + a submenu).
            //
            // …and until the account has resolved, *neither* answer is known, so the button
            // is absent rather than guessing — see CharacterListUiState.showsCreateAffordance.
            Box {
                if (uiState.showsCreateAffordance) FloatingActionButton(
                    onClick = {
                        if (uiState.hasAccount == true) {
                            creatorMenuOpen = true
                        } else {
                            onNewLocalCharacterClick()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(
                            if (uiState.hasAccount == true) {
                                R.string.action_new_character
                            } else {
                                R.string.action_new_local_character
                            },
                        ),
                    )
                }
                DropdownMenu(
                    expanded = creatorMenuOpen,
                    onDismissRequest = { creatorMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_dicecloud_character)) },
                        onClick = { creatorMenuOpen = false; onNewCharacterClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_local_character)) },
                        onClick = { creatorMenuOpen = false; onNewLocalCharacterClick() },
                    )
                }
            }
        },
    ) { innerPadding ->
        dmPicker?.let { picker ->
            DmViewPickerSheet(
                state = picker,
                onToggle = viewModel::toggleDmMember,
                onDismiss = viewModel::dismissDmPicker,
                onConfirm = { viewModel.confirmDmSelection(onDmViewClick) },
            )
        }

        Column(Modifier.padding(innerPadding)) {
            // Absent, not "Connecting…", when there is no account — see
            // CharacterListUiState.showsConnection.
            if (uiState.showsConnection) {
                ConnectionStrip(state = uiState.connection, error = uiState.error)
            }

            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { if (uiState.canRefresh) viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isLoadingFirstPage -> CenteredMessage {
                        CircularProgressIndicator()
                    }

                    uiState.isEmpty -> CenteredMessage {
                        Text(
                            text = stringResource(R.string.character_list_empty),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(
                                if (uiState.hasAccount == true) {
                                    R.string.character_list_empty_hint
                                } else {
                                    R.string.character_list_empty_hint_local
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.characters, key = { it.creatureId }) { character ->
                            CharacterCard(
                                character = character,
                                onClick = { onCharacterClick(character.creatureId) },
                            )
                        }

                        // 09 decision 3: "below the signed-in account's characters (or alone
                        // when signed out)". The header carries the section on its own when
                        // there are no account cards above it, which is why it is not
                        // conditional on there being any.
                        if (uiState.localCharacters.isNotEmpty()) {
                            item(key = "local-header") {
                                SectionHeader(stringResource(R.string.character_list_on_this_device))
                            }
                            items(uiState.localCharacters, key = { "local-${it.id}" }) { character ->
                                LocalCharacterCard(
                                    character = character,
                                    onClick = { onLocalCharacterClick(character.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "On this device" heading.
 *
 * A plain label rather than a card: it is a divider with a name, and giving it the same
 * elevation as the rows underneath would make it look like a character called "On this
 * device".
 */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A local character's card (09 decision 3).
 *
 * Deliberately the same shape as [CharacterCard] — monogram, name, subtitle in the same two
 * styles — and deliberately not the same function. What it drops is everything that belongs
 * to a DiceCloud character: there is no portrait to load (the form has no picture field), and
 * no owner badge, because "not mine" cannot happen for a character that exists only here.
 * Sharing the composable would have meant three `null` arguments and two `if`s inside it, to
 * express that this row is simpler.
 */
@Composable
private fun LocalCharacterCard(
    character: LocalCharacterCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("list:local:${character.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = character.monogram,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (character.subtitle.isNotEmpty()) {
                    Text(
                        text = character.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The always-visible connection state (docs/design/04-screens-ux.md, UX
 * principles: "Connection state is always visible, never a surprise error
 * dialog"). WP6 grows this into the full status strip with the concentration
 * banner; here it is the connection plus any non-fatal subscription error.
 */
@Composable
private fun ConnectionStrip(state: ConnectionState, error: String?) {
    val (label, container) = when (state) {
        ConnectionState.LIVE ->
            stringResource(R.string.connection_live) to MaterialTheme.colorScheme.secondaryContainer

        ConnectionState.CONNECTING ->
            stringResource(R.string.connection_connecting) to MaterialTheme.colorScheme.tertiaryContainer

        ConnectionState.OFFLINE ->
            stringResource(R.string.connection_offline) to MaterialTheme.colorScheme.surfaceVariant

        ConnectionState.AUTH_FAILED ->
            stringResource(R.string.connection_auth_failed) to MaterialTheme.colorScheme.errorContainer
    }

    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CharacterCard(
    character: CharacterSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Portrait(character)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (character.subtitle.isNotEmpty()) {
                    Text(
                        text = character.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!character.isOwnedByMe) {
                // 04 §2: "owner badge when not mine (DM view shows the whole
                // party — this is the DM feature in v1)". The publication does not
                // send the owning user's document, so there is no name to show.
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(R.string.character_badge_shared),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * The portrait, with the monogram drawn *underneath* rather than as an error
 * painter: the table's sheets store HeroForge **configurator** links in `picture`,
 * which are HTML pages, not images. Coil simply fails to decode them and the
 * monogram is what remains — no error state, no layout jump.
 */
@Composable
private fun Portrait(character: CharacterSummary) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = character.monogram,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (character.picture != null) {
            AsyncImage(
                model = character.picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

/**
 * FR-19's membership picker (docs/design/14-large-screen-arc.md decisions 11 and 16).
 *
 * ### Why the picker is here and not inside the dashboard
 *
 * Decision 17's binding rule is *"subscribe the set ONCE on entry"*. A picker inside the
 * dashboard would make changing the table a tear-down and a fresh burst against a subscription
 * rate limit the **whole table** shares (50 per 10 s, global across users). Choosing on the list
 * means the dashboard opens knowing its members and never changes them. `DmPickerState`'s KDoc
 * carries the full argument.
 *
 * ### The over-cap rows stay tappable
 *
 * Decision 16's maximum of six is enforced in `toggleDmMember`, not by disabling every unticked
 * row the moment the sixth is ticked — a list that goes half-dead reads as broken, where a sheet
 * that says *"That is the maximum — untick one to swap"* reads as a rule. Same argument the pane
 * picker makes for its last-checked segment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DmViewPickerSheet(
    state: DmPickerState,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("list:dm-picker"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.dm_view_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.dm_view_picker_body,
                    DM_VIEW_MIN_MEMBERS,
                    DM_VIEW_MAX_MEMBERS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (state.isFull) {
                    stringResource(R.string.dm_view_picker_full)
                } else {
                    stringResource(R.string.dm_view_picker_remaining, state.remaining)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(state.candidates, key = { "dm-${it.creatureId}" }) { character ->
                    val selected = character.creatureId in state.selected
                    // One merged node per row carrying name *and* membership: a `Checkbox` beside
                    // an unlabelled `Text` announces "checked" about nothing in particular, which
                    // on a list of six similar names is the one thing a screen-reader user cannot
                    // reconstruct. Same rule as the inventory tab's summary rows.
                    val spoken = stringResource(
                        R.string.dm_view_picker_row,
                        character.name,
                        stringResource(
                            if (selected) {
                                R.string.dm_view_picker_selected
                            } else {
                                R.string.dm_view_picker_unselected
                            },
                        ),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { contentDescription = spoken }
                            .testTag("list:dm-picker:${character.creatureId}"),
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggle(character.creatureId) },
                        )
                        Text(
                            text = character.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    // Decision 16's minimum of two. `canConfirm` is the rule, stated once in
                    // `DmPickerState` and read here — the sheet's own copy already says why.
                    enabled = state.canConfirm,
                    modifier = Modifier.testTag("list:dm-picker:confirm"),
                ) {
                    Text(stringResource(R.string.dm_view_picker_confirm))
                }
            }
        }
    }
}
