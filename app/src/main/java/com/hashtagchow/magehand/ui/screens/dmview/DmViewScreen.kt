package com.hashtagchow.magehand.ui.screens.dmview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets

/**
 * FR-19's DM dashboard (docs/design/14-large-screen-arc.md decisions 12, 14 and 19).
 *
 * ### What this composable is allowed to decide
 *
 * Almost nothing. Every rule that could be wrong in a way a human would not notice lives in
 * `DmViewSelection.kt`, `DmCardUiState.kt` or `DmViewUiState.kt` and is pinned there —
 * `:app` has no Compose test harness (`StartDestinationNavigationTest` says why), so a rule that
 * only exists as a branch here can be checked by reading the source and in no other way. In
 * particular this file never asks *whether* a card may be edited: it reads
 * [DmCardUiState.showsWriteControls], which is one function with one test per condition.
 *
 * The two things it does decide are layout, and both are handed a number: the column count comes
 * from [dmGridColumns], and the width it is measured against is the grid's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmViewScreen(
    onBack: () -> Unit,
    onCharacterClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DmViewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    // FR-25 decision 10: **default collapsed**, and not persisted — a glance during one session
    // at a table, like this screen's own editing toggle. `rememberSaveable` so a rotation
    // mid-read does not shut it.
    var feedExpanded by rememberSaveable { mutableStateOf(false) }

    // 06 §Snapshot lifecycle step 2: "mirror → snapshot refresh on every app-background",
    // `CharacterHomeScreen`'s hook verbatim, for all the open cards at once.
    //
    // Wiring it rather than deleting `captureSnapshots()`, which had no caller: this is the
    // screen that makes 06 step 2 *matter most*. Six live mirrors, none of them cached, means a
    // cold re-open of any of those characters — the ones a DM is provably about to look at —
    // starts on a spinner instead of a sheet. The character screen has had this hook since WP6;
    // the dashboard shipping without one is the omission, not the hook being redundant.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.captureSnapshots()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dm_view_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Decision 18's capability gate: absent, not disabled, when nothing on this
                    // table is editable. See `DmViewUiState.showsEditingToggle`.
                    if (uiState.showsEditingToggle) {
                        EditingToggle(
                            state = uiState,
                            onCheckedChange = viewModel::setEditingEnabled,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            if (uiState.showsEditingBanner) EditingBanner()
            uiState.error?.let { ErrorBanner(message = it, onDismiss = viewModel::dismissError) }

            when {
                uiState.isLoading -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator()
                }

                // FR-25 decision 10: the grid and the feed side by side. A `Row`, not an
                // overlay, so `DmCardGrid`'s own `BoxWithConstraints` measures the width that is
                // actually left to it and `dmGridColumns` stays correct — an overlay would have
                // let it keep counting columns against the full window.
                else -> Row(Modifier.fillMaxSize()) {
                    DmCardGrid(
                        cards = uiState.cards,
                        onCardClick = onCharacterClick,
                        onSpend = viewModel::spend,
                        onRestore = viewModel::restore,
                        onChangeHitPoints = viewModel::changeHitPoints,
                        onToggleCondition = viewModel::toggleCondition,
                        modifier = Modifier.weight(1f),
                    )
                    DmFeedPanel(
                        entries = feed,
                        expanded = feedExpanded,
                        onToggle = { feedExpanded = !feedExpanded },
                    )
                }
            }
        }
    }
}

/**
 * Decision 12's adaptive grid.
 *
 * ### Why `BoxWithConstraints` and a fixed column count rather than `GridCells.Adaptive`
 *
 * `Adaptive(minSize)` would put the same arithmetic inside the grid where nothing can assert it,
 * and the threshold that matters here is a property of the *card's content* (see
 * [MIN_CARD_WIDTH_DP]), not of a layout convention. Measuring once and asking [dmGridColumns] is
 * what makes decision 12's "adaptive" a rule with a test rather than a constant in a call.
 *
 * `maxWidth` is already in scaled dp, so FR-18's density factor moves the column count — which is
 * correct and is stated on [dmGridColumns]: a DM who asked for bigger text gets bigger cards and
 * therefore fewer of them, rather than the same six squeezed.
 */
@Composable
private fun DmCardGrid(
    cards: List<DmCardUiState>,
    onCardClick: (String) -> Unit,
    onSpend: (String, String, Int) -> Unit,
    onRestore: (String, String, Int) -> Unit,
    onChangeHitPoints: (String, Int) -> Unit,
    onToggleCondition: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = dmGridColumns(maxWidth.value.toInt())
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().testTag("dm:grid:$columns"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.creatureId }) { card ->
                DmCard(
                    card = card,
                    onClick = { onCardClick(card.creatureId) },
                    onSpend = { propertyId -> onSpend(card.creatureId, propertyId, 1) },
                    onRestore = { propertyId -> onRestore(card.creatureId, propertyId, 1) },
                    onChangeHitPoints = { delta -> onChangeHitPoints(card.creatureId, delta) },
                    onToggleCondition = { propertyId ->
                        onToggleCondition(card.creatureId, propertyId)
                    },
                )
            }
        }
    }
}

/**
 * Decision 14's toggle, with the spoken state the same decision's TalkBack note asks for.
 *
 * ### Why `clearAndSetSemantics` around the whole control
 *
 * A `Switch` announces its own on/off state, but announces it about a label the row draws
 * beside it — so a TalkBack user hears "Enable editing" and "on", from two nodes, with no
 * indication of *what it would affect*. On this screen that number is the whole meaning: turning
 * editing on with one editable card and with five are very different acts. Collapsing the pair
 * into one node carrying `dm_view_editing_state` is the same merge the pane picker's group label
 * does (14 decision 4's note), applied to a control whose consequence is a write to somebody
 * else's character sheet.
 *
 * The `Role` and the toggle action survive the clear, because `toggleable` semantics come from
 * the `Switch` itself and `clearAndSetSemantics` is applied to the wrapper, not to it.
 */
@Composable
private fun EditingToggle(
    state: DmViewUiState,
    onCheckedChange: (Boolean) -> Unit,
) {
    val stateWord = stringResource(
        if (state.editingEnabled) R.string.dm_view_editing_on else R.string.dm_view_editing_off,
    )
    val spoken = stringResource(
        R.string.dm_view_editing_state,
        stateWord,
        state.editableCardCount,
        state.cards.size,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("dm:editing-toggle"),
    ) {
        Text(
            text = stringResource(R.string.dm_view_editing_toggle),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = state.editingEnabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * Decision 14's *"unmistakable persistent banner while ON"*.
 *
 * Three things make it unmistakable, and each is doing a job:
 *
 *  - **`errorContainer`**, not a tertiary or a neutral tint. This is the app's loudest surface and
 *    it is used here for something that is not an error, deliberately: the state it reports is one
 *    where an accidental tap changes another person's character sheet.
 *  - **Persistent**, with no dismiss. A banner that could be waved away would leave editing on and
 *    the warning gone, which is the exact state decision 14 exists to prevent.
 *  - **A live region**, so TalkBack announces the change at the moment the toggle flips rather
 *    than only when a user happens to swipe onto it. It is spoken with its own sentence
 *    (`dm_view_editing_banner_spoken`) rather than the visible copy, because the visible line is
 *    written to be read at a glance and the spoken one has to open with the word "Warning".
 */
@Composable
private fun EditingBanner() {
    val spoken = stringResource(R.string.dm_view_editing_banner_spoken)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Assertive
            }
            .testTag("dm:editing-banner"),
    ) {
        Text(
            text = stringResource(R.string.dm_view_editing_banner),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Decision 18's *"surfaced error"* — the half that is a sentence, beside the half that is a card
 * going read-only.
 *
 * Dismissible where [EditingBanner] is not, and the asymmetry is the point: the banner reports a
 * *state* that is still true, this reports an *event* that has already happened. Dismissing it
 * does not restore the card (see `DmViewViewModel.permissionDenied`), which is why the copy talks
 * about the card rather than about the tap.
 */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag("dm:error"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close),
                )
            }
        }
    }
}

/**
 * Suppresses a subtree's own semantics entirely.
 *
 * Used on a card's *read* half so the merged summary sentence is what a screen reader gets,
 * rather than the sentence plus the eleven fragments it was built from. `clearAndSetSemantics`
 * with an empty block rather than `mergeDescendants`, because merging would still expose the
 * children's own descriptions underneath the group.
 */
internal fun Modifier.spokenAs(description: String): Modifier =
    clearAndSetSemantics { contentDescription = description }
