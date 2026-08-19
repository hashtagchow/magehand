package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    /**
     * The detail sheet's Delete, **after** its destructive confirm (FR-9, 12 decision 7).
     *
     * Not on the list, and for a stronger version of the reason the quantity stepper is not:
     * a stepper on every row is clutter, a delete on every row is a mis-tap that costs the
     * player an item. It is two deliberate taps deep — open the item, confirm — which is the
     * ratio decision 7's "destructive confirm" is asking for.
     */
    val onDelete: (propertyId: String) -> Unit = {},

    /**
     * A destination was picked in "Move to…" (12 decision 8). `containerId` is `null` for the
     * carried root — see `InventoryMoveTargetState`.
     */
    val onMove: (propertyId: String, containerId: String?) -> Unit = { _, _ -> },

    /**
     * A section header was tapped: remember that this character wants it shut, or open
     * (FR-16, docs/design/13-collapsible-sections-local-gear.md decisions 1 and 3).
     *
     * `collapsed` is the state being **requested**, as [onEquip]'s `equipped` is, and for a
     * weaker version of the same reason: the ViewModel re-reads the stored arrangement before
     * writing, so a tap that raced a re-sync writes against what is on disk rather than against
     * a frame that may already be stale.
     *
     * The **Wallet never reaches this** — its chevron is wired to a `rememberSaveable` in
     * [InventoryTab], which is decision 3's exception. `InventoryLayoutPlan.setCollapsed` refuses
     * the key as well, so the exception holds even if a future caller forgets.
     */
    val onCollapse: (key: String, collapsed: Boolean) -> Unit = { _, _ -> },
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
    // FR-11 (11 decision 4). Collapsed by default and **ephemeral**, exactly like the tracker's
    // inactive-conditions drawer: it is a glance, not a preference. A player who opened the
    // steppers to hand over 15 gp does not want every character they open next week to start
    // with four steppers between the top line and their gear. `rememberSaveable` so a rotation
    // mid-count does not slam it shut.
    var walletExpanded by rememberSaveable { mutableStateOf(false) }

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

            // 12 decisions 1 and 3: the order is the player's, and so is whether the wallet is
            // on the tab at all — so this walks one list rather than drawing the wallet and then
            // the sections. See `InventoryBlock`.
            state.blocks.forEach { block ->
                when (block) {
                    // Always four rows, even on a sheet carrying no coins at all (10 decision 5):
                    // "you have no silver" and "this app could not find your silver" look
                    // identical when the row is simply missing, and only one of them is true.
                    // FR-11 collapses the *steppers*, never the block: while the wallet is on the
                    // tab, its header and summary are always on screen.
                    is InventoryBlock.Wallet -> {
                        item(key = "wallet-header") {
                            WalletHeader(
                                wallet = state.wallet,
                                expanded = walletExpanded,
                                onToggle = { walletExpanded = !walletExpanded },
                            )
                        }
                        if (walletExpanded) {
                            item(key = "wallet") {
                                WalletRows(
                                    wallet = state.wallet,
                                    canWrite = state.canWrite,
                                    onDelta = actions.onCoinDelta,
                                )
                            }
                        }
                    }

                    is InventoryBlock.Items -> {
                        val section = block.section
                        item(key = "${section.key}-header") {
                            SectionHeader(
                                section = section,
                                onToggle = {
                                    actions.onCollapse(section.key, !section.collapsed)
                                },
                            )
                        }
                        // FR-16 (13 decision 1). The header stays and the rows go — which is the
                        // whole difference from a *hidden* section, whose rows are still on the
                        // tab under Gear. See `toInventoryUiState`'s collapse section.
                        if (!section.collapsed) {
                            items(section.rows, key = { "${section.key}-${it.propertyId}" }) { row ->
                                InventoryRow(
                                    row = row,
                                    canWrite = state.canWrite,
                                    onEquip = actions.onEquip,
                                    onTap = actions.onRowTap,
                                )
                            }
                        }
                    }
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
 * The wallet's one always-visible row: the section title, its compact summary, and a chevron
 * (FR-11, docs/design/11-inventory-polish.md decision 4).
 *
 * ### What collapsing actually buys
 *
 * Four stepper rows at 56 dp each is 224 dp — most of a phone's first screenful — spent on the
 * one part of the inventory a player touches after a session rather than during one. Collapsed,
 * the same information is one line ("2 pp · 15 gp · 3 sp"), and Equipped starts above the fold.
 * The *reading* is therefore not lost, only the controls, which is the trade: at the table you
 * glance at your money far more often than you change it.
 *
 * ### No weight figure, still
 *
 * Coins *do* count towards the carried total (see `Wallet.weightLb`), but a "2.2 lb" beside the
 * purse invites the reader to add the section figures up and find they do not match the top
 * line, which sums containers by the server's rollup. The one authoritative number is the one
 * at the top — 10 decision 10's rule, and FR-11 does not change it.
 *
 * ### One spoken sentence, not three merged fragments
 *
 * This row is clickable, and a clickable **merges its descendants into one accessibility
 * node**. A `contentDescription` naming only the action would therefore *replace* the title and
 * the summary rather than adding to them, and a screen-reader user would be told the control
 * exists without ever being told how much money they have — which is the one reading FR-11
 * offers in exchange for hiding the steppers. [WalletUiState.spokenLabel] folds all three into
 * a sentence; the copy is resolved here and the rule is pinned there.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WalletHeader(
    wallet: WalletUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.inventory_section_wallet)
    val emptyLabel = stringResource(R.string.inventory_wallet_empty)
    // The chevron is what a sighted user reads as "this opens"; the spoken sentence has to say
    // it in words, because a title and a coin total do not say it between them.
    val action = stringResource(
        if (expanded) R.string.inventory_wallet_collapse else R.string.inventory_wallet_expand,
    )
    val spoken = wallet.spokenLabel(title = title, emptyLabel = emptyLabel, action = action)

    ExpanderRow(
        expanded = expanded,
        spoken = spoken,
        onToggle = onToggle,
        testTag = "inventory:section:${InventoryLayoutKeys.WALLET}",
        modifier = modifier,
    ) {
        SectionTitle(title)
        Text(
            text = wallet.summary ?: emptyLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .testTag("inventory:wallet:summary"),
        )
    }
}

/**
 * The chrome every expander on this tab shares (FR-16, 13 decision 1): one 48 dp clickable row,
 * one merged accessibility node carrying [spoken], and a chevron that points the way the tap
 * goes.
 *
 * ### Why the chrome is shared and the headers are not
 *
 * Decision 1 asks for the wallet's affordance on every section — *"same chevron + tap-target
 * chrome"* — and this is that, literally: the parts that must not drift are the touch target
 * (04's large-targets rule), the `mergeDescendants` semantics that make a clickable row one node,
 * and which direction the arrow points. Those are here, once.
 *
 * What is *not* shared is the middle. [WalletHeader] prints a coin line and no weight figure at
 * all (10 decision 10: the coins count towards the top line and print no section figure), while
 * [SectionHeader] prints a count and a weight. Folding both into one composable would have meant
 * three nullable slots each used by exactly one caller — the shape `InventorySectionKind`'s KDoc
 * already refuses for the wallet, arriving at the same answer one layer up. Two headers, one
 * chrome, and the `content` slot is where they differ.
 *
 * @param spoken the whole sentence. **Not** an action fragment: a clickable merges its
 *   descendants, so whatever is set here *replaces* everything inside [content] for a screen
 *   reader rather than adding to it. See [WalletUiState.spokenLabel].
 * @param content the row's middle, laid out in a `RowScope` — at least one child must take the
 *   remaining width (`Modifier.weight(1f)`) or the chevron floats in against the title.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExpanderRow(
    expanded: Boolean,
    spoken: String,
    onToggle: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            content()
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                // Silent: the merged node above already says "collapsed, tap to expand" in
                // words, and a chevron that also announced itself would say it twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * A section's name as the eye reads it.
 *
 * Uppercased **here and not in the spoken sentence**, which is the point of it being its own
 * composable: a screen reader may spell an all-caps word out letter by letter, so every
 * `spokenLabel` on this tab is handed the title as written while the row draws it shouting.
 */
@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
    // 12 decision 2. The rule — which verb, and that the state fragment appears only when the
    // item is equipped — lives on the row where a test can call it; this resolves the copy.
    val equipDescription = row.spokenEquipLabel(
        equippedLabel = stringResource(R.string.inventory_chip_equipped),
        action = stringResource(row.equipActionRes),
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
            // One line for both facts. The quantity half is absent at ×1 (see
            // `showsQuantity`); the weight half is **always** printed, as a number or as an em
            // dash — 11 decision 6, K10. Dropping the cell used to make an unweighed row read
            // as "0 lb" to anyone not stopping to think about it, and lost the column's
            // right-hand alignment wherever a sheet had been sloppy. See `stackWeightLabel`.
            val weight = if (row.hasWeight) {
                stringResource(R.string.inventory_weight, row.stackWeightLabel)
            } else {
                row.stackWeightLabel
            }
            val meta = listOfNotNull(
                row.quantity.takeIf { row.showsQuantity }
                    ?.let { stringResource(R.string.inventory_quantity, it) },
                weight,
            ).joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("inventory:row:${row.propertyId}:meta"),
            )
        }

        // 11 decision 3: only on rows this app is willing to call equippable. A tinderbox gets
        // nothing — not a disabled chip, which would be a control the player could tap at
        // forever, but no control at all. An overridden item gets one back (decision 2), which
        // is why this reads the effective answer rather than the board's.
        if (row.showsEquipControl) {
            FilterChip(
                selected = row.equipped,
                onClick = { onEquip(row.propertyId, !row.equipped) },
                enabled = canWrite,
                // "Equip" while it is off, "Equipped" while it is on (12 decision 2). The chip
                // used to read one word in both states and leave the difference to its tint,
                // which is legible only when the two are side by side.
                label = { Text(stringResource(row.equipChipLabelRes), maxLines = 1) },
                modifier = Modifier
                    .semantics { contentDescription = equipDescription }
                    .testTag("inventory:row:${row.propertyId}:equip"),
            )
        }
    }
}

/**
 * A section heading: its name, a summary of what is in it, and a chevron
 * (FR-16, docs/design/13-collapsible-sections-local-gear.md decisions 1 and 5).
 *
 * ### What changed, and what did not
 *
 * This used to be a plain label with a weight on the right. FR-16 makes it an **expander** — the
 * wallet's affordance, generalized to every section, per the standing convention in 00-DESIGN.md
 * that sectioned surfaces are collapsible. What did not change is the weight itself, which is
 * still the two different sums argued on [toInventoryUiState]: a container prints the **server's**
 * rollup so it cannot disagree with DiceCloud's own UI, while Equipped and the three Carried
 * subsections print client sums so they cannot disagree with the removed-filtered grand total
 * above them.
 *
 * Every section drawn through here has a weight; the wallet is the deliberate exception
 * (10 decision 10) and has its own header. What the two share is [ExpanderRow] — see there.
 *
 * ### The spoken sentence
 *
 * Built by [InventorySectionState.spokenLabel] from five resolved fragments, because the row is a
 * clickable and a clickable merges its descendants into **one** accessibility node: a description
 * naming only the action would replace the name, the count and the weight rather than adding to
 * them. Every word is `strings.xml`'s and the order is the state's; see there for the rule.
 *
 * The count is a **plural resource** rather than a format string, and that is not pedantry: this
 * sentence is read aloud, and "1 items" is the kind of thing a screen-reader user hears in full.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionHeader(
    section: InventorySectionState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = section.containerName ?: stringResource(section.kind.titleRes)
    val countLabel = pluralStringResource(
        R.plurals.inventory_section_items,
        section.itemCount,
        section.itemCount,
    )
    val weightLabel = stringResource(R.string.inventory_weight, section.weight)
    val stateLabel = stringResource(
        if (section.collapsed) {
            R.string.inventory_section_collapsed
        } else {
            R.string.inventory_section_expanded
        },
    )
    val action = stringResource(
        if (section.collapsed) {
            R.string.inventory_section_expand
        } else {
            R.string.inventory_section_collapse
        },
    )

    ExpanderRow(
        expanded = !section.collapsed,
        spoken = section.spokenLabel(
            title = title,
            countLabel = countLabel,
            weightLabel = weightLabel,
            stateLabel = stateLabel,
            action = action,
        ),
        onToggle = onToggle,
        testTag = "inventory:section:${section.key}",
        modifier = modifier,
    ) {
        SectionTitle(title)
        Text(
            text = section.summary(countLabel = countLabel, weightLabel = weightLabel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Right-aligned into the remaining width, so a column of headers keeps the weight
            // figures lined up the way the old label-plus-weight row did.
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .testTag("inventory:section:${section.key}:summary"),
        )
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
