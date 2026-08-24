package com.hashtagchow.magehand.core.data.contract

import com.hashtagchow.magehand.core.ddp.MeteorId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * The **synthetic** sheets and identifiers the contract export is generated from.
 *
 * ### Public-safe by construction, not by scrubbing
 *
 * `contract-export/` is vendored into the sibling WebHand repo, which may be mirrored
 * publicly. So nothing in this file — and therefore nothing in the export — may come from
 * the private live capture that `Fixtures.kt` reads, from the party's sheets, or from any
 * real server. Every property below was written here to *exercise a documented rule*, and
 * every id is minted by [MeteorId.random] from a fixed seed.
 *
 * That last point is the difference between "we believe these ids are fake" and "these ids
 * cannot be real": [MeteorId] is the production id generator, so a fixture id is guaranteed
 * to be 17 characters of `UNMISTAKABLE_CHARS` — the same alphabet a real Meteor id uses, so
 * the vectors exercise real validators — while being drawn from a seeded PRNG that has never
 * seen a server. `ContractExportTest` asserts the alphabet rather than trusting it.
 *
 * ### Why the ids are seeded per label rather than per position
 *
 * [fakeId] seeds from the label's own hash, so inserting a fixture in the middle of this file
 * does not renumber every id after it — which would rewrite the whole golden export and bury
 * the one intended change in a thousand-line diff. `String.hashCode` is specified by the JLS,
 * so the mapping is stable across JVMs and machines.
 */
object ContractFixtures {

    // ---------------------------------------------------------------- identity

    /** The synthetic character every vector is written against. */
    val creatureId: String = fakeId("creature")

    /** The synthetic logged-in user. */
    val userId: String = fakeId("user")

    /**
     * A synthetic resume token, in the shape the REST login returns.
     *
     * Not a Meteor id — real resume tokens are 43-character base64url — but built from the
     * same seeded generator so it is provably not a credential. The login vector needs
     * *something* in the field, and a placeholder that looks nothing like a token would let a
     * consumer conclude the field's shape is unconstrained.
     */
    val resumeToken: String = "fake-resume-token-" + MeteorId.random(24, Random("token".hashCode().toLong()))

    // ------------------------------------------------------- property ids used by vectors

    val spellSlotL1Id: String = fakeId("slot-l1")
    val spellSlotL2Id: String = fakeId("slot-l2")
    val spellSlotUnreachableId: String = fakeId("slot-unreachable")
    val spellSlotRemovedId: String = fakeId("slot-removed")
    val spellSlotInactiveId: String = fakeId("slot-inactive")
    val deathSaveSuccessId: String = fakeId("death-save-success")
    val deathSaveFailureId: String = fakeId("death-save-failure")
    val kiId: String = fakeId("resource-ki")
    val emptyResourceId: String = fakeId("resource-empty")
    val hitPointsId: String = fakeId("hit-points")
    val tempHitPointsId: String = fakeId("temp-hit-points")
    val rageToggleId: String = fakeId("toggle-rage")
    val concentrationToggleId: String = fakeId("toggle-concentration")
    val computedToggleId: String = fakeId("toggle-computed")
    val strengthId: String = fakeId("attribute-strength")

    val inventoryFolderId: String = fakeId("folder-inventory")
    val carriedFolderId: String = fakeId("folder-carried")
    val equipmentFolderId: String = fakeId("folder-equipment")
    val backpackId: String = fakeId("container-backpack")

    val longswordId: String = fakeId("item-longsword")
    val halfPlateId: String = fakeId("item-half-plate")
    val tinderboxId: String = fakeId("item-tinderbox")
    val handmadeEquippedId: String = fakeId("item-handmade-equipped")
    val handmadeStowedId: String = fakeId("item-handmade-stowed")
    val potionId: String = fakeId("item-potion")
    val singletonId: String = fakeId("item-singleton-no-quantity")
    val removedItemId: String = fakeId("item-removed")
    val ropeInBackpackId: String = fakeId("item-rope-in-backpack")

    val goldId: String = fakeId("coin-gold")
    val silverId: String = fakeId("coin-silver")
    val platinumId: String = fakeId("coin-platinum")
    // No copper item: the wallet must render a denomination the sheet does not carry.

    // ------------------------------------------------------------------ sheets

    /**
     * Tracker discovery, one property per documented rule (design 03 §Discovery rules).
     *
     * The exclusions are the interesting half, so each one is present in the input and
     * absent from the output — a vector that only carried the rows that survive would prove
     * nothing about the filter.
     */
    fun trackerSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            // Rule 1: spell slots. Two survive.
            attribute(
                id = spellSlotL1Id, name = "Level 1 Spell Slots", attributeType = "spellSlot",
                total = 4, damage = 1, reset = "longRest", spellSlotLevel = 1, order = 10,
            ),
            attribute(
                id = spellSlotL2Id, name = "Level 2 Spell Slots", attributeType = "spellSlot",
                total = 3, damage = 0, reset = "longRest", spellSlotLevel = 2, order = 11,
            ),
            // Rule 1 exclusion: total == 0 (a slot level the character cannot reach).
            attribute(
                id = spellSlotUnreachableId, name = "Level 9 Spell Slots", attributeType = "spellSlot",
                total = 0, damage = 0, reset = "longRest", spellSlotLevel = 9, order = 12,
            ),
            // Rule 1 exclusion, THE quirk: death saves are spell slots with `reset: null`.
            // One states the field as JSON null, one omits it — both are `reset == null` and
            // both must be excluded, and a consumer reading only one shape would ship the bug.
            attribute(
                id = deathSaveSuccessId, name = "Succeeded Saves", attributeType = "spellSlot",
                total = 3, damage = 1, reset = null, spellSlotLevel = null, order = 13,
                explicitNullReset = true,
            ),
            attribute(
                id = deathSaveFailureId, name = "Failed Saves", attributeType = "spellSlot",
                total = 3, damage = 0, reset = null, spellSlotLevel = null, order = 14,
            ),
            // Soft-removed and inactive slots: delivered by the server, dropped by discovery.
            attribute(
                id = spellSlotRemovedId, name = "Level 3 Spell Slots", attributeType = "spellSlot",
                total = 2, damage = 0, reset = "longRest", spellSlotLevel = 3, order = 15,
                removed = true,
            ),
            attribute(
                id = spellSlotInactiveId, name = "Level 4 Spell Slots", attributeType = "spellSlot",
                total = 2, damage = 0, reset = "longRest", spellSlotLevel = 4, order = 16,
                inactive = true,
            ),
            // Rule 2: resources.
            attribute(
                id = kiId, name = "Ki Points", attributeType = "resource",
                total = 5, damage = 2, reset = "shortRest", order = 20,
            ),
            // Rule 2 exclusion: total == 0 and value == 0.
            attribute(
                id = emptyResourceId, name = "Sorcery Points", attributeType = "resource",
                total = 0, damage = 0, reset = "longRest", order = 21,
            ),
            // Rule 3: HP / temp HP, found by variableName rather than attributeType.
            attribute(
                id = hitPointsId, name = "Hit Points", attributeType = "healthBar",
                variableName = "hitPoints", total = 42, damage = 12, order = 1,
            ),
            attribute(
                id = tempHitPointsId, name = "Temp HP", attributeType = "healthBar",
                variableName = "tempHitPoints", total = 8, damage = 0, order = 2,
            ),
            // Rule 5: toggles. `enabled`/`disabled` is what makes one flippable — a toggle
            // carrying neither is computed and the server refuses to flip it.
            toggle(id = rageToggleId, name = "Rage", enabled = true, order = 30),
            toggle(id = concentrationToggleId, name = "Concentration: Bless", enabled = true, order = 31),
            toggle(id = computedToggleId, name = "Bloodied", enabled = null, order = 32),
        ),
    )

    /**
     * Inventory discovery (designs 10 and 11), including every case 11 decision 1's
     * equippability rule turns on and the coin handling of 10 decision 5.
     */
    fun inventorySheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            // Strength drives the carry capacity line (10 decision 8).
            attribute(
                id = strengthId, name = "Strength", attributeType = "ability",
                variableName = "strength", total = 15, damage = 0, order = 1,
            ),
            folder(inventoryFolderId, "Inventory", listOf("inventory"), parentId = null, order = 100),
            folder(carriedFolderId, "Carried", listOf("carried"), parentId = inventoryFolderId, order = 101),
            folder(equipmentFolderId, "Equipment", listOf("equipment"), parentId = inventoryFolderId, order = 102),

            // Equippable by tag, equipped → Equipped section.
            item(
                id = longswordId, name = "Longsword", quantity = 1, weight = 3.0, value = 15.0,
                tags = listOf("martial weapon", "melee weapon"), equipped = true,
                parentId = equipmentFolderId, order = 110,
            ),
            // 11 decision 1's data defect: Half Plate carries `medium armor` and NOT the bare
            // `armor` tag. A rule keyed on the bare word would refuse it an equip control.
            item(
                id = halfPlateId, name = "Half Plate", quantity = 1, weight = 40.0, value = 750.0,
                tags = listOf("medium armor"), equipped = false,
                parentId = carriedFolderId, order = 111,
            ),
            // No equippable tag and not equipped → no equip control. A tinderbox is gear.
            item(
                id = tinderboxId, name = "Tinderbox", quantity = 1, weight = 1.0, value = 0.5,
                tags = listOf("adventuring gear", "mundane"), equipped = false,
                parentId = carriedFolderId, order = 112,
            ),
            // The load-bearing `equipped` disjunct: a hand-made item with no taxonomy at all,
            // already worn. Without the disjunct, equipping it would be a one-way door.
            item(
                id = handmadeEquippedId, name = "A Small Knife", quantity = 1, weight = 0.5, value = null,
                tags = emptyList(), equipped = true,
                parentId = equipmentFolderId, order = 113,
            ),
            // The rule's known residual false negative — the same item, taken off. It loses
            // its control until the player uses 11 decision 2's per-item override.
            item(
                id = handmadeStowedId, name = "A Quill", quantity = 1, weight = 0.0, value = null,
                tags = emptyList(), equipped = false,
                parentId = carriedFolderId, order = 114,
            ),
            item(
                id = potionId, name = "Potion of Healing", quantity = 3, weight = 0.5, value = 50.0,
                tags = listOf("potion", "magic", "common"), equipped = false,
                parentId = carriedFolderId, order = 115,
                description = "Drink as an action to regain 2d4 + 2 hit points.",
            ),
            // MED-4 (11 decision 7): NO `quantity` field. Both engines must read 1.
            item(
                id = singletonId, name = "Signet Ring", quantity = null, weight = 0.0, value = 5.0,
                tags = emptyList(), equipped = false,
                parentId = carriedFolderId, order = 116,
            ),
            // Soft-removed: still streamed by the server, dropped by everything that lists or
            // sums. Its 100 lb would be visible in the carried weight if the filter were missed.
            item(
                id = removedItemId, name = "Anvil", quantity = 1, weight = 100.0, value = 10.0,
                tags = emptyList(), equipped = false,
                parentId = carriedFolderId, order = 117, removed = true,
            ),

            container(
                id = backpackId, name = "Backpack", weight = 5.0, value = 2.0,
                parentId = carriedFolderId, order = 120,
                contentsWeight = 10.0, contentsValue = 1.0,
            ),
            item(
                id = ropeInBackpackId, name = "Rope, Hempen (50 feet)", quantity = 1,
                weight = 10.0, value = 1.0, tags = listOf("adventuring gear", "mundane"),
                equipped = false, parentId = backpackId, order = 121,
            ),

            // 10 decision 5: coins are ordinary items distinguished only by a tag. Copper is
            // deliberately absent — the wallet must still render a `cp` row, at zero, with a
            // null propertyId, because that row is what creates the item on first use.
            item(
                id = platinumId, name = "Platinum piece", quantity = 2, weight = 0.02, value = 10.0,
                tags = listOf("platinum"), equipped = false, parentId = carriedFolderId, order = 130,
            ),
            item(
                id = goldId, name = "Gold piece", quantity = 15, weight = 0.02, value = 1.0,
                tags = listOf("gold"), equipped = false, parentId = carriedFolderId, order = 131,
            ),
            item(
                id = silverId, name = "Silver piece", quantity = 3, weight = 0.02, value = 0.1,
                tags = listOf("silver"), equipped = false, parentId = carriedFolderId, order = 132,
            ),
        ),
    )

    // ----------------------------------------------------------------- builders

    fun creature(): JsonObject = buildJsonObject {
        put("_id", creatureId)
        put("name", "Contract Dummy")
        put("owner", userId)
        put("alignment", "True Neutral")
    }

    /**
     * @param explicitNullReset writes `"reset": null` rather than omitting the key. Both
     *   shapes mean "no reset rule" and both must exclude a death save; see the fixture.
     */
    @Suppress("LongParameterList")
    fun attribute(
        id: String,
        name: String,
        attributeType: String,
        total: Int,
        damage: Int,
        order: Int,
        reset: String? = null,
        spellSlotLevel: Int? = null,
        variableName: String? = null,
        removed: Boolean = false,
        inactive: Boolean = false,
        explicitNullReset: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "attribute")
        put("name", name)
        put("attributeType", attributeType)
        put("total", total)
        put("damage", damage)
        put("value", total - damage)
        put("order", order)
        if (reset != null) put("reset", reset) else if (explicitNullReset) put("reset", JsonNull)
        if (spellSlotLevel != null) put("spellSlotLevel", spellSlotLevel)
        if (variableName != null) put("variableName", variableName)
        if (removed) put("removed", true)
        if (inactive) put("inactive", true)
    }

    /** @param enabled `null` writes neither `enabled` nor `disabled` — a *computed* toggle. */
    fun toggle(id: String, name: String, enabled: Boolean?, order: Int): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "toggle")
        put("name", name)
        put("order", order)
        when (enabled) {
            true -> put("enabled", true)
            false -> put("disabled", true)
            null -> put("condition", "hitPoints.value <= hitPoints.total / 2")
        }
        if (enabled == false) put("inactive", true)
    }

    @Suppress("LongParameterList")
    fun item(
        id: String,
        name: String,
        quantity: Int?,
        weight: Double?,
        value: Double?,
        tags: List<String>,
        equipped: Boolean,
        parentId: String,
        order: Int,
        description: String? = null,
        removed: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "item")
        put("name", name)
        if (quantity != null) put("quantity", quantity)
        if (weight != null) put("weight", weight)
        if (value != null) put("value", value)
        put("equipped", equipped)
        put("order", order)
        if (tags.isNotEmpty()) put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        // The read side accepts both a bare string and the `{text: …}` wrapper; the wrapper
        // is what the server stores and what `creatureProperties.insert` demands on the way in.
        if (description != null) put("description", buildJsonObject { put("text", description) })
        put("parent", parentRef(parentId))
        if (removed) put("removed", true)
    }

    @Suppress("LongParameterList")
    fun container(
        id: String,
        name: String,
        weight: Double,
        value: Double,
        parentId: String,
        order: Int,
        contentsWeight: Double,
        contentsValue: Double,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "container")
        put("name", name)
        put("quantity", 1)
        put("weight", weight)
        put("value", value)
        put("order", order)
        // The server's own rollup over the subtree. Section headers prefer it; the grand
        // carried total deliberately does not (10 decision 8 as amended).
        put("contentsWeight", contentsWeight)
        put("contentsValue", contentsValue)
        put("parent", parentRef(parentId))
    }

    fun folder(
        id: String,
        name: String,
        tags: List<String>,
        parentId: String?,
        order: Int,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "folder")
        put("name", name)
        put("order", order)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        put("parent", parentRef(parentId ?: creatureId, if (parentId == null) "creatures" else "creatureProperties"))
    }

    fun parentRef(id: String, collection: String = "creatureProperties"): JsonObject = buildJsonObject {
        put("id", id)
        put("collection", collection)
    }

    /** The `GET /api/creature/:id` envelope — the shape `CreatureSheet.fromSnapshotJson` parses. */
    fun snapshotBody(creature: JsonObject, properties: List<JsonObject>): JsonObject = buildJsonObject {
        put("creatures", JsonArray(listOf(creature)))
        put("creatureProperties", JsonArray(properties))
        put("creatureVariables", JsonArray(emptyList()))
    }

    /** @see ContractFixtures — a seeded id from the production generator. */
    fun fakeId(label: String): String = MeteorId.random(17, Random(label.hashCode().toLong()))
}
