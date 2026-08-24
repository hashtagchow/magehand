package com.hashtagchow.magehand.ui.screens.dmview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.MINUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PLUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PipRowState

/**
 * One condensed character card (docs/design/14-large-screen-arc.md decision 12).
 *
 * ### The card is a *card*, not a small character screen
 *
 * Decision 12 lists exactly what goes on it — *"name, HP bar + current/max, slot pips, resource
 * counts, condition chips, concentration banner"* plus an inventory summary line — and everything
 * else the tracker draws is deliberately absent: no defenses, no rolls picker, no consumable
 * steppers, no undo history, no customize entry. Those are not omissions to be filled in later;
 * they are the difference between a dashboard that can be scanned in one look and six tabs
 * stacked side by side. *"Tapping a card opens the full character as today"* is where the rest
 * lives, and it is one tap away.
 *
 * ### The semantics split
 *
 * The read half is one merged node speaking [DmCardUiState.spokenLabel]; the write controls sit
 * outside it as their own nodes. A single merged node over the whole card would swallow the
 * controls' actions and leave a screen-reader user able to hear a card and unable to operate it —
 * see `spokenLabel`'s KDoc for the whole argument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DmCard(
    card: DmCardUiState,
    onClick: () -> Unit,
    onSpend: (String) -> Unit,
    onRestore: (String) -> Unit,
    onChangeHitPoints: (Int) -> Unit,
    onToggleCondition: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("dm:card:${card.creatureId}"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The whole read half as one spoken sentence. Everything inside is silenced, so
            // TalkBack gets the sentence and not the fragments it was built from.
            Column(
                modifier = Modifier.spokenAs(card.spoken()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                when (card.availability) {
                    DmCardAvailability.LOADING -> Text(
                        text = stringResource(R.string.dm_view_card_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Decision 19, in the one place a DM sees it: an explicit state, never an
                    // empty tracker card. The body says what it probably means without claiming
                    // to know — the probe established that the client genuinely cannot tell a
                    // withdrawn share from an empty sheet.
                    DmCardAvailability.NOT_AVAILABLE -> {
                        Text(
                            text = stringResource(R.string.dm_view_card_unavailable),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.dm_view_card_unavailable_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DmCardAvailability.AVAILABLE -> CardBody(card)
                }
            }

            // Decision 18's second half: a card the server refused says why it went read-only,
            // rather than the controls silently not being there any more.
            if (card.permissionDenied) {
                Text(
                    text = stringResource(R.string.dm_view_card_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Decision 14. The composable asks ONE question and it was answered at the state
            // layer — see `dmCardShowsWriteControls`, which is where the four conditions live and
            // where each has a test. A read-only card renders no write control at all: absent,
            // not disabled, because a disabled stepper on somebody else's sheet invites the tap.
            if (card.showsWriteControls) {
                CardWriteControls(
                    card = card,
                    onSpend = onSpend,
                    onRestore = onRestore,
                    onChangeHitPoints = onChangeHitPoints,
                    onToggleCondition = onToggleCondition,
                )
            }
        }
    }
}

/** The read half: decision 12's five facts, in the order a DM's eye takes them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardBody(card: DmCardUiState) {
    card.concentratingOn?.let { spell ->
        // The banner first, above HP: it is the one fact that changes what the DM *says* next,
        // and on a six-card grid it has to survive being seen out of the corner of an eye.
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.dm_view_card_concentrating, spell),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    card.hp?.let { hp ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.dm_view_card_hp, hp.current, hp.max),
                style = MaterialTheme.typography.bodyMedium,
            )
            // `HpState.fraction` is already clamped, so a server value briefly out of range
            // cannot draw a bar past its track — the tracker's own guarantee, reused rather
            // than re-derived.
            LinearProgressIndicator(
                progress = { hp.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Slots and resources as one count line rather than five pip rows: a card has no room for
    // per-level pips and stay a glance, and "3 spell slots spent" is what a DM scanning six
    // cards for "who is out?" is actually looking for. The per-level detail is one tap away.
    if (card.slots.isNotEmpty()) {
        Text(
            text = pluralStringResource(
                R.plurals.dm_view_card_slots_spent,
                card.spentSlots,
                card.spentSlots,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    card.resources.forEach { row -> ResourceLine(row) }

    if (card.conditions.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            card.conditions.forEach { condition ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(condition.name, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }

    card.inventory?.let { inventory ->
        Text(
            text = pluralStringResource(
                if (inventory.overCapacity) {
                    R.plurals.dm_view_card_inventory_over
                } else {
                    R.plurals.dm_view_card_inventory
                },
                inventory.itemCount,
                inventory.itemCount,
                inventory.weight,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (inventory.overCapacity) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** One tracked resource as `label  value/total`. Counts, not pips — see [CardBody]. */
@Composable
private fun ResourceLine(row: PipRowState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.dm_view_card_ratio, row.value, row.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Decision 14's *"cards surface the tracker's stepper/rest controls, routed through the SAME
 * OpenCharacter intents as the owner path"*.
 *
 * ### What is here, and what is deliberately not
 *
 * HP damage/heal, slot spend/restore, and the condition chips. Those are the writes a DM makes
 * *about somebody else's character during combat*, which is the whole case this feature exists
 * for. Rest is **not** offered from a card, and that is a considered omission rather than an
 * oversight: a rest is not undoable, its confirm dialog would have to name which of six
 * characters is resting, and a mis-tap would silently restore a player's resources without their
 * knowledge. The character screen still has it, one tap away, with the dialog the design
 * requires.
 *
 * Inventory is not writable here either — decision 12's own out-of-scope line: *"cards summarize
 * only — editing inventory means opening the character"*.
 *
 * ### `enabled`, not absent
 *
 * [DmCardUiState.writeControlsEnabled] is false while the connection cannot carry a write, and
 * the controls **dim** rather than disappear. That is 04's rule (connection state is visible,
 * never a surprise error dialog) and it is the opposite of the *capability* rule one level up:
 * "you may not edit this character" is absence, "not this second" is a dimmed control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardWriteControls(
    card: DmCardUiState,
    onSpend: (String) -> Unit,
    onRestore: (String) -> Unit,
    onChangeHitPoints: (Int) -> Unit,
    onToggleCondition: (String) -> Unit,
) {
    val enabled = card.writeControlsEnabled

    card.hp?.let {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("dm:hp:${card.creatureId}"),
        ) {
            CardStepper(
                glyph = MINUS,
                contentDescription = stringResource(R.string.tracker_damage),
                enabled = enabled,
                onClick = { onChangeHitPoints(-1) },
            )
            CardStepper(
                glyph = PLUS,
                contentDescription = stringResource(R.string.tracker_heal),
                enabled = enabled,
                onClick = { onChangeHitPoints(1) },
            )
        }
    }

    card.slots.forEach { row ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dm_view_card_ratio, row.value, row.total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardStepper(
                glyph = MINUS,
                contentDescription = stringResource(R.string.tracker_spend_one, row.label),
                // The tracker's own clamp, mirrored in the control's enabled state so a
                // press-and-hold cannot outrun it: `OpenCharacter.spend` drops a spend at zero,
                // and a live-looking button that does nothing is the defect that hides.
                enabled = enabled && row.value > 0,
                onClick = { onSpend(row.propertyId) },
            )
            CardStepper(
                glyph = PLUS,
                contentDescription = stringResource(R.string.tracker_restore_one, row.label),
                enabled = enabled && row.value < row.total,
                onClick = { onRestore(row.propertyId) },
            )
        }
    }

    if (card.conditions.any { it.canFlip }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Only the flippable ones get a control. A computed toggle is worth *reading* (it is
            // in the card body above) but tapping it would earn a guaranteed `Computed toggle`
            // error — `ConditionChipState.canFlip`'s whole reason.
            card.conditions.filter { it.canFlip }.forEach { condition ->
                FilterChip(
                    selected = condition.enabled,
                    onClick = { onToggleCondition(condition.propertyId) },
                    enabled = enabled,
                    label = { Text(condition.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

/**
 * The card's spoken sentence, with every fragment resolved from `strings.xml`.
 *
 * A thin `@Composable` wrapper over [DmCardUiState.spokenLabel] rather than the sentence being
 * built inside that function, because the *rule* — which fragments, in what order, and which are
 * dropped when absent — is what needs a test, and a function that called `stringResource` could
 * not have one in this module. The words are copy; the shape is a decision.
 */
@Composable
private fun DmCardUiState.spoken(): String = spokenLabel(
    unavailableLabel = stringResource(R.string.dm_view_card_unavailable),
    loadingLabel = stringResource(R.string.dm_view_card_loading),
    hpLabel = hp?.let { stringResource(R.string.dm_view_card_hp, it.current, it.max) },
    slotsLabel = spentSlots
        .takeIf { it > 0 }
        ?.let { pluralStringResource(R.plurals.dm_view_card_slots_spent, it, it) },
    conditionsLabel = conditions
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { it.name }
        ?.let { stringResource(R.string.dm_view_card_conditions, it) },
    concentrationLabel = concentratingOn?.let {
        stringResource(R.string.dm_view_card_concentrating, it)
    },
    inventoryLabel = inventory?.let {
        pluralStringResource(
            if (it.overCapacity) {
                R.plurals.dm_view_card_inventory_over
            } else {
                R.plurals.dm_view_card_inventory
            },
            it.itemCount,
            it.itemCount,
            it.weight,
        )
    },
    // Spoken when this account has **no write grant** on this character — decision 18's
    // capability, not the toggle. Deliberately toggle-independent: "read only" said on every card
    // while the toggle is off would be six clauses restating the screen's own state, which is the
    // noise `spokenEquipLabel` avoids by dropping a fragment that merely agrees with the default.
    // What this fragment carries is the fact the DM cannot see any other way — that this
    // particular player has not shared write access, so no toggle will ever give them controls.
    readOnlyLabel = stringResource(R.string.dm_view_card_read_only).takeIf { !grantedEditing },
)

/**
 * One card-sized stepper button.
 *
 * ### Why a text glyph and not a Material icon
 *
 * `TrackerScreen.StepperButton`'s decision, reused rather than diverged from: [MINUS] is
 * U+2212 MINUS SIGN, which matches the `+` optically at small sizes where `Icons.Filled.Remove`
 * and `Icons.Filled.Add` do not — and a DM whose eye moves between a card and the character
 * screen must not find the same control drawn two ways.
 *
 * It is **not** `StepperButton` itself: that one carries the press-and-hold repeat the tracker's
 * pips need, which a card deliberately does not have. Holding a stepper on somebody else's sheet
 * to take 30 damage is not a gesture worth supporting on a screen where six characters are one
 * mis-tap apart; a DM applying a big hit opens the character and uses the number pad.
 */
@Composable
private fun CardStepper(
    glyph: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium)
    }
}
