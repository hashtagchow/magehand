package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.EquipGroup
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
 * U+2014 EM DASH — what a number the source never gave prints as (11 decision 6, K10).
 *
 * Here rather than in `strings.xml` because it is a **typographic glyph, not copy**: there is
 * no language in which "the sheet did not say" is spelled differently, and a translator handed
 * this string has nothing to do with it but break the column it exists to align. That is the
 * same split the file KDoc above draws between numbers and sentences, applied to the case where
 * the number is absent.
 *
 * It must stay identical to `R.string.inventory_unknown`, which the detail sheet has printed
 * since FR-8 and which is the same glyph. Two spellings of "absent" on one screen — an en dash
 * in the list and an em dash in the sheet — is exactly the kind of drift nobody reports and
 * everybody notices.
 */
const val EM_DASH: String = "—"

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
 * The kinds of item section, in the order they render.
 *
 * ### Why Carried is three kinds and not one
 *
 * 11 decision 3: what was a single CARRIED section is now **Weapons · Armor · Gear**, split by
 * [EquipGroup]. Three kinds rather than one kind with a group field, because each is a section
 * in its own right — its own header, its own weight, its own present-or-absent rule — and a
 * "carried, but which flavour" field would have made every reader of a section ask a second
 * question to know what it was looking at.
 *
 * There is deliberately no `CARRIED` value left behind. A sheet whose carried items are all
 * gear now reads GEAR rather than CARRIED, and that is the honest label: the word "carried"
 * still names the *whole* — it is the top line's "142 lb carried", and the grand total is
 * still the sum of all three — while a section heading names its contents.
 *
 * The wallet is deliberately **not** one of these: it is four fixed coin rows with steppers
 * rather than a list of item rows, it can never be empty, and it is the one section whose
 * contents are not [InventoryItem]s at all. Modelling it as another kind here would put a
 * nullable list on every section so that one of them could ignore it.
 */
enum class InventorySectionKind {
    EQUIPPED,
    CONTAINER,
    WEAPONS,
    ARMOR,
    GEAR,
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
            WEAPONS -> R.string.inventory_section_weapons
            ARMOR -> R.string.inventory_section_armor
            GEAR -> R.string.inventory_section_gear
        }

    companion object {
        /** The section an unequipped, uncontained item belongs in (11 decision 3). */
        fun of(group: EquipGroup): InventorySectionKind = when (group) {
            EquipGroup.WEAPON -> WEAPONS
            EquipGroup.ARMOR -> ARMOR
            EquipGroup.GEAR -> GEAR
        }
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
    /**
     * What the **board** says about equippability (11 decision 1) — before any override.
     *
     * Kept separate from [equippableOverridden] rather than pre-combined into one boolean,
     * because the two answer different questions and the detail sheet needs both: the effective
     * answer decides whether the *equip* control renders, and this one decides whether the
     * *override* toggle renders at all. Collapsing them would make an item the sheet already
     * classified indistinguishable from one the player rescued by hand, and the toggle would
     * then have to appear on every row in the app to be reachable on the few that need it.
     */
    val isEquippable: Boolean = true,
    /** Whether this character's [EquippableOverrideStore] carries this item. 11 decision 2. */
    val equippableOverridden: Boolean = false,
) {
    /**
     * Whether the row prints a `×n` badge.
     *
     * False at exactly one, because "×1" is on almost every row of almost every sheet and a
     * badge that is always there stops being read — which is precisely when the one row
     * carrying "×20" needs to be noticed. The quantity is always visible in the detail sheet.
     */
    val showsQuantity: Boolean get() = quantity != 1

    /**
     * Whether the equip control renders on this row at all (11 decision 3).
     *
     * The whole of decision 3's *"the equip control renders ONLY on equippable items — a
     * tinderbox shows none"*, plus decision 2's override. Both disjuncts are needed: the board's
     * answer covers the SRD taxonomy and everything currently equipped, the override covers the
     * documented residual (a hand-made item the player has taken off).
     */
    val showsEquipControl: Boolean get() = isEquippable || equippableOverridden

    /**
     * Whether the detail sheet offers the "Can be equipped" toggle (11 decision 2).
     *
     * **Only on items the board did not already classify.** An item the sheet's own tags name
     * as a weapon has nothing to gain from the switch, and putting one on every row would turn
     * a rescue affordance for a handful of hand-made items into a piece of chrome on the
     * majority of a character's inventory — which is how a control stops being read.
     *
     * Note that this is [isEquippable] and *not* [showsEquipControl]: turning the override on
     * gains the row its equip control and the switch **stays**, because it is the only way
     * back. A rule keyed on the effective answer would make the switch vanish the instant it
     * was used.
     *
     * ### What happens once an overridden item is actually equipped
     *
     * The switch hides — and that is correct rather than a hole. `isEquippable` carries the
     * board's answer, and 11 decision 1's first disjunct makes an **equipped** item equippable
     * outright, so the board now classifies it without help. Offering to correct a
     * classification that is no longer in doubt would be chrome.
     *
     * The override is not lost, only unneeded: it stays in the store (nothing here writes to
     * it), so unequipping the item returns `isEquippable` to `false`, the switch reappears
     * already on, and the equip control never went anywhere. That round trip is the whole
     * behaviour, and `InventoryUiStateTest` pins each leg of it.
     */
    val showsEquippableToggle: Boolean get() = !isEquippable

    /** What the whole stack weighs, or `null` when the source gave no weight at all. */
    val stackWeight: String? get() = weightLb?.let { formatAmount(it * quantity) }

    /**
     * The weight column's text **without its unit**: the stack weight, or [EM_DASH] when the
     * source gave no weight at all (11 decision 6, K10).
     *
     * The unit is not here because "lb" is copy and lives in `strings.xml`, and because an em
     * dash takes no unit — "— lb" would read as a measurement whose number went missing rather
     * than as a measurement that was never taken. [hasWeight] is what the composable branches
     * on to decide whether to wrap this in the unit format.
     *
     * The column is now always printed, which is the point of K10 rather than a side effect:
     * before this, a weightless-unknown row simply dropped the cell, so a list of items lost
     * its right-hand alignment wherever a sheet had been sloppy — and a *blank* said "0 lb" to
     * every reader who did not stop to think about it. Absent data, stated as absent.
     */
    val stackWeightLabel: String get() = stackWeight ?: EM_DASH

    /** True when [stackWeightLabel] is a number and therefore takes the "lb" unit. */
    val hasWeight: Boolean get() = weightLb != null

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
) {
    /**
     * The collapsed wallet's one-line summary — *"2 pp · 15 gp · 3 sp"* (FR-11, 11 decision 4).
     *
     * **Nonzero denominations only**, in [CoinKind.inWalletOrder]. That is the difference
     * between a summary and a smaller copy of the four rows: a purse holding gold and nothing
     * else should say "15 gp", not "0 pp · 15 gp · 0 sp · 0 cp", which is four facts to read to
     * learn one. The four zeroes are still there the instant the section is expanded, and the
     * expanded rows are unchanged — an absent denomination is a stepper the player can still
     * press, which is what creates the coin item on a sheet that lacks one.
     *
     * `null` when every row reads zero. The composable renders `R.string.inventory_wallet_empty`
     * there rather than an empty line, because a blank summary beside a collapsed header says
     * "this app has not loaded your money yet" and "Empty" says the true thing.
     *
     * A bare string of numbers and [CoinKind.abbreviation]s, matching this file's rule that
     * numbers are formatted here and sentences are not. The abbreviations are already the app's
     * own vocabulary — the expanded rows label themselves with the same four — so nothing here
     * is a second spelling of anything.
     */
    val summary: String?
        get() = rows.filter { it.quantity > 0 }
            .joinToString(SUMMARY_SEPARATOR) { "${it.quantity} ${it.coin.abbreviation}" }
            .takeIf { it.isNotEmpty() }

    /**
     * What TalkBack says when it lands on the collapsed wallet's header.
     *
     * ### Why this exists rather than three adjacent `Text`s
     *
     * The header is one **clickable** row, and a clickable merges its descendants into a single
     * accessibility node. Putting the action alone on that node — "Show the coin steppers" —
     * replaces the title and the summary rather than adding to them, so a screen-reader user
     * would be told the control exists and never told *how much money they have*. That reading
     * is the entire thing FR-11 promises in exchange for collapsing the steppers, and it would
     * have been available to sighted users only.
     *
     * So the three facts are folded into one sentence, which is `DefenseRows`' house pattern in
     * `TrackerScreen` ("Resistant: Fire, Poison" as a fact, rather than a stray word followed
     * by a list) applied to a control instead of a read-out.
     *
     * ### Why it is built here and not in the composable
     *
     * Because a test can call it. Every part that is *copy* arrives as a parameter — the title,
     * the all-zero word and the action are `strings.xml`'s, and this function never names a
     * resource — while the part that is a **rule** (nonzero denominations, and "Empty" standing
     * in for an absent summary) is here where `InventoryUiStateTest` can pin it. A future edit
     * that drops the summary back out of the sentence fails a test rather than going unnoticed
     * until someone runs the app with TalkBack on.
     *
     * @param title the section's own name, already localized.
     * @param emptyLabel what stands in for [summary] when every row reads zero.
     * @param action "show"/"hide the coin steppers" — the part that says it can be tapped,
     *   which a title and a coin count do not say between them.
     */
    fun spokenLabel(title: String, emptyLabel: String, action: String): String =
        listOf(title, summary ?: emptyLabel, action).joinToString(SPOKEN_SEPARATOR)

    private companion object {
        /** A middle dot with hair spaces around it, as the item rows' meta line already uses. */
        const val SUMMARY_SEPARATOR = " · "

        /**
         * A comma, because this one is *read aloud*.
         *
         * The middle dot above is a visual separator a screen reader would either skip or
         * announce as "middle dot"; a comma is the pause a sentence needs. The two are
         * deliberately different characters for that reason and must not be unified.
         */
        const val SPOKEN_SEPARATOR = ", "
    }
}

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
 *  1. **Every section is absent when empty**, header and all — the same rule the tracker's
 *     defenses and rolls sections use. A header over nothing is a row of chrome reporting that
 *     a thing did not happen.
 *  2. **Carried is three sections, not one** (11 decision 3): Weapons, Armor and Gear, split by
 *     [InventoryItem.equipGroup] and each present only if it has rows. A character carrying
 *     nothing but gear therefore sees one GEAR header, not a CARRIED header with a GEAR header
 *     nested under it.
 *
 * ### K9: a container with nothing displayable in it does not render
 *
 * 11 decision 5, and it **reverses** what this function did in FR-8, so the old argument is
 * worth stating before the new one. The rule used to be "a container renders even when empty",
 * on the grounds that an empty backpack still weighs 5 lb and still counts against capacity, so
 * dropping its header would leave the weight line unexplained.
 *
 * The case that broke it is the coins-only purse, which the live capture has: every item in it
 * is a coin, coins render in the Wallet by [InventoryBoard]'s precedence, so the section renders
 * a header, a weight, and **no rows at all**. The player is shown a container that appears to
 * hold nothing, directly above a Wallet holding what is actually in it.
 *
 * The old argument's premise is also simply false here, and that is what settles it: the purse's
 * own 5 lb is **not** lost when the header goes. [InventoryBoard.carriedWeightLb] is a client
 * sum over items *plus every container's own empty weight* — it never consulted the section
 * list, so folding a section away cannot change the total. `InventoryEngineTest` asserts that
 * shell weight explicitly, precisely so that a future reader who notices the folded-away purse
 * and "fixes" the total by adding it back in gets a failing test instead of a double count.
 *
 * The rule is therefore *displayable rows*, not *emptiness*: `contents` is already
 * coin-filtered and removed-filtered by the engine, so an empty `contents` means there is
 * genuinely nothing for the section to show.
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
 * @param equippableOverrides the ids this character's `EquippableOverrideStore` carries
 *   (11 decision 2). Passed in rather than read here because this function is pure and must
 *   stay so — the store is a flow, and the view model is where flows are combined.
 */
fun toInventoryUiState(
    creatureId: String,
    board: InventoryBoard,
    connection: ConnectionState = ConnectionState.LIVE,
    isShowingSnapshot: Boolean = false,
    canWrite: Boolean = false,
    equippableOverrides: Set<String> = emptySet(),
): InventoryUiState {
    val sections = buildList {
        if (board.equipped.isNotEmpty()) {
            add(
                InventorySectionState(
                    kind = InventorySectionKind.EQUIPPED,
                    key = "equipped",
                    weight = formatAmount(board.equipped.sumOf { it.totalWeightLb }),
                    rows = board.equipped.map { it.toRow(equippableOverrides) },
                ),
            )
        }
        // K9 — see the KDoc above. `contents` is already coin- and removed-filtered, so an
        // empty one means there is nothing displayable, and the shell's weight is counted by
        // the board's client sum whether or not this section exists.
        board.containers
            .filter { it.contents.isNotEmpty() }
            .forEach { add(it.toSection(equippableOverrides)) }
        // 11 decision 3. Grouped rather than filtered three times so the partition is provably
        // total: every carried item lands in exactly one of the three, because `EquipGroup` has
        // exactly three values and `of` is exhaustive over them.
        val carried = board.carried.groupBy { InventorySectionKind.of(it.equipGroup) }
        CARRIED_SECTION_ORDER.forEach { kind ->
            val items = carried[kind].orEmpty()
            if (items.isEmpty()) return@forEach
            add(
                InventorySectionState(
                    kind = kind,
                    key = kind.name.lowercase(Locale.ROOT),
                    weight = formatAmount(items.sumOf { it.totalWeightLb }),
                    rows = items.map { it.toRow(equippableOverrides) },
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

/**
 * The order Carried's three subsections render in (11 decision 3).
 *
 * Named rather than left to `EquipGroup`'s declaration order, because this is a *presentation*
 * decision — what a player scans for first at the table — and the model's enum order is free to
 * change for its own reasons without silently reordering this screen.
 */
private val CARRIED_SECTION_ORDER = listOf(
    InventorySectionKind.WEAPONS,
    InventorySectionKind.ARMOR,
    InventorySectionKind.GEAR,
)

private fun InventoryItem.toRow(equippableOverrides: Set<String>): InventoryRowState =
    InventoryRowState(
        propertyId = propertyId,
        name = name,
        quantity = quantity,
        equipped = equipped,
        weightLb = weightLb,
        valueGp = valueGp,
        description = description?.takeIf { it.isNotBlank() },
        requiresAttunement = requiresAttunement,
        attuned = attuned,
        isEquippable = isEquippable,
        equippableOverridden = propertyId in equippableOverrides,
    )

private fun InventoryContainer.toSection(equippableOverrides: Set<String>): InventorySectionState =
    InventorySectionState(
        kind = InventorySectionKind.CONTAINER,
        key = "container:$propertyId",
        // Blank falls back to the generic title rather than rendering an empty header. A sheet
        // is allowed to have an unnamed property; a section with no heading is not.
        containerName = name.takeIf { it.isNotBlank() },
        weight = formatAmount(displayWeightLb),
        rows = contents.map { it.toRow(equippableOverrides) },
    )
