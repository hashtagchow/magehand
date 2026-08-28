package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionGroup
import com.hashtagchow.magehand.core.model.DamageLine
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader
import com.hashtagchow.magehand.core.model.UseTarget

/**
 * FR-26's Actions surface — a spell and action list for one DiceCloud character
 * (docs/design/16-actions-and-feed.md decisions 1–7).
 *
 * ### One callback reaches the character, and the signature says so
 *
 * 16 decision 7 was *"no callbacks that reach a character"*, with the note that *"if a future wave
 * adds casting, the signature changing is the review's cue that the posture changed"*. FR-28 is
 * that wave and this is that cue: [onUse] is the one callback, and it takes a
 * [com.hashtagchow.magehand.core.model.UseTarget] rather than a property id — a value that cannot
 * be constructed for a row 17 decision 2 forbids. Collapse, the filter and which row is open
 * remain local view state.
 *
 * ### Collapse is `rememberSaveable`, not a store
 *
 * Decision 3 asks for collapsible headers *"per the standing convention"*. The convention has two
 * implementations in this app and this one follows `TrackerScreen.InactiveConditions` rather than
 * the inventory's persisted sections — deliberately, and the difference is what the state *means*.
 * The inventory's collapse rides its layout store because arranging that tab is a durable
 * statement about how you keep your gear. Which spell levels are folded while you scan for a
 * second-level slot is a glance, not a preference; it should survive a rotation and not survive a
 * week. `rememberSaveable` is exactly that lifetime, and it costs no sixth DataStore file, no
 * codec and no reap path — see `PaneLayoutStore`'s KDoc on why each of those is a real cost.
 *
 * This is a judgment call and it is recorded as one: the design says "collapsible", not
 * "persisted", and nothing in 16 asks for the state to outlive the screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ActionsScreen(
    state: ActionsUiState,
    onUse: (UseTarget, String?, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsedKeys by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }

    // The OPEN ROW's id, not the row — see `ActionDetailState`. Saveable, so a rotation with the
    // sheet up re-derives it from the new board rather than dropping the player back to the list.
    var openRowId by rememberSaveable { mutableStateOf<String?>(null) }

    // Re-sectioned here rather than in the ViewModel because both inputs are this composable's
    // own state. The ViewModel supplies the board-derived half (`state`); this applies the two
    // view-local layers on top. `toActionsUiState` is pure, so this is a cheap re-map, and it is
    // the same function `ActionsUiStateTest` exercises.
    val shown = state.withView(query = query, collapsedKeys = collapsedKeys)

    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("actions:list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (shown.isEmpty) {
            item { EmptyActions() }
            return@LazyColumn
        }

        if (shown.spellLists.isNotEmpty()) {
            item { SpellListHeaders(shown.spellLists) }
        }

        if (shown.showsFilter) {
            item {
                ActionsFilterField(
                    query = query,
                    matchCount = shown.matchCount,
                    active = shown.filterActive,
                    onQueryChange = { query = it },
                )
            }
        }

        if (shown.showsNoMatches) {
            item { NoMatches(query) }
        }

        shown.sections.forEach { section ->
            item(key = section.key) {
                SectionHeader(
                    section = section,
                    onToggle = {
                        collapsedKeys = if (section.key in collapsedKeys) {
                            collapsedKeys - section.key
                        } else {
                            collapsedKeys + section.key
                        }
                    },
                )
            }
            if (!section.collapsed) {
                items(section.rows.size, key = { section.rows[it].key }) { index ->
                    val row = section.rows[index]
                    val open = { openRowId = row.key }
                    when (row) {
                        is ActionRow.Spell -> SpellRow(row.entry, onClick = open)
                        is ActionRow.Action -> ActionEntryRow(row.entry, onClick = open)
                    }
                }
            }
        }
    }

    // Re-derived per frame from the live sections, so cost, uses and usability move with the
    // sheet — and so a row that leaves the list closes its own detail. See `ActionDetailState`.
    shown.detailFor(openRowId)?.let { detail ->
        ActionDetailSheet(
            state = detail,
            onUse = onUse,
            onDismiss = { openRowId = null },
        )
    }
}

/**
 * The spell lists' DC and modifier — decision 4's *"the spell-list HEADER shows the list's
 * `dc.value` and `abilityMod`"*.
 *
 * This block is the surface's answer to the question a per-spell hit bonus would have answered
 * wrongly. See [SpellEntry]'s KDoc for the trap; the short version is that these two numbers are
 * computed honestly at rest and a spell's own `attackRoll` is not.
 */
@Composable
private fun SpellListHeaders(lists: List<SpellListHeader>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lists.forEach { list ->
                Row(
                    Modifier.fillMaxWidth().testTag("actions:spelllist:${list.propertyId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Both nullable and neither defaulted: a list whose DC the server did not
                    // compute shows no DC rather than a plausible invented one.
                    list.dc?.let {
                        Text(
                            text = stringResource(R.string.actions_dc, it),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    list.abilityMod?.let {
                        Text(
                            text = stringResource(R.string.actions_ability_mod, it),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A spell row (decisions 4 and 5).
 *
 * **No hit bonus is rendered here and there is none to render**: [SpellEntry] has no such field,
 * by design. If you are adding one, read that type's KDoc first — a spell's `attackRoll.value` is
 * `0` at rest for every spell on every sheet, and printing it would put "+0 to hit" beside a
 * spell whose real bonus is +7.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpellRow(entry: SpellEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RowShell(
        name = entry.name,
        dimmed = entry.inactive,
        testTag = "actions:spell:${entry.propertyId}",
        onClick = onClick,
        modifier = modifier,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.concentration) Badge(stringResource(R.string.actions_concentration))
            if (entry.ritual) Badge(stringResource(R.string.actions_ritual))
            // Decision 5: from the FIELDS. The two states below can coexist and both show.
            if (entry.showsUnpreparedBadge) Badge(stringResource(R.string.actions_unprepared))
            if (entry.inactive) Badge(stringResource(R.string.actions_inactive))
        }
        // `castingTime · range`, scalars only (decision 4). Absent halves simply do not appear;
        // there is no placeholder, because a placeholder is a claim.
        val summary = listOfNotNull(entry.castingTime, entry.range).joinToString(" · ")
        if (summary.isNotEmpty()) SubText(summary)
        DamageLines(entry.damage)
    }
}

/** An action or attack row (decision 4). Unlike a spell, its `attackRoll` is real. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionEntryRow(entry: ActionEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    RowShell(
        name = entry.name,
        // Two independent reasons to dim, both stated in words below rather than left as a
        // colour: "greyed out" alone does not tell a player which of the two to fix.
        dimmed = entry.inactive || entry.insufficientResources,
        testTag = "actions:action:${entry.propertyId}",
        trailing = entry.attackRoll?.let { stringResource(R.string.actions_attack_bonus, it) },
        onClick = onClick,
        modifier = modifier,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.insufficientResources) Badge(stringResource(R.string.actions_insufficient))
            if (entry.inactive) Badge(stringResource(R.string.actions_inactive))
        }
        // Local vals: `ActionEntry` lives in :core:model, so its `val`s are not smart-cast
        // across the module boundary and the alternative is a pair of `!!`.
        val left = entry.usesLeft
        val max = entry.usesMax
        val uses = when {
            left != null && max != null -> stringResource(R.string.actions_uses, left, max)
            left != null -> stringResource(R.string.actions_uses_left, left)
            else -> null
        }
        uses?.let { SubText(it) }
        DamageLines(entry.damage)
    }
}

/**
 * The damage rollups, one line each.
 *
 * Server strings verbatim — see `ActionEngine.toDamageLine`, which also records that
 * `amount.value` is not always fully resolved at rest. Nothing is computed here.
 */
@Composable
private fun DamageLines(damage: List<DamageLine>, modifier: Modifier = Modifier) {
    damage.forEach { line ->
        SubText(
            stringResource(R.string.actions_damage, line.amount, line.damageType),
            modifier = modifier,
        )
    }
}

/**
 * The shared frame of both row kinds: name, an optional trailing number, and a body.
 *
 * `mergeDescendants` so TalkBack reads a row as one sentence rather than as five nodes — the
 * badges and the damage lines are parts of one statement about one spell, and hearing them as
 * separate focus stops is how a list of thirty becomes unusable.
 */
@Composable
private fun RowShell(
    name: String,
    dimmed: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val tint = if (dimmed) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 16 decision 4's "tap → detail sheet", finally wired by FR-28. The whole row is the
            // target and its minimum height is a touch target: a name plus two badges is one
            // thing to press, not four. A5 nit: `Role.Button` + `onClickLabel` so TalkBack
            // announces this as a button that opens details, not a bare "double-tap to activate".
            .clickable(
                onClickLabel = stringResource(R.string.actions_row_click_label),
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { }
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
        body()
    }
}

@Composable
private fun SubText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun Badge(label: String, modifier: Modifier = Modifier) {
    AssistChip(onClick = {}, enabled = false, label = { Text(label) }, modifier = modifier)
}

/**
 * A collapsible section header — FR-16's `ExpanderRow` shape, re-stated here rather than shared.
 *
 * Not extracted into a common component alongside the inventory's, because the two differ in
 * their summary half (weight versus a count) and in what they key their collapse on, and a shared
 * component parameterised over both would be two headers wearing one name. That is the same call
 * `InventoryScreen` and `TrackerScreen.InactiveConditions` already made independently; a third
 * site is where an extraction would start to pay, and this wave notes it rather than doing it.
 */
@Composable
private fun SectionHeader(
    section: ActionSection,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = section.title.label()
    val count = pluralStringResource(
        R.plurals.actions_section_count,
        section.rows.size,
        section.rows.size,
    )
    val action = stringResource(
        if (section.collapsed) R.string.actions_section_expand else R.string.actions_section_collapse,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            // The title is handed over as written, not uppercased: a screen reader may spell an
            // all-caps word out letter by letter. `SectionTitle` below does the shouting.
            .semantics(mergeDescendants = true) { contentDescription = "$title, $count, $action" }
            .testTag("actions:section:${section.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Icon(
            imageVector = if (section.collapsed) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.KeyboardArrowUp
            },
            // Silent: the merged node already says "collapsed, tap to expand" in words.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The header's visible words. See `actions_spell_cantrips` for why level 0 is special-cased. */
@Composable
private fun ActionSectionTitle.label(): String = when (this) {
    is ActionSectionTitle.Cantrips -> stringResource(R.string.actions_spell_cantrips)
    is ActionSectionTitle.SpellLevel -> stringResource(R.string.actions_spell_level, level)
    is ActionSectionTitle.Group -> stringResource(
        when (group) {
            ActionGroup.ATTACKS -> R.string.actions_group_attacks
            ActionGroup.ACTIONS -> R.string.actions_group_actions
            ActionGroup.BONUS -> R.string.actions_group_bonus
            ActionGroup.REACTIONS -> R.string.actions_group_reactions
            ActionGroup.OTHER -> R.string.actions_group_other
        },
    )
}

/**
 * FR-24's filter field, applied to this surface (decision 6).
 *
 * Structurally the same control as `InventoryScreen.InventoryFilterField` — a plain
 * `OutlinedTextField` with a polite live region carrying the match count — for that function's
 * stated reasons, which are unchanged here. Duplicated rather than shared for the same reason the
 * section header is: the two differ only in their strings today, and a shared field whose only
 * parameters are four string resources is an indirection, not an abstraction.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ActionsFilterField(
    query: String,
    matchCount: Int,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = pluralStringResource(R.plurals.actions_filter_matches, matchCount, matchCount)

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.actions_filter_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("actions:filter:clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.actions_filter_clear),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("actions:filter"),
        )

        if (active) {
            // Announced, not drawn — the count is redundant for anyone who can see the list
            // change, and is the only signal for anyone who cannot.
            Spacer(
                Modifier
                    .height(0.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = matches
                    },
            )
        }
    }
}

/** FR-24 decision 16's honest no-match line, with the query printed back. */
@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.actions_filter_none, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp).testTag("actions:filter:empty"),
        )
    }
}

/**
 * The empty state.
 *
 * Reachable in one narrow window only — the surface is discovery-gated, so a character with
 * nothing to act with has no Actions tab at all (decision 1). What remains is the beat between
 * the screen opening and the board arriving, and a live edit that removes the last spell while
 * the tab is open. Both deserve a sentence rather than a blank column.
 */
@Composable
private fun EmptyActions(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.actions_empty),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.actions_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("actions:empty"),
            )
        }
    }
}
