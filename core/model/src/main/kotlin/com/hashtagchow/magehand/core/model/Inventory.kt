package com.hashtagchow.magehand.core.model

/**
 * The domain types the **inventory tab** renders (docs/design/10-inventory.md).
 *
 * Same posture as [TrackerBoard]: no JSON, no Room, no DiceCloud vocabulary. The discovery
 * rules that produce these live in `:core:data`'s `InventoryEngine` (a DiceCloud sheet) and
 * `LocalInventoryBoard` (a Room-backed local character), so one set of types serves both
 * kinds of character exactly as the tracker's already do.
 *
 * ### Why the sections are states, not folders
 *
 * 10 decision 2. `creatureProperties.equip` **reparents** the item — equipping moves it under
 * the `equipment`-tagged folder and unequipping under `carried`, and it never restores the
 * original parent (probe, 2026-08-19). Grouping by the server's folder tree would therefore
 * make every equip look like the item teleporting. Grouping by *state* makes the reparenting
 * invisible by design: the row moves between [InventoryBoard.equipped] and
 * [InventoryBoard.carried], which is the move the user asked for and the only one they see.
 */

/**
 * A denomination of coin.
 *
 * **DiceCloud has no currency model.** Coins are ordinary `item` properties distinguished
 * only by a tag, adjusted through the same `adjustQuantity` method as a potion (10's probe
 * facts). So this enum is the whole of the app's currency knowledge, and every number on it
 * is a *lookup key or a creation default* rather than a computation — nothing here overrides
 * what a sheet already says.
 */
enum class CoinKind {
    PLATINUM,
    GOLD,
    SILVER,
    COPPER,
    ;

    /**
     * The `tags` entry that identifies this denomination on a sheet.
     *
     * The tag and not the name: a sheet may spell the item "Gold piece", "gp" or "Gold
     * Pieces", and the tag is the one field DiceCloud's own currency handling keys on.
     */
    val tag: String
        get() = when (this) {
            PLATINUM -> "platinum"
            GOLD -> "gold"
            SILVER -> "silver"
            COPPER -> "copper"
        }

    /** Value of one coin in gold pieces — the `value` field DiceCloud stores per unit. */
    val valueGp: Double
        get() = when (this) {
            PLATINUM -> 10.0
            GOLD -> 1.0
            SILVER -> 0.1
            COPPER -> 0.01
        }

    /**
     * Weight of one coin, in pounds. 5e's "fifty coins weigh a pound" for every denomination.
     *
     * Used **only** when this app creates a coin item that the sheet did not have (see
     * [WalletRow.propertyId]); a coin the sheet already carries keeps whatever weight the
     * sheet gave it, including none. Overwriting a player's own number with a rulebook
     * constant is not this app's job.
     */
    val weightLb: Double get() = 0.02

    /** The name given to a coin item this app creates. DiceCloud's own singular spelling. */
    val itemName: String
        get() = when (this) {
            PLATINUM -> "Platinum piece"
            GOLD -> "Gold piece"
            SILVER -> "Silver piece"
            COPPER -> "Copper piece"
        }

    /** `pp` / `gp` / `sp` / `cp` — the wallet row's label. */
    val abbreviation: String
        get() = when (this) {
            PLATINUM -> "pp"
            GOLD -> "gp"
            SILVER -> "sp"
            COPPER -> "cp"
        }

    companion object {
        /**
         * The four rows in the order a 5e sheet prints them, highest denomination first.
         *
         * Declaration order, named rather than left implicit, because the wallet renders
         * exactly four rows in exactly this order whether the sheet carries them or not.
         */
        val inWalletOrder: List<CoinKind> get() = entries

        /**
         * The denomination [tags] identifies, or `null` when it names none.
         *
         * Case-insensitive: the tag is free text a sheet author typed, and "Gold" failing to
         * match "gold" would silently drop a player's money out of the wallet and into the
         * carried list. First match wins; a single item tagged with two denominations is
         * nonsense the sheet would have to have invented, and picking the highest-value
         * reading of it would be a guess.
         */
        fun fromTags(tags: List<String>): CoinKind? =
            entries.firstOrNull { coin -> tags.any { it.equals(coin.tag, ignoreCase = true) } }
    }
}

/**
 * One denomination's row in the wallet — always present, even when the sheet is not.
 *
 * 10 decision 5: the wallet is **four fixed rows**. A character with no silver still has a
 * silver row reading 0, because "you have no silver" and "this app could not find your
 * silver" look identical when the row is simply absent, and only one of them is true.
 */
data class WalletRow(
    val coin: CoinKind,
    /** How many of this coin. `0` when the sheet carries no such item. */
    val quantity: Int,
    /**
     * The backing `creatureProperties._id`, or **`null` when the sheet has no item for this
     * denomination at all**.
     *
     * That nullability is the whole reason this type exists rather than a plain
     * `Map<CoinKind, Int>`: `adjustQuantity` needs an id, and there is nothing to adjust. The
     * first increment on a null-id row has to *create* the item instead (10 decision 5), which
     * is a different DDP method with a different undo story — see `OpenCharacter.adjustCoins`.
     */
    val propertyId: String?,
    /**
     * How many coins sit on the **head stack alone** — the one [propertyId] names.
     *
     * Almost always equal to [quantity], and deliberately defaulted to it: a sheet with one
     * item per denomination (and a local character, whose coins are columns) has exactly one
     * stack, so the head *is* the row.
     *
     * It is a separate number for the sheet that carries several items with one denomination's
     * tag. [quantity] sums them because that is the only honest total to show; [propertyId]
     * names the first because that is the only single `adjustQuantity` call available. Clamping
     * a spend against the sum while sending it at the head is how a wallet reading 105 gp
     * (5 + 100 across two stacks) turned a 50 gp spend into a head stack of **−45** on the
     * server. This field is what the clamp is allowed to use.
     *
     * @see totalWeightLb for the same head-vs-sum distinction applied to [weightLb].
     */
    val headQuantity: Int = quantity,
    /**
     * Weight of one such coin **as the source records it**, or `null` when it does not.
     *
     * Carried rather than always taken from [CoinKind.weightLb] because the sheet is the
     * authority on its own numbers: the live capture records silver and copper at 0.02 lb and
     * gives gold no weight at all, and substituting the rulebook constant would quietly
     * disagree with the weight DiceCloud's own UI shows for the same purse. `null` falls back
     * to the constant, which is the only number available for a local character (whose coins
     * are columns, not items) and for a sheet that simply did not say.
     */
    val weightLb: Double? = null,
) {
    val valueGp: Double get() = quantity * coin.valueGp

    /** What this stack weighs, for the carried-weight line. See [weightLb]. */
    val totalWeightLb: Double get() = quantity * (weightLb ?: coin.weightLb)

    /** True when the sheet carries no item for this denomination — the insert path. */
    val isAbsent: Boolean get() = propertyId == null
}

/**
 * The four coin rows and what they add up to.
 *
 * No exchange or make-change arithmetic (10 decision 5's explicit fence): converting 10 sp
 * into 1 gp is a *table* decision with house rules attached, and an app that did it silently
 * would be rewriting a player's sheet to satisfy its own tidiness.
 */
data class Wallet(
    /** Always four rows, in [CoinKind.inWalletOrder]. */
    val rows: List<WalletRow>,
) {
    /** The client-computed "total in gp" line under the rows. */
    val totalGp: Double get() = rows.sumOf { it.valueGp }

    /** What the coins weigh. Counted into the board's carried weight like any other item. */
    val weightLb: Double get() = rows.sumOf { it.totalWeightLb }

    /** True when every row reads zero — the section can then render flat rather than proud. */
    val isEmpty: Boolean get() = rows.all { it.quantity == 0 }

    fun row(coin: CoinKind): WalletRow =
        rows.firstOrNull { it.coin == coin } ?: WalletRow(coin, quantity = 0, propertyId = null)

    companion object {
        /** Four absent rows — what a character with no coin items at all produces. */
        val EMPTY: Wallet = Wallet(
            CoinKind.inWalletOrder.map { WalletRow(it, quantity = 0, propertyId = null) },
        )
    }
}

/**
 * Which of Carried's three subsections an item belongs to (docs/design/11-inventory-polish.md
 * decision 3).
 *
 * ### Why the group and "is it equippable" are two facts and not one
 *
 * They cross. [GEAR] holds both a tinderbox (not equippable) and a hand-made item the player
 * has already equipped or overridden (equippable) — see [InventoryItem.isEquippable] — so a
 * single three-value field could not answer both questions without inventing a fourth value
 * meaning "gear, but with a control on it". The grouping answers *where the row goes*; the
 * flag answers *what the row can do*, and 11 decision 3 is explicit that an overridden item
 * keeps its Gear grouping while gaining the control.
 *
 * There is deliberately no `SHIELD` value: 5e's shield is armor for every purpose this screen
 * has, and a section holding one row on the sheets that carry one is chrome, not structure.
 */
enum class EquipGroup {
    WEAPON,
    ARMOR,
    GEAR,
}

/**
 * One item on the inventory tab.
 *
 * Distinct from [TrackedResource] on purpose. A tracked resource is a *countable row* — a
 * name and two integers — because that is all the tracker's pips need. An inventory row is an
 * object with weight, worth, prose and an equipped state, and bending `TrackedResource` around
 * that would put five nullable fields on the type every pip on the tracker is built from.
 * The two overlap at `propertyId`, which is what lets the tracker's quantity stepper and this
 * screen's write to the same item.
 */
data class InventoryItem(
    /** `creatureProperties._id` — the write target for equip and for quantity. */
    val propertyId: String,
    val name: String,
    val quantity: Int,
    /**
     * Weight of **one** unit, in pounds, or `null` when the sheet does not say.
     *
     * `null` rather than `0.0`: a torch with no recorded weight and a torch recorded as
     * weightless are different facts, and only the second one is a claim. Sums treat the
     * absence as zero (there is no other arithmetic available) but the field keeps the
     * distinction so the detail sheet can render "—" instead of "0 lb".
     */
    val weightLb: Double?,
    /** Value of one unit, in gold pieces, or `null` when the sheet does not say. */
    val valueGp: Double?,
    /** The sheet's prose, flattened out of DiceCloud's `{text, value, …}` wrapper. */
    val description: String?,
    val equipped: Boolean,
    /** The sheet's own tags, unaltered. Coin denominations are read off these. */
    val tags: List<String> = emptyList(),
    /**
     * The tags the item inherited from the SRD library node it was created from.
     *
     * A second list rather than merged into [tags], because they are different claims: [tags]
     * is what *this sheet* says, `libraryTags` is what the *source entry* said. The live
     * capture carries both and they usually agree — but a hand-edited item can have its tags
     * changed with the library's left alone, and flattening the two would make it impossible
     * to tell an authored tag from an inherited one if a later feature ever needs to.
     *
     * Equippability reads their **union** (11 decision 1): either list naming a weapon or a
     * piece of armor is the sheet telling us what the thing is.
     */
    val libraryTags: List<String> = emptyList(),
    /**
     * `requiresAttunement` / `attuned` as the sheet carries them — **both usually absent**.
     *
     * Nullable rather than defaulted to `false` because 10 decision 9 hangs on the
     * difference: the "Attuned n/3" chip is shown only when at least one item carries either
     * field, and a default of `false` would make every sheet look like it had answered the
     * question. See [InventoryBoard.hasAttunementData].
     */
    val requiresAttunement: Boolean? = null,
    val attuned: Boolean? = null,
    /** The container this item sits directly inside, or `null` when it does not. */
    val containerId: String? = null,
    /** The server's `order`, the only stable tie-breaker it gives us. */
    val sortOrder: Int = 0,
    /**
     * Whether this app is willing to put an equip control on the row (11 decision 1).
     *
     * Computed by whichever board built the item, because the *evidence* differs by source:
     * `InventoryEngine` reads the sheet's tag taxonomy, and `LocalInventoryBoard` has no
     * taxonomy at all. See each for its own argument.
     *
     * **Defaults to `true`**, and that direction is the deliberate one: a board that has said
     * nothing about equippability has not said "no". Defaulting to `false` would make every
     * future source of items — and every test fixture built before this field existed — quietly
     * lose a control the player had, which is the failure mode that is invisible in a diff.
     */
    val isEquippable: Boolean = true,
    /** Which Carried subsection the row belongs to (11 decision 3). See [EquipGroup]. */
    val equipGroup: EquipGroup = EquipGroup.GEAR,
) {
    /** What the whole stack weighs. A missing per-unit weight counts as zero — see [weightLb]. */
    val totalWeightLb: Double get() = (weightLb ?: 0.0) * quantity

    val totalValueGp: Double get() = (valueGp ?: 0.0) * quantity

    /** True when this item says anything at all about attunement. See [attuned]. */
    val carriesAttunementData: Boolean get() = requiresAttunement != null || attuned != null
}

/**
 * A `container` property and the items rendered under it.
 *
 * DiceCloud gives containers their own property type and computes weight/value rollups over
 * their subtree server-side. Those rollups are carried here **for display** and are
 * deliberately not what [InventoryBoard.carriedWeightLb] is built from — see there.
 */
data class InventoryContainer(
    val propertyId: String,
    val name: String,
    val quantity: Int,
    /** The empty container's own weight, per unit, or `null` when the sheet does not say. */
    val weightLb: Double?,
    /** What the empty container itself is worth, per unit. Not its contents — see [rollupValueGp]. */
    val valueGp: Double? = null,
    /**
     * The server's own rollup for what is inside — `carriedWeight` where the sheet has it,
     * otherwise `contentsWeight`.
     *
     * `null` on a container the server has not computed one for (the live capture has two of
     * each). **Not removed-filtered**, and cannot be: it is a number the server computed over
     * its own tree, and nothing on the client can subtract a soft-deleted item back out of it.
     * That is exactly why it is a display field and not a summand — [contentsWeightLb] is the
     * removed-filtered answer, and 10 decision 3 makes removed-filtering the wave's rule.
     */
    val rollupWeightLb: Double?,
    /** The server's `contentsValue` rollup, same caveat as [rollupWeightLb]. */
    val rollupValueGp: Double?,
    /** The non-removed items parented directly to this container. */
    val contents: List<InventoryItem>,
    val sortOrder: Int = 0,
) {
    /** What the container itself weighs, empty. */
    val ownWeightLb: Double get() = (weightLb ?: 0.0) * quantity

    /** Client sum over [contents]. Removed-filtered by construction — the list already is. */
    val contentsWeightLb: Double get() = contents.sumOf { it.totalWeightLb }

    val contentsValueGp: Double get() = contents.sumOf { it.totalValueGp }

    /**
     * The number the section header prints: the container plus what the **server** says is in
     * it, falling back to this client's own sum when the server computed no rollup.
     *
     * The server's number is preferred here and only here, because a header that disagreed
     * with DiceCloud's own web UI on the same screen would read as this app being wrong. The
     * grand total on the board takes the other choice, for the reason stated there.
     */
    val displayWeightLb: Double get() = ownWeightLb + (rollupWeightLb ?: contentsWeightLb)
}

/**
 * What the inventory tab renders. Every list and every sum here is already
 * `removed:true`-filtered (10 decision 3).
 *
 * ### Each item appears in exactly one section
 *
 * Precedence is wallet → equipped → container → carried, and it is a precedence rather than a
 * set of independent filters because an item can satisfy two at once: a sheet in the live
 * capture has an equipped knife *inside* a container, and coins *inside* a purse. Rendering
 * such an item twice would double it in front of the player; summing it twice would make the
 * carried-weight line wrong. See `InventoryEngine.build`.
 */
data class InventoryBoard(
    val wallet: Wallet = Wallet.EMPTY,
    /** `equipped == true`, in the sheet's own order. */
    val equipped: List<InventoryItem> = emptyList(),
    /** One entry per `container` property, each with its own contents. */
    val containers: List<InventoryContainer> = emptyList(),
    /** Everything else. Folders are flattened away — 10 decision 2 never renders the tree. */
    val carried: List<InventoryItem> = emptyList(),
    /**
     * The "142 / 225 lb" line's left half: a **client sum** of `weight × quantity` over every
     * non-removed item and container, each counted exactly once.
     *
     * Not built from the server's container rollups, and that is the one place this board
     * disagrees with [InventoryContainer.displayWeightLb] on purpose. A rollup cannot be
     * removed-filtered from here (see [InventoryContainer.rollupWeightLb]), so a total that
     * mixed filtered sums with unfiltered rollups would be neither one thing nor the other —
     * and 10 decision 3 exists because soft-deleted items reaching the client is a live bug
     * risk, not a hypothetical. A number that is consistently the client's own is auditable;
     * a hybrid is not.
     */
    val carriedWeightLb: Double = 0.0,
    /**
     * `STR × 15`, or `null` when the source expresses no Strength score.
     *
     * `null` and not a default: 15 × some assumed 10 would print "142 / 150 lb" at a player
     * whose sheet never said 10, and a capacity bar is exactly the kind of number that gets
     * believed. Absent means the line does not render (10 decision 8).
     */
    val capacityLb: Int? = null,
    /** How many items read `attuned: true`. Zero on the overwhelming majority of sheets. */
    val attunedCount: Int = 0,
    /**
     * True when **any** discovered item carries `requiresAttunement` or `attuned`.
     *
     * 10 decision 9's gate: the "Attuned n/3" chip renders only when this is true, so a sheet
     * whose data never mentions attunement gets no chip rather than a confident "0/3". The
     * server models no cap at all — see [ATTUNEMENT_SLOTS].
     */
    val hasAttunementData: Boolean = false,
) {
    val isEmpty: Boolean
        get() = wallet.isEmpty && equipped.isEmpty() && containers.isEmpty() && carried.isEmpty()

    /** Every item on the board, each exactly once, in section order. */
    val allItems: List<InventoryItem>
        get() = equipped + containers.flatMap { it.contents } + carried

    /** Total worth of [allItems] plus the wallet, in gp. Client-summed, removed-filtered. */
    val totalValueGp: Double
        get() = allItems.sumOf { it.totalValueGp } +
            containers.sumOf { (it.valueGp ?: 0.0) * it.quantity } +
            wallet.totalGp

    /** True when [capacityLb] exists and the carried weight is over it. */
    val isOverCapacity: Boolean
        get() = capacityLb != null && carriedWeightLb > capacityLb

    companion object {
        val EMPTY: InventoryBoard = InventoryBoard()

        /**
         * The attunement cap, as a **client-side constant**.
         *
         * 5e's rule, not DiceCloud's: the server stores no slot count anywhere, so this is
         * the app asserting a rulebook number rather than reading one. It is a constant here
         * so the one place it is written is the one place a house rule would change it.
         */
        const val ATTUNEMENT_SLOTS: Int = 3

        /** Pounds of carrying capacity per point of Strength (5e). See [capacityLb]. */
        const val CAPACITY_PER_STRENGTH: Int = 15
    }
}

/**
 * A new item to create — the catalog's "add" and the custom form's "save", as one type.
 *
 * The two paths differ only in where the fields came from, so they are one spec rather than
 * two intents: `:app` builds this from a [CatalogItem] or from five text fields, and
 * `OpenCharacter.addItem` neither knows nor cares which.
 *
 * Every optional field is `null` rather than defaulted, and that carries through to the wire:
 * a field this spec leaves null is **omitted** from the insert body rather than sent as zero,
 * so a custom item with no stated weight arrives on the sheet the way a hand-created one does.
 */
data class NewItemSpec(
    val name: String,
    val quantity: Int = 1,
    val weightLb: Double? = null,
    val valueGp: Double? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * The [CatalogItem.id] this came from, or `null` for the custom form.
     *
     * **Never sent to the server.** It is this app's own vocabulary and would be meaningless
     * on a sheet opened in DiceCloud's web UI. It exists so wave B can tell "the player picked
     * Torch from the list" from "the player typed the word torch", which is the difference
     * between a repeat-add affordance that works and one that guesses.
     */
    val catalogId: String? = null,
) {
    /** True when the spec has enough to create anything at all. */
    val isValid: Boolean get() = name.isNotBlank() && quantity > 0

    companion object {
        /** The catalog path: an entry plus how many of it. */
        fun of(entry: CatalogItem, quantity: Int = entry.defaultQuantity): NewItemSpec =
            NewItemSpec(
                name = entry.name,
                quantity = quantity,
                weightLb = entry.weightLb,
                valueGp = entry.valueGp,
                description = entry.description,
                tags = entry.tags,
                catalogId = entry.id,
            )

        /** The wallet's insert path: the coin item a sheet was missing (10 decision 5). */
        fun ofCoin(coin: CoinKind, quantity: Int): NewItemSpec = NewItemSpec(
            name = coin.itemName,
            quantity = quantity,
            weightLb = coin.weightLb,
            valueGp = coin.valueGp,
            tags = listOf(coin.tag),
        )
    }
}
