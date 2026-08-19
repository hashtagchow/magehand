package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.MINUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PLUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.StepperButton

/**
 * Everything the inventory tab can ask of the ViewModel (docs/design/10-inventory.md).
 *
 * One parameter object for the same reason `TrackerActions` is one, and with the same rule:
 * each callback carries an identity — a `creatureProperties._id`, or a [CoinKind] — and never
 * a row object. The row is re-resolved against the live board in the ViewModel, so a tap that
 * raced a re-sync writes to nothing rather than to a stale value.
 */
data class InventoryActions(
    /**
     * The row's one-tap equip control (10 decision 4's headline interaction).
     *
     * `equipped` is the state being *requested*, not the current one. The ViewModel re-reads
     * the current state before writing, which is what lets `:core:data` build a correct
     * inverse for undo without trusting a frame that may already be stale.
     */
    val onEquip: (propertyId: String, equipped: Boolean) -> Unit = { _, _ -> },

    /** A wallet stepper. `+1` on a denomination the sheet lacks creates it — see `adjustCoins`. */
    val onCoinDelta: (coin: CoinKind, delta: Int) -> Unit = { _, _ -> },

    /** The detail sheet's quantity stepper. Not on the list — see [InventoryRow]. */
    val onQuantityDelta: (propertyId: String, delta: Int) -> Unit = { _, _ -> },

    /** A row was tapped: open its detail sheet (10 decision 7). */
    val onRowTap: (propertyId: String) -> Unit = {},
)

/**
 * The Inventory tab (docs/design/10-inventory.md decision 2) — the third tab on a DiceCloud
 * character and the second on a local one.
 *
 * ### Layout, top→bottom
 *
 * carried-weight line (+ attunement chip) · Wallet · Equipped · one section per container ·
 * Carried.
 *
 * That order is decision 2's, and the reason it is *states* rather than the sheet's folder
 * tree is the whole design: `creatureProperties.equip` **reparents** the item, so a tree view
 * would make every equip look like the item teleporting into a folder the player never chose.
 * Grouping by state makes the reparenting invisible by construction — the row moves between
 * Equipped and Carried, which is the move the player asked for and the only one they see.
 *
 * ### What a tap can and cannot do
 *
 * As on the tracker: every control calls an [InventoryActions] lambda with an id. There is no
 * DDP method name in this file and no `WriteOp`; the vocabulary lives in `:core:data` behind
 * `OpenCharacter`'s named intents, and `WritePostureTest` asserts that mechanically. Controls
 * are inert unless [InventoryUiState.canWrite].
 *
 * ### testTagsAsResourceId
 *
 * Same reason the tracker opts in: the emulator probe reads rendered values back with
 * `adb uiautomator dump`, which sees `resource-id` and not Compose test tags.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InventoryTab(
    state: InventoryUiState,
    modifier: Modifier = Modifier,
    actions: InventoryActions = InventoryActions(),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "summary") { InventorySummary(state) }

            // Always present, always four rows, even on a sheet carrying no coins at all
            // (10 decision 5). "You have no silver" and "this app could not find your silver"
            // look identical when the row is simply missing, and only one of them is true.
            item(key = "wallet-header") {
                SectionHeader(
                    title = stringResource(R.string.inventory_section_wallet),
                    // No weight figure: coins *do* count towards the carried total (see
                    // `Wallet.weightLb`), but a "2.2 lb" beside the purse invites the reader
                    // to add the section figures up and find they do not match the top line,
                    // which sums containers by the server's rollup. The one number that is
                    // authoritative is the one at the top.
                    weight = null,
                    testTag = "inventory:section:wallet",
                )
            }
            item(key = "wallet") {
                WalletRows(
                    wallet = state.wallet,
                    canWrite = state.canWrite,
                    onDelta = actions.onCoinDelta,
                )
            }

            state.sections.forEach { section ->
                item(key = "${section.key}-header") {
                    SectionHeader(
                        title = section.containerName
                            ?: stringResource(section.kind.titleRes),
                        weight = section.weight,
                        testTag = "inventory:section:${section.key}",
                    )
                }
                items(section.rows, key = { "${section.key}-${it.propertyId}" }) { row ->
                    InventoryRow(
                        row = row,
                        canWrite = state.canWrite,
                        onEquip = actions.onEquip,
                        onTap = actions.onRowTap,
                    )
                }
            }

            if (state.isEmpty) item(key = "empty") { EmptyInventory() }
        }
    }
}

/**
 * The top line: *"142 / 225 lb"*, and the attunement chip when the sheet has anything to say.
 *
 * The denominator is absent — not zero, not a guess — when the source expresses no Strength,
 * and the sentence changes with it rather than trailing an empty slash. See
 * [InventoryUiState.capacityWeight]; a capacity bar is exactly the kind of number that gets
 * believed, so an invented one is worse than none.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InventorySummary(state: InventoryUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.capacityWeight?.let {
                    stringResource(R.string.inventory_carried_of, state.carriedWeight, it)
                } ?: stringResource(R.string.inventory_carried, state.carriedWeight),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("inventory:carried"),
            )
            if (state.isOverCapacity) {
                Text(
                    text = stringResource(R.string.inventory_over_capacity),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("inventory:over-capacity"),
                )
            }
        }

        // 10 decision 9: present only when at least one item carries `requiresAttunement` or
        // `attuned`. Absent on the overwhelming majority of sheets, which is the honest
        // rendering of data that never mentions attunement.
        state.attunement?.let { chip ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(stringResource(R.string.inventory_attuned, chip.attuned, chip.slots))
                },
                // A read-out, not a control — there is no DiceCloud method that attunes an
                // item, so `AssistChip` disabled is the app's existing vocabulary for "a chip
                // shaped thing that states a fact". Its own colours rather than the disabled
                // ones, because nothing here is switched off; it was never switchable.
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.testTag("inventory:attunement"),
            )
        }
    }
}

/**
 * The four coin rows and the total (10 decision 5).
 *
 * The steppers are the tracker's own [StepperButton], press-and-hold acceleration included,
 * and that is not just code reuse: adding 50 gp after a session is a hold, and the write
 * queue coalesces the whole burst into a single `adjustQuantity` with the summed value. A
 * bespoke button here would have had to re-earn that.
 *
 * No exchange or make-change arithmetic, deliberately (decision 5's fence): converting 10 sp
 * into 1 gp is a table decision with house rules attached, and an app that did it silently
 * would be rewriting a player's sheet to satisfy its own tidiness.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WalletRows(
    wallet: WalletUiState,
    canWrite: Boolean,
    onDelta: (CoinKind, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        wallet.rows.forEach { row ->
            val label = row.coin.abbreviation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("inventory:wallet:${row.coin.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StepperButton(
                    glyph = MINUS,
                    contentDescription = stringResource(R.string.inventory_coin_remove, label),
                    // A decrement on an empty row is refused in `:core:data` too; dimming it
                    // is 04's rule that the refusal is visible rather than surprising.
                    enabled = canWrite && row.canDecrement,
                    onStep = { onDelta(row.coin, -1) },
                    testTag = "inventory:wallet:${row.coin.name.lowercase()}:minus",
                )
                Text(
                    text = "${row.quantity}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .width(64.dp)
                        .testTag("inventory:wallet:${row.coin.name.lowercase()}:value"),
                )
                StepperButton(
                    glyph = PLUS,
                    contentDescription = stringResource(R.string.inventory_coin_add, label),
                    // Never disabled by quantity: on a sheet with no such coin this is the
                    // tap that creates it, and the player is told nothing about the
                    // difference because there is nothing they could do with it.
                    enabled = canWrite,
                    onStep = { onDelta(row.coin, +1) },
                    testTag = "inventory:wallet:${row.coin.name.lowercase()}:plus",
                )
            }
        }

        Text(
            text = stringResource(R.string.inventory_wallet_total, wallet.totalGp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("inventory:wallet:total"),
        )
    }
}

/**
 * One item row: name, a compact quantity/weight line, and the equip control.
 *
 * ### Why the quantity stepper is not here
 *
 * It was the obvious thing to put on the row and it is deliberately in the detail sheet
 * instead (10 decision 7). A stepper is two 48 dp targets plus a number — a third of the row
 * — on every row of a list whose job is to be *scanned*, and the great majority of a
 * character's inventory is things whose count never changes. Keeping the list calm is what
 * makes the one interaction that does belong on every row — equip — findable at a glance.
 *
 * ### Why the equip control is a `FilterChip`
 *
 * The same argument the condition chips make: a filter chip has a *selected* state built in,
 * which is exactly what equipped is, and it carries that state into the accessibility tree
 * rather than relying on a container colour. It is therefore both the indicator and the
 * control, in one node, which is what a screen reader needs.
 *
 * Its checked state is redundant with the row's section by construction — `InventoryBoard`'s
 * precedence puts every equipped item in Equipped and nowhere else — and it is kept anyway,
 * because "redundant for a sighted user scrolling" and "the only statement of the fact for a
 * screen reader landing on the row" are different things.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InventoryRow(
    row: InventoryRowState,
    canWrite: Boolean,
    onEquip: (String, Boolean) -> Unit,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val equipDescription = stringResource(
        if (row.equipped) R.string.inventory_unequip else R.string.inventory_equip,
        row.name,
    )
    val openDescription = stringResource(R.string.inventory_open_detail, row.name)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onTap(row.propertyId) }
            .semantics { contentDescription = openDescription }
            .testTag("inventory:row:${row.propertyId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // One line for both facts, and each half is absent rather than blank when the
            // sheet does not say: a row reading "×1 · 0 lb" would be claiming a weight the
            // source never gave. See InventoryRowState.showsQuantity / stackWeight.
            val meta = listOfNotNull(
                row.quantity.takeIf { row.showsQuantity }
                    ?.let { stringResource(R.string.inventory_quantity, it) },
                row.stackWeight?.let { stringResource(R.string.inventory_weight, it) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("inventory:row:${row.propertyId}:meta"),
                )
            }
        }

        FilterChip(
            selected = row.equipped,
            onClick = { onEquip(row.propertyId, !row.equipped) },
            enabled = canWrite,
            label = { Text(stringResource(R.string.inventory_section_equipped), maxLines = 1) },
            modifier = Modifier
                .semantics { contentDescription = equipDescription }
                .testTag("inventory:row:${row.propertyId}:equip"),
        )
    }
}

/**
 * A section heading, with its weight on the right.
 *
 * The two numbers are not the same sum and that is argued on [toInventoryUiState]: a
 * container prints the **server's** rollup so it cannot disagree with DiceCloud's own UI,
 * while Equipped and Carried print client sums so they cannot disagree with the
 * removed-filtered grand total above them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionHeader(
    title: String,
    weight: String?,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        weight?.let {
            Text(
                text = stringResource(R.string.inventory_weight, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyInventory(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.inventory_empty),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.inventory_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("inventory:empty"),
            )
        }
    }
}
