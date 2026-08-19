package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
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
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var hpPadOpen by rememberSaveable { mutableStateOf(false) }
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
