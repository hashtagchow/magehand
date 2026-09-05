package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.core.model.ConcentrationPrompt
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.panes.CharacterHomeChrome
import com.hashtagchow.magehand.ui.panes.HomeOverflowCustomize
import com.hashtagchow.magehand.ui.panes.HomeOverflowMenu
import com.hashtagchow.magehand.ui.panes.HomeTabRow
import com.hashtagchow.magehand.ui.panes.PaneOrderSheet
import com.hashtagchow.magehand.ui.panes.PanePicker
import com.hashtagchow.magehand.ui.panes.PaneRow
import com.hashtagchow.magehand.ui.panes.characterHomeChrome
import com.hashtagchow.magehand.ui.panes.homeOverflowHistory
import com.hashtagchow.magehand.ui.panes.resolvePaneLayout
import com.hashtagchow.magehand.ui.panes.serverHomeTabs
import com.hashtagchow.magehand.ui.panes.serverPaneSurfaces
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsScreen
import com.hashtagchow.magehand.ui.screens.characterhome.quests.QuestLogSheet
import com.hashtagchow.magehand.ui.panes.sheetWanted
import com.hashtagchow.magehand.ui.panes.surface
import com.hashtagchow.magehand.ui.window.LocalExpandedWidth
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.AddItemSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryActions
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryCustomizeSheet
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.InventoryTab
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.ItemDetailSheet
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpNumberPadDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.RestConfirmDialog
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ShakeSignal
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConcentrationPromptBanner
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
 *
 * ### FR-17: the same screen, two kinds of chrome (14 decisions 5-10)
 *
 * On an EXPANDED-width window the tab row becomes a multi-select pane picker and the chosen
 * surfaces render as equal-weight columns. Everything else on this screen — the app bar, the
 * sheets, the dialogs, the snackbar, the view model — is unchanged, and that is the point:
 * `characterHomeChrome` returns which of the two to draw, and both branches call the *same*
 * three tab bodies with the *same* arguments. The bodies are extracted to [TrackerPane],
 * [InventoryPane] and [SheetPane] purely so the two branches cannot drift apart.
 *
 * The phone path is structurally untouched (decision 5): with `LocalExpandedWidth` false — its
 * default, and every compact and medium window — this composes the same tab row and `when` it
 * composed before FR-17. FR-27 moved the row itself into [HomeTabRow], shared with the local
 * screen, because its order and its label measuring are now rules that have to hold on both
 * screens; the branch it sits in is unchanged, and `PaneSelectionTest` pins that.
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
    // FR-27's order sheet. A third flag rather than one shared with the two above, for their
    // reason: three sheets, three controls, three pieces of saved state.
    var paneOrderOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    // FR-32's log, on its own flag for the reason every other sheet on this screen has one.
    var questsOpen by rememberSaveable { mutableStateOf(false) }
    // FR-31's prompt (18 decisions 9–12). **Not** `rememberSaveable`, and the distinction is the
    // feature: a concentration check belongs to the moment its damage landed, so a prompt that
    // survived process death would greet the player with a check for a hit taken before the app
    // was killed. It is also not a snackbar — see `ConcentrationPromptBanner` for why the
    // assertive live region decision 12 asks for rules that widget out.
    var concentrationPrompt by remember { mutableStateOf<ConcentrationPrompt?>(null) }
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
    // FR-26 decision 1's one-tab-drop: the Actions tab exists only for a character whose sheet
    // has something to act with. `remember(hasActions)` rather than a bare `remember`, because
    // this list now depends on data that arrives after the first composition — the old
    // `remember { entries }` would have frozen the pre-discovery answer forever.
    val hasActions = uiState.hasActions
    val tabs = remember(hasActions) { serverHomeTabs(hasActions) }
    val availablePanes = remember(hasActions) { serverPaneSurfaces(hasActions) }

    // 04 §3's two snackbars, from the view model's two event streams, in **two** coroutines.
    //
    // `SnackbarHostState.showSnackbar` suspends until the snackbar goes away, so one collector
    // over both kinds is serial by construction: it queues confirmations rather than stacking
    // them, which is what the 5 s undo window wants — and it makes a failure wait out every
    // confirmation ahead of it, which is what it emphatically does not want. Two collectors
    // decouple them; `writeEvents`' one-deep drop-oldest buffer is the other half, and it only
    // works because nothing merges the two streams back together on the way here (see its KDoc).
    val undoLabel = stringResource(R.string.action_undo)
    // M3 [architect ruling]: `describe()` is deliberately plain Kotlin (JVM-testable, no
    // Android context), so the dropped-use copy — the one case here that IS a string
    // resource — is read here and substituted in, rather than taught to that function.
    val useDroppedMessage = stringResource(R.string.action_use_dropped)
    LaunchedEffect(viewModel) {
        viewModel.writeEvents.collect { event ->
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
    }

    LaunchedEffect(viewModel) {
        viewModel.failureEvents.collect { event ->
            // The number has already snapped back — the optimistic overlay drops a
            // failed op rather than reversing it — so all that is left is saying so.
            shake = ShakeSignal(event.failure.propertyId, event.failure.id)
            // A confirmation currently on screen is *superseded* by this, not queued behind
            // it: `showSnackbar` takes a mutex the visible snackbar is holding, so without the
            // dismiss the failure would still wait out its full `Short` duration. Dismissing
            // costs the player a receipt whose number they have already seen; keeping it costs
            // them four seconds of not being told a write was lost.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = if (event.failure.dropped) useDroppedMessage else event.failure.describe(),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // FR-31. One prompt at a time: a second replaces the first rather than stacking, which is what
    // "snackbar-priority" means for a banner — the newer check is the one the player owes.
    LaunchedEffect(viewModel) {
        viewModel.concentrationPrompts.collect { concentrationPrompt = it }
    }

    // The prompt outlives its cause but not its subject: a character who has stopped concentrating
    // — because the player dropped it, or the buff lapsed, or another client ended it — has no
    // check to make, so the banner goes with it. Keyed on the banner's source so this fires on the
    // transition rather than on every recomposition.
    val concentratingOn = uiState.tracker.concentratingOn
    LaunchedEffect(concentratingOn) {
        if (concentratingOn == null) concentrationPrompt = null
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

    // 14 decisions 5 + 10. Both halves of the state are read here and exactly one is rendered;
    // neither is derived from the other, which is what makes crossing the gate lossless in both
    // directions. See `characterHomeChrome`.
    val panes by viewModel.panes.collectAsStateWithLifecycle()
    // FR-27: resolved ONCE, here, and handed to all three consumers — the chrome, the picker's
    // toggle and the order sheet. Three readers of one order is the shape that cannot disagree
    // with itself; see `characterHomeChrome`'s `layout` parameter.
    val layout = remember(panes, availablePanes) { resolvePaneLayout(panes, availablePanes) }
    val chrome = characterHomeChrome(
        expandedWidth = LocalExpandedWidth.current,
        selectedTab = selectedTab,
        layout = layout,
        // The gated tab list, so a tab cannot be drawn without its pane or the reverse. The tab
        // branch also needs this to resolve a saved `Actions` selection on a character that has
        // none — see `resolveTab`.
        availableTabs = tabs,
        surfaceOf = { it.surface },
    )

    // 14 decision 9: in pane mode the WebView's lifetime is the Sheet pane's selection, not the
    // sticky "ever opened" flag the tab path uses. `rememberSheetWebViewState` destroys the
    // instance when handed a null session, so deselecting the pane is the destroy.
    val sheetState = rememberSheetWebViewState(
        uiState.session.takeIf { sheetWanted(chrome, sheetEverOpened) },
    )

    MageHandTheme(accentColor = uiState.accentColor) {
        Scaffold(
            contentWindowInsets = screenContentWindowInsets,
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                HomeAppBar(
                    title = uiState.characterName
                        ?: stringResource(R.string.title_character_home),
                    onBack = onBack,
                    trackerShowing = CharacterHomeTab.Tracker.isShowing(chrome),
                    inventoryShowing = CharacterHomeTab.Inventory.isShowing(chrome),
                    trackerCanWrite = uiState.tracker.canWrite,
                    inventoryCanWrite = uiState.inventory.canWrite,
                    onShortRest = { restToConfirm = RestKind.SHORT },
                    onLongRest = { restToConfirm = RestKind.LONG },
                    onAddItem = { addItemOpen = true },
                ) {
                    // 1.9.1: the wrench(es), quests, pane-order and settings — every
                    // low-frequency action this bar carries — collapse into one overflow
                    // menu, and FR-39 adds history to that list. See `HomeOverflowMenu`'s
                    // KDoc for why five is the cap this bar now respects and the arithmetic
                    // behind it (the operator's screenshot: the back arrow overlapping
                    // "Short" on a bar that could carry up to nine controls at once).
                    HomeOverflowMenu(
                        onPaneOrder = { paneOrderOpen = true },
                        settingsLabel = stringResource(R.string.action_settings),
                        onSettings = onSettingsClick,
                        // FR-39: the tracker's session sheet, gated on the same tab the
                        // wrench below is, since it is that tab's action. Its label and its
                        // tag are `homeOverflowHistory`'s and not this screen's — unlike the
                        // wrench, both screens' history item is the same item.
                        history = if (CharacterHomeTab.Tracker.isShowing(chrome)) {
                            homeOverflowHistory { historyOpen = true }
                        } else {
                            null
                        },
                        customize = when {
                            CharacterHomeTab.Tracker.isShowing(chrome) -> HomeOverflowCustomize(
                                labelRes = R.string.customize_title,
                                testTag = "tracker:customize:open",
                                onClick = { customizeOpen = true },
                            )
                            CharacterHomeTab.Inventory.isShowing(chrome) -> HomeOverflowCustomize(
                                labelRes = R.string.inventory_customize_title,
                                testTag = "inventory:customize:open",
                                onClick = { inventoryCustomizeOpen = true },
                            )
                            else -> null
                        },
                        // FR-32 decision 14: discovery-gated, so a character with no quest
                        // notes carries no entry (decision 14: "present only when ≥1 quest
                        // note exists").
                        quests = if (uiState.hasQuests) {
                            HomeOverflowCustomize(
                                labelRes = R.string.quests_title,
                                testTag = "quests:open",
                                onClick = { questsOpen = true },
                            )
                        } else {
                            null
                        },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // FR-31's prompt, above everything — including the tab row, because it is a
                // statement about the *character* rather than about whichever surface is on
                // screen, and because the DM card path (decision 9) puts it in the same place.
                // Its Drop action is the existing `toggle` intent against the property the prompt
                // names, which is why this feature added no intent at all.
                concentrationPrompt?.let { prompt ->
                    ConcentrationPromptBanner(
                        prompt = prompt,
                        canWrite = uiState.tracker.canWrite,
                        onDrop = { toggleId ->
                            viewModel.toggleCondition(toggleId)
                            concentrationPrompt = null
                        },
                        onDismiss = { concentrationPrompt = null },
                    )
                }

                // One body, two chromes. Every argument below is identical in both branches
                // because they are the same call — see the class KDoc.
                val tracker = @Composable {
                    TrackerTab(
                        state = uiState.tracker,
                        actions = TrackerActions(
                            onSpend = viewModel::spend,
                            onRestore = viewModel::restore,
                            onHpDelta = viewModel::changeHitPoints,
                            onHpTap = { hpPadOpen = true },
                            // FR-22 (15 decisions 5 and 6). `onHpSet` is the number pad's own
                            // Set intent, reached by the long press instead of the pad.
                            onHpSet = viewModel::setHitPoints,
                            onResourceSet = viewModel::setResourceValue,
                            onItemDelta = viewModel::adjustItem,
                            onItemSet = viewModel::setItemQuantity,
                            onDeathSaves = viewModel::setDeathSaves,
                            onToggle = viewModel::toggleCondition,
                            onSelectRoll = viewModel::selectRoll,
                            onConnectionDetails = { connectionOpen = true },
                        ),
                        shake = shake,
                    )
                }
                val inventory = @Composable {
                    InventoryTab(
                        state = uiState.inventory,
                        actions = InventoryActions(
                            onEquip = viewModel::setEquipped,
                            onCoinDelta = viewModel::adjustCoins,
                            onCoinSet = viewModel::setCoins,
                            onQuantityDelta = viewModel::adjustItemQuantity,
                            onQuantitySet = viewModel::setInventoryItemQuantity,
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
                }
                // FR-26 + FR-28. Still no actions *bundle*: the surface has exactly one gesture
                // (17 decision 2's Use), so it takes one callback rather than the grouped
                // lambdas the tracker and inventory need. The callback's first parameter is a
                // `UseTarget` and not a property id — see `ActionsScreen`'s KDoc for why that
                // difference is the gate rather than a style choice.
                val actions = @Composable {
                    ActionsScreen(
                        state = uiState.actions,
                        onUse = viewModel::use,
                    )
                }
                val sheet = @Composable {
                    if (sheetState == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        SheetWebViewHost(state = sheetState, modifier = Modifier.fillMaxSize())
                    }
                }

                when (chrome) {
                    is CharacterHomeChrome.Tabs -> {
                        // `chrome.tabs`, not `tabs`: FR-27 puts the row in the player's order and
                        // the chrome is what resolved it. BUG-4's single-line labels live in
                        // `HomeTabRow`, shared with the local screen — see its KDoc.
                        HomeTabRow(
                            tabs = chrome.tabs,
                            selected = chrome.selected,
                            onSelect = { selectedTab = it },
                            titleResId = { it.titleResId },
                        )

                        when (chrome.selected) {
                            CharacterHomeTab.Tracker -> tracker()
                            CharacterHomeTab.Inventory -> inventory()
                            CharacterHomeTab.Actions -> actions()
                            CharacterHomeTab.Sheet -> sheet()
                        }
                    }

                    is CharacterHomeChrome.Panes -> {
                        PanePicker(
                            panes = chrome.panes,
                            // The resolved order, which is already gated: passing the ungated
                            // list would draw an Actions segment for a pane `resolvePaneLayout`
                            // refuses to render — a control that does nothing — and would put
                            // the segments in a different order from the columns beneath them.
                            available = layout.map { it.surface },
                            // The *resolved* arrangement, not the stored one — see `togglePane`.
                            onToggle = { viewModel.togglePane(layout, it) },
                        )
                        PaneRow(panes = chrome.panes) { surface ->
                            when (surface) {
                                PaneSurface.TRACKER -> tracker()
                                PaneSurface.INVENTORY -> inventory()
                                PaneSurface.ACTIONS -> actions()
                                PaneSurface.SHEET -> sheet()
                            }
                        }
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

            if (questsOpen) {
                QuestLogSheet(
                    // Already ordered and prefix-stripped by `QuestEngine` — open above closed,
                    // sheet order within each group (decision 13). The sheet draws; it does not
                    // sort.
                    quests = uiState.quests,
                    onDismiss = { questsOpen = false },
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
                    onQuantitySet = viewModel::setInventoryItemQuantity,
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
                    onSetSortCriterion = viewModel::setInventorySortCriterion,
                    onSetSortDirection = viewModel::setInventorySortDirection,
                )
            }

            if (paneOrderOpen) {
                PaneOrderSheet(
                    // The same resolved order the chrome drew, so the sheet lists what the
                    // player is looking at — including, on a phone, the surfaces that are not
                    // open as panes. See `PaneOrderSheet`.
                    order = layout.map { it.surface },
                    onDismiss = { paneOrderOpen = false },
                    onMove = { surface, delta -> viewModel.movePane(layout, surface, delta) },
                    onReset = viewModel::resetPaneLayout,
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

/**
 * The character-home top app bar: back, title, this tab's actions, and the overflow menu.
 *
 * ### Why it is its own composable (FR-39 leftover, 1.14.2)
 *
 * A **pure move** out of [CharacterHomeScreen]'s `topBar` slot, with no behaviour change — every
 * gate, every tag and every `enabled` is the caller's, exactly as it was inline. It moved because
 * this bar is the one surface FR-39 argued about — five elements against ~240 dp at 150 % on a
 * 360 dp phone — with zero pixel coverage, and the 1.14.1 device sweep then found the title
 * rendering *zero* characters at that scale rather than the sliver the wave claimed. A composable
 * that a golden can construct is what turns that from an estimate into a picture:
 * `NarrowWidthGoldenTest`'s `HomeAppBar_320_150.png`. Hilt wires the screen; nothing wires this.
 *
 * ### Why the overflow is a slot
 *
 * [overflow] is passed rather than parameterised because the menu's contents are entirely the
 * screen's: which tab gates the wrench, whether this character has quests, where history goes.
 * Spelling them out here would have moved that decision off the screen `PaneSelectionTest` reads
 * to prove history is in the menu and not on the bar — and history's test tag has exactly one
 * definition, `homeOverflowHistory` in `PaneChrome.kt`, which is the point of that test. (Not
 * quoted here, deliberately: that test reads this file for the literal, and a copy of it in a
 * comment would be indistinguishable from a copy of it on the bar.)
 *
 * ### The compact rule (FR-43 / BUG-17, 1.14.2)
 *
 * The golden above caught the bar drawing its back arrow **under** the "S" of "Short" at 320 dp
 * × 150 %, and the title rendering nothing at all. Below [HOME_APP_BAR_COMPACT_WIDTH] the two
 * rest buttons drop their text and become icons, which returns ~10–12 dp each to the title. It
 * is a **fit** rule and not a scale rule — see that constant for why, and for the arithmetic.
 *
 * Nothing else about the bar changes with it: same tags, same `enabled`, same callbacks. The
 * spoken label does change, and deliberately — see [RestAction].
 *
 * @param trackerShowing whether the Tracker surface is on screen — a tab selected, or a pane open.
 *   In pane mode both it and [inventoryShowing] can be true and both sets of actions belong here;
 *   see `isShowing`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeAppBar(
    title: String,
    onBack: () -> Unit,
    trackerShowing: Boolean,
    inventoryShowing: Boolean,
    trackerCanWrite: Boolean,
    inventoryCanWrite: Boolean,
    onShortRest: () -> Unit,
    onLongRest: () -> Unit,
    onAddItem: () -> Unit,
    overflow: @Composable () -> Unit,
) = BoxWithConstraints {
    // The bar asks how much room it was given, not how large the user's text is. See
    // HOME_APP_BAR_COMPACT_WIDTH.
    val compact = maxWidth < HOME_APP_BAR_COMPACT_WIDTH

    TopAppBar(
        title = {
            Text(
                text = title,
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
            if (trackerShowing) {
                // 04 §3's Short/Long rest buttons live in the app bar. Neither writes
                // directly: a rest rewrites the whole sheet and cannot be undone, so both
                // open the confirm dialog that lists what will reset.
                //
                // FR-43: below the fit threshold they draw as icons, and speak the rest
                // dialog's fuller titles rather than the one-word button labels — see
                // `RestAction` for why a glyph called "Short" is a worse sentence than the text
                // button was. The buttons do the same thing in a smaller coat.
                RestAction(
                    compact = compact,
                    iconRes = R.drawable.ic_rest_short,
                    labelRes = R.string.tracker_short_rest,
                    descriptionRes = R.string.tracker_rest_title_short,
                    testTag = "tracker:rest:short",
                    enabled = trackerCanWrite,
                    onClick = onShortRest,
                )
                RestAction(
                    compact = compact,
                    iconRes = R.drawable.ic_rest_long,
                    labelRes = R.string.tracker_long_rest,
                    descriptionRes = R.string.tracker_rest_title_long,
                    testTag = "tracker:rest:long",
                    enabled = trackerCanWrite,
                    onClick = onLongRest,
                )
            }
            // FR-8's add affordance lives in the app bar, beside the tracker's rest actions,
            // for the reason those are there: it belongs to *one tab* and the bar is where
            // this screen already puts per-tab actions. A FAB would have floated over the
            // last Carried row on every scroll, on the one tab that is a long list. It stays
            // on the bar rather than moving into the overflow menu for the same reason Short
            // and Long do: it is what a player reaches for most on this tab, not a
            // once-a-session control. (History used to be in that list; FR-39 found it was
            // not — see `HomeOverflowMenu`'s KDoc for the supersession.)
            if (inventoryShowing) {
                IconButton(
                    onClick = onAddItem,
                    enabled = inventoryCanWrite,
                    colors = mageHandIconButtonColors(),
                    modifier = Modifier.testTag("inventory:add"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.inventory_add),
                    )
                }
            }
            overflow()
        },
    )
}

/**
 * The width below which [HomeAppBar] draws Short and Long as icons rather than as text buttons
 * (FR-43, which fixes BUG-17).
 *
 * ### The arithmetic
 *
 * Five fixed elements, at their minimum widths: back 48 + Short 58 + Long 58 + overflow 48 =
 * **212 dp** (58 dp is `ButtonDefaults.MinWidth`, and it is a *floor* — a real "Short" measures
 * nearer 61 dp, more at a larger font scale). A title needs somewhere around **72 dp** to be a
 * title rather than an ellipsis glyph, so the bar is comfortable at **284 dp** and is not below
 * it. Compact retires **~10–12 dp per rest button** — an `IconButton` is 48 dp against that
 * 58 dp floor, and against the ~60 dp a real one measures with its own text in it — which is a
 * smaller saving than it sounds and still the whole difference here: the four non-title elements
 * fall from 212 dp to 192 dp, and the title goes from nothing to ~21 dp at 320 dp × 150 % and
 * ~70 dp at 393 dp × 150 %.
 *
 * ### Why it is a fit rule and not a scale rule
 *
 * The obvious spelling is "compact at ≥150 %", and it is wrong twice over. It would leave 320 dp
 * × 125 % — 256 effective dp, still short — in the crowded layout, and it would put a 1000 dp
 * tablet at 150 % into the compact one for no reason at all. What the bar is short of is *room*,
 * and room is what `BoxWithConstraints` measures: `ProvideUiScale` multiplies density, so the
 * `maxWidth` the bar is handed is already in the scaled dp its children lay out in. One question,
 * asked of the thing that actually varies.
 *
 * Every width at or above this renders exactly as it did in 1.14.1 — which is every phone at
 * 100 %, and is why this change costs no golden but the two the bar itself owns.
 */
internal val HOME_APP_BAR_COMPACT_WIDTH: Dp = 284.dp

/**
 * One rest action, as a text button or as an icon (FR-43).
 *
 * The two spellings are one composable so that the tag, the `enabled` flag and the callback
 * cannot drift apart between them — the failure this shape prevents is a compact bar whose
 * buttons are reachable by a *different* tag, which every test and every sweep step would keep
 * passing right through.
 *
 * ### The two modes do not say the same words, and that is the ruling
 *
 * The first cut reused [labelRes] as the icon's `contentDescription`, on the argument that a
 * screen reader should hear the same sentence either side of the threshold. The review's L5 says
 * that argument runs the wrong way: *"Short"* is a tolerable label only because a sighted user
 * reads it next to *"Long"* on a bar they can see, and a glyph whose entire spoken name is
 * "Short" is a **worse** sentence than the text button ever was — there is nothing beside it to
 * disambiguate. So compact speaks [descriptionRes], the rest dialog's own
 * `tracker_rest_title_short`/`_long` (*"Short rest"* / *"Long rest"*), and text mode keeps
 * [labelRes] as the visible word it has always drawn.
 *
 * Everything that is not the label is identical across the threshold — the tag, the `enabled`
 * flag, the callback — which is why the two spellings are one composable: the failure this shape
 * prevents is a compact bar reachable by a *different* tag, which every test and every sweep
 * step would keep passing right through.
 *
 * @param labelRes the visible word in text mode: `tracker_short_rest` / `tracker_long_rest`.
 * @param descriptionRes what compact mode *says*: the fuller `tracker_rest_title_*` phrase.
 */
@Composable
private fun RestAction(
    compact: Boolean,
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    @StringRes descriptionRes: Int,
    testTag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (compact) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = mageHandIconButtonColors(),
            modifier = Modifier.testTag(testTag),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(descriptionRes),
            )
        }
    } else {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        ) {
            Text(stringResource(labelRes))
        }
    }
}

/**
 * Whether this tab's surface is on screen right now — one tab selected, or one of several panes.
 *
 * The app bar's per-tab actions (rest, history, customize, add) hang off this rather than off
 * `selectedTab`, because in pane mode there is no selected tab: Tracker and Inventory can both be
 * up, and both sets of actions belong in the bar. That is also why the bar is not split per pane
 * — 14 decision 6 replaces the *tab row*, not the app bar, and a per-column action bar would have
 * been a fourth place this screen puts controls.
 */
private fun CharacterHomeTab.isShowing(chrome: CharacterHomeChrome<CharacterHomeTab>): Boolean =
    when (chrome) {
        is CharacterHomeChrome.Tabs -> chrome.selected == this
        is CharacterHomeChrome.Panes -> surface in chrome.panes
    }
