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
    // FR-50 R1: armour class, discovered by `variableName == "armor"` exactly as HP is. Folded
    // into `trackerSheetBody` rather than given its own sheet — unlike hit dice, this is not a
    // new discriminator a consumer could miss wholesale, it is one more `variableName` beside the
    // three the tracker vector already carries, and the vector's value is that the whole board
    // comes out of one input.
    val armorClassId: String = fakeId("armor-class")
    // M6: FR-30's own row, exercised as its own vector rather than folded into
    // `trackerSheetBody` — see `hitDiceSheetBody`.
    val hitDiceD8Id: String = fakeId("hit-dice-d8")
    // FR-44 R1: the limited-use rows, exercised as their own vector for `hitDiceSheetBody`'s
    // reason — see `limitedUseSheetBody`.
    val limitedUseActionId: String = fakeId("limited-use-action")
    val limitedUseSpellId: String = fakeId("limited-use-spell")
    val limitedUseUnusedId: String = fakeId("limited-use-unused")
    val limitedUseInactiveId: String = fakeId("limited-use-inactive")
    val limitedUseRemovedId: String = fakeId("limited-use-removed")
    val limitedUseUnlimitedId: String = fakeId("limited-use-unlimited")
    val limitedUseZeroId: String = fakeId("limited-use-zero")
    // FR-53 R1: the buff population, exercised as its own vector for `hitDiceSheetBody`'s reason
    // and one stronger — the three documents differ from each other by two boolean fields, so a
    // consumer that skipped the filter would still produce a plausible-looking board.
    val appliedBuffId: String = fakeId("buff-applied")
    val templateBuffId: String = fakeId("buff-template")
    val removedBuffId: String = fakeId("buff-removed")
    val buffOwnerActionId: String = fakeId("buff-owner-action")
    // FR-59 stage 1 (22 R7): one resource row per arm of the `charge-consumers` vector, so every
    // arm's answer is observable on its own row — see `chargeConsumerSheetBody`.
    val consumerDirectResourceId: String = fakeId("consumer-resource-direct")
    val consumerDirectActionId: String = fakeId("consumer-action-direct")
    val consumerDirectBuffId: String = fakeId("consumer-buff-direct")
    val consumerTriggerResourceId: String = fakeId("consumer-resource-trigger")
    val consumerTriggerActionId: String = fakeId("consumer-action-trigger")
    val consumerTriggerId: String = fakeId("consumer-trigger")
    val consumerTriggerBuffId: String = fakeId("consumer-buff-trigger")
    val consumerSpellTypedTriggerId: String = fakeId("consumer-trigger-spell-typed")
    val consumerStrippedResourceId: String = fakeId("consumer-resource-stripped")
    val consumerStrippedActionId: String = fakeId("consumer-action-stripped")
    val consumerStrippedTriggerId: String = fakeId("consumer-trigger-stripped")
    val consumerSubsetResourceId: String = fakeId("consumer-resource-subset")
    val consumerSubsetActionId: String = fakeId("consumer-action-subset")
    val consumerInactiveResourceId: String = fakeId("consumer-resource-inactive")
    val consumerInactiveActionId: String = fakeId("consumer-action-inactive")
    val consumerRemovedResourceId: String = fakeId("consumer-resource-removed")
    val consumerRemovedActionId: String = fakeId("consumer-action-removed")
    val consumerSpellResourceId: String = fakeId("consumer-resource-spell")
    val consumerSpellId: String = fakeId("consumer-spell")
    val consumerTwofoldResourceId: String = fakeId("consumer-resource-twofold")
    val consumerTwofoldActionId: String = fakeId("consumer-action-twofold")
    val consumerTargetedResourceId: String = fakeId("consumer-resource-targeted")
    val consumerTargetedActionId: String = fakeId("consumer-action-targeted")
    val consumerZeroResourceId: String = fakeId("consumer-resource-zero")
    val consumerZeroActionId: String = fakeId("consumer-action-zero")
    val consumerSharedResourceId: String = fakeId("consumer-resource-shared")
    val consumerSharedFirstActionId: String = fakeId("consumer-action-shared-first")
    val consumerSharedSecondActionId: String = fakeId("consumer-action-shared-second")

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
            // FR-50 R1: armour class. `attributeType: "stat"` on purpose — it is what the live
            // sheet publishes, no other rule in the engine reads a `stat` attribute, and a
            // consumer that keyed on the sub-type rather than on `variableName` would pass every
            // other assertion in this vector and find no AC on any real character.
            attribute(
                id = armorClassId, name = "Armor Class", attributeType = "stat",
                variableName = TrackerEngine.VAR_ARMOR, total = 14, damage = 0, order = 3,
            ),
            // Rule 5: toggles. `enabled`/`disabled` is what makes one flippable — a toggle
            // carrying neither is computed and the server refuses to flip it.
            toggle(id = rageToggleId, name = "Rage", enabled = true, order = 30),
            toggle(id = concentrationToggleId, name = "Concentration: Bless", enabled = true, order = 31),
            toggle(id = computedToggleId, name = "Bloodied", enabled = null, order = 32),
        ),
    )

    /**
     * M6: FR-30's own row (docs/design/18-table-pack.md decision 17), run through the
     * production engine like every other vector — never hand-computed.
     *
     * A one-property sheet rather than a row folded into [trackerSheetBody]: hit dice are their
     * own [TrackerEngine.ATTR_HIT_DICE] discriminator over `spellSlot`/`resource`, which is
     * exactly the six-release miss probe H4 found (`domain/rules.json#discovery.hitDice
     * .discoveryTrap`), so the vector that proves it is discovered stands on its own rather
     * than riding in on a fixture built to exercise five *other* rules.
     */
    fun hitDiceSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            attribute(
                id = hitDiceD8Id, name = "Hit Dice", attributeType = TrackerEngine.ATTR_HIT_DICE,
                total = 4, damage = 1, order = 10, hitDiceSize = "d8", constitutionMod = 3,
            ),
        ),
    )

    /**
     * FR-44 R1's rows, as a sheet where **every wrong implementation produces a different
     * answer** — which is what `rolls-discovery` established as the bar for a discovery fixture.
     *
     * Seven properties, three of which must survive and four of which must not, and the four
     * exclusions are in the input on purpose: a vector carrying only the survivors proves nothing
     * about the filter (`tracker-discovery`'s own wording).
     *
     *  - an `action` with `uses: 2, usesUsed: 1, reset: shortRest` — the arithmetic case;
     *  - a `spell` with `uses: 2, usesUsed: 0, reset: longRest` — because the headline row this
     *    FR was raised for (Guiding Bolt from a Star Map) is a **spell**, and a consumer matching
     *    `action` alone would pass every other assertion here;
     *  - an `action` with `uses: 3` and **no `usesUsed` key at all** — the never-used case, which
     *    must read 3 rather than being treated as unknown;
     *  - an `inactive` one, a `removed` one, an `action` with no `uses` at all, and one whose
     *    `uses` computes to **zero**, none of which may appear.
     *
     * `uses` is written as the `_calculation` wrapper the server actually publishes rather than a
     * bare number — the live party sheets read `uses: {calculation:"proficiencyBonus", …, value:2}`
     * — so a consumer that reads the field literally fails this vector instead of failing at a
     * table.
     */
    fun limitedUseSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            limitedUse(
                id = limitedUseActionId, type = "action", name = "Second Wind",
                uses = 2, usesUsed = 1, reset = "shortRest", order = 10,
            ),
            limitedUse(
                id = limitedUseSpellId, type = "spell", name = "Guiding Bolt (Star Map)",
                uses = 2, usesUsed = 0, reset = "longRest", order = 11,
            ),
            limitedUse(
                id = limitedUseUnusedId, type = "action", name = "Relentless Endurance",
                uses = 3, usesUsed = null, reset = "longRest", order = 12,
            ),
            limitedUse(
                id = limitedUseInactiveId, type = "action", name = "Cosmic Omen",
                uses = 1, usesUsed = null, reset = "longRest", order = 13, inactive = true,
            ),
            limitedUse(
                id = limitedUseRemovedId, type = "spell", name = "Deleted Trick",
                uses = 1, usesUsed = null, reset = "longRest", order = 14, removed = true,
            ),
            limitedUse(
                id = limitedUseUnlimitedId, type = "action", name = "Dash",
                uses = null, usesUsed = null, reset = null, order = 15,
            ),
            // `uses` present and computing to ZERO. Not a contrived case: `uses` is a calculation
            // (`proficiencyBonus`, `max(1, wisdom.modifier)` on the reference party's sheets), so
            // a character below the level that grants an ability publishes a real row whose count
            // evaluates to nothing. A consumer that keyed on the field's *presence* rather than on
            // its value draws a pip row with no pips.
            limitedUse(
                id = limitedUseZeroId, type = "action", name = "Not Yet Granted",
                uses = 0, usesUsed = null, reset = "longRest", order = 16,
            ),
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

    /**
     * The deliberately-wrong `usesLeft` every limited-use fixture row publishes.
     *
     * Not a near-miss: a consumer that reads this field renders "99 uses left" on a two-use
     * ability, which fails loudly instead of looking almost right. `LimitedUseDiscoveryTest` uses
     * the same number for the same reason, and the two are independent on purpose — one pins the
     * engine, the other pins the exported vector.
     */
    const val LIMITED_USE_STALE_USES_LEFT: Int = 99

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
        /**
         * M6: `hitDiceSize` — FR-30's die, `"d8"` on the live sheet.
         * [TrackerEngine.ATTR_HIT_DICE] rows only.
         */
        hitDiceSize: String? = null,
        /**
         * FR-51 R1: `constitutionMod` — the modifier the server has already added up for this
         * die. [TrackerEngine.ATTR_HIT_DICE] rows only, and present on every one the live sheets
         * publish.
         */
        constitutionMod: Int? = null,
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
        if (hitDiceSize != null) put("hitDiceSize", hitDiceSize)
        if (constitutionMod != null) put("constitutionMod", constitutionMod)
    }

    /**
     * An `action` or `spell` property carrying a use count (FR-44 R1).
     *
     * @param uses `null` omits the key — the unlimited ability, which is not a tracker row at all.
     *   Written as the `_calculation` wrapper the server publishes, never as a bare number.
     * @param usesUsed `null` omits the key, which is what a never-used ability looks like on a
     *   live sheet and must read as zero.
     */
    @Suppress("LongParameterList")
    fun limitedUse(
        id: String,
        type: String,
        name: String,
        uses: Int?,
        usesUsed: Int?,
        reset: String?,
        order: Int,
        removed: Boolean = false,
        inactive: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", type)
        put("name", name)
        put("actionType", "action")
        if (uses != null) {
            put(
                TrackerEngine.FIELD_USES,
                buildJsonObject {
                    put("type", "_calculation")
                    put("calculation", uses.toString())
                    put("value", uses)
                },
            )
        }
        if (usesUsed != null) put(TrackerEngine.FIELD_USES_USED, usesUsed)
        // The server's own derived field, published beside the two above and DELIBERATELY not
        // read by MageHand: 17 decision 1 records that it lags a debounced recompute by 4-10 s
        // (probe U5), and FR-44's own re-probe caught it in the act — one frame after a write set
        // `usesUsed: 1` on a 2-use row, the same document still published `usesLeft: 2`.
        //
        // **So the fixture publishes a STALE value, not the correct one.** It used to publish
        // `uses − usesUsed`, which made the trap unenforceable: a consumer that read the easy
        // field got the right answer from the vector and the wrong one from a server. The vector
        // now disagrees with the arithmetic on every row, so reading `usesLeft` fails it — which
        // is what `domain/rules.json#discovery.limitedUses.usesLeftTrap` has been claiming.
        // [LIMITED_USE_STALE_USES_LEFT] rather than a plausible-looking number, so nobody reads it
        // as a value the rule might accept.
        if (uses != null) put("usesLeft", LIMITED_USE_STALE_USES_LEFT)
        if (reset != null) put("reset", reset)
        put("order", order)
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
    /**
     * FR-53 R1's three populations, on one sheet, of which **one** survives discovery.
     *
     * The bar `rolls-discovery` set — *"every wrong implementation produces a different answer"* —
     * is harder to clear here than anywhere else, because the applied buff and its template are
     * the **same document** apart from two boolean fields. So all three are present and the
     * excluded two are in the input on purpose:
     *
     *  - the **applied** copy at the creature root, `silent: true` and `target: "self"` exactly as
     *    the probed one is, with a description carrying an inline calculation (so the vector
     *    exercises `#text.description` at the same time);
     *  - the **template** under the action that applies it, carrying `deactivatedByAncestor` and
     *    the `inactive` the server derives from it — a consumer matching `type == 'buff'` alone
     *    puts a spell the character has not cast onto their tracker;
     *  - a **removed** copy, which the server still delivers.
     *
     * The `silent` flag is on the survivor deliberately: a consumer that read it as "hidden" would
     * produce an empty array here and would look correct on a sheet that had none.
     */
    fun buffSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            buildJsonObject {
                put("_id", buffOwnerActionId)
                put("type", "action")
                put("name", "Shield")
                put("actionType", "action")
                put("order", 5)
            },
            buff(
                id = appliedBuffId,
                name = "Shield",
                order = 10,
                parentId = creatureId,
                parentCollection = "creatures",
                silent = true,
                descriptionSource = "+{5} bonus to AC until the start of your next turn.",
                descriptionRendered = "**+5** bonus to AC until the start of your next turn.",
            ),
            buff(
                id = templateBuffId,
                name = "Shield",
                order = 11,
                parentId = buffOwnerActionId,
                parentCollection = "creatureProperties",
                inactive = true,
                silent = true,
                descriptionSource = "+{5} bonus to AC until the start of your next turn.",
                descriptionRendered = "**+5** bonus to AC until the start of your next turn.",
            ),
            buff(
                id = removedBuffId,
                name = "Bless",
                order = 12,
                parentId = creatureId,
                parentCollection = "creatures",
                removed = true,
            ),
        ),
    )

    /**
     * One `buff` property in the shape the 2026-09-14 probe recorded.
     *
     * `duration` is written on every one of them **as the parse error the live sheet carries**,
     * which is the point: the reference client does not read the field, and a consumer that does
     * finds an error object rather than a countdown.
     */
    @Suppress("LongParameterList")
    fun buff(
        id: String,
        name: String,
        order: Int,
        parentId: String,
        parentCollection: String,
        inactive: Boolean = false,
        removed: Boolean = false,
        silent: Boolean = false,
        descriptionSource: String? = null,
        descriptionRendered: String? = null,
        /** FR-59 R2: only `"self"` buffs make a charge consumer; the default is every probed buff's. */
        target: String = "self",
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "buff")
        put("name", name)
        put("order", order)
        put("target", target)
        put("parent", buildJsonObject { put("id", parentId); put("collection", parentCollection) })
        if (silent) put("silent", true)
        if (inactive) {
            put("deactivatedByAncestor", true)
            put("inactive", true)
        }
        if (removed) put("removed", true)
        if (descriptionSource != null || descriptionRendered != null) {
            put(
                "description",
                buildJsonObject {
                    if (descriptionSource != null) put("text", descriptionSource)
                    if (descriptionRendered != null) put("value", descriptionRendered)
                },
            )
        }
        put(
            "duration",
            buildJsonObject {
                put("type", "_calculation")
                put("calculation", "1 Turn")
                put(
                    "errors",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("type", "error")
                                put("message", "Expected an expression")
                            },
                        ),
                    ),
                )
            },
        )
    }

    /**
     * FR-59 stage 1's discovery rule (22 R1/R2) — one `resource` row per arm, each consumed by
     * the property that arm is about, so the answer for every arm is the `consumers` array on its
     * own row and no arm can mask another.
     *
     * The rows that get NO consumers are the interesting half, and each is a near-miss of a row
     * that does: the stripped trigger is the trigger arm minus one field; the subset arm is the
     * trigger arm with one extra target tag; the inactive and removed arms are the direct arm with
     * one flag; the targeted arm is the direct arm with `target` changed; the zero arm is the direct
     * arm at quantity 0. A consumer that skipped any one rule would put a consumer on exactly one
     * row that should have none.
     *
     * Every buff under an action or a trigger is written as the TEMPLATE the library ships —
     * `deactivatedByAncestor` and `inactive` set — because that is what a buff a use *would* apply
     * looks like on a sheet; R2 says the flag is expected there and is not a filter.
     *
     * The trigger arm also carries a second complete trigger on the same tag whose
     * `actionPropertyType` is `spell`: it fires for spells, not for this action, so the action's
     * `applies` stays a single name. A reader that skipped the type comparison would see two buffs
     * and print none.
     */
    fun chargeConsumerSheetBody(): JsonObject = snapshotBody(
        creature = creature(),
        properties = listOf(
            // (1) a buff that is a direct descendant of the action — through a folder.
            consumerResource(consumerDirectResourceId, "Direct Charges", "directCharges", order = 1),
            consumerAction(consumerDirectActionId, "action", "Direct Form", consumes = "directCharges", order = 10),
            folder(id = fakeId("consumer-folder-direct"), name = "Effects", tags = emptyList(), parentId = consumerDirectActionId, order = 11),
            buff(
                id = consumerDirectBuffId, name = "An applied direct buff", order = 12,
                parentId = fakeId("consumer-folder-direct"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (2) a buff held by a live trigger carrying all three fields, tag-matched to the action.
            consumerResource(consumerTriggerResourceId, "Trigger Charges", "triggerCharges", order = 2),
            consumerAction(
                consumerTriggerActionId, "action", "Trigger Form", consumes = "triggerCharges", order = 20,
                tags = listOf("triggerForm"),
            ),
            consumerTrigger(consumerTriggerId, "Form Trigger", targetTags = listOf("triggerForm"), order = 21),
            buff(
                id = consumerTriggerBuffId, name = "An applied form buff", order = 22,
                parentId = consumerTriggerId, parentCollection = "creatureProperties", inactive = true,
            ),
            consumerTrigger(
                consumerSpellTypedTriggerId, "Spell-Only Trigger", targetTags = listOf("triggerForm"), order = 23,
                actionPropertyType = "spell",
            ),
            buff(
                id = fakeId("consumer-buff-spell-typed"), name = "A buff for spells only", order = 24,
                parentId = consumerSpellTypedTriggerId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (3) the same trigger with `actionPropertyType` stripped — the imported library's
            // shape (BUG-27). The server never files it, so it is not a consumer on that basis.
            consumerResource(consumerStrippedResourceId, "Stripped Charges", "strippedCharges", order = 3),
            consumerAction(
                consumerStrippedActionId, "action", "Stripped Form", consumes = "strippedCharges", order = 30,
                tags = listOf("strippedForm"),
            ),
            consumerTrigger(
                consumerStrippedTriggerId, "Stripped Trigger", targetTags = listOf("strippedForm"), order = 31,
                actionPropertyType = null,
            ),
            buff(
                id = fakeId("consumer-buff-stripped"), name = "A buff behind a stripped trigger", order = 32,
                parentId = consumerStrippedTriggerId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (4) a complete trigger whose targetTags are NOT a subset of the action's tags.
            consumerResource(consumerSubsetResourceId, "Subset Charges", "subsetCharges", order = 4),
            consumerAction(
                consumerSubsetActionId, "action", "Subset Form", consumes = "subsetCharges", order = 40,
                tags = listOf("subsetForm"),
            ),
            consumerTrigger(
                fakeId("consumer-trigger-subset"), "Wider Trigger", targetTags = listOf("subsetForm", "otherTag"),
                order = 41,
            ),
            buff(
                id = fakeId("consumer-buff-subset"), name = "A buff behind a wider trigger", order = 42,
                parentId = fakeId("consumer-trigger-subset"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (5) an inactive action with a direct-child self buff.
            consumerResource(consumerInactiveResourceId, "Inactive Charges", "inactiveCharges", order = 5),
            consumerAction(
                consumerInactiveActionId, "action", "Switched-Off Form", consumes = "inactiveCharges", order = 50,
                inactive = true,
            ),
            buff(
                id = fakeId("consumer-buff-inactive"), name = "A buff under a switched-off action", order = 51,
                parentId = consumerInactiveActionId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (6) a removed action with a direct-child self buff (delivered: the server still sends it).
            consumerResource(consumerRemovedResourceId, "Removed Charges", "removedCharges", order = 6),
            consumerAction(
                consumerRemovedActionId, "action", "Removed Form", consumes = "removedCharges", order = 60,
                removed = true,
            ),
            buff(
                id = fakeId("consumer-buff-removed"), name = "A buff under a removed action", order = 61,
                parentId = consumerRemovedActionId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (7) a SPELL consumer.
            consumerResource(consumerSpellResourceId, "Spell Charges", "spellCharges", order = 7),
            consumerAction(consumerSpellId, "spell", "Companion Spell", consumes = "spellCharges", order = 70),
            buff(
                id = fakeId("consumer-buff-spell"), name = "A companion buff", order = 71,
                parentId = consumerSpellId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (8) two qualifying buffs on one action — a consumer, with `applies: null`.
            consumerResource(consumerTwofoldResourceId, "Twofold Charges", "twofoldCharges", order = 8),
            consumerAction(consumerTwofoldActionId, "action", "Twofold Form", consumes = "twofoldCharges", order = 80),
            buff(
                id = fakeId("consumer-buff-twofold-a"), name = "A first buff", order = 81,
                parentId = consumerTwofoldActionId, parentCollection = "creatureProperties", inactive = true,
            ),
            buff(
                id = fakeId("consumer-buff-twofold-b"), name = "A second buff", order = 82,
                parentId = consumerTwofoldActionId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (9) a consumer whose only buff targets someone else — no self buff, not a consumer.
            consumerResource(consumerTargetedResourceId, "Targeted Charges", "targetedCharges", order = 9),
            consumerAction(consumerTargetedActionId, "action", "Targeted Form", consumes = "targetedCharges", order = 90),
            buff(
                id = fakeId("consumer-buff-targeted"), name = "A buff for the target", order = 91,
                parentId = consumerTargetedActionId, parentCollection = "creatureProperties", inactive = true,
                target = "target",
            ),

            // (10) a cost of quantity 0 — it names the resource and spends none of it.
            consumerResource(consumerZeroResourceId, "Zero Charges", "zeroCharges", order = 10),
            consumerAction(
                consumerZeroActionId, "action", "Free Form", consumes = "zeroCharges", order = 100, quantity = 0,
            ),
            buff(
                id = fakeId("consumer-buff-zero"), name = "A buff that costs nothing", order = 101,
                parentId = consumerZeroActionId, parentCollection = "creatureProperties", inactive = true,
            ),

            // (11) two consumers of one resource, declared in the opposite of their `order`.
            consumerResource(consumerSharedResourceId, "Shared Charges", "sharedCharges", order = 11),
            consumerAction(
                consumerSharedSecondActionId, "action", "Second Shared Form", consumes = "sharedCharges", order = 111,
            ),
            buff(
                id = fakeId("consumer-buff-shared-second"), name = "A second shared buff", order = 112,
                parentId = consumerSharedSecondActionId, parentCollection = "creatureProperties", inactive = true,
            ),
            consumerAction(
                consumerSharedFirstActionId, "action", "First Shared Form", consumes = "sharedCharges", order = 110,
            ),
            buff(
                id = fakeId("consumer-buff-shared-first"), name = "A first shared buff", order = 113,
                parentId = consumerSharedFirstActionId, parentCollection = "creatureProperties", inactive = true,
            ),

            // --- Addendum 2 (design 22): one arm per rule the export states. ---

            // (12-16) near-misses of the trigger arm, one field or flag each.
            *triggerArm("noevent", "No-Event", order = 120, event = null),
            *triggerArm("notiming", "No-Timing", order = 125, timing = null),
            *triggerArm("oddtiming", "Odd-Timing", order = 130, timing = "whenever"),
            *triggerArm("inactivetrigger", "Inactive-Trigger", order = 135, inactive = true),
            *triggerArm("emptytags", "Empty-Tags", order = 140, targetTags = emptyList()),
            // (17) an extraTags arm with operation NOT — the server may cancel the match.
            *triggerArm("nottags", "Not-Tags", order = 145, notExtraTags = listOf("otherTag")),
            // (18) a condition false at rest; (19) the same condition true at rest — counted.
            *triggerArm("falsecondition", "False-Condition", order = 150, condition = false),
            *triggerArm("truecondition", "True-Condition", order = 155, condition = true),
            // (20) timing `afterChildren` — one of the engine's own options, counted.
            *triggerArm("afterchildren", "After-Children", order = 160, timing = "afterChildren"),

            // (21) a buff behind a branch under the action — conditional, not promised.
            consumerResource(fakeId("consumer-resource-branch"), "Branch Charges", "branchCharges", order = 21),
            consumerAction(fakeId("consumer-action-branch"), "action", "Branch Form", consumes = "branchCharges", order = 170),
            consumerChild(fakeId("consumer-branch"), "branch", "On a hit", fakeId("consumer-action-branch"), order = 171),
            buff(
                id = fakeId("consumer-buff-branch"), name = "A buff behind a branch", order = 172,
                parentId = fakeId("consumer-branch"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (22) a buff under a CHILD trigger of the action — that trigger's own event fires it.
            consumerResource(fakeId("consumer-resource-childtrigger"), "Child-Trigger Charges", "childTriggerCharges", order = 22),
            consumerAction(
                fakeId("consumer-action-childtrigger"), "action", "Child-Trigger Form", consumes = "childTriggerCharges",
                order = 175,
            ),
            consumerTrigger(
                fakeId("consumer-trigger-child"), "On Rest", targetTags = emptyList(), order = 176,
                event = "anyRest", actionPropertyType = null, parentId = fakeId("consumer-action-childtrigger"),
            ),
            buff(
                id = fakeId("consumer-buff-childtrigger"), name = "A buff under a child trigger", order = 177,
                parentId = fakeId("consumer-trigger-child"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (23) a buff under a NESTED action — that action's use applies it, not this one's.
            consumerResource(fakeId("consumer-resource-nested"), "Nested Charges", "nestedCharges", order = 23),
            consumerAction(fakeId("consumer-action-nested-outer"), "action", "Outer Form", consumes = "nestedCharges", order = 180),
            consumerChild(fakeId("consumer-action-nested-inner"), "action", "Inner Action", fakeId("consumer-action-nested-outer"), order = 181),
            buff(
                id = fakeId("consumer-buff-nested"), name = "A buff under a nested action", order = 182,
                parentId = fakeId("consumer-action-nested-inner"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (24) a consumer naming the resource TWICE, with its buff behind two folders — one
            // consumer, one buff.
            consumerResource(fakeId("consumer-resource-twice"), "Twice Charges", "twiceCharges", order = 24),
            consumerAction(
                fakeId("consumer-action-twice"), "action", "Twice Form", consumes = "twiceCharges", order = 185,
                consumesTwice = true,
            ),
            consumerChild(fakeId("consumer-folder-twice-outer"), "folder", "Effects", fakeId("consumer-action-twice"), order = 186),
            consumerChild(fakeId("consumer-folder-twice-inner"), "folder", "More effects", fakeId("consumer-folder-twice-outer"), order = 187),
            buff(
                id = fakeId("consumer-buff-twice"), name = "A buff named once", order = 188,
                parentId = fakeId("consumer-folder-twice-inner"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (25) two resource rows sharing one variableName — neither gets consumers.
            consumerResource(fakeId("consumer-resource-shared-name-a"), "Shared-Name Charges A", "sharedNameCharges", order = 25),
            consumerResource(fakeId("consumer-resource-shared-name-b"), "Shared-Name Charges B", "sharedNameCharges", order = 26),
            consumerAction(fakeId("consumer-action-shared-name"), "action", "Shared-Name Form", consumes = "sharedNameCharges", order = 190),
            buff(
                id = fakeId("consumer-buff-shared-name"), name = "A buff for an ambiguous charge", order = 191,
                parentId = fakeId("consumer-action-shared-name"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (26) a spell cast without a spell slot — a SPELL consumer like any other here; the
            // flag is what its Use reads (no picker, no slotId).
            consumerResource(fakeId("consumer-resource-slotless"), "Slotless Charges", "slotlessCharges", order = 27),
            consumerAction(
                fakeId("consumer-spell-slotless"), "spell", "Slotless Companion", consumes = "slotlessCharges", order = 195,
                castWithoutSpellSlots = true,
            ),
            buff(
                id = fakeId("consumer-buff-slotless"), name = "A slotless companion buff", order = 196,
                parentId = fakeId("consumer-spell-slotless"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (27) an unresolved quantity — no computed value — reads 1: a consumer.
            consumerResource(fakeId("consumer-resource-unresolved"), "Unresolved Charges", "unresolvedCharges", order = 28),
            consumerAction(
                fakeId("consumer-action-unresolved"), "action", "Unresolved Form", consumes = "unresolvedCharges", order = 200,
                quantityUnresolved = true,
            ),
            buff(
                id = fakeId("consumer-buff-unresolved"), name = "A buff at an unresolved cost", order = 201,
                parentId = fakeId("consumer-action-unresolved"), parentCollection = "creatureProperties", inactive = true,
            ),

            // (28) rows that are NOT resources never carry consumers, even when a buff-applying
            // action spends them: a spell slot and a hit die by `variableName`, and a limited-use
            // action (the row is the action itself, which spends a resource and applies a buff).
            attribute(
                id = fakeId("consumer-slot"), name = "Level 1 Spell Slots", attributeType = "spellSlot",
                total = 2, damage = 0, reset = "longRest", spellSlotLevel = 1, order = 29, variableName = "slotLevel1",
            ),
            attribute(
                id = fakeId("consumer-hit-die"), name = "d8 Hit Dice", attributeType = "hitDice",
                total = 2, damage = 0, order = 30, variableName = "d8HitDice", hitDiceSize = "d8",
            ),
            consumerAction(fakeId("consumer-action-on-slot"), "action", "Slot Form", consumes = "slotLevel1", order = 205),
            buff(
                id = fakeId("consumer-buff-on-slot"), name = "A buff on a slot", order = 206,
                parentId = fakeId("consumer-action-on-slot"), parentCollection = "creatureProperties", inactive = true,
            ),
            consumerAction(fakeId("consumer-action-on-hit-die"), "action", "Hit-Die Form", consumes = "d8HitDice", order = 207),
            buff(
                id = fakeId("consumer-buff-on-hit-die"), name = "A buff on a hit die", order = 208,
                parentId = fakeId("consumer-action-on-hit-die"), parentCollection = "creatureProperties", inactive = true,
            ),
            limitedUse(
                id = fakeId("consumer-limited-use"), type = "action", name = "Limited Form", uses = 2, usesUsed = 0,
                reset = "longRest", order = 210,
            ),
        ),
    )

    /**
     * One trigger arm: its own resource, an action tagged `<key>Form` that spends it, a trigger on
     * that tag shaped by the overrides, and a buff under the trigger. The complete shape
     * (`consumerTriggerResourceId`'s arm) is the default, so each call is one field away from it.
     */
    @Suppress("LongParameterList")
    fun triggerArm(
        key: String,
        label: String,
        order: Int,
        event: String? = "doActionProperty",
        timing: String? = "after",
        inactive: Boolean = false,
        targetTags: List<String> = listOf("${key}Form"),
        notExtraTags: List<String>? = null,
        condition: Boolean? = null,
    ): Array<JsonObject> = arrayOf(
        consumerResource(fakeId("consumer-resource-$key"), "$label Charges", "${key}Charges", order = order / 5 - 12),
        consumerAction(
            fakeId("consumer-action-$key"), "action", "$label Form", consumes = "${key}Charges", order = order,
            tags = listOf("${key}Form"),
        ),
        consumerTrigger(
            fakeId("consumer-trigger-$key"), "$label Trigger", targetTags = targetTags, order = order + 1,
            event = event, timing = timing, inactive = inactive, notExtraTags = notExtraTags, condition = condition,
        ),
        buff(
            id = fakeId("consumer-buff-$key"), name = "A buff behind the $label trigger", order = order + 2,
            parentId = fakeId("consumer-trigger-$key"), parentCollection = "creatureProperties", inactive = true,
        ),
    )

    /** A 2-charge class resource carrying the `variableName` a cost names it by. */
    fun consumerResource(id: String, name: String, variableName: String, order: Int): JsonObject = attribute(
        id = id, name = name, attributeType = "resource", total = 2, damage = 0, order = order,
        reset = "longRest", variableName = variableName,
    )

    /**
     * An `action` or `spell` whose `resources.attributesConsumed` names [consumes], in the wire
     * shape: `quantity` is a `_calculation` wrapper, and the entry carries the server's `statName`.
     */
    @Suppress("LongParameterList")
    fun consumerAction(
        id: String,
        type: String,
        name: String,
        consumes: String,
        order: Int,
        quantity: Int = 1,
        tags: List<String> = emptyList(),
        inactive: Boolean = false,
        removed: Boolean = false,
        /** Write the `quantity` as an unresolved calculation — no computed `value` (addendum 2 item 5). */
        quantityUnresolved: Boolean = false,
        /** Name the resource in TWO `attributesConsumed` entries. */
        consumesTwice: Boolean = false,
        /** `castWithoutSpellSlots: true` on a spell (addendum 2 item 1). */
        castWithoutSpellSlots: Boolean = false,
        /** `null` → the creature root. */
        parentId: String? = null,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", type)
        put("name", name)
        put("actionType", "action")
        if (type == "spell") put("level", 1)
        if (castWithoutSpellSlots) put("castWithoutSpellSlots", true)
        put("order", order)
        put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
        val entry = buildJsonObject {
            put("variableName", consumes)
            put(
                "quantity",
                buildJsonObject {
                    put("calculation", if (quantityUnresolved) "chargeCost" else quantity.toString())
                    if (!quantityUnresolved) put("value", quantity)
                },
            )
        }
        put(
            "resources",
            buildJsonObject {
                put("attributesConsumed", JsonArray(if (consumesTwice) listOf(entry, entry) else listOf(entry)))
                put("itemsConsumed", JsonArray(emptyList()))
            },
        )
        put("parent", if (parentId == null) parentRef(creatureId, "creatures") else parentRef(parentId))
        if (inactive) put("inactive", true)
        if (removed) put("removed", true)
    }

    /** Any other property type in a consumer's subtree — a `branch`, a `folder`, … */
    fun consumerChild(id: String, type: String, name: String, parentId: String, order: Int): JsonObject =
        buildJsonObject {
            put("_id", id)
            put("type", type)
            put("name", name)
            put("order", order)
            put("parent", parentRef(parentId))
        }

    /**
     * A `trigger` in the shape DiceCloud's own editor stores — `event`, `actionPropertyType` and
     * `timing` all present. Passing `null` for any of the three writes the imported library's
     * stripped shape (BUG-27) for that field.
     *
     * @param notExtraTags writes one `extraTags` arm with `operation: "NOT"` over these tags.
     * @param condition writes `condition` as a calculation whose computed `value` is this.
     */
    @Suppress("LongParameterList")
    fun consumerTrigger(
        id: String,
        name: String,
        targetTags: List<String>,
        order: Int,
        actionPropertyType: String? = "action",
        event: String? = "doActionProperty",
        timing: String? = "after",
        inactive: Boolean = false,
        notExtraTags: List<String>? = null,
        condition: Boolean? = null,
        parentId: String? = null,
    ): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "trigger")
        put("name", name)
        put("order", order)
        if (event != null) put("event", event)
        if (actionPropertyType != null) put("actionPropertyType", actionPropertyType)
        if (timing != null) put("timing", timing)
        put("targetTags", JsonArray(targetTags.map { JsonPrimitive(it) }))
        if (notExtraTags != null) {
            put(
                "extraTags",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("_id", fakeId("$id-extra"))
                            put("operation", "NOT")
                            put("tags", JsonArray(notExtraTags.map { JsonPrimitive(it) }))
                        },
                    ),
                ),
            )
        }
        if (condition != null) {
            put(
                "condition",
                buildJsonObject {
                    put("calculation", if (condition) "level >= 1" else "level >= 99")
                    put("value", condition)
                },
            )
        }
        if (inactive) put("inactive", true)
        put("parent", if (parentId == null) parentRef(creatureId, "creatures") else parentRef(parentId))
    }

    fun snapshotBody(creature: JsonObject, properties: List<JsonObject>): JsonObject = buildJsonObject {
        put("creatures", JsonArray(listOf(creature)))
        put("creatureProperties", JsonArray(properties))
        put("creatureVariables", JsonArray(emptyList()))
    }

    /** @see ContractFixtures — a seeded id from the production generator. */
    fun fakeId(label: String): String = MeteorId.random(17, Random(label.hashCode().toLong()))
}
