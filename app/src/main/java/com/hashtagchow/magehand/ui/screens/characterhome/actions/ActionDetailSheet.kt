package com.hashtagchow.magehand.ui.screens.characterhome.actions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.UseTarget

/**
 * One spell or action, expanded — 16 decision 4's *"tap → detail sheet"*, carrying 17 decision 1's
 * three truths and the one gesture this surface has.
 *
 * ### What is here that is not on the row, and why
 *
 * The list is a scan; this is the read. `ItemDetailSheet`'s split exactly. The row shows a name, a
 * couple of badges and a damage line because that is what survives being scrolled past at a table.
 * The three things that decide whether you can actually *do* the thing — what it costs, how many
 * uses are left, and whether it is usable at all — are decisions, and decisions belong behind a
 * deliberate tap where there is room to state them in words.
 *
 * ### The Use button is absent, not disabled, and this file cannot make it otherwise
 *
 * 17 decision 2. [ActionDetailState.use] is `null` for an unprepared or switched-off row and there
 * is no other route to a Use in this package — the button is drawn inside a `?.let`, and the type
 * it needs ([UseAffordance]) cannot be constructed from a row that failed the gate. What the sheet
 * draws instead is a *sentence* ([ActionDetailState.unusableReason]), because decision 2 asks for
 * dimmed rows to explain why and a missing button explains nothing.
 *
 * The `enabled = false` state that *does* exist is a different claim: offline, or a use already on
 * the wire (decision 5). Those are "not right now", which is what a disabled control means; "not
 * ever, on this row, in this state" is what an absent one means, and conflating the two is how a
 * player ends up tapping a greyed button waiting for it to work.
 *
 * @param onUse handed the [UseTarget] the affordance carries, plus the player's slot and ritual
 *   choices. It takes no property id, deliberately: an id could be built for any row, and a
 *   `UseTarget` could not.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ActionDetailSheet(
    state: ActionDetailState,
    onUse: (UseTarget, String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .testTag("actions:detail:${state.row.key}"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            when (val row = state.row) {
                is ActionRow.Spell -> SpellFacts(row)
                is ActionRow.Action -> ActionFacts(row)
            }

            HorizontalDivider()

            // Decision 1's Cost and Uses, in that order — what it costs is the question a player
            // asks first, and how many are left is the one they ask when the answer is "nothing".
            CostBlock(state.cost)
            state.uses?.let { uses ->
                Text(
                    text = pluralStringResource(
                        R.plurals.action_detail_uses,
                        uses.remaining,
                        uses.remaining,
                        uses.max,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("actions:detail:uses"),
                )
            }

            state.body?.let {
                HorizontalDivider()
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            val use = state.use
            if (use == null) {
                // Decision 2: the row explains itself rather than showing a dead control.
                Text(
                    text = stringResource(state.unusableReason.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("actions:detail:unusable"),
                )
            } else {
                Button(
                    onClick = { confirming = true },
                    enabled = use.enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("actions:detail:use"),
                ) {
                    Text(
                        stringResource(
                            when {
                                use.inFlight -> R.string.action_use_in_flight
                                !use.canWrite -> R.string.action_use_offline
                                else -> R.string.action_use
                            },
                        ),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_detail_close))
            }
        }
    }

    // Decision 4: confirm before EVERY use. The dialog carries the slot picker and the ritual
    // checkbox as well as the cost lines — one dialog rather than a picker followed by a confirm,
    // because the slot is *part of* what the use will spend and a player choosing an upcast is
    // making one decision, not two. Recorded as the wave's judgment call.
    val use = state.use
    if (confirming && use != null) {
        UseConfirmDialog(
            use = use,
            onConfirm = { slotId, ritual -> onUse(use.target, slotId, ritual) },
            onDismiss = { confirming = false },
        )
    }

    // A row that stops being usable while the dialog is open — the settle window landing, another
    // client spending the last charge — takes the dialog down with it rather than leaving a
    // Confirm button that would be dropped by `:core:data`'s second gate with no explanation.
    LaunchedEffect(use == null) {
        if (use == null) confirming = false
    }
}

/**
 * The four sentences of 17 decision 2's *"dimmed rows explain why in the detail sheet"*.
 *
 * `null` cannot reach here from [ActionDetailSheet] — it is only called on the branch where the
 * Use is absent, and [ActionDetailState.unusableReason] is non-null exactly there — but the
 * signature takes a nullable anyway rather than a `!!`, and falls back to the resource-shortage
 * line. A crash is not an improvement over a slightly wrong sentence in a state that cannot occur.
 */
@StringRes
private fun UnusableReason?.labelRes(): Int = when (this) {
    UnusableReason.UNPREPARED -> R.string.action_detail_unusable_unprepared
    UnusableReason.INACTIVE -> R.string.action_detail_unusable_inactive
    UnusableReason.NO_USES -> R.string.action_detail_unusable_uses
    UnusableReason.NO_RESOURCES, null -> R.string.action_detail_unusable_resources
}

/** The spell-shaped facts (16 decision 4's scalars, plus the level the picker keys on). */
@Composable
private fun SpellFacts(row: ActionRow.Spell) {
    val entry = row.entry
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (entry.level == 0) {
                stringResource(R.string.action_detail_cantrip)
            } else {
                stringResource(R.string.action_detail_spell_level, entry.level)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        entry.castingTime?.let { Fact(stringResource(R.string.action_detail_casting_time), it) }
        entry.range?.let { Fact(stringResource(R.string.action_detail_range), it) }
        entry.damage.forEach {
            Fact(stringResource(R.string.actions_damage, it.amount, it.damageType), "")
        }
    }
}

/** The action-shaped facts. `attackRoll` is real here — see [com.hashtagchow.magehand.core.model.SpellEntry]. */
@Composable
private fun ActionFacts(row: ActionRow.Action) {
    val entry = row.entry
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entry.attackRoll?.let {
            Text(
                text = stringResource(R.string.actions_attack_bonus, it),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        entry.damage.forEach {
            Fact(stringResource(R.string.actions_damage, it.amount, it.damageType), "")
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Text(
        text = if (value.isBlank()) label else "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Decision 1's Cost block — *"'Free' when none"*.
 *
 * A line prints its remaining count only when the sheet could be joined to one
 * ([CostLine.available] non-null). A cost naming something the sheet does not carry prints the
 * amount alone rather than "(0 left)", which would be this app asserting an absence it has not
 * established — see [CostLine.satisfied] for why that case is permitted through rather than
 * refused.
 */
@Composable
private fun CostBlock(cost: ActionCost, modifier: Modifier = Modifier) {
    Column(modifier = modifier.testTag("actions:detail:cost"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.action_detail_cost),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (cost.isFree) {
            Text(stringResource(R.string.action_detail_cost_free), style = MaterialTheme.typography.bodyMedium)
        } else {
            cost.lines.forEach { line -> Text(line.label(), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** One cost line's words. See [CostBlock] for why the remaining count is conditional. */
@Composable
private fun CostLine.label(): String = available
    ?.let { stringResource(R.string.action_detail_cost_line_with_remaining, name, amount, it) }
    ?: stringResource(R.string.action_detail_cost_line, name, amount)

/**
 * Decision 4's dialog, in `RestConfirmDialog`'s shape deliberately — this app has one visual
 * vocabulary for "this is the last chance to stop", and a use is the same class of write a rest
 * is: no inverse, real consequences, confirmed rather than undone.
 *
 * ### It is shown for a free action too
 *
 * Decision 4 in as many words: *"Free actions get the same dialog minus the cost lines (they still
 * log/post)."* That looks like friction for nothing until you read [R.string.action_use_no_undo] —
 * the thing being confirmed is not only the spend, it is the party-feed entry and the Discord post
 * (probe U4). A dialog that appeared only when something was spent would teach the player that no
 * dialog means no consequences, which is false on exactly the taps they would make fastest.
 *
 * ### The confirm latch
 *
 * `confirmed` is a synchronous check-and-set inside `onClick`, `DeleteConfirmDialog`'s pattern:
 * `enabled` is one recomposition too late to stop a double tap. It is belt to the braces of
 * `:core:data`'s single-flight latch — that one is the guarantee, this one is what keeps a second
 * tap from being *silently* dropped a layer down where the player cannot see why.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun UseConfirmDialog(
    use: UseAffordance,
    onConfirm: (String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmed by remember { mutableStateOf(false) }
    // Survives a rotation with the dialog, keyed on the target's property id — not on nothing:
    // a saved pick cannot follow the dialog onto a different row, and re-opening it (on the
    // same row or a new one) re-defaults to the cheapest legal slot rather than remembering the
    // exact choice — the right answer more often than "the slot you picked last time", which
    // may no longer exist.
    var slotId by rememberSaveable(use.target.propertyId) { mutableStateOf(use.defaultSlotId) }
    var ritual by rememberSaveable(use.target.propertyId) { mutableStateOf(false) }

    val isSpell = use.target is UseTarget.Spell

    // B1 [architect ruling]: an empty picker on a non-ritual leveled spell must not reach
    // Confirm at all — `doCastSpell` would receive no `slotId` and the contract says the
    // server may auto-pick one, which is a burned, unchosen slot with no undo (probe U2).
    // The row's Use button stays reachable; only THIS dialog's Confirm is gated, because the
    // picker being empty is a fact the dialog itself just showed in words above.
    // `confirmDisabled` is a pure function on [UseAffordance] precisely so this rule is
    // unit-testable — see `ActionsUiStateTest`.
    val slotPickerEmpty = use.confirmDisabled(ritual)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isSpell) R.string.action_use_confirm_cast_title else R.string.action_use_confirm_title,
                    use.target.name,
                ),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { testTagsAsResourceId = true },
            ) {
                val cost = use.target.cost
                if (cost.isFree) {
                    Text(stringResource(R.string.action_use_confirm_free))
                } else {
                    Text(stringResource(R.string.action_use_confirm_spends))
                    cost.lines.forEach { line ->
                        Text(
                            text = "•  ${line.label()}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("actions:use:cost:${line.name}"),
                        )
                    }
                }

                // Decision 4's "uses left after". Computed here rather than read back from the
                // sheet for the obvious reason: the sheet has not been written to yet.
                use.target.uses?.let { uses ->
                    Text(
                        text = stringResource(
                            R.string.action_use_confirm_uses_after,
                            (uses.remaining - 1).coerceAtLeast(0),
                            uses.max,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("actions:use:uses"),
                    )
                }

                if (use.showsRitual) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("actions:use:ritual"),
                    ) {
                        Checkbox(checked = ritual, onCheckedChange = { ritual = it })
                        Text(
                            text = stringResource(R.string.action_use_ritual),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Decision 3's picker. Hidden while the ritual box is ticked, because a ritual
                // cast spends no slot and offering a choice that will be ignored is a lie the
                // player only discovers afterwards.
                if (use.showsSlotPicker && !ritual) {
                    Text(
                        text = stringResource(R.string.action_use_slot_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (use.slots.isEmpty()) {
                        Text(
                            text = stringResource(R.string.action_use_slot_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("actions:use:slots:none"),
                        )
                    }
                    use.slots.forEach { slot ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = slotId == slot.propertyId,
                                    onClick = { slotId = slot.propertyId },
                                )
                                .testTag("actions:use:slot:${slot.propertyId}"),
                        ) {
                            RadioButton(selected = slotId == slot.propertyId, onClick = null)
                            Text(
                                text = stringResource(
                                    R.string.action_use_slot_option,
                                    slot.level,
                                    slot.remaining,
                                    slot.total,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                // 18 decision 4: **absent** on an on-device character, where the use IS undoable
                // and the sentence would be a lie. Not softened, not reworded — the local dialog's
                // whole content is the cost and the uses-after, which is what "lighter than the
                // server's" means. See `ActionsUiState.usesAreUndoable` for why the flag is
                // screen-level, and `LocalOpenCharacter.useAction` for where the undo lives.
                if (!use.undoable) {
                    Text(
                        text = stringResource(R.string.action_use_no_undo),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("actions:use:warning"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!confirmed && !slotPickerEmpty) {
                        confirmed = true
                        // A ritual cast sends no slot at all — see the picker's note above.
                        onConfirm(if (ritual) null else slotId, ritual)
                        onDismiss()
                    }
                },
                enabled = !confirmed && !slotPickerEmpty,
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("actions:use:confirm"),
            ) {
                Text(
                    stringResource(
                        if (isSpell) R.string.action_use_confirm_cast else R.string.action_use_confirm,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
