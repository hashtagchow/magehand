package com.hashtagchow.magehand.ui.screens.dmview

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.FeedEntry

/**
 * FR-25's activity feed panel (docs/design/16-actions-and-feed.md decisions 9–12).
 *
 * ### What it shows, and the sentence that says so
 *
 * A *DiceCloud activity* feed — rests, casts, checks and rolls the **server** logged. The empty
 * state names DiceCloud rather than saying "no activity yet" full stop, which decision 9 calls the
 * honest empty state and which is doing real work: a DM who has been watching players tap this app
 * for an hour and then opens a panel claiming nothing has happened would reasonably conclude the
 * panel is broken. See `dm_feed_empty_hint`.
 *
 * **Corrected by FR-28** (docs/design/17-use-action.md decision 8). Decision 9's wording was
 * *"MageHand's own writes produce no entries"* — true of everything this app could write in
 * 1.9.x (probe L3: `damage` and `adjustQuantity` never log server-side) and **not** true of a
 * Use. `doAction`/`doCastSpell` run the server's own machinery, so the server writes the log
 * entry and this panel carries it with no code here at all. Nothing in this file changed for
 * that; the empty state's second sentence did, because it had become a claim the app disproves
 * the first time anybody presses Use.
 *
 * ### Default collapsed, and collapse is not persisted
 *
 * Decision 10 says default collapsed. The state is the caller's `rememberSaveable`, matching
 * `EditingBanner`'s sibling toggle on this screen — which `DmViewViewModel` deliberately backs
 * with no store — and `ActionsScreen`'s collapse, for the same reason: whether the feed is open
 * is a glance during one session at a table, not a preference about how the DM works.
 *
 * ### Layout
 *
 * A fixed-width column on the right when open, a narrow strip when closed. Decision 10 also
 * mentions a bottom-sheet affordance "otherwise", which does not arise here: the DM view is
 * EXPANDED-width only (`DmViewSelection`), so there is no narrow case for this screen to have.
 * Recorded rather than built, because building a second presentation for a width the screen
 * refuses to open at would be untestable and unreachable.
 *
 * The grid beside this panel re-measures itself: `DmCardGrid`'s `BoxWithConstraints` sits inside
 * the `Row`, so the width this column takes is already subtracted before `dmGridColumns` reads
 * `maxWidth`. That is why the panel is a sibling rather than an overlay — an overlay would have
 * left the grid computing its column count against a width it no longer had.
 */
@Composable
fun DmFeedPanel(
    entries: List<FeedEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .width(if (expanded) EXPANDED_WIDTH_DP.dp else COLLAPSED_WIDTH_DP.dp)
                .fillMaxHeight()
                .testTag("dm:feed"),
        ) {
            FeedHeader(count = entries.size, expanded = expanded, onToggle = onToggle)
            if (!expanded) return@Column

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (entries.isEmpty()) {
                EmptyFeed()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("dm:feed:list"),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.logId }) { FeedRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FeedHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.dm_feed_title)
    val countLabel = pluralStringResource(R.plurals.dm_feed_count, count, count)
    val action = stringResource(
        if (expanded) R.string.dm_feed_collapse else R.string.dm_feed_expand,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $countLabel, $action" }
            .padding(horizontal = 12.dp)
            .testTag("dm:feed:header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = if (expanded) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.KeyboardArrowUp
            },
            // Silent: the merged header already says "collapsed, tap to expand" in words.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One entry — decision 11's *"`creatureName · relative time` header"* then the content lines.
 *
 * ### Attribution is the creature, and only the creature
 *
 * There is no actor anywhere in a `creatureLogs` document: it records what happened to a
 * creature, never who pressed it. So the header names the creature and stops. At a table where
 * two people share a sheet, inventing a player would be inventing the wrong one — see
 * [FeedEntry]'s KDoc.
 *
 * ### The one thing here that is not unit-tested, and why that is the right trade
 *
 * `DateUtils.getRelativeTimeSpanString` is the platform's localized "5 minutes ago", so it is not
 * reachable from a plain JVM test. Writing our own would make it testable and would also make it
 * *ours* — a second, worse, English-only implementation of a string every other Android app on
 * the DM's phone already renders correctly in their language. The part that carries real risk is
 * the **ordering**, and that is pure and pinned in `ActivityFeedTest`; formatting a timestamp
 * this app did not compute is not where a bug would hide.
 *
 * A missing timestamp says so rather than guessing — `dm_feed_no_time`. Such an entry also sorts
 * last (see `ActivityFeedEngine.build`), so it cannot claim the top of a panel whose whole
 * ordering claim is recency.
 */
@Composable
private fun FeedRow(entry: FeedEntry, modifier: Modifier = Modifier) {
    val time = entry.dateMillis?.let {
        DateUtils.getRelativeTimeSpanString(
            it,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    } ?: stringResource(R.string.dm_feed_no_time)

    // L4: a log document with no name reaches here as `null` (ActivityFeedEngine), never the
    // empty string — the same "absent resolves to a labelled fallback, right here" shape as
    // `time` above, so a nameless entry gets a real header instead of a blank one.
    val creatureName = entry.creatureName ?: stringResource(R.string.dm_feed_unknown_creature)

    val spoken = stringResource(
        R.string.dm_feed_entry_spoken,
        creatureName,
        time,
        entry.lines.joinToString(". ") { listOfNotNull(it.name, it.value).joinToString(": ") },
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // One focus stop per entry: a feed of fifty rows read as three nodes each is a
            // screen reader walking 150 stops through a panel meant to be glanced at.
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("dm:feed:entry:${entry.logId}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = creatureName,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        entry.lines.forEach { line ->
            line.name?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            line.value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Decision 11's clip. `maxLines` rather than a truncated string, so the full
                    // text is still in the node a screen reader reads and in the layout an
                    // expand would reveal — truncating the string would destroy it here.
                    maxLines = ENTRY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Decision 9's honest empty state. The wording is the content — see [DmFeedPanel]'s KDoc. */
@Composable
private fun EmptyFeed(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.dm_feed_empty),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.dm_feed_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("dm:feed:empty"),
            )
        }
    }
}

/**
 * Wide enough for a creature name and a relative time on one line, narrow enough to leave the
 * grid a column at the width this screen opens at.
 *
 * 320 dp against `DmCardGrid`'s `MIN_CARD_WIDTH_DP`: the DM view opens at EXPANDED width
 * (≥ 840 dp), so the worst case leaves the grid over 500 dp — comfortably more than one card.
 * The panel is collapsed by default anyway, so the common case costs the grid [COLLAPSED_WIDTH_DP].
 */
private const val EXPANDED_WIDTH_DP = 320

/** Just the chevron's touch target. See [EXPANDED_WIDTH_DP]. */
private const val COLLAPSED_WIDTH_DP = 48

/** Decision 11's "long values clipped". */
private const val ENTRY_MAX_LINES = 4
