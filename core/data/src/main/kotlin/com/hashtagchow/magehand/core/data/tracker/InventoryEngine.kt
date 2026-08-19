package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.core.model.WalletRow

/**
 * Turns one creature's raw properties into an [InventoryBoard]
 * (docs/design/10-inventory.md, grounded in the live probe of 2026-08-19).
 *
 * Pure, exactly like [TrackerEngine]: no I/O, no coroutines, no clock, same input → same
 * output whether the input came from the REST snapshot or the live DDP mirror.
 *
 * ### Why this is a second object rather than more of [TrackerEngine]
 *
 * A judgment call, and the file's own architecture is what decides it. [TrackerEngine] is a
 * *row* engine: nine discovery rules that each reduce a property to a countable line, followed
 * by one override layer (hide / pin / reorder) applied last to all of them. Everything in it
 * shares that shape, which is why the shared readers and the shared `order()` helper pay for
 * themselves.
 *
 * The inventory shares **none** of it. It produces a nested structure rather than flat lists,
 * it groups by *state* rather than by property type (10 decision 2), it walks the parent tree
 * — which [TrackerEngine] deliberately never does — and it has no override layer at all
 * (`tracker_prefs` pins are a tracker concept; the inventory shows everything). Folding it in
 * would have meant a second, differently-shaped half inside an object whose KDoc opens "rule
 * order matters", and the one thing genuinely shared — the JSON readers in `CreatureSheet.kt`
 * — is `internal` to the module and needs no inheritance to reach.
 *
 * So: same package, same input type, same purity, separate object. The two compose at
 * `CreatureSheet` and nowhere else, which is the seam that already exists.
 *
 * ### The `removed` rule
 *
 * Every list and every sum below filters `removed: true`, via [CreatureSheet.livePropertyList]
 * at the single point of entry rather than nine times over. The probe proved soft-deleted
 * documents reach the client on **both** transports, so this is not defence-in-depth — an
 * unfiltered read here would put a deleted item in front of the player and its weight in the
 * carried total (10 decision 3).
 */
object InventoryEngine {

    // --- DiceCloud vocabulary (probe-verified against the live capture) ------
    private const val TYPE_ITEM = "item"
    private const val TYPE_CONTAINER = "container"

    private const val FIELD_QUANTITY = "quantity"
    private const val FIELD_WEIGHT = "weight"
    private const val FIELD_VALUE = "value"
    private const val FIELD_EQUIPPED = "equipped"
    private const val FIELD_DESCRIPTION = "description"
    private const val FIELD_REQUIRES_ATTUNEMENT = "requiresAttunement"
    private const val FIELD_ATTUNED = "attuned"

    /**
     * The server's own weight rollup over a container's subtree.
     *
     * Two names because DiceCloud has two: `carriedWeight` is the one 10's design text names,
     * `contentsWeight` is the one the live capture actually carries. Read in that order —
     * `carriedWeight` includes the container's own weight where it exists, which is the more
     * complete number, and falling back rather than picking one keeps this correct against
     * both shapes instead of against whichever sheet happened to be captured.
     */
    private const val FIELD_CARRIED_WEIGHT = "carriedWeight"
    private const val FIELD_CONTENTS_WEIGHT = "contentsWeight"
    private const val FIELD_CONTENTS_VALUE = "contentsValue"

    private const val ATTR_ABILITY = "ability"
    private const val VAR_STRENGTH = "strength"

    private const val FIELD_TAGS = "tags"
    private const val FIELD_LIBRARY_TAGS = "libraryTags"

    // -----------------------------------------------------------------------
    // Equippability (docs/design/11-inventory-polish.md decision 1)
    // -----------------------------------------------------------------------

    /**
     * The tags that make an item a weapon. Matched **case-insensitively and whole**.
     *
     * Whole-tag equality rather than a `contains("weapon")` substring, because the sheet's
     * taxonomy also carries `spellcasting focus`, `weapon proficiency` and skill tags like
     * `simpleRangedWeapon` that a substring test would sweep in — and the FR-10 probe's 0
     * false positives on two real sheets is a claim about *this* set, not about the word.
     */
    val WEAPON_TAGS: Set<String> = setOf(
        "melee weapon",
        "ranged weapon",
        "martial weapon",
        "simple weapon",
    )

    /**
     * The tags that make an item armor, shields included.
     *
     * **Data defect 1, and the reason this set is enumerated rather than reduced to the bare
     * word `armor`:** the SRD's *Half Plate* carries `medium armor` and does **not** carry
     * `armor`. A rule keyed on the bare tag would therefore refuse an equip control to a suit
     * of armor — the single most equippable object in the game. Every category is listed, and
     * `armor` is kept alongside them for the entries that do carry it. Nothing here may be
     * collapsed back into one tag, however tidy that looks.
     *
     * The probe also found one malformed barding tag on the reference sheet. It is left
     * unmatched on purpose: barding is worn by a mount, not by the character holding the
     * phone, so a miss there costs nothing and a special case would cost a line of judgment.
     */
    val ARMOR_TAGS: Set<String> = setOf(
        "armor",
        "light armor",
        "medium armor",
        "heavy armor",
        "shield",
    )

    /**
     * The whole equippable taxonomy — 11 decision 1's set, verbatim from the FR-10 mini-probe
     * (0 false positives, 0 false negatives on both real sheets).
     *
     * **Data defect 2, and why the rule's other disjunct is load-bearing:** the reference sheet
     * carries four *hand-made* items the player has already equipped — "A Small Knife", "A
     * quill", a cloak, a set of clothes — and three of them are tagged with nothing this set
     * contains. `equipped == true` is what keeps the **unequip** control on them. Without it,
     * equipping a home-made item would be a one-way door: the control that put it on would
     * vanish the moment it went on.
     *
     * That disjunct has a known residual, and it is a false *negative* rather than a wrong
     * answer: once such an item is unequipped it stops matching either half of the rule and
     * loses its control until the player turns it back on by hand. That is precisely what the
     * per-item override of 11 decision 2 exists for — and it is why the fix is an override and
     * never a name heuristic, which would guess at "knife" and be wrong about "A Little Bag of
     * Sand" on the same sheet.
     */
    val EQUIPPABLE_TAGS: Set<String> = WEAPON_TAGS + ARMOR_TAGS

    /**
     * 11 decision 1's rule, whole: **live AND (equipped OR a tag in [EQUIPPABLE_TAGS])**.
     *
     * The `live` half is not tested here and cannot be — [build] filters `removed` and
     * `inactive` at the single point of entry, so an item that reaches this function is live by
     * construction. Restating it as a third condition would be a second answer to a question
     * already settled one call up.
     *
     * @param tags the union of the item's own `tags` and its `libraryTags`, already unioned by
     *   the caller. See [InventoryItem.libraryTags] for why the source keeps them apart.
     */
    private fun isEquippable(tags: List<String>, equipped: Boolean): Boolean =
        equipped || tags.any { it.normalizedTag() in EQUIPPABLE_TAGS }

    /**
     * Which of Carried's three subsections the item belongs to (11 decision 3).
     *
     * Weapon **before** armor, because it is a precedence and not a partition: an item tagged
     * both ways exists only if a sheet author typed both, and putting it under Weapons is the
     * reading that matches what such an item almost always is. `equipped` deliberately plays no
     * part — an equipped item renders in the Equipped section, which 11 decision 3 leaves
     * unchanged, so its group would never be read.
     *
     * Everything else is [EquipGroup.GEAR], including the equipped-but-untagged items the rule
     * above rescues and the overridden ones 11 decision 2 adds. Decision 3 says so in as many
     * words: an override buys the *control*, not a promotion to the Weapons list — this app
     * still does not know what the thing is, and filing it under Weapons would be a claim.
     */
    private fun equipGroup(tags: List<String>): EquipGroup {
        val normalized = tags.map { it.normalizedTag() }
        return when {
            normalized.any { it in WEAPON_TAGS } -> EquipGroup.WEAPON
            normalized.any { it in ARMOR_TAGS } -> EquipGroup.ARMOR
            else -> EquipGroup.GEAR
        }
    }

    /**
     * A sheet's tag, reduced to the form the sets above are written in.
     *
     * Lowercased because the taxonomy is free text a sheet author typed, and trimmed because
     * `" simple weapon"` is the same tag as `"simple weapon"` to every human who reads it and a
     * different one to `Set.contains`. Stray whitespace is ordinary in hand-typed and
     * copy-pasted tags, and the cost of not trimming falls in exactly the wrong place: a real
     * weapon silently loses its equip control, and the player has to reach for 11 decision 2's
     * override to rescue an item this app could have classified correctly. The override exists
     * for data the taxonomy genuinely does not cover, not for a space.
     *
     * Nothing further is normalized — no de-hyphenating, no singularising, no fuzzy matching.
     * Those would be guesses at what an author meant; this only undoes what a text field did.
     */
    private fun String.normalizedTag(): String = trim().lowercase()

    /**
     * Builds the board.
     *
     * @param sheet the same input [TrackerEngine.build] takes, from either source.
     */
    fun build(sheet: CreatureSheet): InventoryBoard {
        val live = sheet.livePropertyList

        val items = live.filter { it.string("type") == TYPE_ITEM && !it.isTrue("inactive") }
            .mapNotNull { it.toInventoryItem() }
        val containers = live.filter { it.string("type") == TYPE_CONTAINER && !it.isTrue("inactive") }

        // --- section assignment: every item lands in exactly one bucket ------
        //
        // Precedence wallet → equipped → container → carried. It has to be a precedence and
        // not four independent filters, because the live capture has items satisfying two at
        // once: coins inside a purse, and an equipped knife inside a pack. Independent
        // filters would render those twice and, worse, sum their weight twice — which is the
        // one number on this screen a player might act on.
        val containerIds = containers.mapNotNull { it.string("_id") }.toSet()

        val wallet = wallet(items)
        // **Every** coin-tagged item, not just the ones the four rows point at. A sheet with
        // two separate gold stacks has both summed into one row, and excluding only the row's
        // own target would leave the second stack to render as a carried item *and* count
        // twice in the weight — the exact double-count this precedence exists to prevent.
        val walletIds = items.filter { CoinKind.fromTags(it.tags) != null }
            .map { it.propertyId }
            .toSet()

        val unclaimed = items.filterNot { it.propertyId in walletIds }
        val equipped = unclaimed.filter { it.equipped }
        val equippedIds = equipped.map { it.propertyId }.toSet()

        val loose = unclaimed.filterNot { it.propertyId in equippedIds }
        val (contained, carried) = loose.partition { it.containerId in containerIds }

        val containerRows = containers
            .mapNotNull { it.toInventoryContainer(contained) }
            .sortedWith(CONTAINER_ORDER)

        return InventoryBoard(
            wallet = wallet,
            equipped = equipped.sortedWith(ITEM_ORDER),
            containers = containerRows,
            // 10 decision 2: folders are flattened away. The `carried` / `equipment` /
            // `inventory` folders an item sits under are never rendered, because `equip`
            // moves items between them and rendering the tree would make every equip look
            // like the item jumping to a different place on the screen.
            carried = carried.sortedWith(ITEM_ORDER),
            carriedWeightLb = carriedWeight(wallet, equipped, containerRows, carried),
            capacityLb = capacity(live),
            attunedCount = items.count { it.attuned == true },
            hasAttunementData = items.any { it.carriesAttunementData },
        )
    }

    // -----------------------------------------------------------------------
    // Insert targeting (10 decisions 5 and 6)
    // -----------------------------------------------------------------------

    /** Where a newly created item goes, and at what position. */
    data class InsertTarget(
        val parentId: String,
        /** `"creatureProperties"` for a folder or container, `"creatures"` for the root. */
        val parentCollection: String,
        /** The `order` the insert body must carry — probe-verified mandatory. */
        val order: Int,
    )

    /**
     * Resolves the parent an added item should hang under.
     *
     * ### Why resolved from the sheet and not remembered
     *
     * The `equip` method moves items between the `equipment`- and `carried`-tagged folders,
     * so those folders' ids are the sheet's own structure and this app must read them rather
     * than cache them. Resolution is by **tag**, which is what DiceCloud's own
     * `insertAsChildOfTag` keys on, so the answer agrees with what the web UI would do.
     *
     * ### The order of preference
     *
     * 1. **[siblingOf]'s parent**, when given and still present. This is 10 decision 5's
     *    "parented like its siblings": a sheet keeping its coins in a purse should get its
     *    new silver in that purse, not loose in the inventory next to the purse. Wave B
     *    passes the first coin row it found.
     * 2. The **`carried`**-tagged folder — where loose, unequipped items live, which is what
     *    a newly added item is. Preferred over `inventory` because `inventory` is the
     *    *container* of both `equipment` and `carried` on a stock sheet, and dropping an item
     *    there puts it beside two folders rather than in one.
     * 3. The **`inventory`**-tagged folder, for a sheet that has no `carried`.
     * 4. The **creature itself**. Every property tree roots there — the capture's own
     *    `Inventory` folder is parented to the creature — so this is a real location and not
     *    a fallback that fails. An item lands at the top level of the sheet, which is visible
     *    and fixable by the player, rather than the add silently failing.
     *
     * Returns `null` only when the sheet names no creature at all, which is an empty sheet:
     * there is nothing to add an item to.
     *
     * ### The order
     *
     * One past the highest `order` on the sheet. DiceCloud's `order` is a single index across
     * the whole property tree, so this lands the new item at the end — where the player just
     * added it — rather than somewhere in the middle of the list they were reading.
     */
    fun insertTarget(sheet: CreatureSheet, siblingOf: String? = null): InsertTarget? {
        val live = sheet.livePropertyList
        val order = (live.mapNotNull { it.number("order") }.maxOrNull() ?: 0) + 1

        siblingOf
            ?.let { id -> live.firstOrNull { it.string("_id") == id } }
            ?.parentId()
            ?.let { return InsertTarget(it, CreatureSheet.CREATURE_PROPERTIES, order) }

        for (tag in listOf(TAG_CARRIED, TAG_INVENTORY)) {
            live.firstOrNull { it.string("type") == TYPE_FOLDER && it.strings("tags").contains(tag) }
                ?.string("_id")
                ?.let { return InsertTarget(it, CreatureSheet.CREATURE_PROPERTIES, order) }
        }

        val creatureId = sheet.creatureId ?: return null
        return InsertTarget(creatureId, CreatureSheet.CREATURES, order)
    }

    private const val TYPE_FOLDER = "folder"
    private const val TAG_CARRIED = "carried"
    private const val TAG_INVENTORY = "inventory"

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * One `item` property.
     *
     * `inactive` is filtered alongside `removed` at the call site, matching
     * [TrackerEngine.item]'s blanket rule rather than inventing a second answer: an item on a
     * switched-off feature branch (the capture has a Backpack and a Pouch under a deactivated
     * ancestor) is not in the character's possession, and the tracker's consumables list
     * already agrees. Two screens showing different item lists for one sheet would be the
     * bug, whichever list was "right".
     */
    private fun JsonObject.toInventoryItem(): InventoryItem? {
        val id = string("_id") ?: return null
        val tags = strings(FIELD_TAGS)
        val libraryTags = strings(FIELD_LIBRARY_TAGS)
        // The union 11 decision 1 names, computed once and handed to both readers below so the
        // grouping and the control can never be derived from different evidence.
        val allTags = tags + libraryTags
        val equipped = isTrue(FIELD_EQUIPPED)

        return InventoryItem(
            propertyId = id,
            name = string("name").orEmpty(),
            // A missing quantity is one, not zero: the field is omitted on singletons often
            // enough that reading it as zero would make half a sheet weigh nothing.
            // `TrackerEngine.item` reads it the same way — see MED-4 there, and the
            // cross-engine agreement test that pins the two together.
            quantity = number(FIELD_QUANTITY) ?: 1,
            weightLb = decimal(FIELD_WEIGHT),
            valueGp = decimal(FIELD_VALUE),
            description = descriptionText(),
            equipped = equipped,
            tags = tags,
            libraryTags = libraryTags,
            requiresAttunement = bool(FIELD_REQUIRES_ATTUNEMENT),
            attuned = bool(FIELD_ATTUNED),
            containerId = parentId(),
            sortOrder = number("order") ?: 0,
            isEquippable = isEquippable(allTags, equipped),
            equipGroup = equipGroup(allTags),
        )
    }

    /** One `container` property, with the already-bucketed items that name it as parent. */
    private fun JsonObject.toInventoryContainer(candidates: List<InventoryItem>): InventoryContainer? {
        val id = string("_id") ?: return null
        return InventoryContainer(
            propertyId = id,
            name = string("name").orEmpty(),
            quantity = number(FIELD_QUANTITY) ?: 1,
            weightLb = decimal(FIELD_WEIGHT),
            valueGp = decimal(FIELD_VALUE),
            rollupWeightLb = decimal(FIELD_CARRIED_WEIGHT) ?: decimal(FIELD_CONTENTS_WEIGHT),
            rollupValueGp = decimal(FIELD_CONTENTS_VALUE),
            contents = candidates.filter { it.containerId == id }.sortedWith(ITEM_ORDER),
            sortOrder = number("order") ?: 0,
        )
    }

    /**
     * The four wallet rows (10 decision 5).
     *
     * Always four, in [CoinKind.inWalletOrder], **whether or not the sheet carries them** —
     * an absent denomination becomes a row reading 0 with a `null` [WalletRow.propertyId],
     * which is the signal the stepper needs to take the insert path on its first increment
     * instead of trying to adjust a property that does not exist.
     *
     * Where a sheet carries several items with one denomination's tag (two separate gold
     * stacks, say), the quantities are **summed** and the row points at the first by the
     * sheet's own order. Summing is the only reading that makes the total honest; adjusting
     * the first is the only one that can be a single `adjustQuantity` call. The alternative —
     * refusing to show a wallet for such a sheet — would hide the money to protect a stepper.
     * The per-coin weight is taken from that same first stack, so a sheet that weighs its
     * coins gets its own number back rather than the rulebook's.
     *
     * The head's **quantity** is carried too, as [WalletRow.headQuantity], because a stepper
     * clamping a spend against the sum would drive the head stack negative on the server.
     * `0` when the denomination is absent, which is the only reading available and is also the
     * one that makes a decrement on an absent row a no-op.
     */
    private fun wallet(items: List<InventoryItem>): Wallet {
        val byCoin = items
            .mapNotNull { item -> CoinKind.fromTags(item.tags)?.let { it to item } }
            .groupBy({ it.first }, { it.second })

        return Wallet(
            CoinKind.inWalletOrder.map { coin ->
                val stacks = byCoin[coin].orEmpty().sortedWith(ITEM_ORDER)
                val head = stacks.firstOrNull()
                WalletRow(
                    coin = coin,
                    quantity = stacks.sumOf { it.quantity },
                    propertyId = head?.propertyId,
                    // The head's own count, alongside the head's own weight and for the same
                    // reason: `propertyId` names this stack, so this is the only number a
                    // clamp on a write to it may use. See [WalletRow.headQuantity].
                    headQuantity = head?.quantity ?: 0,
                    weightLb = head?.weightLb,
                )
            },
        )
    }

    /**
     * `STR × 15`, or `null` when the sheet expresses no Strength score (10 decision 8).
     *
     * Read the same way [TrackerEngine.abilityCheck] reads an ability — `type: "attribute"`,
     * `attributeType: "ability"` — but keyed on `variableName` rather than on the display
     * name, because the name is whatever the sheet's author typed and `variableName` is what
     * every formula on the sheet already references.
     *
     * `total` before `value`: `total` is the number after every effect the server folded in,
     * which is the score the character actually has. A belt of giant strength should move the
     * capacity line, and reading `value` would ignore it.
     *
     * Encumbrance tiers are deliberately absent (10 decision 8's fence). This is one number
     * and a ceiling, not a rules engine.
     */
    private fun capacity(properties: List<JsonObject>): Int? {
        val strength = properties.firstOrNull {
            it.string("type") == "attribute" &&
                it.string("attributeType") == ATTR_ABILITY &&
                it.string("variableName") == VAR_STRENGTH &&
                !it.isTrue("inactive")
        } ?: return null
        val score = strength.number("total") ?: strength.number(FIELD_VALUE) ?: return null
        return score * InventoryBoard.CAPACITY_PER_STRENGTH
    }

    /**
     * The grand carried total: a **client sum**, every item and container counted once.
     *
     * The sections have already partitioned the items, so this is simply their sum plus each
     * container's own empty weight plus the coins. Container *rollups* are not used here —
     * see [InventoryBoard.carriedWeightLb] for why a number that mixed removed-filtered sums
     * with an unfiltered server rollup would be worse than either.
     */
    private fun carriedWeight(
        wallet: Wallet,
        equipped: List<InventoryItem>,
        containers: List<InventoryContainer>,
        carried: List<InventoryItem>,
    ): Double = wallet.weightLb +
        equipped.sumOf { it.totalWeightLb } +
        carried.sumOf { it.totalWeightLb } +
        containers.sumOf { it.ownWeightLb + it.contentsWeightLb }

    // -----------------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------------

    /**
     * The item's prose, out of DiceCloud's `{text, value, hash, inlineCalculations}` wrapper.
     *
     * `text` before `value`: on every described item in the capture the two are identical,
     * and `text` is the authored source while `value` is the post-computation render. A plain
     * string is also accepted, because nothing guarantees the wrapper on a hand-made item.
     */
    private fun JsonObject.descriptionText(): String? {
        val raw = this[FIELD_DESCRIPTION]
        val text = when (raw) {
            is JsonObject -> raw.string("text") ?: raw.string(FIELD_VALUE)
            else -> string(FIELD_DESCRIPTION)
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /** `parent: {id, collection}` → the id, or `null` when the property names no parent. */
    private fun JsonObject.parentId(): String? = (this["parent"] as? JsonObject)?.string("id")

    /** The sheet's own order, then the name — the same tie-break every other list here uses. */
    private val ITEM_ORDER: Comparator<InventoryItem> =
        compareBy<InventoryItem> { it.sortOrder }.thenBy { it.name }

    private val CONTAINER_ORDER: Comparator<InventoryContainer> =
        compareBy<InventoryContainer> { it.sortOrder }.thenBy { it.name }
}
