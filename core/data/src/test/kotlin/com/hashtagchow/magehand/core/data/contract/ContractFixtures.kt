package com.hashtagchow.magehand.core.data.contract

import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.ddp.MeteorId
import com.hashtagchow.magehand.core.model.DeathSaves
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
    val downedHitPointsId: String = fakeId("death-save-hit-points")
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

    val abilityIntelligenceId: String = fakeId("ability-intelligence")
    val abilityStrengthCheckId: String = fakeId("ability-strength-check")
    val abilityNoModifierId: String = fakeId("ability-no-modifier")
    val skillArcanaId: String = fakeId("skill-arcana")
    val skillStealthAdvantageId: String = fakeId("skill-stealth-advantage")
    val saveDexterityId: String = fakeId("save-dexterity")
    val saveWisdomDisadvantageId: String = fakeId("save-wisdom-disadvantage")
    val checkInitiativeId: String = fakeId("check-initiative")
    val skillLanguageId: String = fakeId("skill-language-common")
    val skillWeaponProficiencyId: String = fakeId("skill-weapon-proficiency")
    val skillArmorProficiencyId: String = fakeId("skill-armor-proficiency")
    val skillInactiveDeathSaveId: String = fakeId("skill-death-save-inactive")
    val skillRemovedId: String = fakeId("skill-removed")
    val skillNamelessId: String = fakeId("skill-nameless")

    // FR-28 (docs/design/17-use-action.md decision 9). Ids only: the two Use vectors record a
    // `method` frame, and a frame carries an id and a slot id and nothing else — no fixture
    // sheet is needed to build one, unlike the insert/move vectors which resolve a parent.
    val rageActionId: String = fakeId("action-rage")
    val fireballSpellId: String = fakeId("spell-fireball")

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
            // The death-save pair (FR-23), and the two facts about it that pull in opposite
            // directions:
            //
            //  - It is EXCLUDED from the slot list. These rows carry `reset == null` — one as an
            //    explicit JSON null, one as an omitted key, because both shapes occur and a
            //    consumer reading only one would ship the bug.
            //  - It is DISCOVERED as death saves, by `variableName` and nothing else (decision
            //    19). Schema 4's fixture omitted `variableName` entirely, which quietly encoded
            //    the retired reading: it modelled a pair that MageHand's own engine would not
            //    recognise, and the export then had no way to state what does recognise it.
            //
            // `reset == null` doing both jobs on one property is exactly the coincidence the
            // probe retired — the exclusion is real, its old explanation was not.
            //
            // Storage is INVERTED (decision 19): `value` is the MARK count and `damage` is
            // `MAX − value`, so `damage` is written as `MAX − marks` here and the shared
            // builder's `value = total − damage` lands on the marks.
            attribute(
                id = deathSaveSuccessId, name = "Succeeded Saves", attributeType = "spellSlot",
                variableName = TrackerEngine.VAR_DEATH_SAVE_SUCCESSES,
                total = DeathSaves.MAX, damage = DeathSaves.MAX - 1, reset = null,
                spellSlotLevel = null, order = 13, explicitNullReset = true,
            ),
            attribute(
                id = deathSaveFailureId, name = "Failed Saves", attributeType = "spellSlot",
                variableName = TrackerEngine.VAR_DEATH_SAVE_FAILURES,
                total = DeathSaves.MAX, damage = DeathSaves.MAX - 2, reset = null,
                spellSlotLevel = null, order = 14,
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
     * FR-23's death-save trigger, as a sheet that can be moved through all three cases.
     *
     * The block's condition has two halves and they fail in different places, so one fixture is
     * not enough to prove either: discovery finds the pair (or does not), and the HP row reads
     * zero (or does not). Three sheets built from this one function cover the cross-product that
     * matters — downed with the pair, up with the pair, downed without it — and the ONLY thing
     * that differs between the first two is `hitPointsValue`, which is what makes the vector a
     * statement about the trigger rather than about two unrelated sheets.
     *
     * Deliberately minimal otherwise. A tracker fixture full of slots and toggles would prove
     * the same thing while burying it.
     *
     * @param hitPointsValue what the `hitPoints` row reads. `0` is the downed case.
     * @param withPair `false` omits BOTH halves — the Dummy's shape, and decision 18's
     *   *"no pair, no block, no error"*.
     * @param successes filled success pips, stored inverted; see [deathSave].
     */
    fun deathSaveSheetBody(
        hitPointsValue: Int,
        withPair: Boolean,
        successes: Int = 1,
        failures: Int = 2,
    ): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOfNotNull(
            attribute(
                id = downedHitPointsId, name = "Hit Points", attributeType = "healthBar",
                variableName = TrackerEngine.VAR_HIT_POINTS,
                total = DOWNED_HP_TOTAL, damage = DOWNED_HP_TOTAL - hitPointsValue, order = 1,
            ),
            if (withPair) {
                deathSave(deathSaveSuccessId, TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, successes, order = 2)
            } else {
                null
            },
            if (withPair) {
                deathSave(deathSaveFailureId, TrackerEngine.VAR_DEATH_SAVE_FAILURES, failures, order = 3)
            } else {
                null
            },
        ),
    )

    private const val DOWNED_HP_TOTAL = 24

    /**
     * One half of the death-save pair, in the inverted storage decision 19 records.
     *
     * `value` is the **mark count** and `damage` is `MAX − value`. Both are written, because the
     * identity is the thing a consumer has to believe: production reads `value` when the server
     * published it and falls back to `total − damage`, and a fixture that stated only one of them
     * would let a client that reads the wrong field pass.
     *
     * `attributeType` is `spellSlot` because that is what the probe's sheets carry — and it is
     * deliberately **not** what discovery keys on. See `TrackerEngine.deathSaves`.
     */
    fun deathSave(id: String, variableName: String, marks: Int, order: Int): JsonObject = attribute(
        id = id, name = variableName, attributeType = "spellSlot", variableName = variableName,
        total = DeathSaves.MAX, damage = DeathSaves.MAX - marks, order = order,
    )

    /**
     * Roll discovery (FR-7) — ability checks, saves, skills, and the advantage rollup.
     *
     * Written from the shapes re-confirmed live on 2026-08-24
     * (docs/verification/probe-p5-rolls.md), and synthetic like everything else here: the
     * numbers are chosen to make each rule *falsifiable*, not to resemble any sheet.
     *
     * Three of those choices carry the whole vector:
     *
     *  - **An ability's [FIELD_MODIFIER][com.hashtagchow.magehand.core.data.tracker.TrackerEngine.FIELD_MODIFIER]
     *    and its score disagree by more than the 5e formula.** Intelligence is scored 14 with
     *    a modifier of 3, which `floor((14 − 10) / 2)` does not produce. A consumer that
     *    re-derives the modifier from the score gets 2 and fails the vector — which is the
     *    point: the server has already folded in whatever a feature contributed, and the
     *    export exists to stop a second client re-implementing that arithmetic badly.
     *  - **A skill's `value` is not `abilityMod + proficiency`.** Arcana reads 7 over
     *    ingredients that sum to 6, for the same reason.
     *  - **The advantage rollup is read for its SIGN, never for `== 1`.** Stealth carries
     *    `advantage: 2` and the Wisdom save carries `advantage: -3`; a magic-constant
     *    comparison answers NONE for both.
     */
    fun rollsSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            // Ability checks: `type: attribute`, `attributeType: ability`, read off `modifier`.
            abilityScore(
                id = abilityIntelligenceId, name = "Intelligence", variableName = "intelligence",
                score = 14, modifier = 3, advantage = 0, order = 10,
            ),
            // The sign convention: a modifier is a signed whole number and may be negative.
            abilityScore(
                id = abilityStrengthCheckId, name = "Strength", variableName = "strength",
                score = 8, modifier = -1, advantage = null, order = 11,
            ),
            // No `modifier` key at all: SKIPPED, never back-derived from the score. An
            // attribute that does not say what it adds is not a roll this app can answer.
            abilityScore(
                id = abilityNoModifierId, name = "Constitution", variableName = "constitution",
                score = 12, modifier = null, advantage = null, order = 12,
            ),

            // Skills / saves / checks: one `type: skill` property class, sorted by `skillType`.
            skill(
                id = skillArcanaId, name = "Arcana", skillType = "skill", variableName = "arcana",
                value = 7, abilityMod = 2, proficiency = 4, ability = "intelligence", order = 20,
            ),
            skill(
                id = skillStealthAdvantageId, name = "Stealth", skillType = "skill",
                variableName = "stealth", value = 3, abilityMod = 3, proficiency = 0,
                ability = "dexterity", advantage = 2, order = 21,
            ),
            skill(
                id = saveDexterityId, name = "Dexterity Save", skillType = "save",
                variableName = "dexteritySave", value = 5, abilityMod = 3, proficiency = 2,
                ability = "dexterity", advantage = 0, order = 22,
            ),
            skill(
                id = saveWisdomDisadvantageId, name = "Wisdom Save", skillType = "save",
                variableName = "wisdomSave", value = -1, abilityMod = -1, proficiency = 0,
                ability = "wisdom", advantage = -3, order = 23,
            ),
            // `check` is a rollable kind and must NOT be filtered out — the exclusion list is
            // an exclusion list precisely so an unfamiliar kind surfaces rather than vanishes.
            skill(
                id = checkInitiativeId, name = "Initiative", skillType = "check",
                variableName = "initiative", value = 3, abilityMod = 3, proficiency = 0,
                ability = "dexterity", order = 24,
            ),

            // The three excluded `skillType`s: a proficiency you hold, not a roll you make.
            // Each carries the same `value` field (the proficiency bonus) purely because the
            // property type has one, which is why a client keyed on `type: skill` alone puts
            // "make a Common check" in the dropdown.
            skill(
                id = skillLanguageId, name = "Common", skillType = "language",
                variableName = "commonLanguage", value = 3, abilityMod = 0, proficiency = 3,
                ability = null, order = 30,
            ),
            skill(
                id = skillWeaponProficiencyId, name = "Simple Melee Weapons", skillType = "weapon",
                variableName = "simpleMeleeWeapon", value = 3, abilityMod = 0, proficiency = 3,
                ability = null, order = 31,
            ),
            skill(
                id = skillArmorProficiencyId, name = "Light Armor", skillType = "armor",
                variableName = "lightArmor", value = 3, abilityMod = 0, proficiency = 3,
                ability = null, order = 32,
            ),

            // The blanket skip, on both of its fields.
            skill(
                id = skillInactiveDeathSaveId, name = "Death Save", skillType = "save",
                variableName = "deathSave", value = 0, abilityMod = 0, proficiency = 0,
                ability = null, order = 33, inactive = true,
            ),
            skill(
                id = skillRemovedId, name = "Perception", skillType = "skill",
                variableName = "perception", value = 4, abilityMod = 1, proficiency = 3,
                ability = "wisdom", order = 34, removed = true,
            ),
            // Nameless: the dropdown IS a list of names, so a blank one is an un-pickable row.
            skill(
                id = skillNamelessId, name = "", skillType = "skill",
                variableName = "unnamed", value = 2, abilityMod = 2, proficiency = 0,
                ability = "charisma", order = 35,
            ),
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

    /**
     * One of the six ability scores, in the shape a live sheet publishes it.
     *
     * @param modifier `null` omits the key entirely — the "does not say what it adds" case.
     * @param advantage `null` omits the key. Absent and `0` are the same answer, and the
     *   fixture carries both so a consumer cannot pass by handling only the shape it saw.
     */
    @Suppress("LongParameterList")
    fun abilityScore(
        id: String,
        name: String,
        variableName: String,
        score: Int,
        modifier: Int?,
        advantage: Int?,
        order: Int,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "attribute")
        put("attributeType", "ability")
        put("name", name)
        put("variableName", variableName)
        // `value` and `total` are the SCORE, not the modifier. Adding either to a d20 is the
        // off-by-about-ten a client makes when it reads the field whose name it recognises.
        put("value", score)
        put("total", score)
        if (modifier != null) put("modifier", modifier)
        if (advantage != null) put("advantage", advantage)
        put("order", order)
    }

    /**
     * A `type: "skill"` property — the one class covering skills, saves, checks, tools and
     * the weapon/armor/language proficiencies that are not rolls at all.
     *
     * `abilityMod` and `proficiency` are written because a real sheet carries them, and the
     * fixture deliberately makes them disagree with `value`; see [rollsSheetBody].
     */
    @Suppress("LongParameterList")
    fun skill(
        id: String,
        name: String,
        skillType: String,
        variableName: String,
        value: Int,
        abilityMod: Int,
        proficiency: Int,
        ability: String?,
        order: Int,
        advantage: Int? = null,
        removed: Boolean = false,
        inactive: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "skill")
        put("skillType", skillType)
        put("name", name)
        put("variableName", variableName)
        put("value", value)
        put("abilityMod", abilityMod)
        put("proficiency", proficiency)
        if (ability != null) put("ability", ability) else put("ability", JsonNull)
        if (advantage != null) put("advantage", advantage)
        put("order", order)
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
