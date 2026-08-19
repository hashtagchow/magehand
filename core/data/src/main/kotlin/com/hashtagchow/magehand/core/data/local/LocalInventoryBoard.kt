package com.hashtagchow.magehand.core.data.local

import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.core.model.WalletRow

/**
 * Turns one local character's stored rows into the **same** [InventoryBoard] the inventory
 * tab renders for a DiceCloud sheet (docs/design/10-inventory.md decision 10; 09 decision 5's
 * "the screen is reused, not forked", applied to the second screen).
 *
 * Pure, for the same reason [LocalTrackerBoard] and `InventoryEngine` are: no I/O, no
 * coroutines, no clock. That is what lets a Room-backed board and a JSON-backed board be
 * checked against each other at the type the UI consumes rather than at two different wires.
 *
 * ### What is deliberately absent from a local board, and why
 *
 * - **Containers.** [InventoryBoard.containers] is always empty. A container on a server sheet
 *   is a property type with a parent tree and server-computed rollups; locally there is no
 *   tree — 09 decision 8's "ONE mechanism" is `sortIndex`, a flat order — so there is nothing
 *   for a container to contain. Every item is therefore equipped or carried, and wave B gets
 *   an absent section by the same rule it already uses for a sheet with no containers, not by
 *   a special case.
 * - **Attunement.** [InventoryBoard.hasAttunementData] is always `false`, so the "Attuned n/3"
 *   chip does not render. The form captures no attunement field, and defaulting one to
 *   `false` would make the chip appear reading "0/3" — a confident answer to a question this
 *   character's data never asked. 10 decision 9's gate does the right thing here for free.
 * - **Server rollups.** There are none to carry, which is why the client sum
 *   `InventoryBoard.carriedWeightLb` is defined the way it is: locally it is the *only*
 *   available number, and the server path chose it too, so the two agree by construction
 *   rather than by coincidence.
 *
 * ### Equippability: every local item is equippable, and that is not the rule being ignored
 *
 * 11 decision 1's rule reads a **tag taxonomy** — `simple weapon`, `medium armor`, `shield` —
 * and a local character has no tags at all. The form captures none, `LocalTrackerRow` stores
 * none, and 11 decision 2 forbids the schema change that would add them.
 *
 * So the rule's second disjunct has no *input* here, which is a different thing from having a
 * negative answer. Running it anyway would return `false` for every unequipped row and strip
 * the equip control from the whole of a local character's inventory — a control they have had
 * since FR-8 shipped, removed on the strength of data that was never collected. That is the
 * mirror image of the mistake 10 decision 9 avoids with the attunement chip: an absent field
 * is not a `false`, and this app does not answer questions its source never asked.
 *
 * The honest reading is therefore "unclassified", and the honest rendering of unclassified is
 * the control the player already had — [InventoryItem.isEquippable] `= true` for every row,
 * [EquipGroup.GEAR] for every row, and a Carried section that does not subdivide because there
 * is nothing to subdivide it by. FR-10's own text puts the local half — a category on catalog
 * entries and an "equippable" switch on the custom form — in a later wave, and this is the
 * behaviour that leaves that wave something to improve rather than something to undo.
 *
 * ### The wallet
 *
 * Four rows from four integer columns, always present and never absent — see [CoinPurse]. The
 * ids are minted here and namespaced like [LocalTrackerBoard.HP_ROW_ID], for the same reason:
 * a [WalletRow.propertyId] is what the stepper writes against, it has to survive every rebuild
 * of the board, and it must never be mistakable for a Meteor id.
 */
object LocalInventoryBoard {

    /**
     * @param character `null` while the row has not loaded, or after it was deleted underneath
     *   an open screen — both render [InventoryBoard.EMPTY], matching [LocalTrackerBoard].
     */
    fun build(character: LocalCharacter?, rows: List<LocalTrackerRow>): InventoryBoard {
        if (character == null) return InventoryBoard.EMPTY

        val items = rows
            .filter { it.kind == LocalRowKind.ITEM }
            .sortedWith(ROW_ORDER)
            .map { it.toInventoryItem() }

        val (equipped, carried) = items.partition { it.equipped }
        val wallet = wallet(character)

        return InventoryBoard(
            wallet = wallet,
            equipped = equipped,
            containers = emptyList(),
            carried = carried,
            carriedWeightLb = wallet.weightLb + items.sumOf { it.totalWeightLb },
            // The same STR × 15 the server path computes, from the score the form captured.
            // Never `null` here: a local character always has six scores (the form defaults
            // them to 10), so the capacity line always renders — which is the honest
            // difference from a sheet that may simply not express Strength.
            capacityLb = character.abilities.score(Ability.STR) * InventoryBoard.CAPACITY_PER_STRENGTH,
            attunedCount = 0,
            hasAttunementData = false,
        )
    }

    /** The four coin rows, from the four columns. Never absent — see the class KDoc. */
    private fun wallet(character: LocalCharacter): Wallet = Wallet(
        CoinKind.inWalletOrder.map { coin ->
            WalletRow(
                coin = coin,
                quantity = character.coins.count(coin),
                // Non-null by construction: the column exists whether or not it reads zero, so
                // there is no insert path to signal. This is exactly the distinction
                // WalletRow.propertyId's nullability was introduced to carry.
                propertyId = walletRowId(coin),
            )
        },
    )

    private fun LocalTrackerRow.toInventoryItem(): InventoryItem = InventoryItem(
        propertyId = id,
        name = label,
        // For an item row `current` and `total` are one number by construction
        // (`LocalCharacterDao.setRowQuantity` moves them together); `current` is the one the
        // tracker's stepper writes, so it is the one that is certainly right.
        quantity = current,
        weightLb = weightLb,
        valueGp = valueGp,
        description = description,
        equipped = equipped,
        // No tags locally: currency is columns, not tagged items, so there is nothing a tag
        // would be read *for*. An empty list rather than an invented one keeps
        // `CoinKind.fromTags` from ever matching a local row and pulling it into the wallet
        // alongside the columns.
        tags = emptyList(),
        libraryTags = emptyList(),
        requiresAttunement = null,
        attuned = null,
        containerId = null,
        sortOrder = sortIndex,
        // 11 decision 1's rule is *stated in terms of a tag taxonomy this source does not
        // have*, and running it anyway would answer "no" for every unequipped row — which is
        // not the rule's answer, it is the absence of an input. See the class KDoc.
        isEquippable = true,
        equipGroup = EquipGroup.GEAR,
    )

    /** The stable id of one local wallet row. */
    fun walletRowId(coin: CoinKind): String = "$WALLET_ROW_ID_PREFIX${coin.name}"

    /** Namespaced like [LocalTrackerBoard.HP_ROW_ID], so it can never look like a Meteor id. */
    const val WALLET_ROW_ID_PREFIX: String = "local:coin:"

    /** The player's order, then the label — [LocalTrackerBoard]'s rule, not a second one. */
    private val ROW_ORDER: Comparator<LocalTrackerRow> =
        compareBy<LocalTrackerRow> { it.sortIndex }.thenBy { it.label }
}
