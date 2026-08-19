package com.hashtagchow.magehand.ui.screens.local

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.ui.navigation.LocalCharacterHomeTab
import com.hashtagchow.magehand.ui.screens.characterhome.TrackerEvent
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.AddItemSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryActions
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryTab
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.ItemDetailSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpNumberPadDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RestConfirmDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerActions
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerCustomizeSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerHistorySheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.describe
import com.hashtagchow.magehand.ui.theme.mageHandIconButtonColors

/**
 * A local character's home (docs/design/09-local-characters.md decisions 5–8).
 *
 * ### What is the same
 *
 * The tracker. `TrackerTab` is reused verbatim, rendering the same `TrackerUiState` — pips,
 * HP block, number pad, rest confirm, history sheet, customize sheet, undo snackbar. Every
 * write goes through the same `OpenCharacter` intents, which is what `WritePostureTest`
 * asserts and what made decision 5's "reused, not forked" cheap enough to be true.
 *
 * ### What is different, and why each one
 *
 * - **Two tabs, not three: Tracker · Inventory** (docs/design/10-inventory.md decision 1).
 *   Until FR-8 this screen had *no* tab row, and the reason was stated plainly: a
 *   `PrimaryTabRow` with one tab is a control that cannot do anything, costing a row of the
 *   vertical space this screen spends on pips. That argument was correct and it expired the
 *   moment there was a second destination — the row now goes somewhere, so it earns its
 *   height. What has **not** changed is the reason there is no third tab: there is no
 *   `SheetSession` on [LocalCharacterHomeUiState] for one to render, so 09 decision 8's "the
 *   Sheet tab absent" stays structural rather than conditional. `LocalCharacterHomeTab` has no
 *   Sheet constant, which is where that guarantee lives.
 * - **No connection dot, ever.** Pinned on the state, not left to arithmetic — see
 *   `TrackerUiState.hasConnection`. There is therefore also no connection sheet and no
 *   `onConnectionDetails` action.
 * - **The reference strip** (decision 6), directly under the app bar: level, the six scores
 *   with modifiers, AC. Above the tracker rather than below it because it is reference for
 *   the rows underneath, and because a strip at the bottom of a scrolling list is a strip
 *   nobody reads.
 * - **An Edit action** in place of the DiceCloud path's Settings icon, because the form is
 *   the only editor (decision 4) and this is the screen you are on when you notice a number
 *   is wrong.
 * - **No accent theming.** `theme_prefs` is account-keyed; see `LocalOpenCharacter.accentColor`.
 *   The screen therefore does not nest a `MageHandTheme`, and inherits the app palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalCharacterHomeScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalCharacterHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(LocalCharacterHomeTab.Tracker) }
    var customizeOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var hpPadOpen by rememberSaveable { mutableStateOf(false) }
    var addItemOpen by rememberSaveable { mutableStateOf(false) }
    var detailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var restToConfirm by rememberSaveable { mutableStateOf<RestKind?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = remember { LocalCharacterHomeTab.entries }

    // The undo snackbar, identical to the DiceCloud tracker's — `showSnackbar` suspends until
    // the snackbar goes away, so a burst of taps queues rather than stacking.
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // `LocalOpenCharacter.writeFailures` never emits, so `Failed` is unreachable here;
            // the `when` stays total rather than assuming that from a KDoc.
            when (event) {
                is TrackerEvent.Wrote -> {
                    if (!event.write.undoable) {
                        snackbarHostState.showSnackbar(event.write.describe())
                        return@collect
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = event.write.describe(),
                        actionLabel = undoLabel,
                        withDismissAction = false,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoLastWrite()
                }

                is TrackerEvent.Failed -> Unit
            }
        }
    }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.characterName ?: stringResource(R.string.title_character_home),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Per-tab, exactly as the DiceCloud bar is. These were unconditional
                    // while this screen had one tab; now that it has two, a "Customize
                    // tracker" button sitting over the inventory list would be a control
                    // pointing at a screen the player is not looking at.
                    if (selectedTab == LocalCharacterHomeTab.Tracker) {
                        TextButton(
                            onClick = { restToConfirm = RestKind.SHORT },
                            modifier = Modifier.testTag("tracker:rest:short"),
                        ) {
                            Text(stringResource(R.string.tracker_short_rest))
                        }
                        TextButton(
                            onClick = { restToConfirm = RestKind.LONG },
                            modifier = Modifier.testTag("tracker:rest:long"),
                        ) {
                            Text(stringResource(R.string.tracker_long_rest))
                        }
                        IconButton(
                            onClick = { historyOpen = true },
                            modifier = Modifier.testTag("tracker:history:open"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.tracker_history_title),
                            )
                        }
                        IconButton(onClick = { customizeOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = stringResource(R.string.customize_title),
                            )
                        }
                    }
                    if (selectedTab == LocalCharacterHomeTab.Inventory) {
                        IconButton(
                            onClick = { addItemOpen = true },
                            colors = mageHandIconButtonColors(),
                            modifier = Modifier.testTag("inventory:add"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.inventory_add),
                            )
                        }
                    }
                    IconButton(
                        onClick = { onEdit(uiState.characterId) },
                        modifier = Modifier.testTag("local:edit"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.action_edit_character),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Above the tabs, not inside one: level, the six scores and AC are reference for
            // *both* tabs — Strength is what the inventory's capacity line is computed from —
            // so hanging it off the tracker would have made the number vanish exactly where
            // it explains something.
            uiState.reference?.let { ReferenceStrip(it) }

            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(tab.titleResId)) },
                    )
                }
            }

            when (selectedTab) {
                LocalCharacterHomeTab.Tracker -> TrackerTab(
                    state = uiState.tracker,
                    actions = TrackerActions(
                        onSpend = viewModel::spend,
                        onRestore = viewModel::restore,
                        onHpDelta = viewModel::changeHitPoints,
                        onHpTap = { hpPadOpen = true },
                        onItemDelta = viewModel::adjustItem,
                        // A local board carries no toggles (09 decision 4), so nothing can
                        // reach this; there is also no connection sheet to open.
                        onToggle = {},
                        onSelectRoll = viewModel::selectRoll,
                        onConnectionDetails = {},
                    ),
                )

                LocalCharacterHomeTab.Inventory -> InventoryTab(
                    state = uiState.inventory,
                    actions = InventoryActions(
                        onEquip = viewModel::setEquipped,
                        onCoinDelta = viewModel::adjustCoins,
                        onQuantityDelta = viewModel::adjustItemQuantity,
                        onRowTap = { detailItemId = it },
                    ),
                )
            }
        }

        // Same live-lookup + close-in-an-effect shape as the DiceCloud screen's; see there.
        val detailRow = detailItemId?.let { uiState.inventory.row(it) }
        if (detailItemId != null && detailRow == null) {
            LaunchedEffect(detailItemId) { detailItemId = null }
        }
        detailRow?.let { row ->
            ItemDetailSheet(
                row = row,
                canWrite = uiState.inventory.canWrite,
                onQuantityDelta = viewModel::adjustItemQuantity,
                onEquip = viewModel::setEquipped,
                onDismiss = { detailItemId = null },
            )
        }

        if (addItemOpen) {
            AddItemSheet(
                onAdd = viewModel::addItem,
                onDismiss = { addItemOpen = false },
            )
        }

        if (historyOpen) {
            TrackerHistorySheet(
                rows = uiState.tracker.history,
                onUndo = { viewModel.undoLastWrite() },
                onDismiss = { historyOpen = false },
            )
        }

        uiState.tracker.hp?.let { hp ->
            if (hpPadOpen) {
                HpNumberPadDialog(
                    current = hp.current,
                    max = hp.max,
                    onDamage = { viewModel.changeHitPoints(-it) },
                    onHeal = { viewModel.changeHitPoints(it) },
                    onSet = viewModel::setHitPoints,
                    onDismiss = { hpPadOpen = false },
                )
            }
        }

        restToConfirm?.let { kind ->
            RestConfirmDialog(
                kind = kind,
                state = uiState.tracker,
                onConfirm = { viewModel.rest(kind) },
                onDismiss = { restToConfirm = null },
            )
        }

        if (customizeOpen) {
            TrackerCustomizeSheet(
                state = uiState.customize,
                onDismiss = { customizeOpen = false },
                onMove = viewModel::moveRow,
                // Reorder-only: the sheet renders none of these controls for a local
                // character (TrackerCustomizeState.reorderOnly), so nothing can call them.
                onSetHidden = { _, _ -> },
                onSetPinned = { _, _ -> },
                onAccentChosen = {},
                onReset = {},
            )
        }
    }
}

/**
 * 09 decision 6's read-only strip: *Level 5 · STR 16 (+3) · … · AC 15*.
 *
 * Horizontally scrollable, because six ability cells plus a level and an AC do not fit a
 * 360 dp phone at a legible size and the alternative — wrapping onto a second line — would
 * take a second row of vertical space away from the pips on every character.
 *
 * Not tappable, anywhere: decision 6 says editing goes through the form, and a strip that
 * responded to a tap would compete with the Edit action for that job while doing it worse.
 * Nothing here has a click handler, so there is no affordance to find.
 */
@Composable
private fun ReferenceStrip(state: LocalReferenceState, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .testTag("local:reference"),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.level?.let { ReferenceCell(label = it, value = null, testTag = "local:reference:level") }

            state.abilities.forEach { ability ->
                ReferenceCell(
                    label = ability.label,
                    value = "${ability.score} (${ability.modifier})",
                    testTag = "local:reference:${ability.label}",
                )
            }

            ReferenceCell(label = state.armorClass, value = null, testTag = "local:reference:ac")
        }
    }
}

/**
 * One cell. [value] is `null` for the two cells whose label *is* the fact ("Level 5", "AC 15")
 * — printing "Level" over "5" would spend two lines saying what one already says.
 */
@Composable
private fun ReferenceCell(label: String, value: String?, testTag: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.testTag(testTag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
