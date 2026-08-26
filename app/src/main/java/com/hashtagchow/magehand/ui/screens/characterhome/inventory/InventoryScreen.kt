package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.ui.components.DirectEntryDialog
import com.hashtagchow.magehand.ui.components.DirectEntryKeys
import com.hashtagchow.magehand.ui.components.DirectEntryKind
import com.hashtagchow.magehand.ui.components.directEntry
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

    /**
     * FR-22 direct entry on a wallet row (15 decisions 5–7): set the denomination to an
     * absolute count.
     *
     * A [CoinKind] rather than a property id for [onCoinDelta]'s reason, whole: the row may
     * have no backing property yet, and on that row a typed number is what *creates* the coin
     * item carrying the whole count.
     */
    val onCoinSet: (coin: CoinKind, value: Int) -> Unit = { _, _ -> },

    /** The detail sheet's quantity stepper. Not on the list — see [InventoryRow]. */
    val onQuantityDelta: (propertyId: String, delta: Int) -> Unit = { _, _ -> },

    /**
     * FR-22 direct entry on an item's quantity — **both** the list row and the detail sheet
     * (decision 5 names both).
     *
     * The stepper deliberately stays off the list ([InventoryRow]'s "why the quantity stepper is
     * not here"); this does not put it back. A long press adds no pixels to a row whose job is
     * to be scanned, which is the whole of decision 7's argument for keeping the list calm — so
     * the two decisions are compatible rather than in tension.
     */
    val onQuantitySet: (propertyId: String, value: Int) -> Unit = { _, _ -> },

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

    // FR-22 (15 decision 5), hosted here for `TrackerTab`'s reasons: one dialog at a time, keyed
    // by id so it stays live against a sync, and `rememberSaveable` so a rotation mid-type keeps
    // the gesture. See `InventoryUiState.directEntryTarget`.
    var entryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val entryTarget = entryKey?.let { state.directEntryTarget(it) }
    if (entryKey != null && entryTarget == null) entryKey = null

    // FR-24 (15 decision 15): "glance, never preference". The query lives here, in a
    // `rememberSaveable`, exactly as the wallet's expander and the tracker's inactive-conditions
    // drawer do — and for the same reason stated one step harder by the design: a search is a
    // thing a player is doing *right now*, and persisting it would mean opening a character next
    // week to a filtered inventory. `rememberSaveable` so a rotation mid-search does not clear it.
    //
    // Everything below reads `filtered`; nothing writes anything. That is what makes decision
    // 15's "NOTHING writes to the FR-16 layout store" structural rather than a promise — see
    // `InventoryUiState.filteredBy`.
    var query by rememberSaveable { mutableStateOf("") }
    // The threshold reads the **unfiltered** state, so narrowing the list cannot remove the
    // field that narrowed it.
    val showsFilter = state.showsFilterField
    val active = showsFilter && query.isNotBlank()
    val filtered = if (showsFilter) state.filteredBy(query) else state

    entryTarget?.let { target ->
        DirectEntryDialog(
            // Every label on this tab is already a *name* rather than copy — an item's is off
            // the sheet and a coin's is `CoinKind.abbreviation`, which the wallet rows and the
            // collapsed summary both already print — so nothing has to be resolved here. The
            // tracker's HP row is the one case that needed a `strings.xml` lookup.
            label = target.label,
            current = target.current,
            max = target.max,
            onSet = { value ->
                when (target.kind) {
                    DirectEntryKind.COIN -> CoinKind.entries
                        .firstOrNull { it.name == target.propertyId }
                        ?.let { actions.onCoinSet(it, value) }

                    DirectEntryKind.ITEM -> actions.onQuantitySet(target.propertyId, value)
                    // This tab mints neither key. Named rather than swept into an `else` for
                    // `TrackerTab`'s reason.
                    DirectEntryKind.HIT_POINTS, DirectEntryKind.RESOURCE -> Unit
                }
            },
            onDismiss = { entryKey = null },
        )
    }

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

            // FR-24 decision 14: "above the sections, below the capacity line" — literally the
            // slot between the summary item and the first block, wallet included. Its own
            // `item` so typing does not recompose the summary above it.
            if (showsFilter) {
                item(key = "filter") {
                    InventoryFilterField(
                        query = query,
                        matchCount = filtered.itemRowCount,
                        active = active,
                        onQueryChange = { query = it },
                    )
                }
            }

            // 12 decisions 1 and 3: the order is the player's, and so is whether the wallet is
            // on the tab at all — so this walks one list rather than drawing the wallet and then
            // the sections. See `InventoryBlock`.
            filtered.blocks.forEach { block ->
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
                                    onDirectEntry = { coin ->
                                        entryKey = DirectEntryKeys.coin(coin)
                                    },
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
                                    onDirectEntry = {
                                        entryKey = DirectEntryKeys.item(row.propertyId)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Decision 16: an honest empty state that prints the query back. Distinct from
            // `EmptyInventory`, which says "this character carries nothing" — a sentence that
            // would be a lie about a sheet holding forty items and no rope.
            if (active && filtered.itemRowCount == 0) {
                item(key = "filter-empty") { NoMatches(query) }
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
    onDirectEntry: (CoinKind) -> Unit,
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
                        // FR-22 decisions 5 and 7: coins floor at zero and have no ceiling. The
                        // gesture is additive — both steppers are untouched — and it leaves the
                        // probe's anchor alone for the reason `PipRow` states.
                        .directEntry(
                            enabled = canWrite,
                            spoken = stringResource(
                                R.string.direct_entry_spoken,
                                label,
                                row.quantity,
                            ),
                            onOpen = { onDirectEntry(row.coin) },
                        )
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
    onDirectEntry: () -> Unit,
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
            // FR-22 decision 5 names "inventory rows" among the direct-entry surfaces, and the
            // gesture lands on the **row** rather than on the quantity text inside it. It has
            // to: this row is a clickable, so it merges its descendants into one accessibility
            // node and an inner clickable on the meta line would be swallowed by it — and the
            // meta line is absent entirely at ×1 (`showsQuantity`), which would make the
            // affordance appear and disappear with the number it edits.
            //
            // The tap is unchanged and still opens the detail sheet, so nothing a player has
            // learned about this list moves.
            .combinedClickable(
                onClick = { onTap(row.propertyId) },
                onLongClick = if (canWrite) onDirectEntry else null,
            )
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

/**
 * FR-24's filter field (docs/design/15-polish-batch.md decisions 14–17).
 *
 * ### Why a plain `OutlinedTextField` and not a `SearchBar`
 *
 * Material3's `SearchBar` is a *navigation* control: it expands to full screen, owns a back
 * gesture and carries a suggestion list. This filter has none of those — decision 15 fences out
 * search history and decision 16's empty state is a line in the list, not a screen — and a
 * control that looks like it will take over the tab and then does not is a worse lie than a
 * field that looks like a field. It is also decision 17's requirement in as many words: *"the
 * field is a standard text field (FR-4 inset class)"*, which is what makes the IME behaviour the
 * same as every other field in the app rather than a second implementation of it.
 *
 * ### The live region (decision 17)
 *
 * The list under a sighted player's finger visibly shrinks as they type; a screen-reader user
 * gets nothing at all unless something says so. `liveRegion = Polite` on a node carrying the
 * match count is that — polite rather than assertive because it must not interrupt the letter
 * the player is currently hearing echoed back.
 *
 * It is a **plural resource**, not a format string, for `SectionHeader`'s reason: this sentence
 * is read aloud and "1 items match" is exactly the kind of thing a screen-reader user hears in
 * full. The node is otherwise invisible — a zero-size `Spacer` would have worked, and a
 * `contentDescription` on the field itself would not: changing a field's description while it
 * has focus is what makes TalkBack re-announce the whole field on every keystroke.
 *
 * ### The clear button
 *
 * Present whenever there is anything to clear (decision 17). Clearing restores the stored layout
 * *exactly*, which costs nothing to guarantee here: the filter never wrote one — see
 * [InventoryUiState.filteredBy].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun InventoryFilterField(
    query: String,
    matchCount: Int,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = pluralStringResource(R.plurals.inventory_filter_matches, matchCount, matchCount)

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.inventory_filter_label)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    // Silent: the field's own label already says what it is, and an icon that
                    // announced "search" beside a field labelled "Search items" says it twice.
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.testTag("inventory:filter:clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.inventory_filter_clear),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory:filter"),
        )

        if (active) {
            // Announced, not drawn. The count is redundant for anyone who can see the list
            // change, and it is the only signal for anyone who cannot.
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

/**
 * FR-24 decision 16: *"an honest 'No items match' state with the query shown"*.
 *
 * The query is printed back because "No items match" on its own leaves a player who typed
 * `rpoe` with nothing to correct — the sentence has to name what was searched for, or the only
 * fact it carries is that something went wrong somewhere.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.inventory_filter_none, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(16.dp)
                .testTag("inventory:filter:empty"),
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
