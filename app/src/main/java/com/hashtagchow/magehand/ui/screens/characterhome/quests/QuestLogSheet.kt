package com.hashtagchow.magehand.ui.screens.characterhome.quests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.QuestEntry

/**
 * FR-32's quest log (docs/design/18-table-pack.md decisions 13–16).
 *
 * ### Why a sheet off the top bar rather than a tab or a pane
 *
 * Decision 14, in as many words: *"a top-bar Quests entry on the character screens (the
 * history-sheet pattern) … **Not a pane/tab** (a log is a glance, not a work surface — recorded;
 * revisit on demand)"*. The tabs and panes on this screen are places a player *does* things —
 * spend a slot, equip a sword, use an action — and each one costs a segment in a row that is
 * already four wide on a phone. A quest log is read once when somebody asks "what were we doing
 * again?", which is exactly the shape [TrackerHistorySheet] already has and is why this is its
 * twin rather than a new kind of surface.
 *
 * ### Discovery-gated, so this composable never renders empty
 *
 * The top-bar entry only exists when the character has at least one quest note (decision 14), so
 * there is deliberately **no empty state here** — unlike the history sheet, which can legitimately
 * open on a session where nothing has happened yet. If this sheet is open, there is something in
 * it.
 *
 * ### Closed quests are at the bottom and still there
 *
 * Decision 13: *"`closed` tag (operator convention) = finished → sorted to the BOTTOM,
 * de-emphasized, never hidden"*. The ordering is `QuestEngine`'s, not this file's — one sort, in
 * one place — so all this does is draw the two groups with a header each and dim the second. The
 * *"a table wants its history"* clause is why there is no filter and no collapse: a finished quest
 * is the record of a session, and hiding it behind a control would be this app deciding the
 * table's history is clutter.
 *
 * ### Read-only, and structurally so
 *
 * Decision 15 fences marking-closed out of v1 (*"the tags write path needs its own probe"*), and
 * this composable takes no callback that could write: the only lambda it has is [onDismiss], and
 * the only gesture on a row is expanding it. There is nothing here to accidentally wire to a
 * `creatureProperties.update`.
 *
 * @param quests already ordered and prefix-stripped by `QuestEngine`. Never empty in practice —
 *   see the discovery note above.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun QuestLogSheet(
    quests: List<QuestEntry>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    // The *ids* of the expanded rows, not the rows — `ActionDetailState`'s rule: the list is
    // re-derived from the sheet on every sync, so holding a row would freeze a quest whose text
    // has since been edited. `rememberSaveable` so a rotation mid-read does not collapse
    // everything the player had opened.
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val open = quests.filterNot { it.closed }
    val closed = quests.filter { it.closed }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.quests_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .testTag("quests:list"),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // The two headers render only when **both** groups exist. A log that is all open
                // quests needs no "Open" heading to distinguish it from nothing, and one that is
                // all closed needs no "Closed" — a header whose only job is to contrast with an
                // absent sibling is a label on the whole list.
                val labelled = open.isNotEmpty() && closed.isNotEmpty()

                if (labelled) item(key = "quests-open-header") { GroupHeader(R.string.quests_open_header) }
                items(open.size, key = { open[it].propertyId }) { index ->
                    QuestRow(
                        quest = open[index],
                        expanded = open[index].propertyId in expanded,
                        onToggle = { expanded = expanded.toggle(open[index].propertyId) },
                    )
                }

                if (labelled) item(key = "quests-closed-header") { GroupHeader(R.string.quests_closed_header) }
                items(closed.size, key = { closed[it].propertyId }) { index ->
                    QuestRow(
                        quest = closed[index],
                        expanded = closed[index].propertyId in expanded,
                        onToggle = { expanded = expanded.toggle(closed[index].propertyId) },
                    )
                }
            }
        }
    }
}

/** Adds or removes one id. A `Set` because the expansion of one row says nothing about another. */
private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun GroupHeader(labelRes: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One quest: the title, the summary, and the description on tap (decision 13).
 *
 * ### The summary renders as-is
 *
 * The table's convention writes it as *"QUEST · Giver: X · Reward: Y · Status: STATE"*, and
 * decision 13 says to print it unchanged. Parsing those three fields out would be this app
 * inventing a schema for a habit — and it would fail silently the first time somebody wrote the
 * line a little differently, which is the one thing a hand-typed convention guarantees will
 * happen. Recorded in `QuestEntry`; the design leaves the parse to a later call.
 *
 * ### Only a row with something to show takes a tap
 *
 * [QuestEntry.hasDetail] gates both the click and the expand affordance. A quest with a summary
 * and no description is complete as drawn, and a tappable row that does nothing is worse than a
 * row that is plainly just text.
 *
 * De-emphasized when closed — dimmed rather than struck through, because a finished quest is
 * *done*, not *undone*. (The history sheet strikes its rows through, and means the other thing.)
 */
@Composable
private fun QuestRow(
    quest: QuestEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (quest.closed) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val expandLabel = stringResource(
        if (expanded) R.string.quests_collapse else R.string.quests_expand,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (quest.hasDetail) {
                    Modifier.clickable(
                        onClickLabel = expandLabel,
                        role = Role.Button,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                },
            )
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { }
            .testTag("quests:row:${quest.propertyId}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = quest.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
        quest.summary?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = tint)
        }
        if (expanded) {
            quest.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("quests:detail:${quest.propertyId}"),
                )
            }
        }
    }
    HorizontalDivider()
}
