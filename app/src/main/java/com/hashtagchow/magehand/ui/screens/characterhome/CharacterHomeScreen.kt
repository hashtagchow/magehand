package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.AddItemSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryActions
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryCustomizeSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryTab
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.ItemDetailSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpNumberPadDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RestConfirmDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ShakeSignal
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerActions
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerConnectionSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerCustomizeSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerHistorySheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.describe
import com.hashtagchow.magehand.ui.theme.MageHandTheme
import com.hashtagchow.magehand.ui.theme.mageHandIconButtonColors
import com.hashtagchow.magehand.ui.webview.SheetWebViewHost
import com.hashtagchow.magehand.ui.webview.rememberSheetWebViewState

/**
 * Screens 3 + 4 — character home (docs/design/04-screens-ux.md).
 *
 * **WP6 scope: the Tracker tab, read-only, plus the customize sheet.** WP5 built the
 * Sheet tab; WP7 makes the tracker writable.
 *
 * The whole subtree is re-themed with the character's accent colour (04 §Theming: "the
 * per-character accent color seeds the scheme"). Nesting a second `MageHandTheme` here
 * rather than hoisting the accent to `MainActivity` is deliberate: the accent belongs to
 * *this* character, and the character list, settings and login screens must keep the app
 * palette — a scheme that changed depending on which character you last opened would be a
 * bug, not a feature.
 *
 * The Sheet WebView is created at screen level and only its host leaves composition on a
 * tab switch — that is what "retained instance" (04 §4) buys. It is created lazily on
 * first visit, so a user who never opens it never pays for the page load.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterHomeScreen(
    onBack: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(CharacterHomeTab.Tracker) }
    var sheetEverOpened by rememberSaveable { mutableStateOf(false) }
    var customizeOpen by rememberSaveable { mutableStateOf(false) }
    // The inventory tab's own wrench (12 decision 3). A second flag rather than one shared with
    // the tracker's sheet: the two sheets are different controls over different state, and one
    // flag would make a rotation on the inventory tab reopen whichever sheet was last used.
    var inventoryCustomizeOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var hpPadOpen by rememberSaveable { mutableStateOf(false) }
    var addItemOpen by rememberSaveable { mutableStateOf(false) }
    // The *id* of the item whose detail sheet is open, not the row — see `InventoryUiState.row`.
    // `rememberSaveable` so a rotation mid-read does not shut the sheet.
    var detailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    // Survives the sheet going live under it on purpose: the sheet then renders its LIVE
    // copy rather than vanishing mid-read. See TrackerConnectionSheet.
    var connectionOpen by rememberSaveable { mutableStateOf(false) }
    var restToConfirm by rememberSaveable { mutableStateOf<RestKind?>(null) }
    var shake by remember { mutableStateOf<ShakeSignal?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = remember { CharacterHomeTab.entries }

    // 04 §3's two snackbars, from one event stream.
    //
    // `SnackbarHostState.showSnackbar` suspends until the snackbar goes away, so this
    // collector is *serial by construction*: a burst of taps queues its confirmations
    // instead of stacking them, and the 5 s undo window is the snackbar's own `Short`
    // duration rather than a timer we would have to cancel by hand.
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
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

                is TrackerEvent.Failed -> {
                    // The number has already snapped back — the optimistic overlay drops a
                    // failed op rather than reversing it — so all that is left is saying so.
                    shake = ShakeSignal(event.failure.propertyId, event.failure.id)
                    snackbarHostState.showSnackbar(
                        message = event.failure.describe(),
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == CharacterHomeTab.Sheet) sheetEverOpened = true
    }

    // 06 §Snapshot lifecycle step 2: "mirror → snapshot refresh on every app-background".
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.captureSnapshot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sheetState = rememberSheetWebViewState(uiState.session.takeIf { sheetEverOpened })

    MageHandTheme(accentColor = uiState.accentColor) {
        Scaffold(
            contentWindowInsets = screenContentWindowInsets,
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.characterName
                                ?: stringResource(R.string.title_character_home),
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
                        if (selectedTab == CharacterHomeTab.Tracker) {
                            // 04 §3's Short/Long rest buttons live in the app bar. Neither
                            // writes directly: a rest rewrites the whole sheet and cannot
                            // be undone, so both open the confirm dialog that lists what
                            // will reset.
                            TextButton(
                                onClick = { restToConfirm = RestKind.SHORT },
                                enabled = uiState.tracker.canWrite,
                                modifier = Modifier.testTag("tracker:rest:short"),
                            ) {
                                Text(stringResource(R.string.tracker_short_rest))
                            }
                            TextButton(
                                onClick = { restToConfirm = RestKind.LONG },
                                enabled = uiState.tracker.canWrite,
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
                        // FR-8's add affordance lives in the app bar, beside the tracker's
                        // rest/history/customize actions, for the reason those are there:
                        // it belongs to *one tab* and the bar is where this screen already
                        // puts per-tab actions. A FAB would have floated over the last
                        // Carried row on every scroll, on the one tab that is a long list.
                        if (selectedTab == CharacterHomeTab.Inventory) {
                            IconButton(
                                onClick = { addItemOpen = true },
                                enabled = uiState.inventory.canWrite,
                                colors = mageHandIconButtonColors(),
                                modifier = Modifier.testTag("inventory:add"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.inventory_add),
                                )
                            }
                            // 12 decision 3: "the same wrench → sheet affordance as the
                            // tracker" — same icon, same place in the bar, three actions along
                            // from the tracker's own. Not gated on `canWrite`, unlike the add
                            // button beside it: an arrangement is a local preference and stays
                            // editable while the socket is down, exactly as the tracker's
                            // customize sheet does.
                            IconButton(
                                onClick = { inventoryCustomizeOpen = true },
                                modifier = Modifier.testTag("inventory:customize:open"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Build,
                                    contentDescription = stringResource(
                                        R.string.inventory_customize_title,
                                    ),
                                )
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
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
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
                    CharacterHomeTab.Tracker -> TrackerTab(
                        state = uiState.tracker,
                        actions = TrackerActions(
                            onSpend = viewModel::spend,
                            onRestore = viewModel::restore,
                            onHpDelta = viewModel::changeHitPoints,
                            onHpTap = { hpPadOpen = true },
                            onItemDelta = viewModel::adjustItem,
                            onToggle = viewModel::toggleCondition,
                            onSelectRoll = viewModel::selectRoll,
                            onConnectionDetails = { connectionOpen = true },
                        ),
                        shake = shake,
                    )

                    CharacterHomeTab.Inventory -> InventoryTab(
                        state = uiState.inventory,
                        actions = InventoryActions(
                            onEquip = viewModel::setEquipped,
                            onCoinDelta = viewModel::adjustCoins,
                            onQuantityDelta = viewModel::adjustItemQuantity,
                            onRowTap = { detailItemId = it },
                            // FR-9. Both arrive from the detail sheet, which is where the
                            // confirm and the picker live — nothing on the list can reach them.
                            onDelete = viewModel::removeItem,
                            onMove = viewModel::moveItem,
                            // FR-16. On the tab rather than in the customize sheet
                            // (13 decision 6), but stored in the same per-character key.
                            onCollapse = viewModel::setInventorySectionCollapsed,
                        ),
                    )

                    CharacterHomeTab.Sheet ->
                        if (sheetState == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            SheetWebViewHost(state = sheetState, modifier = Modifier.fillMaxSize())
                        }
                }
            }

            if (connectionOpen) {
                TrackerConnectionSheet(
                    status = uiState.tracker.status,
                    onRetry = viewModel::reconnect,
                    onDismiss = { connectionOpen = false },
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

            // Re-resolved from the *current* state every recomposition, so a sync landing
            // under an open sheet updates it. See `InventoryUiState.row`.
            val detailRow = detailItemId?.let { uiState.inventory.row(it) }
            if (detailItemId != null && detailRow == null) {
                // The item stopped existing underneath an open sheet — another client
                // removed it. Closing in an effect rather than inline: writing state during
                // composition is how a recomposition loop starts, and this path is rare
                // enough that nobody would ever reproduce the loop to find it.
                LaunchedEffect(detailItemId) { detailItemId = null }
            }
            detailRow?.let { row ->
                ItemDetailSheet(
                    row = row,
                    canWrite = uiState.inventory.canWrite,
                    onQuantityDelta = viewModel::adjustItemQuantity,
                    onEquip = viewModel::setEquipped,
                    onEquippableOverride = viewModel::setEquippableOverride,
                    onDismiss = { detailItemId = null },
                    // Already filtered of the container the item is in (12 decision 8):
                    // a picker listing where you already are is a control with a no-op in it.
                    moveTargets = uiState.inventory.moveTargetsFor(row),
                    onDelete = viewModel::removeItem,
                    onMove = viewModel::moveItem,
                )
            }

            if (addItemOpen) {
                AddItemSheet(
                    // A DiceCloud character: no category chooser, because a DiceCloud item is
                    // classified by its tags and `NewItemSpec.category` never reaches the wire.
                    // See `AddItemSheet`'s KDoc.
                    isLocal = false,
                    onAdd = viewModel::addItem,
                    onDismiss = { addItemOpen = false },
                )
            }

            if (inventoryCustomizeOpen) {
                InventoryCustomizeSheet(
                    state = uiState.inventory.customize,
                    onDismiss = { inventoryCustomizeOpen = false },
                    onMove = viewModel::moveInventorySection,
                    onSetHidden = viewModel::setInventorySectionHidden,
                    onReset = viewModel::resetInventoryLayout,
                )
            }

            if (customizeOpen) {
                TrackerCustomizeSheet(
                    state = uiState.customize,
                    onDismiss = { customizeOpen = false },
                    onMove = viewModel::moveRow,
                    onSetHidden = viewModel::setRowHidden,
                    onSetPinned = viewModel::setRowPinned,
                    onAccentChosen = viewModel::setAccentColor,
                    onReset = viewModel::resetCustomizations,
                )
            }
        }
    }
}
