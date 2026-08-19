package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.Wallet
import java.util.Locale

/**
 * The inventory tab's rendered state (docs/design/10-inventory.md).
 *
 * Same posture as `TrackerUiState`: everything the screen draws is derived from one
 * [InventoryBoard] plus two session signals by [toInventoryUiState], which is a **pure
 * function**. The section split, the weight arithmetic and the two gates that decide whether
 * a line renders at all (capacity, attunement) are the parts most likely to go quietly wrong,
 * and all of them are unit-tested in `InventoryUiStateTest` with no device and no Compose
 * runtime.
 *
 * ### Why the numbers are formatted here and the sentences are not
 *
 * Every field below that reads as a number is a **bare string** — `"142"`, `"0.02"` — and
 * every field that reads as a sentence is a `@StringRes`. That split is deliberate: the
 * arithmetic and the rounding are what a test can assert and what a bad edit would break,
 * while "lb", "gp" and "Attuned %1$d/%2$d" are copy, and copy belongs in `strings.xml` where
 * it can be translated. The composable does the one join.
 */

/** How many decimal places a weight or a price is ever shown to. See [formatAmount]. */
private const val AMOUNT_DECIMALS = 2

/**
 * `142.0` → `"142"`, `1.5` → `"1.5"`, `0.02` → `"0.02"`, `0.005` → `"0.01"`.
 *
 * **The one copy of this rule**, used for every weight and every price on the tab.
 *
 * Trailing zeros are stripped rather than padded because the overwhelming majority of these
 * numbers are whole — a torch weighs 1 lb, not 1.00 lb — and a column of `"1.00"`, `"2.00"`,
 * `"7.00"` makes a sheet of ordinary gear look like a spreadsheet. The two decimals that
 * survive are the ones that carry meaning: a copper piece is worth 0.01 gp and a coin weighs
 * 0.02 lb, and rounding either to zero would print "free" and "weightless" at a player
 * holding 400 of them.
 *
 * [Locale.ROOT] rather than the device locale, and that is not an oversight: this string is
 * fed back into `strings.xml` formats beside a translated unit, but the *number* has to keep
 * a `.` separator because it is also what the emulator parity probe reads back and compares
 * against a REST snapshot. A device set to a comma locale would otherwise make the probe
 * disagree with the server about a weight that never changed.
 */
fun formatAmount(value: Double): String {
    val scale = Math.pow(10.0, AMOUNT_DECIMALS.toDouble())
    val rounded = Math.round(value * scale) / scale
    // `-0.0` prints as "-0" through the integral branch, which is a minus sign in front of
    // nothing. It cannot arise from a weight today; normalising costs one comparison.
    if (rounded == 0.0) return "0"
    if (rounded == Math.floor(rounded) && !rounded.isInfinite()) return rounded.toLong().toString()
    return String.format(Locale.ROOT, "%.${AMOUNT_DECIMALS}f", rounded)
        .trimEnd('0')
        .trimEnd('.')
}

/**
 * The three kinds of item section, in the order 10 decision 2 lists them.
 *
 * The wallet is deliberately **not** one of these: it is four fixed coin rows with steppers
 * rather than a list of item rows, it can never be empty, and it is the one section whose
 * contents are not [InventoryItem]s at all. Modelling it as a fourth kind here would put a
 * nullable list on every section so that one of them could ignore it.
 */
enum class InventorySectionKind {
    EQUIPPED,
    CONTAINER,
    CARRIED,
    ;

    /**
     * The section's title when it has no name of its own.
     *
     * [CONTAINER]'s entry is a *fallback*, not the usual case: a container names itself, and
     * this is what renders for a sheet whose container property has a blank name. Having one
     * is what keeps [InventorySectionState.containerName] nullable without a `!!` at the
     * call site.
     */
    @get:StringRes
    val titleRes: Int
        get() = when (this) {
            EQUIPPED -> R.string.inventory_section_equipped
            CONTAINER -> R.string.inventory_section_container
            CARRIED -> R.string.inventory_section_carried
        }
}

/**
 * One row on the inventory list.
 *
 * A near-mirror of [InventoryItem], and worth saying why it is not simply that type. The
 * board's item is *what the sheet says*; this is *what the row prints*, which is the same
 * facts plus five formatting decisions ([showsQuantity], the four amount labels) that the
 * screen would otherwise re-derive per row and no test would ever see. `PipRowState` mirrors
 * `TrackedResource` for exactly the same reason.
 *
 * The detail sheet (10 decision 7) is built from this row rather than from a second lookup,
 * so a row and its expanded form can never disagree about a number.
 */
data class InventoryRowState(
    /** `creatureProperties._id` — what the equip and quantity intents write against. */
    val propertyId: String,
    val name: String,
    val quantity: Int,
    val equipped: Boolean,
    /** Per unit, in pounds, or `null` when the source does not say. See [InventoryItem.weightLb]. */
    val weightLb: Double?,
    /** Per unit, in gold pieces, or `null` when the source does not say. */
    val valueGp: Double?,
    val description: String?,
    val requiresAttunement: Boolean?,
    val attuned: Boolean?,
) {
    /**
     * Whether the row prints a `×n` badge.
     *
     * False at exactly one, because "×1" is on almost every row of almost every sheet and a
     * badge that is always there stops being read — which is precisely when the one row
     * carrying "×20" needs to be noticed. The quantity is always visible in the detail sheet.
     */
    val showsQuantity: Boolean get() = quantity != 1

    /** What the whole stack weighs, or `null` when the source gave no weight at all. */
    val stackWeight: String? get() = weightLb?.let { formatAmount(it * quantity) }

    /** Per unit, for the detail sheet. `null` renders as an em dash, never as "0 lb". */
    val unitWeight: String? get() = weightLb?.let { formatAmount(it) }

    val unitValue: String? get() = valueGp?.let { formatAmount(it) }

    val stackValue: String? get() = valueGp?.let { formatAmount(it * quantity) }

    /**
     * Whether the detail sheet shows an attunement block at all.
     *
     * The row-level twin of [InventoryBoard.hasAttunementData], and the same rule: both
     * fields absent means the sheet never answered the question, and a block reading "Not
     * attuned" would be this app answering it on the sheet's behalf.
     */
    val showsAttunement: Boolean get() = requiresAttunement != null || attuned != null

    /** The quantity stepper's `−`. There is no such thing as taking away the last nothing. */
    val canDecrement: Boolean get() = quantity > 0
}

/**
 * One section: a header and its rows.
 *
 * @param key stable across rebuilds, so a `LazyColumn` keeps its scroll position when a sync
 *   lands. Prefixed by kind because a container's `_id` and the two fixed sections' names
 *   live in the same key space.
 * @param containerName the container's own name, or `null` for the two fixed sections **and**
 *   for a container whose sheet left the name blank — both then fall back to
 *   [InventorySectionKind.titleRes].
 * @param weight the header's weight figure, already formatted. Every section has one
 *   (10 decision 8: "per-section and grand carried totals"), but they are not all the same
 *   sum — see [toInventoryUiState].
 */
data class InventorySectionState(
    val kind: InventorySectionKind,
    val key: String,
    val containerName: String? = null,
    val weight: String = "0",
    val rows: List<InventoryRowState> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * One coin row's rendered state.
 *
 * ### What is deliberately absent
 *
 * [com.hashtagchow.magehand.core.model.WalletRow.isAbsent] — the flag saying the sheet
 * carries no item for this denomination, so the first `+` has to *create* one. It is load
 * bearing in `:core:data` and it is **nothing to the player**: pressing `+` on an empty
 * silver row adds a silver piece, which is what pressing `+` is for. Surfacing it would put
 * an explanation on screen for a difference that only exists on the wire, and the only thing
 * the player could do with the explanation is worry about it.
 */
data class WalletRowState(
    val coin: CoinKind,
    val quantity: Int,
) {
    /** The `−` stepper. A decrement below zero is refused in `:core:data` too. */
    val canDecrement: Boolean get() = quantity > 0
}

/**
 * The wallet: four rows and what they come to.
 *
 * Always four, always in [CoinKind.inWalletOrder], **including on a sheet with no coins at
 * all** — see `WalletRow`. That is the case `InventoryUiStateTest` pins by name, because it
 * is the one a "render the list you were given" implementation gets wrong for free.
 */
data class WalletUiState(
    val rows: List<WalletRowState> = CoinKind.inWalletOrder.map { WalletRowState(it, 0) },
    /** The client-computed total, in gp. Already formatted. */
    val totalGp: String = "0",
)

/**
 * The "Attuned n/3" chip (10 decision 9).
 *
 * Its **existence** is the decision, not its contents: this object is built only when the
 * board reports [InventoryBoard.hasAttunementData], so a `null` chip on the state is the
 * honest rendering of a sheet whose data never mentions attunement. A chip reading "0/3"
 * there would be a confident answer to a question nobody asked.
 *
 * @param slots always [InventoryBoard.ATTUNEMENT_SLOTS]. Carried on the state rather than
 *   read from the companion in the composable so the copy has one source and a house rule
 *   changing the cap changes the chip.
 */
data class AttunementChipState(
    val attuned: Int,
    val slots: Int = InventoryBoard.ATTUNEMENT_SLOTS,
)

data class InventoryUiState(
    val creatureId: String = "",
    /** Always present, always four rows. The first thing on the tab (10 decision 2). */
    val wallet: WalletUiState = WalletUiState(),
    /** Equipped, then one per container, then Carried. See [toInventoryUiState]. */
    val sections: List<InventorySectionState> = emptyList(),
    /** The top line's left half — a client sum over every non-removed item, formatted. */
    val carriedWeight: String = "0",
    /**
     * The top line's right half, or **`null` when the source expresses no Strength**.
     *
     * The nullability is the whole point and is pinned in both directions: absent means the
     * line prints `"142 lb carried"` rather than inventing a denominator, because a capacity
     * bar is exactly the kind of number that gets believed (10 decision 8).
     */
    val capacityWeight: String? = null,
    val isOverCapacity: Boolean = false,
    /** `null` unless the sheet says something about attunement — see [AttunementChipState]. */
    val attunement: AttunementChipState? = null,
    /**
     * Whether a tap may reach the server. Every control on the tab is dimmed and inert when
     * false, matching the tracker's rule rather than restating it.
     */
    val canWrite: Boolean = false,
    /**
     * True while nothing has been discovered *and* nothing is going to be until the socket
     * arrives — the cold-open spinner.
     *
     * Derived rather than passed, and derived the same way `TrackerUiState.isLoading` is, so
     * the two tabs of one screen cannot disagree about whether the character has loaded.
     */
    val isLoading: Boolean = false,
) {
    /**
     * True when the character carries nothing at all — no coins, no items, no containers.
     *
     * The wallet still renders in this state (it always does), so this drives a hint under
     * it rather than an empty screen.
     */
    val isEmpty: Boolean get() = sections.isEmpty() && wallet.rows.all { it.quantity == 0 }

    /**
     * The row with this id, wherever it is, or `null` when nothing on the board has it.
     *
     * The detail sheet is opened by **id** and looks itself up here on every recomposition,
     * rather than being handed the row it was opened with. That is what makes an open sheet
     * *live*: a sync that changes the quantity, or the player's own stepper inside the sheet,
     * re-renders it. And `null` is the honest answer when the item stops existing underneath
     * an open sheet — the screen closes rather than showing a frozen copy of something the
     * sheet no longer has.
     */
    fun row(propertyId: String): InventoryRowState? =
        sections.firstNotNullOfOrNull { section ->
            section.rows.firstOrNull { it.propertyId == propertyId }
        }
}

/**
 * Board → UI, in one pure step.
 *
 * ### The section split
 *
 * Straight from [InventoryBoard], which has already done the hard half: its precedence rule
 * (wallet → equipped → container → carried) guarantees each item appears exactly once, so
 * this function never has to de-duplicate and never has to decide. What it adds is the two
 * *presentation* rules:
 *
 *  1. **Equipped and Carried are absent when empty**, header and all — the same rule the
 *     tracker's defenses and rolls sections use. A header over nothing is a row of chrome
 *     reporting that a thing did not happen.
 *  2. **A container is rendered even when empty.** The deliberate exception, and the reason
 *     is that a container is a thing the player *owns and carries*: an empty backpack still
 *     weighs 5 lb and still counts against capacity, so dropping its header would make the
 *     weight line disagree with a list that no longer explains it. The other two sections
 *     have no such existence — "Equipped" is a state, not an object.
 *
 * ### Two different weights, on purpose
 *
 * A container header prints [InventoryContainer.displayWeightLb], which prefers the
 * **server's** rollup, because a header disagreeing with DiceCloud's own web UI on the same
 * number reads as this app being wrong. The Equipped and Carried headers — and the grand
 * total on the top line — are **client sums**, because a rollup cannot be removed-filtered
 * and 10 decision 3 makes removed-filtering the wave's rule. Both choices are argued in full
 * on the model; this function only obeys them.
 *
 * @param connection used **only** to derive [InventoryUiState.isLoading]. A local character
 *   passes `LIVE` and is therefore never loading, which is true: its board comes off Room.
 * @param isShowingSnapshot true when the board came from the Room cache. A snapshot is real
 *   content, so it ends the loading state exactly as it does on the tracker.
 */
fun toInventoryUiState(
    creatureId: String,
    board: InventoryBoard,
    connection: ConnectionState = ConnectionState.LIVE,
    isShowingSnapshot: Boolean = false,
    canWrite: Boolean = false,
): InventoryUiState {
    val sections = buildList {
        if (board.equipped.isNotEmpty()) {
            add(
                InventorySectionState(
                    kind = InventorySectionKind.EQUIPPED,
                    key = "equipped",
                    weight = formatAmount(board.equipped.sumOf { it.totalWeightLb }),
                    rows = board.equipped.map { it.toRow() },
                ),
            )
        }
        board.containers.forEach { add(it.toSection()) }
        if (board.carried.isNotEmpty()) {
            add(
                InventorySectionState(
                    kind = InventorySectionKind.CARRIED,
                    key = "carried",
                    weight = formatAmount(board.carried.sumOf { it.totalWeightLb }),
                    rows = board.carried.map { it.toRow() },
                ),
            )
        }
    }

    return InventoryUiState(
        creatureId = creatureId,
        wallet = board.wallet.toUiState(),
        sections = sections,
        carriedWeight = formatAmount(board.carriedWeightLb),
        capacityWeight = board.capacityLb?.let { formatAmount(it.toDouble()) },
        isOverCapacity = board.isOverCapacity,
        // 10 decision 9's gate, and the only place it is applied.
        attunement = if (board.hasAttunementData) AttunementChipState(board.attunedCount) else null,
        canWrite = canWrite,
        // Identical to TrackerUiState.isLoading: nothing discovered, nothing cached, and the
        // socket not yet live. A board that is empty because the character genuinely owns
        // nothing is *not* loading, which is why the connection has to be part of the answer.
        isLoading = board.isEmpty && connection != ConnectionState.LIVE && !isShowingSnapshot,
    )
}

private fun Wallet.toUiState(): WalletUiState = WalletUiState(
    // The board's own ordering, which `Wallet` guarantees is CoinKind.inWalletOrder. Not
    // re-sorted here: a second opinion about an order already decided is how two screens
    // start disagreeing.
    rows = rows.map { WalletRowState(coin = it.coin, quantity = it.quantity) },
    totalGp = formatAmount(totalGp),
)

private fun InventoryItem.toRow(): InventoryRowState = InventoryRowState(
    propertyId = propertyId,
    name = name,
    quantity = quantity,
    equipped = equipped,
    weightLb = weightLb,
    valueGp = valueGp,
    description = description?.takeIf { it.isNotBlank() },
    requiresAttunement = requiresAttunement,
    attuned = attuned,
)

private fun InventoryContainer.toSection(): InventorySectionState = InventorySectionState(
    kind = InventorySectionKind.CONTAINER,
    key = "container:$propertyId",
    // Blank falls back to the generic title rather than rendering an empty header. A sheet
    // is allowed to have an unnamed property; a section with no heading is not.
    containerName = name.takeIf { it.isNotBlank() },
    weight = formatAmount(displayWeightLb),
    rows = contents.map { it.toRow() },
)
