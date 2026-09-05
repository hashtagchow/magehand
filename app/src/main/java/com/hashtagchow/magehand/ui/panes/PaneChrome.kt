package com.hashtagchow.magehand.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.PaneSurface

/**
 * Decision 6's pane picker: *"a top-bar segmented multi-select of the surfaces that exist for
 * this character"*, replacing the tab row when the window is EXPANDED-width.
 *
 * ### Why a multi-choice segmented row and not checkboxes or a menu
 *
 * It is the control the tab row was, doing one more thing. A `PrimaryTabRow` and a
 * `MultiChoiceSegmentedButtonRow` occupy the same strip of the screen, read left-to-right in the
 * same order, and are operated with the same tap — so the tablet layout is not a different app,
 * it is the same row that now lets you keep two of them. A menu would have hidden the state the
 * player is choosing behind a tap, and checkboxes would have spent three rows saying what one
 * row says.
 *
 * ### The last pane stays enabled
 *
 * Decision 6's minimum of one is enforced in [togglePane], not by disabling the last checked
 * button. A disabled segment in an otherwise live row reads as *broken*, and the player has no
 * way to know it is a rule rather than a bug; a segment that stays checked when tapped reads as
 * *already on*, which is what it is. The rule is stated once, in [togglePane]'s KDoc, and
 * pinned in `PaneSelectionTest`.
 *
 * @param panes what is on screen now, as [openPanes] produced it — never the raw stored
 *   arrangement, for [togglePane]'s reason.
 * @param available every surface this character has, **in the player's order** (FR-27 decision
 *   1). The picker is the tab row's replacement, so it reorders with it: a row whose segments sat
 *   in a different order from the columns underneath them would be a control pointing at the
 *   wrong thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanePicker(
    panes: List<PaneSurface>,
    available: List<PaneSurface>,
    onToggle: (PaneSurface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupLabel = stringResource(R.string.panes_picker_label)
    MultiChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // The group announces itself once; each segment then reads its own label and
            // checked state, the way FR-18's scale control does (14 decision 4's TalkBack note
            // applied to this row).
            .semantics { contentDescription = groupLabel }
            .testTag("panes:picker"),
    ) {
        available.forEachIndexed { index, surface ->
            SegmentedButton(
                checked = surface in panes,
                onCheckedChange = { onToggle(surface) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = available.size),
                modifier = Modifier.testTag("panes:picker:${surface.key}"),
            ) {
                // BUG-4's rule, applied to the picker for the same reason it is applied to the
                // tab row: `MultiChoiceSegmentedButtonRow` gives every segment an equal share of
                // the width, so the longest label is the one that runs out of room — and a
                // *segmented button* that wraps mid-word looks broken in a way a paragraph does
                // not. Truncate, never wrap. See [HomeTabRow].
                Text(
                    text = stringResource(surface.titleResId),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The phone chrome: the tab row, over the tabs in the order [CharacterHomeChrome.Tabs] resolved.
 *
 * ### Why both home screens now call one composable instead of writing their own row
 *
 * They wrote the same seven lines twice, which was fine while the row was `PrimaryTabRow` around
 * `entries.forEach`. FR-27 gives the row an order to respect and BUG-4 gives its labels a
 * measuring rule to respect, and a rule that has to hold on two screens holds on one composable
 * or it holds on neither — the DiceCloud screen's row is the one the operator saw wrap, and the
 * local screen's row is the one nobody would have thought to check.
 *
 * ### BUG-4: "Inventory" wrapped to "Inventor / y" on a four-tab phone row
 *
 * `PrimaryTabRow` divides the width into equal cells, so four tabs on a 360-411 dp phone leave
 * each label ~90-102 dp. Material's own `Tab(text = …)` then spends **32 dp of that on padding**
 * (`HorizontalTextPadding`, 16 dp a side) and lets the label wrap into the two-line tab slot.
 * "Inventory" at `titleSmall` needs about 70 dp, so on the common phone widths it lands a pixel
 * or two over and breaks mid-word — the ugliest possible failure, and one that got worse with
 * every UI-scale step above 100 %.
 *
 * The fix is both halves of the ruling, in the order the ruling gives them:
 *
 *  1. **The label is single-line, always.** `maxLines = 1` **and** `softWrap = false`: the first
 *     alone still breaks the word and shows one line of the result, which is the same bug wearing
 *     a smaller hat. `softWrap = false` is what makes wrapping unrepresentable.
 *  2. **The padding comes back.** 4 dp a side instead of 16, which is what buys the room the
 *     operator could see going spare. Reaching it means composing the content-slot `Tab` overload
 *     rather than the `text =` one, because Material's padding is applied *inside* the slot and
 *     there is no parameter for it. That overload is the documented escape hatch and it hands
 *     back everything except the height and the text style, both of which are restated below
 *     from the same Material tokens (`PrimaryNavigationTabTokens.ContainerHeight` is 48 dp;
 *     `LabelTextFont` is `titleSmall`), so the row measures and reads exactly as it did.
 *  3. **Ellipsis is the insurance, not the plan.** At 100 % every label fits. At 150 % the
 *     longest one may truncate, and that is the accepted outcome: truncation is legible and
 *     mid-word wrapping is not.
 *
 * `PaneSelectionTest` pins (1) structurally, because `:app` has no Compose test harness and a
 * measuring rule that lives only in a composable is a rule the next edit deletes as noise.
 *
 * @param titleResId the tab's label. A lambda rather than an interface on the two tab enums:
 *   `CharacterHomeTab` and `LocalCharacterHomeTab` are deliberately unrelated types (see
 *   `PaneSurfaces`), and giving them a shared supertype to satisfy one composable would undo the
 *   guarantee that a local character cannot name the Sheet.
 */
@Composable
fun <T> HomeTabRow(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    titleResId: (T) -> Int,
) {
    // The index within the DRAWN list, not the enum ordinal. Those agreed while every tab was
    // always drawn in declaration order; with the Actions tab gated (FR-26) and the row
    // reorderable (FR-27) they do not, and `ordinal` would put the selection indicator under the
    // wrong tab.
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected), modifier = modifier) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.height(TAB_HEIGHT),
            ) {
                Text(
                    text = stringResource(titleResId(tab)),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = TAB_LABEL_PADDING),
                )
            }
        }
    }
}

/** `PrimaryNavigationTabTokens.ContainerHeight`, which the content-slot `Tab` does not apply. */
private val TAB_HEIGHT = 48.dp

/** Material's own is 16 dp a side, and 32 dp is more than a four-tab phone row can spare. */
private val TAB_LABEL_PADDING = 4.dp

/**
 * Decision 7's layout: *"equal-weight columns in a Row"*.
 *
 * ### Equal weight, and what that is instead of
 *
 * No drag-resize in v1 (decision 7, recorded out of scope), and no per-surface weighting either
 * — the tracker is not "more important" than the sheet in a way this app could know, and a
 * hard-coded 2:1 split would be a guess that every character disagreed with differently. Equal
 * columns are the one split that needs no justification per character.
 *
 * ### Per-pane scroll and collapse independence, and why [key] is required for it
 *
 * Decision 7 requires each pane to own its scroll and collapse state. Most of that is true by
 * construction: the pane bodies are the same composables the tabs render, each with its own
 * `rememberScrollState`/`LazyListState` inside its own subtree, the per-character stores behind
 * collapse are keyed by *character*, and a surface appears at most once (the picker is a set —
 * `PaneSelectionTest` pins it), so two columns can never key the same state.
 *
 * What is **not** free is which column owns which state when the set *changes*. Compose's
 * default identity for a `forEachIndexed` body is positional, so with Tracker + Sheet on screen
 * and the player adding the Inventory pane — which sorts to the left of the Sheet (decision 6:
 * panes are places) — slot 1 goes from Sheet to Inventory and slot 2 from nothing to Sheet.
 * Positionally that is "the Sheet column changed into an Inventory column, and a new Sheet
 * column appeared": the first subtree is *recreated*, which destroys the Sheet's WebView and
 * boots Meteor again in the new one. The pane the player did not touch is the one that goes
 * blank for several seconds — and every scroll offset to the left of an inserted pane resets
 * with it.
 *
 * [key] gives each column the identity it actually has, so adding or removing a pane moves the
 * others rather than rebuilding them. It is cheap and it is not optional; a `PaneSurface` is a
 * stable enum, which is exactly what a good key is.
 *
 * ### The divider
 *
 * Between columns, not around them: the outer edges are the window, which already ends. It is
 * inside the [key] with its column because a divider is a property of "this column, not first",
 * and hoisting it out would put an unkeyed node between keyed siblings.
 */
@Composable
fun PaneRow(
    panes: List<PaneSurface>,
    modifier: Modifier = Modifier,
    pane: @Composable (PaneSurface) -> Unit,
) {
    Row(modifier = modifier.fillMaxSize()) {
        panes.forEachIndexed { index, surface ->
            key(surface) {
                if (index > 0) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .testTag("panes:column:${surface.key}"),
                ) {
                    pane(surface)
                }
            }
        }
    }
}

/**
 * One low-frequency item folded into [HomeOverflowMenu] — a tab's own customize wrench, whose
 * label and target differ per screen and per which tab is showing.
 *
 * The name is 1.9.1's, from when the wrench was the only thing shaped like this; FR-39's history
 * item is the second and the shape fit unchanged. Kept rather than renamed to something like
 * `HomeOverflowItem`, because the type is referenced by name in both home screens and in the
 * ledger, and a rename would cost every one of those readings to buy a better noun.
 */
data class HomeOverflowCustomize(val labelRes: Int, val testTag: String, val onClick: () -> Unit)

/**
 * FR-39's history item, built here rather than at each of the two call sites.
 *
 * The wrench above is spelled out on both home screens because its label and target genuinely
 * differ per screen and per tab — that is the type's whole reason. History does not differ: same
 * label, same tag, same sheet, and only the lambda that opens it belongs to the screen. Written
 * twice it would be two literals of `tracker:history:open` that nothing stops from drifting
 * apart, and the tag is addressed by name from `tools/sweep/flows` and from the render tests,
 * which is exactly the kind of string that must have one definition.
 *
 * `PaneSelectionTest` reads the two screens' source for that literal and fails if it reappears
 * there — the check reads as "history is not on the bar", and it can only mean that while this
 * is the only place the tag is written.
 */
fun homeOverflowHistory(onClick: () -> Unit) = HomeOverflowCustomize(
    labelRes = R.string.tracker_history_action,
    testTag = "tracker:history:open",
    onClick = onClick,
)

/**
 * 1.9.1's app-bar decrowding: the character-home overflow menu, shared by both home screens for
 * [HomeTabRow]'s reason — a rule that has to hold on two screens holds on one composable or it
 * holds on neither.
 *
 * ### What moved in, and what did not
 *
 * The operator's screenshot showed the back arrow overlapping the Short-rest button on a bar
 * that could carry up to nine controls at once (two wrenches, quests, pane-order and settings,
 * on top of Short/Long/history) — FR-32's quests icon was what tipped DEFAULT scale over, and
 * the 1.9.1 spot-check flagged the same crowding again at 150 %. Short, Long and history stay on
 * the bar: they are what a player reaches for on every turn. Customize, quests, pane-order and
 * whatever the trailing item is (Settings on the DiceCloud screen, Edit on the local one) are
 * reached rarely enough that hiding them behind one tap costs little and buys back the room the
 * bar needs.
 *
 * ### Superseded 2026-09-03 (FR-39): history comes in too
 *
 * The paragraph above stands as written for Short and Long, and is **wrong about history** — the
 * claim was a frequency claim, and the operator's 2026-09-02 table judgement answers it the other
 * way. Undo is not reached through the sheet: the snackbar carries UNDO at the moment of the
 * write, and `HistoryRowState.canUndo` carries it on the row afterwards. What the sheet is for is
 * a once-in-a-while look back at the session, which is exactly the frequency this menu was built
 * to hold. So history moves in — the *first* item, above `customize`, because it is the one
 * action among a menu of destinations — and Short and Long stay on the bar, still on the original
 * argument. The 1.9.1 paragraph is kept rather than edited because the reversal is the record: a
 * ruling that quietly rewrites its own premise leaves nobody able to see that it changed.
 *
 * ### The arithmetic, rewritten for five elements
 *
 * With history in the menu the bar carries at most five fixed elements **on a phone** — back,
 * title, Short, Long, overflow — where the tabs are exclusive and only one tab's actions are
 * ever on the bar at once. In pane mode the surfaces are side by side, so the Inventory add
 * button can sit beside the tracker's rest buttons and the count goes higher; that mode is not
 * what this budget is about, because [isExpandedWidth] does not admit it below 840 dp
 * (`WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND`) — more than twice the width this arithmetic
 * is worried about, even after 150 % has taken its third.
 *
 * [ProvideUiScale] scales `density` (`UiScaleProvider`'s "both components are scaled"),
 * so at [com.hashtagchow.magehand.core.data.settings.UiScale.LARGE_150] (1.5×) the *physical*
 * screen does not grow — a 360 dp phone (`WindowSizeGateTest`: "the width every layout in this
 * app was designed against") offers only 360 / 1.5 ≈ 240 dp of bar width in the units these
 * controls are declared in. The four non-title elements' *minimum* declared widths — back
 * (`IconButtonDefaults`, 48 dp), Short and Long (`ButtonDefaults.MinWidth`, 58 dp each) and this
 * button (48 dp) — sum to 212 dp, which is **inside** that budget by about 28 dp, next to the
 * 260 dp (~20 dp over) the six-element bar demanded and the ~230 dp overspend of the nine-element
 * bar before 1.9.1. `AppBarKt`'s title is measured last against whatever is left and floored at
 * zero rather than negative, so what that margin buys is the concrete thing 1.9.1 had to spend:
 * at the single most extreme combination (150 % on the narrowest phone) the title keeps a
 * **sliver** instead of being squeezed to nothing, and the back arrow stops colliding with
 * "Short". It does not buy a title that *reads* — 58 dp is `ButtonDefaults.MinWidth`, a floor,
 * and the rest buttons measure nearer 61 dp with their own labels in them, so the real headroom
 * is closer to 18 dp than to 28. Expect an ellipsis there and treat it as the design's outcome,
 * not a defect. Short and Long still cannot shrink below their own touch-target floor without
 * failing accessibility, so that margin is the whole of the headroom — the next control that
 * wants a place on this bar spends it.
 *
 * **FR-43 (1.14.2) bought some of it back, after the arithmetic above turned out to be
 * optimistic.** The first app-bar golden showed the back arrow drawing *under* the "S" of
 * "Short" at 320 dp × 150 % — ~213 dp of budget, not 240 — which is BUG-17. `HomeAppBar` now
 * measures the width it was actually given and, below 284 dp (this 212 dp plus a 72 dp title
 * allowance), draws Short and Long as icon buttons: 48 dp each instead of ~61, so the four
 * non-title elements fall to 192 dp and the title keeps ~21 dp at that corner rather than
 * nothing. It is a fit rule and not a scale rule — see `HOME_APP_BAR_COMPACT_WIDTH` — so every
 * width at or above 284 dp, which is every phone at 100 %, renders exactly the bar this
 * paragraph describes.
 *
 * @param history the tracker's session sheet, or `null` when the Tracker tab is not showing —
 *   gated exactly as [customize] is, since it is that tab's action and nothing else's. FR-39: it
 *   carries the testTag `tracker:history:open` the app-bar `IconButton` used to, because the tag
 *   moves surfaces and not names, and the sweep flows address it by name.
 * @param customize the visible tab's own wrench, or `null` when neither tab's customize sheet
 *   applies right now (the Sheet or Actions tab is showing).
 * @param quests the quest log action, or `null` when there is nothing to open it for — a local
 *   character has no quest log at all (09 decision 8's structural absence), and a DiceCloud
 *   character with zero notes is gated the same way the standalone icon was (`hasQuests`).
 * @param settingsLabel the trailing item's own label, so one composable serves both screens'
 *   different last item.
 */
@Composable
fun HomeOverflowMenu(
    onPaneOrder: () -> Unit,
    settingsLabel: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    history: HomeOverflowCustomize? = null,
    customize: HomeOverflowCustomize? = null,
    quests: HomeOverflowCustomize? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("home:overflow:open"),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.action_more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // First, and the ordering is the point: everything below opens a settings-ish
            // destination, while this one opens the log of what this session already did.
            history?.let { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = { expanded = false; item.onClick() },
                    modifier = Modifier.testTag(item.testTag),
                )
            }
            customize?.let { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = { expanded = false; item.onClick() },
                    modifier = Modifier.testTag(item.testTag),
                )
            }
            quests?.let { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.labelRes)) },
                    onClick = { expanded = false; item.onClick() },
                    modifier = Modifier.testTag(item.testTag),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.panes_order_title)) },
                onClick = { expanded = false; onPaneOrder() },
                modifier = Modifier.testTag("panes:order:open"),
            )
            DropdownMenuItem(
                text = { Text(settingsLabel) },
                onClick = { expanded = false; onSettings() },
                modifier = Modifier.testTag("home:overflow:settings"),
            )
        }
    }
}
