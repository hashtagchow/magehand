package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.MINUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PLUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.StepperButton

/**
 * One item, expanded (docs/design/10-inventory.md decision 7).
 *
 * ### What this is for
 *
 * The list is a scan; this is the read. Everything the sheet knows about the item lands here
 * — quantity, weight, value, equipped, attunement, the prose — and the two controls that did
 * not earn a place on every row of the list live here too: the **quantity stepper** (see
 * `InventoryRow` for why it is not on the row) and a second copy of the equip control, so the
 * player who opened the wrong item's details can act without going back.
 *
 * ### Absent, not zero
 *
 * Every number the sheet did not give renders as an em dash rather than as `0`. A torch with
 * no recorded weight and a torch recorded as weightless are different facts and only the
 * second one is a claim — `InventoryItem.weightLb` keeps that distinction all the way from
 * the wire specifically so this sheet can print it.
 *
 * ### The "Can be equipped" switch
 *
 * 11 decision 2's override, and this is the one place it lives. The list is a scan and this is
 * the read — a per-item correction to how the app classified something is exactly the kind of
 * decision that belongs behind a deliberate tap, not on a row being scrolled past.
 *
 * @param row the same [InventoryRowState] the list rendered, not a second lookup. A row and
 *   its expanded form therefore cannot disagree about a number.
 * @param onEquippableOverride the switch above. Local only — it writes an
 *   `EquippableOverrideStore` key, never the sheet, so there is no undo entry and nothing to
 *   roll back. It is offered whether or not [canWrite]: refusing it offline would mean a player
 *   on a bad connection could not even fix how their own inventory is *displayed*.
 *
 * ### Delete and Move (FR-9, 12 decisions 7 and 8)
 *
 * Both live at the **bottom**, under everything the sheet reads out, and behind a divider. That
 * is not decoration: this sheet's job is the read, and the two controls that can change what the
 * player owns should not sit where a thumb lands on the way to the quantity stepper. Delete is
 * additionally behind a destructive confirm — the rest dialog's pattern, including the coloured
 * warning line — and the warning it prints differs by character kind, because a DiceCloud delete
 * is reversible and a local one is not (see [InventoryRowState.deleteWarningRes]).
 *
 * Whether each control renders at all is [InventoryRowState]'s decision, not this file's:
 * [InventoryRowState.showsDeleteControl] and [InventoryRowState.showsMoveControl] carry the
 * coin / equipped / local exclusions where a unit test can call them. This composable resolves
 * copy and owns two pieces of dialog latch state, and nothing else.
 *
 * @param moveTargets where this item may go, already filtered of the container it is in — pass
 *   `InventoryUiState.moveTargetsFor(row)`. Empty hides the Move control even where the row
 *   would offer it, which is the honest rendering of a sheet with nowhere else to put things.
 * @param onDelete confirmed deletion. The dialog has already been shown and accepted.
 * @param onMove `containerId` is `null` for the carried root — see `InventoryMoveTargetState`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ItemDetailSheet(
    row: InventoryRowState,
    canWrite: Boolean,
    onQuantityDelta: (propertyId: String, delta: Int) -> Unit,
    onEquip: (propertyId: String, equipped: Boolean) -> Unit,
    onEquippableOverride: (propertyId: String, canEquip: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    moveTargets: List<InventoryMoveTargetState> = emptyList(),
    onDelete: (propertyId: String) -> Unit = {},
    onMove: (propertyId: String, containerId: String?) -> Unit = { _, _ -> },
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val unknown = stringResource(R.string.inventory_unknown)
    // Two latches rather than one nullable enum: they are mutually exclusive in practice but
    // nothing needs them to be, and `rememberSaveable` keeps a half-made decision alive across a
    // rotation — the player who tilted their phone mid-confirm gets the dialog back rather than
    // a silently cancelled delete.
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var choosingMove by rememberSaveable { mutableStateOf(false) }
    // 12 decision 2's verb split, the same call the row makes — so the chip in the sheet and
    // the chip on the row it was opened from cannot say different things about one item.
    val equipDescription = row.spokenEquipLabel(
        equippedLabel = stringResource(R.string.inventory_chip_equipped),
        action = stringResource(row.equipActionRes),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("inventory:detail"),
        ) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("inventory:detail:name"),
            )

            Spacer8()

            // The equip control, second copy. Same FilterChip vocabulary as the row's, so the
            // two read as the same control rather than as two ways of doing something — and
            // gated by the same rule (11 decision 3), so the sheet cannot offer an equip the
            // row it was opened from does not.
            if (row.showsEquipControl) {
                FilterChip(
                    selected = row.equipped,
                    onClick = { onEquip(row.propertyId, !row.equipped) },
                    enabled = canWrite,
                    label = { Text(stringResource(row.equipChipLabelRes)) },
                    modifier = Modifier
                        .semantics { contentDescription = equipDescription }
                        .testTag("inventory:detail:equip"),
                )
                Spacer8()
            }

            // 11 decision 2. Present **only** where the sheet's own tags left this app unable
            // to tell — see `InventoryRowState.showsEquippableToggle` for why that is the
            // board's answer and not the effective one (a switch that vanished the moment it
            // was used would be a one-way door).
            if (row.showsEquippableToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inventory:detail:equippable"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.inventory_detail_equippable),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(
                                R.string.inventory_detail_equippable_hint,
                                row.name,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = row.equippableOverridden,
                        onCheckedChange = { onEquippableOverride(row.propertyId, it) },
                        modifier = Modifier.testTag("inventory:detail:equippable:switch"),
                    )
                }
                Spacer8()
            }
            HorizontalDivider()
            Spacer8()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.inventory_detail_quantity),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                StepperButton(
                    glyph = MINUS,
                    contentDescription = stringResource(R.string.tracker_use_one, row.name),
                    enabled = canWrite && row.canDecrement,
                    onStep = { onQuantityDelta(row.propertyId, -1) },
                    testTag = "inventory:detail:quantity:minus",
                )
                Text(
                    text = "${row.quantity}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .width(64.dp)
                        .testTag("inventory:detail:quantity"),
                )
                StepperButton(
                    glyph = PLUS,
                    contentDescription = stringResource(R.string.tracker_add_one, row.name),
                    enabled = canWrite,
                    onStep = { onQuantityDelta(row.propertyId, +1) },
                    testTag = "inventory:detail:quantity:plus",
                )
            }

            DetailLine(
                label = stringResource(R.string.inventory_detail_weight),
                value = row.unitWeight?.let { stringResource(R.string.inventory_weight, it) }
                    ?: unknown,
                testTag = "inventory:detail:weight",
            )
            DetailLine(
                label = stringResource(R.string.inventory_detail_value),
                value = row.unitValue?.let { stringResource(R.string.inventory_value, it) }
                    ?: unknown,
                testTag = "inventory:detail:value",
            )

            // The stack line only earns its place when there is more than one of the thing
            // *and* the sheet gave both numbers — otherwise it repeats the two lines above.
            val stackWeight = row.stackWeight
            val stackValue = row.stackValue
            if (row.quantity > 1 && stackWeight != null && stackValue != null) {
                Text(
                    text = stringResource(
                        R.string.inventory_detail_stack,
                        stackWeight,
                        stackValue,
                        row.quantity,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("inventory:detail:stack"),
                )
            }

            // 10 decision 9's gate again, at row scale: absent unless *this* item says
            // something. A block reading "Not attuned" on an ordinary SRD torch would be the
            // app answering a question the sheet never asked.
            if (row.showsAttunement) {
                Spacer8()
                HorizontalDivider()
                Spacer8()
                Text(
                    text = stringResource(R.string.inventory_detail_attunement),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier.testTag("inventory:detail:attunement"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (row.requiresAttunement == true) {
                        Text(
                            text = stringResource(R.string.inventory_attunement_requires),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // Only printed when the sheet carries the field at all — `null` stays
                    // silent rather than becoming "Not attuned".
                    row.attuned?.let { attuned ->
                        Text(
                            text = stringResource(
                                if (attuned) {
                                    R.string.inventory_attunement_attuned
                                } else {
                                    R.string.inventory_attunement_not_attuned
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            row.description?.let { description ->
                Spacer8()
                HorizontalDivider()
                Spacer8()
                Text(
                    text = stringResource(R.string.inventory_detail_description),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("inventory:detail:description"),
                )
            }

            // FR-9. Last, under a divider, and only when the row offers either — a lone
            // divider over nothing is worse than no divider.
            val showsMove = row.showsMoveControl && moveTargets.isNotEmpty()
            if (row.showsDeleteControl || showsMove) {
                Spacer8()
                HorizontalDivider()
                Spacer8()
                if (showsMove) {
                    TextButton(
                        onClick = { choosingMove = true },
                        enabled = canWrite,
                        modifier = Modifier.testTag("inventory:detail:move"),
                    ) { Text(stringResource(R.string.inventory_move)) }
                }
                if (row.showsDeleteControl) {
                    TextButton(
                        onClick = { confirmingDelete = true },
                        enabled = canWrite,
                        // Error colour on the button itself, so the destructive one is
                        // distinguishable from the neighbouring Move before it is read.
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.testTag("inventory:detail:delete"),
                    ) { Text(stringResource(R.string.inventory_delete)) }
                }
            }
        }
    }

    if (confirmingDelete) {
        DeleteConfirmDialog(
            row = row,
            onConfirm = {
                onDelete(row.propertyId)
                // The sheet closes with the dialog: the item it was reading out no longer
                // exists, and `InventoryUiState.row` would return null a frame later anyway
                // (the screen's own "the item vanished" branch). Closing here makes the tap
                // feel finished rather than leaving a sheet that dismisses itself.
                onDismiss()
            },
            onDismiss = { confirmingDelete = false },
        )
    }

    if (choosingMove) {
        MoveTargetDialog(
            row = row,
            targets = moveTargets,
            onPick = { containerId ->
                onMove(row.propertyId, containerId)
                choosingMove = false
                onDismiss()
            },
            onDismiss = { choosingMove = false },
        )
    }
}

/**
 * The destructive confirm in front of Delete (12 decision 7).
 *
 * `RestConfirmDialog`'s pattern, deliberately down to the shape: a title naming the thing, a
 * short body, and the consequence in the error colour at the bottom. That the two dialogs look
 * alike is the point — this app has one visual vocabulary for "this is the last chance to stop",
 * and a second one would make the first stop meaning anything.
 *
 * Where it *differs* from the rest dialog is the one line that matters: a rest is never undoable
 * and says so flatly, while a delete's reversibility depends on the character. That sentence is
 * chosen by [InventoryRowState.deleteWarningRes] rather than here, so a test can assert which
 * warning a given row earns without a Compose runtime.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeleteConfirmDialog(
    row: InventoryRowState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    /**
     * Fires the confirm **once** (LOW-2).
     *
     * A double tap on this button used to submit two `RemoveProperty` ops. Nothing downstream
     * could absorb the second: the op has no `coalesceKey` by design (a delete is not a stepper
     * — see `WriteOp.RemoteProperty`), and `DefaultOpenCharacter.removeItem`'s stale-id guard
     * reads the board, which cannot have updated between two taps in the same frame. The result
     * was a second `RestoreProperty` left on the undo stack: one UNDO put the item back, and the
     * button stayed lit offering a restore of something already restored.
     *
     * The check is made **synchronously inside the lambda**, not by `enabled` alone. `enabled`
     * only takes effect at the next recomposition, which is exactly the window a double tap
     * lives in; reading and writing the state in the click handler closes it, because a
     * `MutableState` write is visible to the next read immediately. `enabled` is kept on top so
     * the refusal is visible rather than silent, per 04 §3's rule for every other dimmed control
     * on these screens.
     *
     * `remember`, not `rememberSaveable`: the dialog dismisses on that same tap, so there is no
     * configuration change for the latch to survive — and one that *did* survive would mean a
     * rotation mid-confirm left a dead button behind.
     */
    var confirmed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inventory_delete_title, row.name)) },
        text = {
            Text(
                text = stringResource(row.deleteWarningRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // Error colour on both wordings. The reversible one is still a deletion, and
                // the sentence a player needs to read is the one that says what happens next.
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("inventory:delete:warning"),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    // Synchronous check-and-set — see the latch's KDoc for why `enabled` alone
                    // is one recomposition too late to stop a double tap.
                    if (!confirmed) {
                        confirmed = true
                        onConfirm()
                        onDismiss()
                    }
                },
                enabled = !confirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("inventory:delete:confirm"),
            ) { Text(stringResource(R.string.inventory_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The "Move to…" picker (12 decision 8): the carried root plus every container, by name.
 *
 * ### A dialog rather than a second bottom sheet
 *
 * The detail sheet is a `ModalBottomSheet`, and stacking another one on it is a fight with two
 * scrims and two back handlers for no gain. A picker is a short list of mutually exclusive
 * choices, which is what an `AlertDialog` is for.
 *
 * ### No confirm, and no "are you sure"
 *
 * A move is fully undoable (`WriteOp.MoveProperty` carries the prior parent and order), and the
 * picker is itself the deliberate step. Putting a confirm in front of a reversible action is how
 * players learn to dismiss confirms without reading them — which is exactly what has to not
 * happen on the delete dialog above.
 *
 * [targets] arrives already filtered of the container the item is in, by
 * `InventoryUiState.moveTargetsFor`, so every row here is a real destination.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MoveTargetDialog(
    row: InventoryRowState,
    targets: List<InventoryMoveTargetState>,
    onPick: (containerId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val carried = stringResource(R.string.inventory_move_carried)
    val unnamedContainer = stringResource(R.string.inventory_section_container)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inventory_move_title, row.name)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { testTagsAsResourceId = true },
            ) {
                targets.forEach { target ->
                    Text(
                        // The carried root is the fixed word; a container is its own name, and
                        // a blank-named one falls back to the generic section heading — the same
                        // fallback `InventorySectionKind.CONTAINER` already makes. Deliberately
                        // *not* falling back to "Carried": two rows reading the same word would
                        // be two destinations nobody could choose between.
                        text = if (target.containerId == null) {
                            carried
                        } else {
                            target.name?.takeIf { it.isNotBlank() } ?: unnamedContainer
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(target.containerId) }
                            .padding(vertical = 12.dp)
                            .testTag("inventory:move:${target.containerId ?: "carried"}"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DetailLine(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(testTag),
        )
    }
}

/** The one vertical rhythm this sheet uses between blocks. */
@Composable
private fun Spacer8() {
    Spacer(Modifier.height(8.dp))
}
