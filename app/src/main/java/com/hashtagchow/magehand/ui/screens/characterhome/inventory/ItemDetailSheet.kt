package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
 * @param row the same [InventoryRowState] the list rendered, not a second lookup. A row and
 *   its expanded form therefore cannot disagree about a number.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ItemDetailSheet(
    row: InventoryRowState,
    canWrite: Boolean,
    onQuantityDelta: (propertyId: String, delta: Int) -> Unit,
    onEquip: (propertyId: String, equipped: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val unknown = stringResource(R.string.inventory_unknown)
    val equipDescription = stringResource(
        if (row.equipped) R.string.inventory_unequip else R.string.inventory_equip,
        row.name,
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
            // two read as the same control rather than as two ways of doing something.
            FilterChip(
                selected = row.equipped,
                onClick = { onEquip(row.propertyId, !row.equipped) },
                enabled = canWrite,
                label = { Text(stringResource(R.string.inventory_section_equipped)) },
                modifier = Modifier
                    .semantics { contentDescription = equipDescription }
                    .testTag("inventory:detail:equip"),
            )

            Spacer8()
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
        }
    }
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
