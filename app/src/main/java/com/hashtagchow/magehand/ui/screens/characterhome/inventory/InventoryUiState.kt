package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.ui.components.DirectEntryKeys
import com.hashtagchow.magehand.ui.components.DirectEntryKind
import com.hashtagchow.magehand.ui.components.DirectEntryTarget
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
 * What separates the facts inside a **spoken** sentence on this tab.
 *
 * A comma, because these strings are read aloud: the middle dot the wallet summary and the item
 * meta line use is a *visual* separator that a screen reader either skips or announces as "middle
 * dot", while a comma is the pause a sentence needs. The two are deliberately different
 * characters and must not be unified.
 *
 * File-level rather than duplicated into each builder, because there are two of them now
 * ([WalletUiState.spokenLabel] and [InventoryRowState.spokenEquipLabel]) and a tab that paused
 * differently in two places would be the sort of drift nobody reports and every screen-reader
 * user hears.
 */
private const val SPOKEN_SEPARATOR = ", "

/**
 * What separates the facts inside a **visible** one-line summary on this tab.
 *
 * A middle dot with hair spaces, as the item rows' meta line already uses. Deliberately a
 * different character from [SPOKEN_SEPARATOR] and deliberately not unified with it: this one is
 * seen, that one is heard, and a screen reader either skips a middle dot or announces it as
 * "middle dot".
 *
 * File-level since FR-16, because there are two builders of it now — the wallet's coin line and
 * [InventorySectionState.summary] — and a tab that punctuated its two summaries differently would
 * be the sort of drift nobody reports and everybody sees.
 */
private const val SUMMARY_SEPARATOR = " · "

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
    /**
     * The container this item sits directly inside, or `null` for the carried root.
     *
     * Carried on the row purely so the move picker can leave out the destination the item is
     * **already in** (12 decision 8) — see [InventoryUiState.moveTargetsFor]. The tab itself
     * still never renders the tree; this is the one fact about the folder structure that a
     * feature which moves things between folders cannot do without.
     */
    val containerId: String? = null,
    /**
     * Whether this item is a coin — `platinum`/`gold`/`silver`/`copper` on its own or its
     * library's tags (12 decision 7).
     *
     * ### Why a row can say this at all, given coins never reach a row
     *
     * `InventoryEngine` routes every coin-tagged item into the wallet before any item section
     * is built (`InventoryBoard`'s precedence), so on a real server board this is `false` on
     * every row that exists. That makes it look redundant, and it is deliberately kept:
     *
     * - **The rule belongs to the item, not to its placement.** "Wallet rows are
     *   stepper-managed, so they offer no delete" is a statement about what a coin *is*. A
     *   delete control that was absent only because a *different* class had filtered the row
     *   out first is one refactor away from appearing, and the refactor would touch neither
     *   this file nor its tests.
     * - **The wallet is the one thing in this feature with no remedy.** A deleted torch is one
     *   UNDO away; a player who deletes their gold stack and dismisses the snackbar has lost a
     *   character's money. Decision 7 fences it, and a fence that is only enforced upstream is
     *   a fence in one place.
     *
     * `DefaultOpenCharacter.removeItem` gates the same rule again at the write, from the other
     * direction — a coin id resolves to nothing in `allItems`. Two gates, stated in both.
     */
    val isCoin: Boolean = false,
    /**
     * Whether this row belongs to a **local** character (12 decisions 7 and 8).
     *
     * A character-level fact repeated per row, and that is the deliberate choice: it is what
     * lets [showsMoveControl] and [deleteIsUndoable] be plain properties of the row, callable
     * from a unit test with no board, no view model and no Compose runtime — which is this
     * file's whole posture (see [showsEquipControl], [equipChipLabelRes]). Passing the flag
     * into the composable instead would split each rule across the state and the screen, and
     * the half in the screen is the half no test sees.
     */
    val isLocal: Boolean = false,
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

    /**
     * Whether the detail sheet offers **Delete** (12 decision 7).
     *
     * Everything except a coin, on both kinds of character. Equipped items included, and
     * deliberately: taking an item off before you may destroy it would be a step the player has
     * to discover, and unlike a *move* there is no second writer of anything to conflict with —
     * a removed property is removed whichever folder it was in. That asymmetry with
     * [showsMoveControl] is decision 8's, not an inconsistency: `equip` owns an equipped item's
     * **location**, and nothing owns its existence.
     *
     * See [isCoin] for why the coin case is a rule about the item rather than a filter the
     * board already applied, and [deleteIsUndoable] for what the confirm then has to say.
     */
    val showsDeleteControl: Boolean get() = !isCoin

    /**
     * Whether deleting this item can be taken back (12 decision 7).
     *
     * **True on a server character, false on a local one**, and this is the fact the confirm
     * dialog's copy turns on. DiceCloud offers no hard delete — `softRemove` sets a flag and
     * `restore` clears it — so the undo is a real inverse. A local row is a Room row and
     * deleting it deletes it; see `LocalOpenCharacter.removeItem` for why buying an undo there
     * would mean a tombstone table.
     *
     * Stated *before* the tap, in the dialog, rather than discovered afterwards from a
     * snackbar with no UNDO button on it.
     */
    val deleteIsUndoable: Boolean get() = !isLocal

    /**
     * The line the delete confirm prints under the item's name.
     *
     * A `@StringRes` and not a boolean-plus-`if` in the composable, per this file's split: the
     * *rule* (which sentence, and therefore whether the player is warned about permanence) is
     * asserted in a unit test, and the words are `strings.xml`'s.
     */
    @get:StringRes
    val deleteWarningRes: Int
        get() = if (deleteIsUndoable) {
            R.string.inventory_delete_undoable
        } else {
            R.string.inventory_delete_permanent
        }

    /**
     * Whether the detail sheet offers **"Move to…"** (12 decision 8).
     *
     * Three exclusions, each with its own reason and none of them cosmetic:
     *
     * - **Equipped items.** `creatureProperties.equip` reparents the property on the server's
     *   own schedule, so an equipped item that had also been hand-placed would have two writers
     *   of one field and the next equip tap would silently undo the player's move. Decision 8's
     *   words: *"two conflicting owners of its location"*.
     * - **Coins.** [isCoin]'s argument, and the wallet has no location to speak of anyway.
     * - **Local characters.** They have no containers — items are Room rows with a sort index
     *   and no tree — so there is nowhere to move to. Absent rather than disabled: a
     *   destination picker with no destinations is not a control.
     *
     * The same three are enforced again at the write (`DefaultOpenCharacter.moveItem` drops an
     * equipped item and cannot resolve a coin; `LocalOpenCharacter.moveItem` is a no-op).
     */
    val showsMoveControl: Boolean get() = !equipped && !isCoin && !isLocal

    /**
     * The equip chip's **label**: "Equip" on an unequipped item, "Equipped" on an equipped one
     * (docs/design/12-inventory-layout.md decision 2, FR-13's addendum).
     *
     * ### Why the chip stopped saying one word in two situations
     *
     * It read "Equipped" in both states and leaned entirely on `FilterChip`'s selected styling to
     * say which was which — a tint difference, on a chip whose word does not change. That is
     * legible when the two states are side by side and ambiguous when they are not, which is
     * every real sheet: a player scrolling Weapons sees a column of chips all reading "Equipped"
     * and has to decode a colour to learn that none of them are.
     *
     * The split is verb-versus-state, which is the ordinary vocabulary for a control that both
     * *does* a thing and *reports* a thing: the unequipped chip offers an **action** ("Equip" —
     * press this and it will be), the equipped chip states the **fact** ("Equipped" — it is).
     * That reading is the same one the spoken sentence makes ([spokenEquipLabel]), so the two
     * cannot drift into describing the chip differently.
     *
     * The rule is here rather than in the composable because a test can call it; the two words
     * are `strings.xml`'s, per this file's number/sentence split.
     */
    @get:StringRes
    val equipChipLabelRes: Int
        get() = if (equipped) R.string.inventory_chip_equipped else R.string.inventory_chip_equip

    /** The verb half of [spokenEquipLabel] — "tap to equip" / "tap to unequip". */
    @get:StringRes
    val equipActionRes: Int
        get() = if (equipped) R.string.inventory_unequip_action else R.string.inventory_equip_action

    /**
     * What TalkBack says when it lands on this row's equip chip — *"Longsword, tap to equip"*, or
     * *"Longsword, Equipped, tap to unequip"* (12 decision 2).
     *
     * ### Why a built sentence rather than one format string per state
     *
     * [WalletUiState.spokenLabel]'s argument, applied to a control instead of a read-out. The
     * chip is a single accessibility node carrying three facts — which item, whether it is on,
     * and that it can be tapped — and a screen-reader user gets exactly what this string says and
     * nothing else. The old descriptions ("Equip %1$s" / "Unequip %1$s") named the item and the
     * action and never said *whether the item was currently equipped*: `FilterChip` does announce
     * its selected state, but it announces it about a `contentDescription` that had already
     * replaced the label, so the one fact a player most wants — am I holding this? — was carried
     * only by the chip's tint.
     *
     * The **state fragment is present only when equipped**, and that asymmetry is the decision
     * rather than an oversight: "Longsword, not equipped, tap to equip" spends a whole clause
     * restating what the verb already implies, on the majority of rows on every sheet. That rule
     * is what this function exists for and what the test pins; the words arrive as parameters,
     * so nothing here names a resource.
     *
     * @param equippedLabel the state fragment, dropped entirely when the item is not equipped.
     *   The chip's own [equipChipLabelRes] word, deliberately: one concept, one string, so a
     *   translator cannot end up with a chip and a sentence that disagree about what "equipped"
     *   is called.
     * @param action "tap to equip" / "tap to unequip" — [equipActionRes] resolved.
     */
    fun spokenEquipLabel(equippedLabel: String, action: String): String =
        listOfNotNull(name, equippedLabel.takeIf { equipped }, action)
            .joinToString(SPOKEN_SEPARATOR)

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
    /**
     * Whether the player has shut this section (13 decisions 1–3).
     *
     * On the **section** rather than on [InventoryBlock.Items], because every consumer that asks
     * whether a section is collapsed already has the section in its hand, and a flag one level
     * out would make `block.collapsed` and `block.section` two things a call site has to keep
     * together. `false` for a section built for the customize sheet, which does not draw rows and
     * is deliberately untouched by FR-16 (decision 6) — see [toInventoryUiState].
     */
    val collapsed: Boolean = false,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * How many **rows** the section holds — the "3 items" in *"Armor · 3 items · 41 lb"*
     * (13 decision 5).
     *
     * Rows and not summed quantities, and the difference is the whole reason this is a property
     * with a KDoc rather than a `.size` at the call site: a quiver of twenty arrows is *one*
     * item on this list. Summing [InventoryRowState.quantity] would print "22 items" over a
     * section a player can expand and count three of, and the number they can check is the one
     * that has to be right. The weight beside it is already the summed figure, so nothing is
     * lost — between them the header says how much there is to read and how much it costs to
     * carry, which are the two questions a collapsed section has to answer.
     */
    val itemCount: Int get() = rows.size

    /**
     * The header's one-line summary — *"3 items · 41 lb"* (13 decision 5).
     *
     * ### Why the copy arrives as parameters
     *
     * [WalletUiState.spokenLabel]'s rule, applied to a visible string: "items" is a plural that a
     * translator owns and "lb" is a unit that lives in `strings.xml`, while the *separator* and
     * the *order* are this tab's own punctuation and belong beside the wallet's, which uses the
     * same middle dot. So the pieces are resolved by the composable and joined here, which is
     * also what lets a test assert the shape without a Compose runtime.
     *
     * ### Why it is printed while the section is open, too
     *
     * A judgment call, recorded because decision 5 states the requirement only for the collapsed
     * case ("a collapsed section still informs"). Two things settle it towards *always*: the
     * wallet — the control this generalizes — has shown its summary in both states since FR-11,
     * and a header whose content changes on expand makes the row it lives in re-measure and jump
     * under the player's finger at the exact moment they tapped it. The count is redundant with
     * the rows below it when open, which is the same harmless redundancy the equip chip's
     * checked state already carries (see [InventoryRowState]).
     */
    fun summary(countLabel: String, weightLabel: String): String =
        listOf(countLabel, weightLabel).joinToString(SUMMARY_SEPARATOR)

    /**
     * What TalkBack says when it lands on this section's header — *"Armor, 3 items, 41 lb,
     * collapsed, tap to expand"* (13 decisions 1 and 5).
     *
     * ### Why a built sentence, again
     *
     * [WalletUiState.spokenLabel]'s argument, and it applies harder here. The header is a
     * **clickable**, so it merges its descendants into one accessibility node: a
     * `contentDescription` naming only the action would *replace* the title, the count and the
     * weight rather than adding to them, and a screen-reader user would be told a control exists
     * over a section whose name they were never given. Every fact a sighted user reads off the
     * row has to be in this string or it is not on the screen at all for them.
     *
     * ### The rule this holds, which is what the test pins
     *
     * **Five facts, in this order, none of them dropped** — what it is, how much is in it, what
     * it weighs, whether it is open, and that it can be tapped. The last two are separate on
     * purpose and neither is inferable from the other: the state word is the *fact* ("collapsed")
     * and the action is the *offer* ("tap to expand"), which is the same verb-versus-state split
     * [InventoryRowState.equipChipLabelRes] draws for the equip chip. A sentence carrying only
     * the action leaves a user who tabs onto the header unable to tell whether the rows below it
     * are hidden or simply absent.
     *
     * Nothing here names a resource; every fragment is copy resolved by the composable. A future
     * edit that quietly drops the weight or the state word fails `InventoryUiStateTest` rather
     * than going unnoticed until someone runs the app with TalkBack on.
     *
     * @param title the section's own name — a container's, or [InventorySectionKind.titleRes]
     *   resolved. As written rather than uppercased, because a screen reader may spell an
     *   all-caps word out letter by letter (see the composable).
     * @param countLabel "3 items" — a plural, which is why it is not built from [itemCount] here.
     * @param weightLabel "41 lb" — the unit is copy.
     * @param stateLabel "collapsed" / "expanded".
     * @param action "tap to expand" / "tap to collapse".
     */
    fun spokenLabel(
        title: String,
        countLabel: String,
        weightLabel: String,
        stateLabel: String,
        action: String,
    ): String =
        listOf(title, countLabel, weightLabel, stateLabel, action).joinToString(SPOKEN_SEPARATOR)
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

/**
 * One destination in the "Move to…" picker (docs/design/12-inventory-layout.md decision 8).
 *
 * @param containerId the container's `creatureProperties._id`, or **`null` for the carried
 *   root**. `null` rather than a sentinel id for the reason `InventoryMoveTarget` gives: the
 *   carried root is resolved from the sheet's tags at write time and has no id this screen is
 *   entitled to remember.
 * @param name the container's name, or `null` for the carried root — whose label is a fixed
 *   string (`inventory_section_gear`'s sibling `inventory_move_carried`) rather than a name off
 *   the sheet, because the folder it resolves to is an implementation detail the player has
 *   never been shown.
 */
data class InventoryMoveTargetState(
    val containerId: String?,
    val name: String? = null,
)

data class InventoryUiState(
    val creatureId: String = "",
    /**
     * Always four rows — but no longer always *drawn*, and no longer always first.
     *
     * The rows themselves are unconditional (10 decision 5: a sheet carrying no coins still shows
     * four zeroes, because "you have no silver" and "this app could not find your silver" look
     * identical when the row is missing). Whether the wallet **block** is on the tab, and where,
     * is now the player's (12 decisions 1 and 3) and is expressed by [blocks].
     */
    val wallet: WalletUiState = WalletUiState(),
    /**
     * Everything the tab draws, in the order it draws it — the wallet block and the item
     * sections, interleaved however this character's stored layout asks (12 decisions 1 and 3).
     *
     * One list rather than "the wallet, then the sections" because the wallet is orderable and
     * hideable now, so the order is a property of the whole tab rather than of the sections
     * alone. See [InventoryBlock] for why the union is two cases and not one.
     */
    val blocks: List<InventoryBlock> = emptyList(),
    /**
     * The customize sheet's state (12 decision 3), built in the same pass as [blocks].
     *
     * On the state rather than in its own flow, unlike the tracker's — see
     * [InventoryCustomizeState] for why one board can answer both questions here and cannot
     * there.
     */
    val customize: InventoryCustomizeState = InventoryCustomizeState(),
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
     * Where "Move to…" can put an item: the carried root first, then **every** container on the
     * sheet (12 decision 8). Empty on a local character, which has no containers.
     *
     * ### Every container, not the ones the tab drew
     *
     * [blocks] deliberately omits a container with no displayable contents (K9 — an empty
     * section is a header over nothing), and this list deliberately does not. An **empty pouch
     * is the single most useful thing to move something into**, and building the picker from
     * the rendered sections would have made exactly the containers a player is trying to fill
     * the ones they cannot choose. So this is built from the board's own `containers`, before
     * the emptiness filter.
     *
     * Hidden containers are in here too, for the same reason and one more: 12 decision 3's
     * invariant is that hiding changes *grouping*, never item reachability, and a destination
     * that vanished from the picker because its section was folded into Gear would break that
     * in the one direction the invariant did not anticipate.
     */
    val moveTargets: List<InventoryMoveTargetState> = emptyList(),
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
     * The item sections being drawn, in order — [blocks] without the wallet.
     *
     * Derived rather than carried, so the two can never disagree about what is on screen. Every
     * existing reader of this tab's sections (the empty check, the row lookup, the emulator
     * probe's section tags) is asking about *item* sections and is unaffected by the wallet
     * having joined the order.
     */
    val sections: List<InventorySectionState>
        get() = blocks.filterIsInstance<InventoryBlock.Items>().map { it.section }

    /**
     * True when the character carries nothing at all — no coins, no items, no containers.
     *
     * The wallet still renders in this state (it always does, unless the player folded it away),
     * so this drives a hint under it rather than an empty screen.
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

    /**
     * The destinations offered for **this** row — [moveTargets] without the one the item is
     * already in (12 decision 8).
     *
     * A picker that lists where you already are is a control with a no-op in it: tapping it
     * would be answered by `DefaultOpenCharacter.moveItem`'s same-parent guard, so the player
     * would choose a destination and nothing at all would happen — the interaction that looks
     * most like a bug. The filter is here rather than in the composable so a test can call it
     * without a Compose runtime, which is this file's rule for every branch that decides
     * whether a control exists.
     *
     * Note this compares [InventoryRowState.containerId] to
     * [InventoryMoveTargetState.containerId] and therefore treats `null == null` as a match:
     * an item already loose in the carried root is not offered "Carried". That is the same
     * equality the write-side guard makes, one frame earlier.
     */
    fun moveTargetsFor(row: InventoryRowState): List<InventoryMoveTargetState> =
        moveTargets.filterNot { it.containerId == row.containerId }

    /**
     * What an open FR-22 direct-entry dialog is editing, resolved from a [DirectEntryKeys] key
     * (15 decisions 5–7).
     *
     * The inventory half of `TrackerUiState.directEntryTarget`, and the same argument for why it
     * takes a key rather than a row — see there. Two kinds reach this tab:
     *
     * - **an item**, looked up through [row], so a dialog open over an item that a sync removes
     *   resolves to `null` and closes rather than editing a ghost;
     * - **a coin**, which is looked up in the wallet by denomination because a wallet row may
     *   have no property at all yet. Its `propertyId` therefore carries the [CoinKind] name —
     *   the only stable identity an absent row has — matching `InventoryActions.onCoinSet`.
     *
     * Neither carries a ceiling (decision 7: *"coins floor 0, no ceiling; quantities floor 0, no
     * ceiling"*), which is the whole difference from the tracker's pip rows.
     */
    fun directEntryTarget(key: String): DirectEntryTarget? = when {
        key.startsWith(DirectEntryKeys.ITEM_PREFIX) ->
            row(key.removePrefix(DirectEntryKeys.ITEM_PREFIX))?.let {
                DirectEntryTarget(
                    kind = DirectEntryKind.ITEM,
                    propertyId = it.propertyId,
                    label = it.name,
                    current = it.quantity,
                    max = null,
                )
            }

        key.startsWith(DirectEntryKeys.COIN_PREFIX) -> {
            val name = key.removePrefix(DirectEntryKeys.COIN_PREFIX)
            wallet.rows.firstOrNull { it.coin.name == name }?.let {
                DirectEntryTarget(
                    kind = DirectEntryKind.COIN,
                    propertyId = it.coin.name,
                    // Already the app's own vocabulary — the expanded rows label themselves with
                    // the same four abbreviations, and so does the collapsed summary.
                    label = it.coin.abbreviation,
                    current = it.quantity,
                    max = null,
                )
            }
        }

        else -> null
    }

    /**
     * Every item row on the tab, counted once (FR-24 decision 14's threshold input).
     *
     * Reading [sections] rather than the board is what makes "counted once" true without a
     * `distinct`: hiding a section moves its rows into Gear and drops the section, so the
     * rendered sections are a partition of the character's items — the invariant
     * [toInventoryUiState] calls *"fold, never vanish"*. Coins are excluded by construction and
     * not by a filter, because `InventoryBoard`'s precedence puts every coin-tagged item in the
     * wallet before any section is built; decision 14 asks for the *non-coin* count and that is
     * what this already is.
     */
    val itemRowCount: Int get() = sections.sumOf { it.itemCount }

    /**
     * Whether the search field renders at all (FR-24 decision 14: total non-coin item count
     * ≥ 15).
     *
     * ### Why a threshold rather than always
     *
     * A field over a nine-item list is chrome above content the player can already see whole,
     * on the tab where 10 decision 7 and 11 decision 4 have both already spent an argument on
     * keeping the top of the screen for gear. Fifteen is where a phone's first screenful stops
     * being the whole inventory. Below it, scrolling *is* the search.
     *
     * Computed from the **unfiltered** state, always: a filter that narrows the list to two
     * matches must not take its own field away.
     */
    val showsFilterField: Boolean get() = itemRowCount >= FILTER_THRESHOLD

    /**
     * This state narrowed to the rows whose name contains [query] (FR-24 decisions 15 and 16).
     *
     * ### A glance, never a preference — which is why this is a pure function
     *
     * Decision 15's binding half is *"NOTHING writes to the FR-16 layout store. Clearing the
     * filter restores the stored layout exactly."* That is a property of **where the filter
     * lives**, not of care taken at each call site: the query is a `rememberSaveable` in the
     * composable and filtering is this transform over an already-built state, so there is no
     * path from typing to `InventoryLayoutStore` at all. Clearing restores exactly because the
     * unfiltered state is the one the view model has been holding the whole time — nothing was
     * ever rewritten to restore.
     *
     * ### What "across ALL sections including collapsed and hidden-folded ones" costs
     *
     * Less than it sounds, because [toInventoryUiState] has already done both halves:
     *
     * - **hidden-folded** rows are *already* in Gear's list (the `foldedIntoGear` block), so
     *   "fold-hidden matches surface under their fold target with their real rows" is satisfied
     *   by not undoing it. There is no second lookup here and no un-hiding.
     * - **collapsed** sections still carry their rows — collapse only decides whether the
     *   composable draws them — so a match inside one is found by the same `filter` as any
     *   other, and `collapsed = false` on the result is what decision 15's *"matching sections
     *   render expanded"* asks for.
     *
     * A section with no matches is dropped entirely, header included, which is
     * [toInventoryUiState]'s own standing rule for an empty section rather than a new one.
     *
     * ### The wallet is exempt (decision 15)
     *
     * Its block passes through untouched. Coins are not items, they carry no names to match, and
     * a purse that vanished while a player searched for "rope" would read as money having gone
     * missing — the one thing on this tab with no remedy (see `InventoryRowState.isCoin`).
     *
     * A blank query returns `this` **identically**, so a tab with the field on screen and
     * nothing typed is not a different object from one without the field at all.
     */
    fun filteredBy(query: String): InventoryUiState {
        val needle = query.trim()
        if (needle.isEmpty()) return this
        val matched = blocks.mapNotNull { block ->
            when (block) {
                is InventoryBlock.Wallet -> block
                is InventoryBlock.Items -> block.section
                    .let { section -> section.copy(rows = section.rows.filter { it.matches(needle) }) }
                    .takeIf { it.rows.isNotEmpty() }
                    // Decision 15: a section holding a match is opened for as long as the filter
                    // is active. Not written anywhere — see this function's KDoc.
                    ?.copy(collapsed = false)
                    ?.let(InventoryBlock::Items)
            }
        }
        return copy(blocks = matched)
    }
}

/** Case-insensitive name substring — the whole of decision 15's matching rule. */
private fun InventoryRowState.matches(needle: String): Boolean =
    name.contains(needle, ignoreCase = true)

/**
 * FR-24 decision 14's *"total non-coin item count ≥ 15"*.
 *
 * Named rather than inlined so the figure the design states and the figure the code applies are
 * the same token, and so `InventoryUiStateTest` can assert the boundary by name rather than by
 * repeating a number that would then have two homes.
 */
const val FILTER_THRESHOLD: Int = 15

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
 *  3. **The order and the grouping are the player's** (12 decisions 1 and 3) — see below.
 *
 * ### The default order, and what customizing can do to it
 *
 * 12 decision 1 replaces FR-8's order with **Wallet · Equipped · Weapons · Armor · containers ·
 * Gear**, which is a pure ordering change: not one section-membership rule from designs 10 and 11
 * moves. What it buys is that the two sections a player reaches for mid-combat — what they are
 * holding, and what they could pick up — are adjacent and near the top, instead of separated by
 * however many backpacks the sheet happens to have.
 *
 * [layout] then rearranges that per character, and the only interesting half is **hiding**, which
 * on this tab does not mean what it means on the tracker. Decision 3's invariant is *items fold,
 * never vanish*: hiding Weapons, Armor or a container moves its rows into **Gear**, so the same
 * items are on the same tab under a different heading. Hiding is a **grouping** control here, not
 * a visibility one, and that is why the tracker's per-row hide has no counterpart on this tab —
 * an inventory that could conceal an item would be an inventory a player cannot trust.
 *
 * The Wallet is the one section whose hide removes something outright, and it is allowed to
 * because there is nothing to fold: the coin rows are not items and are not duplicated anywhere
 * else on the tab. Equipped and Gear cannot be hidden at all; see
 * [InventoryLayoutKeys.isHideable] for both reasons.
 *
 * ### Collapse, and the invariant it has to compose with (13 decision 4)
 *
 * Collapsing is **orthogonal** to hiding and is applied strictly after it. Hiding decides *which
 * sections render*; collapsing decides *whether a rendered section shows its rows*. Nothing
 * below branches on the two together, and that is the implementation of decision 4 rather than
 * an accident: the `hidden` filter runs once, over `resolved`, and the `collapsed` flag is
 * stamped onto whatever survives it.
 *
 * What that buys is the extended invariant — **every item is at most two taps away**. Hiding
 * cannot lose an item because a hidden section's rows are added to Gear (the `foldedIntoGear`
 * block below, which has no branch that drops anything); collapsing cannot lose one because a
 * collapsed section still *has* its rows and its header is one tap from showing them. Compose
 * the two and every item is in exactly one rendered, un-hidden section, which is either open or
 * one tap from open. `InventoryUiStateTest` pins the whole grid of hide × collapse combinations
 * — item count unchanged, and every item in a section this state can name.
 *
 * The wallet is not part of that grid and cannot be: its rows are coins, not items, and its
 * collapse is not stored at all (see [InventoryLayoutKeys.persistsCollapse]).
 *
 * ### What the fold does to the weights
 *
 * A folded container's contents are summed into **Gear's** header, and the container's own shell
 * weight is then in no section header at all. That is correct rather than lost: the top line is
 * the one authoritative total (10 decision 10), it is a client sum over items plus every
 * container's empty weight, and it has never consulted the section list — the K9 argument below
 * is the same fact from the other direction. A future reader who "fixes" Gear's figure by adding
 * a folded shell's weight into it gets a failing test, because that would be a double count.
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
 * @param layout this character's stored arrangement (12 decision 5), or empty for the default.
 *   Passed in for [equippableOverrides]' reason, whole: another flow, another view model job.
 * @param isLocal whether this is a local character (12 decisions 7 and 8). It decides whether
 *   delete offers an undo and whether move is offered at all, so it is stamped onto every row
 *   ([InventoryRowState.isLocal]) rather than kept at the top — see there for why. Defaulted
 *   `false`, i.e. "a DiceCloud character", which is the reading every existing caller and test
 *   fixture already means.
 */
fun toInventoryUiState(
    creatureId: String,
    board: InventoryBoard,
    connection: ConnectionState = ConnectionState.LIVE,
    isShowingSnapshot: Boolean = false,
    canWrite: Boolean = false,
    equippableOverrides: Set<String> = emptySet(),
    layout: List<InventoryLayoutEntry> = emptyList(),
    isLocal: Boolean = false,
): InventoryUiState {
    // 11 decision 3. Grouped rather than filtered three times so the partition is provably
    // total: every carried item lands in exactly one of the three, because `EquipGroup` has
    // exactly three values and `of` is exhaustive over them.
    val carried = board.carried.groupBy { InventorySectionKind.of(it.equipGroup) }
    // K9 — see the KDoc above. `contents` is already coin- and removed-filtered, so an empty one
    // means there is nothing displayable, and the shell's weight is counted by the board's client
    // sum whether or not this section exists.
    val containers = board.containers.filter { it.contents.isNotEmpty() }

    // 12 decision 1's order, restricted to the sections this character actually has. This is the
    // vocabulary `InventoryLayoutPlan` arranges; a key that is not in here cannot be ordered,
    // which is exactly how a vanished container drops out harmlessly (decision 4).
    val defaultKeys = buildList {
        // Unconditional: the wallet is the one section that exists on every character, including
        // one carrying nothing (10 decision 5). It is in the *order* even when the player has
        // folded it away, because the fold is a position too — see `InventoryLayoutPlan.move`.
        add(InventoryLayoutKeys.WALLET)
        if (board.equipped.isNotEmpty()) add(InventoryLayoutKeys.EQUIPPED)
        if (carried[InventorySectionKind.WEAPONS].orEmpty().isNotEmpty()) {
            add(InventoryLayoutKeys.WEAPONS)
        }
        if (carried[InventorySectionKind.ARMOR].orEmpty().isNotEmpty()) {
            add(InventoryLayoutKeys.ARMOR)
        }
        containers.forEach { add(InventoryLayoutKeys.container(it.propertyId)) }
        // Gear is in the order whenever anything *could* land in it — its own items, or anything
        // foldable. Keyed on "could" rather than on "does" because the fold has not been computed
        // yet and computing it needs the resolved order: a character carrying only weapons has no
        // Gear section until the moment they fold Weapons away, and Gear has to already have a
        // place for it to fold into. An entry whose section turns out empty simply draws nothing.
        if (board.carried.isNotEmpty() || containers.isNotEmpty()) add(InventoryLayoutKeys.GEAR)
    }
    val resolved = InventoryLayoutPlan.resolve(defaultKeys, layout)
    val hidden = resolved.filter { it.hidden }.map { it.key }.toSet()

    // Decision 3's invariant, and the one place it is implemented: every item a hidden section
    // would have shown is added to Gear's rows instead. Note what is *not* here — no branch drops
    // an item — which is what makes "fold, never vanish" a property of the code rather than a
    // promise in a KDoc. `InventoryUiStateTest` pins the item count across every hide combination.
    //
    // The folded rows arrive **after** Gear's own, in decision 1's section order rather than in
    // the player's: Gear is the section they did not touch, so its contents should not reshuffle
    // when an unrelated section is folded, and hidden sections carry no order the player can see.
    val foldedIntoGear = buildList {
        if (InventoryLayoutKeys.WEAPONS in hidden) {
            addAll(carried[InventorySectionKind.WEAPONS].orEmpty())
        }
        if (InventoryLayoutKeys.ARMOR in hidden) {
            addAll(carried[InventorySectionKind.ARMOR].orEmpty())
        }
        containers
            .filter { InventoryLayoutKeys.container(it.propertyId) in hidden }
            .forEach { addAll(it.contents) }
    }
    val gearItems = carried[InventorySectionKind.GEAR].orEmpty() + foldedIntoGear

    // The section one key draws, or `null` when it has no rows to draw. Called **twice** on
    // purpose — once for the tab and once for the customize sheet — so the two cannot disagree
    // about what a section is called or what it weighs. See `InventoryCustomizeState`.
    //
    // Every branch but Gear's is the section's own items; Gear's is `gearItems`, which already
    // has everything folded in. A folded Weapons section still resolves here to its own rows,
    // which is what the sheet needs to name a row the tab is not drawing.
    fun sectionFor(key: String): InventorySectionState? = when {
        key == InventoryLayoutKeys.EQUIPPED -> board.equipped
            .toSection(InventorySectionKind.EQUIPPED, key, equippableOverrides, isLocal)

        key == InventoryLayoutKeys.WEAPONS -> carried[InventorySectionKind.WEAPONS].orEmpty()
            .toSection(InventorySectionKind.WEAPONS, key, equippableOverrides, isLocal)

        key == InventoryLayoutKeys.ARMOR -> carried[InventorySectionKind.ARMOR].orEmpty()
            .toSection(InventorySectionKind.ARMOR, key, equippableOverrides, isLocal)

        key == InventoryLayoutKeys.GEAR ->
            gearItems.toSection(InventorySectionKind.GEAR, key, equippableOverrides, isLocal)

        InventoryLayoutKeys.isContainer(key) -> containers
            .firstOrNull { InventoryLayoutKeys.container(it.propertyId) == key }
            ?.toSection(equippableOverrides, isLocal)

        // A key naming nothing this build knows about. Unreachable today — `resolve` only ever
        // returns keys from `defaultKeys` — and `null` rather than an `error` so that a future
        // section key read off a newer install's preferences file is a section that does not
        // draw, not a crash on the inventory tab.
        else -> null
    }

    val walletUi = board.wallet.toUiState()

    val blocks = resolved.filterNot { it.hidden }.mapNotNull { entry ->
        if (entry.key == InventoryLayoutKeys.WALLET) {
            InventoryBlock.Wallet
        } else {
            // 13 decision 4: collapse is orthogonal to hide and is applied *after* it — the
            // filter above has already decided what renders, and this decides how much of it.
            // Stamped here rather than inside `sectionFor` so the customize sheet's copy of the
            // same section is untouched (decision 6: the sheet manages order and hide, and
            // collapse is an in-place gesture on the tab).
            sectionFor(entry.key)
                ?.copy(collapsed = entry.collapsed)
                ?.let(InventoryBlock::Items)
        }
    }

    // Every entry, folded ones included — a section the tab is not drawing a header for still has
    // to be listed in the sheet or there would be no way to bring it back.
    val customizeRows = resolved.mapNotNull { entry ->
        if (entry.key == InventoryLayoutKeys.WALLET) {
            InventoryCustomizeRow(
                key = InventoryLayoutKeys.WALLET,
                titleRes = R.string.inventory_section_wallet,
                // No weight, by 10 decision 10: the coins count towards the top line and print no
                // section figure. The coin line is the distinguishing fact instead.
                summary = walletUi.summary,
                hidden = entry.hidden,
                canHide = InventoryLayoutKeys.isHideable(entry.key),
                // Carried, never drawn — see [InventoryCustomizeRow.collapsed] for why the
                // sheet's state has to hold a flag the sheet must not render.
                collapsed = entry.collapsed,
            )
        } else {
            sectionFor(entry.key)?.let { section ->
                InventoryCustomizeRow(
                    key = entry.key,
                    titleRes = section.kind.titleRes,
                    containerName = section.containerName,
                    // What the section weighs is what tells two same-named pouches apart, which
                    // is the job the tracker sheet's `detail` does. No new copy for it: the
                    // number is already formatted and "lb" is already a string.
                    weightLabel = section.weight,
                    hidden = entry.hidden,
                    canHide = InventoryLayoutKeys.isHideable(entry.key),
                    // Carried, never drawn — see [InventoryCustomizeRow.collapsed].
                    collapsed = entry.collapsed,
                )
            }
        }
    }

    return InventoryUiState(
        creatureId = creatureId,
        wallet = walletUi,
        blocks = blocks,
        customize = InventoryCustomizeState(customizeRows),
        carriedWeight = formatAmount(board.carriedWeightLb),
        capacityWeight = board.capacityLb?.let { formatAmount(it.toDouble()) },
        isOverCapacity = board.isOverCapacity,
        // 10 decision 9's gate, and the only place it is applied.
        attunement = if (board.hasAttunementData) AttunementChipState(board.attunedCount) else null,
        // 12 decision 8. Built from `board.containers` and **not** from the `containers` local
        // above, which is emptiness-filtered for the section list (K9) — an empty pouch is the
        // most useful destination there is, and filtering it out here would have hidden exactly
        // the containers a player is trying to fill. See `InventoryUiState.moveTargets`.
        //
        // Empty on a local character: `LocalInventoryBoard` produces no containers, so the list
        // is empty by construction rather than by a branch on `isLocal`. The control is gated on
        // `isLocal` anyway (`InventoryRowState.showsMoveControl`), which is the rule; this is
        // the data agreeing with it.
        moveTargets = buildList {
            // The carried root first, because it is the destination that always exists and the
            // one every container's contents came out of.
            add(InventoryMoveTargetState(containerId = null))
            board.containers.forEach {
                add(InventoryMoveTargetState(containerId = it.propertyId, name = it.name))
            }
        },
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
 * A list of items as a drawable section, or `null` when the list is empty.
 *
 * The "absent when empty" rule from [toInventoryUiState]'s KDoc, in one place for all four
 * non-container sections.
 *
 * The weight is a **client sum over exactly the rows shown**, which is what keeps a folded Gear
 * header honest: fold two containers into it and its figure grows by what actually arrived, with
 * no rollup to disagree with. Container headers keep the server's rollup for their own reasons —
 * see [toInventoryUiState].
 *
 * [key] is a parameter rather than derived from [kind] because [InventorySectionKind.CONTAINER]
 * has no single key; see [InventoryLayoutKeys] for why the persisted vocabulary is not the enum's
 * names.
 */
private fun List<InventoryItem>.toSection(
    kind: InventorySectionKind,
    key: String,
    equippableOverrides: Set<String>,
    isLocal: Boolean,
): InventorySectionState? = takeIf { it.isNotEmpty() }?.let { items ->
    InventorySectionState(
        kind = kind,
        key = key,
        weight = formatAmount(items.sumOf { it.totalWeightLb }),
        rows = items.map { it.toRow(equippableOverrides, isLocal) },
    )
}

private fun InventoryItem.toRow(
    equippableOverrides: Set<String>,
    isLocal: Boolean,
): InventoryRowState =
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
        containerId = containerId,
        // 12 decision 7's coin exclusion, computed from the item's own tags rather than from
        // which section it landed in. `InventoryEngine` already routes every coin-tagged item
        // into the wallet, so on a server board this is redundant today — and it is here
        // anyway, because "no delete on coins" is a rule about the item and not about the
        // board that happened to place it. See `InventoryRowState.isCoin`.
        isCoin = CoinKind.fromTags(tags + libraryTags) != null,
        isLocal = isLocal,
    )

private fun InventoryContainer.toSection(
    equippableOverrides: Set<String>,
    isLocal: Boolean,
): InventorySectionState =
    InventorySectionState(
        kind = InventorySectionKind.CONTAINER,
        key = InventoryLayoutKeys.container(propertyId),
        // Blank falls back to the generic title rather than rendering an empty header. A sheet
        // is allowed to have an unnamed property; a section with no heading is not.
        containerName = name.takeIf { it.isNotBlank() },
        weight = formatAmount(displayWeightLb),
        rows = contents.map { it.toRow(equippableOverrides, isLocal) },
    )
