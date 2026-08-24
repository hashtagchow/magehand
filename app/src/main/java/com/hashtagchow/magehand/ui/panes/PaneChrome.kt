package com.hashtagchow.magehand.ui.panes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * @param panes what is on screen now, as [resolvePanes] produced it — never the raw stored set,
 *   for [togglePane]'s reason.
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
                Text(stringResource(surface.titleResId))
            }
        }
    }
}

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
