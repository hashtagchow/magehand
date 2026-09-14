package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.model.AppliedBuff
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.DeathSaves
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.RollModifier
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * Turns one creature's raw properties into a [TrackerBoard].
 *
 * Pure: no I/O, no coroutines, no clock. Same input → same output, whether the input
 * came from the REST snapshot or the live DDP mirror (docs/design/03-data-model.md
 * §Discovery rules; docs/design/06-offline-and-sync.md §Snapshot lifecycle).
 *
 * Rule order matters: discovery first, then the local override layer (hide / pin /
 * reorder), which is applied **last** and never mutates server data.
 */
object TrackerEngine {

    // --- DiceCloud vocabulary (docs/design/02-ddp-and-api.md, verified against live data) ---
    private const val TYPE_ATTRIBUTE = "attribute"
    private const val TYPE_ITEM = "item"
    private const val TYPE_TOGGLE = "toggle"
    private const val TYPE_BUFF = "buff"
    private const val TYPE_DAMAGE_MULTIPLIER = "damageMultiplier"
    const val TYPE_SKILL = "skill"

    private const val ATTR_SPELL_SLOT = "spellSlot"
    private const val ATTR_RESOURCE = "resource"
    const val ATTR_ABILITY = "ability"

    /**
     * FR-30 decision 17's discriminator — `attributeType: 'hitDice'`.
     *
     * Public because the contract export states the discovery rule from the constant that
     * implements it, exactly as [VAR_HIT_POINTS] is, so the two cannot drift.
     *
     * **Probe H4's "one-line unblock", and what was actually blocked.** The documents were in the
     * mirror the whole time: `singleCharacter` publishes every `creatureProperties` row and
     * nothing about the transport dropped them. They were filtered out *here* — [spellSlot] wants
     * `spellSlot`, [resource] wants `resource`, and a `hitDice` attribute matched neither, so it
     * fell out of discovery with no error and no empty section to notice. That is the whole of why
     * this feature was a predicate rather than a protocol change.
     */
    const val ATTR_HIT_DICE = "hitDice"

    /**
     * FR-44 R1's discriminators — the two property **types** a limited-use row can be.
     *
     * Public for [ATTR_HIT_DICE]'s reason: the contract export states the discovery rule from the
     * constants that implement it, so the two cannot drift.
     *
     * Both, and not just `action`: the live probe of the party's sheets found the headline case —
     * a Stars druid's *Guiding Bolt (Star Map)*, 2 / long rest — typed `spell`, because a Star Map spell
     * granted by a feature is a spell that costs a use rather than a slot. Matching `action`
     * alone would have missed exactly the row the FR was raised for.
     */
    const val TYPE_ACTION = "action"
    const val TYPE_SPELL = "spell"

    /**
     * The counter a limited-use row carries, and the field the write moves.
     *
     * Public because [WriteOp.SetUsesUsed] sends this exact string as the `path` element and the
     * contract export prints it; three copies of `"usesUsed"` is three chances for one of them to
     * be a typo the server answers with a silent no-op. (`ActionEngine` keeps its own private
     * copies because it is a *reader* on the other side of the same field; the two are pinned
     * against each other by `LimitedUseDiscoveryTest`.)
     */
    const val FIELD_USES = "uses"
    const val FIELD_USES_USED = "usesUsed"

    /** The die a hit-dice row counts — `"d8"` on the live sheet. See [hitDieSize]. */
    private const val FIELD_HIT_DICE_SIZE = "hitDiceSize"

    /**
     * FR-51 R1's field: the modifier the server has already added up for this hit die, written
     * onto the `hitDice` property itself (fixture: `"constitutionMod": 1` on *d6 Hit Dice*, at
     * CON 13).
     *
     * Read from the row rather than derived from the `constitution` ability, because the server
     * recomputes and re-publishes it whenever CON changes and it is therefore the sheet's own
     * answer. [constitutionModifier] is the fallback for a row that does not carry it, and it is
     * a fallback rather than the primary source for 10 decision 3's reason: never compute over a
     * number the server already stated.
     *
     * Public for [VAR_HIT_POINTS]' reason: the contract export states the discovery rule from the
     * constant that implements it, so the exported field name and the one this engine reads
     * cannot drift apart.
     */
    const val FIELD_CONSTITUTION_MOD = "constitutionMod"

    /** Public so the contract export states the rule from the constant that implements it. */
    const val VAR_HIT_POINTS = "hitPoints"

    /**
     * FR-50 R1's discriminator — DiceCloud publishes armour class as an `attribute` whose
     * `variableName` is `armor` (18 decision 21).
     *
     * The `attributeType` is deliberately **unchecked**, exactly as it is for [VAR_HIT_POINTS]
     * and the death-save pair: the live sheet types this one `stat`, no other rule in this
     * engine reads a `stat` attribute, and keying on a sub-type would re-create the fragility
     * the variable-name rules exist to remove. The variable name is what the sheet's own
     * formulas compute against, so it is the server's word rather than ours.
     *
     * Public for [VAR_HIT_POINTS]' reason: the contract export states the discovery rule from
     * the constant that implements it, so the two cannot drift.
     */
    const val VAR_ARMOR = "armor"

    /**
     * FR-51 R2's fallback source — the `constitution` ability row, whose `modifier` the engine
     * already parses for the rolls list ([abilityCheck]).
     *
     * Not a second implementation of anything: the same [FIELD_MODIFIER] read, on the row the
     * server computes it on. See [constitutionModifier].
     */
    const val VAR_CONSTITUTION = "constitution"

    /**
     * FR-23 decision 19's discriminators. `Fails`, not `Failures` — DiceCloud's own spelling,
     * and the whole value of a variable-name rule is that it is the server's word rather than
     * ours.
     */
    const val VAR_DEATH_SAVE_SUCCESSES = "deathSaveSuccesses"
    const val VAR_DEATH_SAVE_FAILURES = "deathSaveFails"

    /**
     * 03 says temp HP is `variableName == "tempHitPoints"`. The live sheet calls it
     * **`tempHP`** — both are accepted so the engine works against 03's text and against
     * the server. See docs/verification/WP4.md §Deviations and
     * docs/verification/probe-p5-rolls.md §tempHP (live re-confirmation, 2026-08-24).
     *
     * Public for the same reason as [VAR_HIT_POINTS]: the contract export shipped only
     * `tempHitPoints` while every real sheet writes `tempHP`, so a consumer implementing
     * the exported rule literally would never find a temp-HP row. The export now renders
     * this set, which makes that class of drift a compile-time impossibility rather than a
     * thing someone has to remember to re-type.
     */
    val TEMP_HP_VARIABLE_NAMES = setOf("tempHitPoints", "tempHP")

    private const val CONCENTRATION = "concentration"

    /**
     * The presence of either field is what makes a toggle manual rather than computed —
     * it is the exact condition `creatureProperties.flipToggle` tests before it will act.
     */
    private val FLIPPABLE_KEYS = listOf("enabled", "disabled")

    /** Matches the leading ordinal of a slot name — `"1st Level"`, `"2nd Level"`, … */
    private val LEADING_ORDINAL = Regex("""^\s*(\d+)""")

    /**
     * The `skillType` values that are a **proficiency you hold**, not a roll you make.
     *
     * DiceCloud files several different things under one property type, distinguished only by
     * this field, and they are not all d20 rolls: alongside the check-shaped kinds it also
     * stores weapon, armour and language proficiencies — each of which carries the same
     * `value` field (the proficiency bonus) purely because the property type has one. A
     * dropdown offering "make a Common check" would be nonsense the sheet never claimed.
     *
     * An **exclusion** list rather than an allow-list, deliberately, and the trade is worth
     * stating: an allow-list would silently drop a kind DiceCloud adds later, and dropping a
     * real roll is the failure the player cannot see or work around. Listing the non-rolls
     * means a new kind shows up in the dropdown instead — visible, harmless, and fixable by
     * one line here. (`armor` is included from DiceCloud's own vocabulary rather than from any
     * data seen here: it is the same proficiency shape as the other two, and leaving it out
     * on the grounds that nobody's sheet happens to carry one would be pedantry with a bug in it.)
     */
    val NON_ROLL_SKILL_TYPES = setOf("language", "weapon", "armor")

    /**
     * The rollup DiceCloud writes onto a computed roll when an effect pushes it either way.
     * Read for its **sign** — see [RollAdvantage.fromWire].
     */
    const val FIELD_ADVANTAGE = "advantage"

    /** An ability *score*'s check modifier, which is a different field from the score itself. */
    const val FIELD_MODIFIER = "modifier"

    /**
     * Builds the board.
     *
     * @param overrides the local layer from Room `tracker_prefs`, keyed by `propertyId`.
     */
    fun build(
        sheet: CreatureSheet,
        overrides: Map<String, TrackerOverride> = emptyMap(),
    ): TrackerBoard {
        val properties = sheet.propertyList

        val slots = properties.mapNotNull { spellSlot(it) }
        val resources = properties.mapNotNull { resource(it) }
        val limitedUses = properties.mapNotNull { limitedUse(it) }
        // FR-51 R2. Resolved once for the whole sheet rather than per row: it is a property of
        // the character, every hit-dice row would find the same one, and a multiclass sheet has
        // several rows to scan the property list for.
        val constitutionMod = constitutionModifier(properties)
        val hitDice = properties.mapNotNull { hitDice(it, constitutionMod) }
        val items = properties.mapNotNull { item(it) }
        val toggles = properties.mapNotNull { toggle(it) }
        val buffs = appliedBuffs(properties)
        val defenses = properties.mapNotNull { damageDefense(it) }
        val rolls = properties.mapNotNull { abilityCheck(it) ?: skillRoll(it) }

        val pinnedIds = overrides.values.filter { it.pinned }.map { it.propertyId }.toSet()

        return TrackerBoard(
            hp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.HIT_POINTS) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            tempHp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.TEMP_HP) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            // FR-50 R1/R2. Deliberately **not** override-filtered, and not because the layer was
            // forgotten: an override is keyed by `propertyId` and AC is not a row, so there is no
            // id here for a preference to name and no control anywhere that could set one. See
            // `TrackerBoard.armorClass`.
            armorClass = armorClass(properties),
            slots = order(slots, overrides, SLOT_ORDER),
            resources = order(resources, overrides, NATURAL_ORDER),
            // FR-44 R1. Sorted but **not** override-filtered, for the reason on the line below
            // and by the same precedent: no customize-sheet control can reach one of these rows,
            // so the override layer could only ever hide one irrecoverably. FR-44's own switch
            // (R3) is the control, and it hides the section rather than a row.
            limitedUses = limitedUses.sortedWith(NATURAL_ORDER),
            // FR-30 decision 17. Sorted but **not** override-filtered, unlike the two lists above
            // and for `deathSaves`' reason one line down: the customize sheet builds its sections
            // from slots, resources, items and toggles, so nothing anywhere can pin, hide or
            // reorder a hit-dice row. Running them through `order` would let a stale preference
            // hide one with no control on screen able to bring it back. See `TrackerBoard.hitDice`.
            hitDice = hitDice.sortedWith(NATURAL_ORDER),
            allItems = order(items, overrides, NATURAL_ORDER),
            pinnedItems = order(items.filter { it.propertyId in pinnedIds }, overrides, NATURAL_ORDER),
            // FR-53 R1/R2. Sorted inside [appliedBuffs] and **not** override-filtered, for
            // `limitedUses`' reason: no customize-sheet control can reach a buff chip, so the
            // layer could only ever hide one irrecoverably. `show_toggles` hides the section.
            buffs = buffs,
            activeToggles = orderToggles(toggles, overrides),
            defenses = orderDefenses(defenses, overrides),
            rolls = orderRolls(rolls, overrides),
            concentratingOn = concentrationSource(properties),
            // FR-23 decision 18. Discovery only — the block's *visibility* also needs the HP row
            // to read zero, and that gate is in `TrackerUiState` (see `TrackerBoard.deathSaves`).
            //
            // Deliberately **not** filtered by the override layer: the hide/pin machinery is for
            // rows a player chose to manage, and there is no customize-sheet control that can
            // reach this pair. A block that is only on screen at 0 HP is not clutter anyone
            // needs to hide.
            deathSaves = deathSaves(properties),
        )
    }

    fun build(sheet: CreatureSheet, overrides: List<TrackerOverride>): TrackerBoard =
        build(sheet, overrides.associateBy { it.propertyId })

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * 03 §1. `attributeType == 'spellSlot'`, excluding:
     * - `reset == null` — a slot-shaped row with **no reset rule**, which the tracker has
     *   nothing to say about: every control on a slot row is "spend it and a rest brings it
     *   back", and a row that no rest restores would offer a promise the sheet does not keep.
     * - `total == 0` — slot levels the character cannot reach yet.
     *
     * ### What this exclusion is NOT (FR-23 decision 19)
     *
     * It used to be documented as *"`reset == null` — **death saves**"*, on the strength of
     * 02 §Known server quirks. That reading was a **coincidence** and the death-save probe
     * (2026-08-24) retired it: the pair happens to carry no reset rule, but so may anything
     * else, and nothing on the wire says "this null means death save". Death saves are
     * discovered by [deathSaves] from their `variableName`, which is what the server actually
     * guarantees — and `docs/dicecloud-api.md`'s line carries the same amendment.
     *
     * The *exclusion* still does its job and is unchanged; only the claim about why has been
     * corrected. Believing the old one would have made the FR-23 block impossible to build on
     * a sheet whose death saves are typed `attribute` rather than `spellSlot`.
     */
    private fun spellSlot(p: JsonObject): TrackedResource? {
        if (!p.isAttribute(ATTR_SPELL_SLOT) || p.isSkipped()) return null
        val reset = ResetRule.fromWire(p.string("reset")) ?: return null
        val total = p.number("total") ?: 0
        if (total == 0) return null
        return p.toResource(TrackerKind.SPELL_SLOT, total = total, reset = reset, level = p.slotLevel())
    }

    /** 03 §2. `attributeType == 'resource'`, kept when `total > 0 || value > 0`. */
    private fun resource(p: JsonObject): TrackedResource? {
        if (!p.isAttribute(ATTR_RESOURCE) || p.isSkipped()) return null
        val total = p.number("total") ?: 0
        val value = p.remaining(total)
        if (total <= 0 && value <= 0) return null
        return p.toResource(TrackerKind.RESOURCE, total = total, reset = ResetRule.fromWire(p.string("reset")))
    }

    /**
     * FR-44 R1: an `action` or `spell` row carrying a **numeric `uses`**.
     *
     * > *"`action` and `spell` rows with a numeric `uses`, not `inactive`, not removed, become
     * > `TrackerKind.LIMITED_USE`: value = `uses − usesUsed` (`usesUsed` absent reads 0, as
     * > `ActionEngine.usesFor`), total = `uses`, label = the property's `name`, `reset` read from
     * > the row so the FR-20 badge applies."*
     *
     * ### Discovery by **shape**, which is the operator's stated requirement
     *
     * Nothing here names a feature, a class or a sheet. A row is a limited-use row because it
     * carries a use count, so a new ability added to any character's sheet appears on the tracker
     * with no build and no list to maintain — the same posture [damageDefense] takes towards a
     * homebrew damage type. The party's own rows span both types, three different `uses`
     * calculations (`2`, `proficiencyBonus`, `max(1, wisdom.modifier)`) and both reset rules; not
     * one of those variations is visible from here, because [number] resolves the server's
     * `_calculation` wrapper down to the answer it already computed.
     *
     * ### `uses` absent is not a limited-use row, and `usesUsed` absent is zero
     *
     * The presence of a **positive** `uses` is the rule — an ability with no counter is an
     * unlimited ability and has nothing to put pips on, and one whose counter computes to `0` or
     * less has no pips to draw either. The zero case is not hypothetical: `uses` is a calculation
     * (*Guiding Bolt (Star Map)* is `proficiencyBonus`, *Weal* is `max(1, wisdom.modifier)`), so a
     * character built below the level that grants an ability can publish a real row whose count
     * evaluates to zero. A zero-pip row is a section entry the player can neither read nor spend,
     * and the export states the same rule (`domain/rules.json#discovery.limitedUses.include`).
     *
     * `usesUsed`, by contrast, is written by the server only once
     * a use has been spent, so its absence is a fact (*"never used"*) rather than an unknown; the
     * live sheets show four of the live druid sheet's five limited rows with the key simply missing.
     * `ActionEngine.usesFor` has read it that way since FR-28 and this is deliberately the same
     * reading in the same words, because the Actions tab's *"N of M uses left"* and this row's
     * pips are two renderings of one number and a player will have both on screen.
     *
     * ### A newly added ability is not on the tracker for the first few seconds
     *
     * `uses` arrives as DiceCloud's `_calculation` wrapper and [number] resolves it through its
     * **`value`** key — the server's computed answer. That answer is written on a *debounced*
     * pass, so between a property being created and that pass landing the wrapper carries a
     * `calculation` and no `value`, [number] returns `null`, and this function correctly declines
     * to invent a row. The re-probe of 2026-09-06 measured the window on the Test Dummy at
     * **~2.5 s** from insert (docs/verification/probe-fr44.md, probe C); every frame after that
     * carried `value` immediately. The same holds for an ability whose calculation *errors* — a
     * reference to a variable the sheet does not have — except that there the row never appears at
     * all, because the server never writes a `value` to appear from.
     *
     * **Ruling (FR-44 fix pass): no fallback that parses `calculation`.** The string is DiceCloud's
     * own expression language over the character's variables; evaluating `proficiencyBonus` or
     * `max(1, wisdom.modifier)` here would be a second implementation of the server's rules engine
     * for the sake of two seconds — 10 decision 3's grand-total lesson, in the discovery layer.
     * The honest behaviour is the one above: the row appears when the server says what it is. This
     * paragraph exists so that "my new ability is not on the tracker yet" is a documented wait
     * rather than a bug report.
     *
     * ### Why `value` is computed rather than read from `usesLeft`
     *
     * The server publishes `usesLeft`, and it is the obvious-looking field. 17 decision 1 is
     * explicit that it **must not** drive the UI: it lags a debounced recompute by 4–10 s (probe
     * U5), so a row driven by it would sit visibly wrong for seconds after every spend — exactly
     * the window a player is looking at it. `uses − usesUsed` is arithmetic over two fields the
     * server writes synchronously, which is why the Actions tab already computes it.
     *
     * FR-44's own re-probe caught the lag in the act and the transcript is worth keeping: one
     * frame after a write set `usesUsed: 1` on a 2-use row, the same document still published
     * `usesLeft: 2`. A row driven by that field would have shown a full ability the instant after
     * the player spent from it.
     *
     * ### The blanket skip, and the one it now also catches
     *
     * [isSkipped] drops `inactive` and `removed` as everywhere else. Both cases are real here
     * rather than theoretical: the live druid sheet carries four limited-use rows that are `inactive` until the
     * levels that grant them (*Cosmic Omen*, *Weal*, *Woe*, *Eat a Goodberry*), and FR-44's own
     * R2 probe left a soft-removed `action` on the Test Dummy — a permanent negative fixture for
     * this predicate. Note that this makes tracker discovery **stricter than the Actions tab**,
     * which deliberately keeps `inactive` rows and badges them (16 decision 2): a dimmed row that
     * explains itself is right for a list of everything the character has, and wrong for a strip
     * of pips whose entire purpose is "spend this".
     */
    private fun limitedUse(p: JsonObject): TrackedResource? {
        val type = p.string("type")
        if (type != TYPE_ACTION && type != TYPE_SPELL) return null
        if (p.isSkipped()) return null
        val total = p.number(FIELD_USES) ?: return null
        if (total <= 0) return null
        return p.toResource(
            TrackerKind.LIMITED_USE,
            total = total,
            reset = ResetRule.fromWire(p.string("reset")),
            // `total − usesUsed`, not [remaining]'s `value ?: total − damage`: an action property
            // carries neither of the two fields that reader looks at. Its `value` is absent and
            // its consumption is `usesUsed`, which counts UP from zero rather than down from the
            // total — the inversion `WriteOp.SetUsesUsed` exists to keep in one place.
            //
            // Floored at zero, because `usesUsed > uses` is a state the server can publish and
            // this app cannot. `uses` is a calculation: an ability whose count shrinks — a lost
            // level, an edited formula, a dropped proficiency bonus — keeps whatever `usesUsed`
            // it had, so a 3-use ability spent twice and re-computed to 1 publishes
            // `uses: 1, usesUsed: 2`. Unfloored that is a row reading −1, with a negative pip
            // count to draw and, worse, a *write* built from it: `WriteOp.adjust` derives
            // `usesUsed = total − remaining`, so the first restore tap would send
            // `usesUsed = 1 − 0 = 1` and the row would still read 0. Flooring here makes the row
            // read "0 of 1" — spent out, which is the truthful reading of a counter that is past
            // its own maximum — and the restore tap then writes `usesUsed: 0` and works.
            valueOverride = (total - (p.number(FIELD_USES_USED) ?: 0)).coerceAtLeast(0),
        )
    }

    /**
     * FR-30 decision 17: `attributeType == 'hitDice'`, one row per die size.
     *
     * ### Deliberately shaped like [resource], because it is the same shape
     *
     * `value = total − damage` (via [remaining]), the same `total > 0 || value > 0` keep-rule, and
     * the same `damage increment` write behind it — decision 18: *"spend is the EXISTING damage
     * increment … same shape as slot spends, ZERO new intents"*. The addendum calls this a
     * one-line predicate unblock and that is exactly what it is: the rows were always in the
     * mirror, [ATTR_HIT_DICE] is the discriminator nothing was matching on.
     *
     * ### `reset = null`, and it is a fact rather than a default
     *
     * Decision 17: *"NO reset field — by design: the server's own rest machinery bypasses reset"*.
     * The property genuinely carries none, so this passes `null` rather than reading a field that
     * is not there. That is not cosmetic. Decision 19 says the server restores half the dice on a
     * long rest **itself** (highest first, per-creature `hitDiceResetMultiplier`, floor 1) and
     * logs it, and that *"the app predicts NOTHING"* — so a hit-dice row must never appear in the
     * rest confirm dialog's restore list, which is `rowsRestoredBy`'s reset-rule filter. A `null`
     * reset keeps it out of that list twice over: the filter would reject it anyway, and
     * [TrackerBoard.hitDice] is not one of the two lists the filter reads.
     *
     * Short rest: untouched by the server, untouched by us.
     *
     * ### A row with no readable die size still renders
     *
     * [dieSize] is `null` and the UI falls back to the property's own `name`. Dropping the row
     * would be losing a resource the player can spend over a *label*, which is the wrong thing to
     * be strict about — the same tolerance [DamageDefense]'s free-text types get.
     */
    private fun hitDice(p: JsonObject, constitutionMod: Int?): TrackedResource? {
        if (!p.isAttribute(ATTR_HIT_DICE) || p.isSkipped()) return null
        val total = p.number("total") ?: 0
        val value = p.remaining(total)
        if (total <= 0 && value <= 0) return null
        return p.toResource(
            TrackerKind.HIT_DICE,
            total = total,
            reset = null,
            dieSize = p.hitDieSize(),
            // FR-51 R1/R2: the row's own number first, the ability row's second, `null` third —
            // and `null` is a real answer that keeps today's label, never an invented `+ 0`.
            dieModifier = p.number(FIELD_CONSTITUTION_MOD) ?: constitutionMod,
        )
    }

    /**
     * FR-51 R2's fallback: the `constitution` ability row's [FIELD_MODIFIER].
     *
     * ### Why there is a fallback at all
     *
     * Every `hitDice` property the live sheets publish carries [FIELD_CONSTITUTION_MOD], so this
     * path is for the sheets nobody here has seen: an older document, a homebrew row typed by
     * hand, a creature imported from somewhere that did not write the field. The number is the
     * same number — the ability row is where the server computed it in the first place — so
     * reading it there is honesty about a *missing field*, not a second implementation of the
     * rule. Decision 24 names it in exactly those terms.
     *
     * ### And why it stops at `null`
     *
     * A sheet with neither the field nor a `constitution` ability row gets `null`, and the row
     * keeps today's *"Hit Dice d8"*. The alternative — deriving `floor((score − 10) / 2)` from a
     * score, or printing `+ 0` — is this app inventing a number the player will add to a die at
     * a table. 18 decision 25 forbids the second and `abilityCheck`'s own KDoc forbids the first
     * ("a missing `modifier` is a skip rather than a fallback to arithmetic on the score").
     *
     * Matched by `variableName`, not by name: *"Constitution"* is copy on the sheet and
     * translatable, `constitution` is what DiceCloud's own formulas compute against.
     */
    private fun constitutionModifier(properties: List<JsonObject>): Int? = properties
        .firstOrNull {
            it.isAttribute(ATTR_ABILITY) &&
                !it.isSkipped() &&
                it.string("variableName") == VAR_CONSTITUTION
        }
        ?.number(FIELD_MODIFIER)

    /**
     * FR-50 R1: armour class, discovered by `variableName == 'armor'` (18 decision 21).
     *
     * ### The same rule HP uses, one variable name over
     *
     * `type == 'attribute'` is checked and `attributeType` deliberately is not — see [VAR_ARMOR].
     * The blanket [isSkipped] rule applies as everywhere else, so a soft-removed or deactivated
     * armour attribute is not the character's AC.
     *
     * ### `total`, falling back to `value`
     *
     * `total` is the computed answer — the fixture's *"Armor Class"* carries
     * `baseValue: 10+dexterity.modifier` and `total: 14`, which is base plus every effect the
     * server folded in. `value` is the same number on every sheet seen here and is read only
     * when `total` is absent, for [remaining]'s reason inverted: never *ignore* a number the
     * server already stated. Both go through [number], which resolves DiceCloud's
     * `_calculation` wrapper and stringified numbers, so an `armor` attribute that publishes its
     * total as `{"calculation":…, "value":14}` reads 14 rather than nothing.
     *
     * ### Absent is `null`, and `firstNotNullOfOrNull` is why
     *
     * The scan returns the first `armor` attribute that yields a **readable number**, rather
     * than the first one that matches and then whatever it happens to hold — so a matching
     * property mid-recompute (wrapper present, `value` not yet written) does not shadow a
     * readable one behind it, and a sheet with no readable number at all lands on `null`. Never
     * `0`: an unarmoured character is AC 10, so zero would be this app's word for "not found"
     * printed as if it were the sheet's. See `TrackerBoard.armorClass`.
     */
    private fun armorClass(properties: List<JsonObject>): Int? = properties
        .firstNotNullOfOrNull { p ->
            if (p.string("type") != TYPE_ATTRIBUTE || p.isSkipped()) return@firstNotNullOfOrNull null
            if (p.string("variableName") != VAR_ARMOR) return@firstNotNullOfOrNull null
            p.number("total") ?: p.number("value")
        }

    /**
     * `hitDiceSize` as the row should print it — `"d8"`.
     *
     * Three shapes are tolerated because DiceCloud is not uniform about which one a field arrives
     * in (see the readers at the foot of `CreatureSheet`): a plain string `"d8"`, a bare number
     * `8`, and a `_calculation` wrapper holding either under `value`. The live sheet publishes the
     * first; the other two cost four lines and remove a whole class of "renders on my sheet, not
     * on yours".
     *
     * **FR-51's `constitutionMod` needed no twin of this function**, and the ruling's *"factor
     * the shape reader into one helper if that is cheaper than two copies"* resolved to "neither":
     * [number] already resolves all three shapes for an `Int` — `intOrNull`, then
     * `content.toDoubleOrNull()` for a stringified one, then the wrapper's `value` recursively —
     * so the modifier is a one-line `number(FIELD_CONSTITUTION_MOD)`. This function exists only
     * because a die size is a *string* the app must not normalise, which is the one case
     * [number] cannot serve.
     *
     * The `d` is **prepended only when it is missing**, rather than the size being parsed to an
     * `Int` and re-rendered. That keeps a homebrew `"d3"`, a `"d20"` and anything else the sheet
     * says intact and unnormalised — [TrackedResource.dieSize]'s own argument, and the same
     * posture `DamageDefense.damageTypes` takes towards strings a sheet's author typed.
     */
    private fun JsonObject.hitDieSize(): String? {
        val raw = when (val element = this[FIELD_HIT_DICE_SIZE]) {
            is JsonObject -> element.string("value") ?: element.number("value")?.toString()
            else -> string(FIELD_HIT_DICE_SIZE) ?: number(FIELD_HIT_DICE_SIZE)?.toString()
        }?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.startsWith("d", ignoreCase = true)) raw else "d$raw"
    }

    /** 03 §3. HP and temp HP, identified by `variableName` rather than by `attributeType`. */
    private fun healthAttribute(p: JsonObject, kind: TrackerKind): TrackedResource? {
        if (p.string("type") != TYPE_ATTRIBUTE || p.isSkipped()) return null
        val variableName = p.string("variableName") ?: return null
        val matches = when (kind) {
            TrackerKind.HIT_POINTS -> variableName == VAR_HIT_POINTS
            TrackerKind.TEMP_HP -> variableName in TEMP_HP_VARIABLE_NAMES
            else -> false
        }
        if (!matches) return null
        return p.toResource(kind, total = p.number("total") ?: 0, reset = ResetRule.fromWire(p.string("reset")))
    }

    /**
     * FR-23 decision 19: the death-save pair, discovered by **`variableName`**.
     *
     * ### The discriminator, and the one it replaced
     *
     * `variableName ∈ {deathSaveSuccesses, deathSaveFails}` — the names DiceCloud's own sheet
     * computes against, so they are stable in a way a shape is not. The design's own words:
     * *"the old 'reset==null ⇒ death save' reading is a COINCIDENCE"*. [spellSlot]'s comment
     * carries the correction from the other side.
     *
     * The `type` is checked (`attribute`) and the `attributeType` deliberately is **not**: the
     * probe found the pair typed `spellSlot` on the sheets it saw and the design allows either
     * (*"type attribute/spellSlot"*), so keying on the sub-type would re-create exactly the
     * fragility the variable-name rule exists to remove.
     *
     * ### The inversion, applied once, here
     *
     * Storage is `value` = marks and `damage` = `3 − value`. [remaining] already returns the
     * property's `value` when it has one, so a mark count falls straight out — and on a sheet
     * that omits `value`, `total − damage` is the same number by the identity above. Nothing
     * downstream of this function has to know; see [DeathSaves] for why that is the point.
     *
     * ### Both, or neither
     *
     * `null` unless **both** halves are found (decision 18: *"no pair, no block, no error"*).
     * A sheet carrying only successes is not a sheet this block can render — three failure pips
     * would have nowhere to write — and half a death-save tracker at a table is worse than
     * none. The Dummy has neither, which is the case that made this explicit rather than
     * assumed.
     *
     * Counts are clamped into `0..MAX` on the way in. The server clamps natively on write
     * (probe-verified) and this is the read side of the same rule: a sheet whose `value` drifted
     * to 4 through some other client would otherwise paint a fourth pip into a row of three.
     */
    private fun deathSaves(properties: List<JsonObject>): DeathSaves? {
        fun find(variableName: String): JsonObject? = properties.firstOrNull {
            it.string("type") == TYPE_ATTRIBUTE &&
                !it.isSkipped() &&
                it.string("variableName") == variableName
        }

        val successes = find(VAR_DEATH_SAVE_SUCCESSES) ?: return null
        val failures = find(VAR_DEATH_SAVE_FAILURES) ?: return null

        fun marks(p: JsonObject): Int =
            p.remaining(p.number("total") ?: DeathSaves.MAX).coerceIn(0, DeathSaves.MAX)

        return DeathSaves(
            successesPropertyId = successes.string("_id") ?: return null,
            failuresPropertyId = failures.string("_id") ?: return null,
            successes = marks(successes),
            failures = marks(failures),
        )
    }

    /**
     * 03 §4. Every live `item`. Pins are a *local* concept, so discovery returns the whole
     * list (the picker needs it) and [build] splits out the pinned subset.
     *
     * `value == total == quantity`: an item has no maximum, and writes go through
     * `adjustQuantity`, not `damage`.
     *
     * ### MED-4: a missing `quantity` is **one**, not zero
     *
     * This read used to be `?: 0` while `InventoryEngine.toInventoryItem` used `?: 1`, so one
     * property produced two different quantities depending on which tab was looking at it — a
     * potion the inventory listed as "×1" was a consumable the tracker showed as 0, with its
     * `−` greyed out. 11 decision 7 settles it in the inventory's favour, and not by coin-toss:
     * DiceCloud omits the field on singletons, so an item without it is one of the thing. The
     * weight argument already forced that reading once (a sheet of unquantified gear would
     * otherwise weigh nothing), and a second engine reading the same absence as "none of it"
     * was the disagreement, not a second opinion worth keeping.
     *
     * The knock-ons follow rather than needing their own edits: `OpenCharacter.adjustItem`
     * clamps a decrement against `item.value`, and the consumable stepper's `−` is enabled on
     * `quantity > 0`. Both now see 1 and both now behave — spending the potion is possible, and
     * lands the same write the inventory tab's own stepper would.
     *
     * `TrackerEngineTest`'s cross-engine agreement test pins the two engines to one answer, so
     * a future edit to either reopens the defect as a test failure rather than as a grey button.
     */
    private fun item(p: JsonObject): TrackedResource? {
        if (p.string("type") != TYPE_ITEM || p.isSkipped()) return null
        val quantity = p.number("quantity") ?: 1
        return TrackedResource(
            propertyId = p.string("_id") ?: return null,
            kind = TrackerKind.ITEM,
            name = p.string("name").orEmpty(),
            value = quantity,
            total = quantity,
            sortOrder = p.number("order") ?: 0,
        )
    }

    /**
     * 03 §5. `toggle` properties, and whether each one can actually be flipped.
     *
     * ### Which toggles are shown
     *
     * All of them except the ones a flip could not affect: removed, or deactivated by an
     * ancestor or another toggle. `inactive` is **not** a skip reason — for a toggle it
     * *is* the state being rendered, and dropping switched-off toggles here would make
     * them unreachable. Which of the discovered toggles the *main list* shows is a
     * separate, later question, answered once in [ConditionToggle.shownByDefault]:
     * discovery must stay complete for that expander to have anything to open.
     *
     * The `condition`-free filter WP4 used has been dropped in favour of [FLIPPABLE_KEYS]
     * below: it was standing in for "is this manual?", and it was standing in for it
     * wrongly (see the next paragraph). A computed toggle is still worth *showing* —
     * "0 HP?" being on is real information at the table — it simply is not tappable.
     *
     * ### Which toggles can be flipped, and how we know
     *
     * The server's own precondition, read out of `flipToggle` in the running bundle and
     * confirmed live against the test dummy (WP7):
     *
     * ```js
     * if (!property.enabled && !property.disabled)
     *   throw new Meteor.Error('Computed toggle', "Can't flip a toggle that is computed");
     * ```
     *
     * So a **manual** toggle is one whose document carries `enabled` or `disabled`; a
     * toggle with neither is driven by its `condition` calculation and `flipToggle`
     * refuses it. Neither of the two rules tried before this survives contact with the
     * server: 03 §5's `showUI == true` matches nothing (no property on any sheet here has
     * the field), and WP4 §6.2's "no `condition` ⇒ manual" fallback matches four of the
     * live capture's toggles, **all four of which the server rejects**. Setting `showUI: true`
     * by hand on the dummy changed nothing; setting `enabled: true` made the flip work.
     */
    private fun toggle(p: JsonObject): ConditionToggle? {
        if (p.string("type") != TYPE_TOGGLE) return null
        if (p.isTrue("removed")) return null
        if (p.isTrue("deactivatedByAncestor") || p.isTrue("deactivatedByToggle")) return null
        return ConditionToggle(
            propertyId = p.string("_id") ?: return null,
            name = p.string("name").orEmpty(),
            enabled = !p.isTrue("inactive") && !p.isTrue("deactivatedBySelf"),
            flippable = FLIPPABLE_KEYS.any { p.containsKey(it) },
            tags = p.strings("tags"),
            sortOrder = p.number("order") ?: 0,
        )
    }

    /**
     * FR-53 R1 — the buffs **currently on the character**, in the sheet's own `order`.
     *
     * ### The rule is the blanket rule, and nothing else
     *
     * `type == "buff"`, not `removed`, not `inactive`. That is 03's skip applied to a fourth
     * property type, and the operator's *"don't show unapplied buffs"* turns out to be exactly it:
     * the 2026-09-14 probe found that an **applied** buff is a copy of a library template at the
     * creature root carrying neither `inactive` nor `deactivatedByAncestor`, while every template
     * carries `deactivatedByAncestor: true` → `inactive: true`. So the unapplied ones are
     * precisely the inactive ones.
     *
     * Three filters were considered and each is deliberately **absent**, because a second rule
     * here could only ever disagree with the first:
     *
     *  - **an ancestor walk** (*"keep only `parent.collection == 'creatures'`"*) — true of every
     *    applied buff on the probe, and one homebrew sheet that nests a live buff one folder deep
     *    away from silently losing it;
     *  - **`silent`** — a probed sheet's applied *Shield* is `silent: true` and is unquestionably
     *    on that character; `silent` is about the party feed, not about whether the buff is
     *    running;
     *  - **`target`** — `"self"` on the probe's example and meaningless for discovery.
     *
     * Unlike [toggle] this rule **does** skip `inactive`, and the asymmetry is the point: for a
     * toggle, *off* is the state being rendered and hiding it would make it unreachable; for a
     * buff, off means *not cast*, and there is nothing a player could do with it from here — a
     * template is turned on by casting the spell, not by tapping a chip.
     *
     * `duration` is not read (R2): it is a `_calculation` and a parse error on the live sheet.
     *
     * Sorted here rather than by the override layer's `order` helper because a buff carries no
     * `TrackerOverride` — see `TrackerBoard.buffs`. Name is the tie-break, as it is everywhere
     * else on this board.
     */
    private fun appliedBuffs(properties: List<JsonObject>): List<AppliedBuff> = properties
        .asSequence()
        .filter { it.string("type") == TYPE_BUFF && !it.isSkipped() }
        .mapNotNull { p ->
            val id = p.string("_id") ?: return@mapNotNull null
            (p.number("order") ?: 0) to AppliedBuff(
                propertyId = id,
                name = p.string("name").orEmpty(),
                description = p.descriptionText(),
            )
        }
        .sortedWith(compareBy({ it.first }, { it.second.name }))
        .map { it.second }
        .toList()

    /**
     * A `description` wrapper's prose — **`value` first, `text` second** (BUG-25 R1).
     *
     * The fourth copy of that one rule, beside `ActionEngine.text`, `QuestEngine.text` and
     * `InventoryEngine.descriptionText`. Duplicated on `QuestEngine`'s stated terms — the readers
     * are private to their engines and a shared version would have to live in [CreatureSheet]'s
     * companion beside the *type* readers, which this is not — and narrowed to the one key this
     * engine needs rather than generalised to a `key` parameter, because `description` is the only
     * wrapper on the tracker's side of the sheet.
     *
     * `value` is the server's rendered string with `{#spellList.dc}`-style tokens substituted;
     * `text` is the author's source. Each half is blank-checked on its own so `"value": ""` falls
     * through rather than reading as absent, and nothing is computed here (R3). The emphasis the
     * rendered string still carries is stripped **at the screen**, not here.
     */
    private fun JsonObject.descriptionText(): String? {
        val raw = this["description"]
        val text = when (raw) {
            is JsonObject -> raw.string("value")?.takeIf { it.isNotBlank() }
                ?: raw.string("text")?.takeIf { it.isNotBlank() }
            else -> string("description")
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * Damage resistances, immunities and vulnerabilities.
     *
     * ### Where this rule comes from
     *
     * **Not** from 03 — the design docs do not mention defenses at all, so this rule was
     * read off the live capture (see `Fixtures.kt`) rather than off a specification. Each
     * `type: "damageMultiplier"` property there carries a numeric `value`, a
     * `damageTypes` list, a `name` and an `order`:
     *
     * ```json
     * { "type": "damageMultiplier", "damageTypes": ["...", "..."], "value": 0.5, "order": 180 }
     * ```
     *
     * `value` is the multiplier applied to incoming damage, and the capture's active
     * example being `0.5` on a feature that grants resistance is what fixes the reading —
     * see [DefenseKind.fromMultiplier] for the rest of the mapping and for the two values
     * it refuses to guess at.
     *
     * The creature document also carries a denormalized `damageMultipliers` rollup, and it
     * is **`{}`** on this capture despite one of the two properties being active. So the
     * server's own summary is not trustworthy here; the properties are, and reading them
     * is also what makes the DDP mirror and the REST snapshot agree.
     *
     * ### Filtering
     *
     * The blanket [isSkipped] rule, exactly as every other discovery rule uses it — which
     * is the whole point: the capture's second multiplier is a resistance granted by a
     * feature that is switched off (`inactive: true` + `deactivatedByToggle: true`), and
     * it must not reach the table. The rule excludes it correctly. No extra
     * `deactivatedBy*` checks are needed on top: all 252 deactivated properties in the
     * capture also carry `inactive: true`, so `inactive` already subsumes them. (The
     * toggle rule checks them individually only because it deliberately does *not* skip
     * `inactive` — for a toggle, off is the state being rendered.)
     *
     * ### Condition immunities
     *
     * Left out on purpose. No property type in the capture expresses one, no creature or
     * variable field does, and `docs/` says nothing about them — so there is no shape to
     * implement without inventing one. Note that this rule is agnostic about what a
     * `damageTypes` entry *says*: a sheet that expresses "immune to charmed" by naming the
     * condition there already renders correctly, it simply is not something any available
     * source lets us assert.
     */
    private fun damageDefense(p: JsonObject): DamageDefense? {
        if (p.string("type") != TYPE_DAMAGE_MULTIPLIER || p.isSkipped()) return null
        // A multiplier with nothing to apply to is not information; drop it rather than
        // render an empty "Resistant:" line.
        val damageTypes = p.strings("damageTypes").filter { it.isNotBlank() }
        if (damageTypes.isEmpty()) return null
        val kind = p.decimal("value")?.let { DefenseKind.fromMultiplier(it) } ?: return null
        return DamageDefense(
            propertyId = p.string("_id") ?: return null,
            kind = kind,
            damageTypes = damageTypes,
            name = p.string("name").orEmpty(),
            sortOrder = p.number("order") ?: 0,
        )
    }

    /**
     * An **ability check** — the six scores, read as the d20 roll you make with them.
     *
     * ### Where this rule comes from
     *
     * The live capture, like [damageDefense]: 03 lists no rule for rolls at all. An ability
     * lives where every other tracked number does — `type: "attribute"` — under
     * `attributeType: "ability"`, and it carries *two* different numbers plus the advantage
     * rollup:
     *
     * ```json
     * { "type": "attribute", "attributeType": "ability", "name": "…",
     *   "total": 13, "value": 13, "modifier": 1, "advantage": 0, "order": … }
     * ```
     *
     * **[FIELD_MODIFIER], not `value`**, and that is the whole point of this function: `value`
     * / `total` are the *score* (the 3–20 number), and adding a score to a d20 would be off by
     * about ten. `modifier` is the server's own computed `floor((score − 10) / 2)`, which is
     * also why nothing here re-derives it — see [RollModifier.modifier].
     *
     * A missing `modifier` is a skip rather than a fallback to arithmetic on the score: an
     * attribute that does not say what it adds is not a roll this app can answer, and guessing
     * would quietly drop every effect the server folded into the real number.
     */
    private fun abilityCheck(p: JsonObject): RollModifier? {
        if (!p.isAttribute(ATTR_ABILITY) || p.isSkipped()) return null
        return p.toRoll(modifier = p.number(FIELD_MODIFIER) ?: return null)
    }

    /**
     * A **skill, save or check** — everything DiceCloud files under its one `skill` property
     * type that is actually rolled.
     *
     * ### Where this rule comes from
     *
     * The capture again. One property type covers a surprising amount of ground, sorted by a
     * `skillType` discriminator, and the rollable ones share a shape:
     *
     * ```json
     * { "type": "skill", "skillType": "…", "name": "…", "ability": "…",
     *   "abilityMod": 1, "proficiency": 0, "value": 1, "order": … }
     * ```
     *
     * `value` is the **total** the sheet already computed — ability modifier, proficiency,
     * and anything a feature added. [NON_ROLL_SKILL_TYPES] is what keeps the non-rolls out;
     * see there for why the filter is stated as an exclusion.
     *
     * `abilityMod` and `proficiency` are deliberately *not* read. They are the ingredients of
     * `value`, and re-adding them here would be a second implementation of the sheet's own
     * arithmetic — one that would disagree with the sheet the moment a feature contributes
     * anything neither field accounts for.
     *
     * ### Advantage
     *
     * [FIELD_ADVANTAGE] is present on some of these and absent on others, which is exactly
     * how [RollAdvantage.fromWire] treats it: absent and zero are one answer. Every roll in
     * the capture reads zero, and that is not an accident of the capture — the effects that
     * would move it (six of them there, `operation: "disadvantage"`, each naming the rolls it
     * targets) all belong to condition buffs that are switched **off**. Which is the whole
     * mechanism working: turn the condition on and the server recomputes the rollup, and this
     * rule reads the new sign with no further work. Nothing here interprets the effects
     * themselves; that is the server's job and it has already done it.
     */
    private fun skillRoll(p: JsonObject): RollModifier? {
        if (p.string("type") != TYPE_SKILL || p.isSkipped()) return null
        if (p.string("skillType") in NON_ROLL_SKILL_TYPES) return null
        return p.toRoll(modifier = p.number("value") ?: return null)
    }

    /**
     * The shared tail of both roll rules: identity, name, advantage and order.
     *
     * A roll with no name is dropped. The dropdown is a list of names — that *is* its whole
     * content — so a nameless entry would be an un-pickable blank line, and there is no second
     * field to fall back on that a player would recognise.
     */
    private fun JsonObject.toRoll(modifier: Int): RollModifier? {
        val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
        return RollModifier(
            id = string("_id") ?: return null,
            name = name,
            modifier = modifier,
            advantage = RollAdvantage.fromWire(number(FIELD_ADVANTAGE)),
            sortOrder = number("order") ?: 0,
        )
    }

    /**
     * 03 §5, second half: an **enabled** toggle or buff whose name or tags mention
     * concentration drives the banner. Buffs are included because that is how DiceCloud
     * models a spell's ongoing effect.
     */
    private fun concentrationSource(properties: List<JsonObject>): String? = properties
        .asSequence()
        .filter { it.string("type") == TYPE_TOGGLE || it.string("type") == TYPE_BUFF }
        .filter { !it.isTrue("removed") && !it.isTrue("inactive") }
        .firstOrNull { p ->
            p.string("name").orEmpty().contains(CONCENTRATION, ignoreCase = true) ||
                p.strings("tags").any { it.equals(CONCENTRATION, ignoreCase = true) }
        }
        ?.string("name")
        ?.takeIf { it.isNotBlank() }

    // -----------------------------------------------------------------------
    // Shared readers
    // -----------------------------------------------------------------------

    private fun JsonObject.isAttribute(attributeType: String): Boolean =
        string("type") == TYPE_ATTRIBUTE && string("attributeType") == attributeType

    /** The blanket rule from 03: skip `inactive: true` and `removed: true`. */
    private fun JsonObject.isSkipped(): Boolean = isTrue("inactive") || isTrue("removed")

    /**
     * What is left. The server publishes `value` already computed, but falls back to
     * `total − damage` — the relationship 03 §Write semantics is built on — if it is
     * missing.
     */
    private fun JsonObject.remaining(total: Int): Int =
        number("value") ?: (total - (number("damage") ?: 0))

    /** `spellSlotLevel` is a `_calculation` object live; the name is the documented fallback. */
    private fun JsonObject.slotLevel(): Int? = number("spellSlotLevel")
        ?: LEADING_ORDINAL.find(string("name").orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private fun JsonObject.toResource(
        kind: TrackerKind,
        total: Int,
        reset: ResetRule?,
        level: Int? = null,
        /** [TrackerKind.HIT_DICE] only — see [TrackedResource.dieSize]. */
        dieSize: String? = null,
        /** [TrackerKind.HIT_DICE] only — see [TrackedResource.dieModifier]. */
        dieModifier: Int? = null,
        /**
         * What is left, when [remaining]'s `value ?: total − damage` is the wrong arithmetic for
         * this row (FR-44: an `action`/`spell` counts consumption in `usesUsed`, and carries
         * neither `value` nor `damage`). `null` keeps every caller before FR-44 unchanged.
         */
        valueOverride: Int? = null,
    ): TrackedResource? = TrackedResource(
        propertyId = string("_id") ?: return null,
        kind = kind,
        name = string("name").orEmpty(),
        value = valueOverride ?: remaining(total),
        total = total,
        reset = reset,
        spellSlotLevel = level,
        dieSize = dieSize,
        dieModifier = dieModifier,
        sortOrder = number("order") ?: 0,
    )

    // -----------------------------------------------------------------------
    // Override layer (03 §6) — applied last
    // -----------------------------------------------------------------------

    private val NATURAL_ORDER: Comparator<TrackedResource> =
        compareBy<TrackedResource> { it.sortOrder }.thenBy { it.name }

    private val SLOT_ORDER: Comparator<TrackedResource> =
        compareBy<TrackedResource> { it.spellSlotLevel ?: Int.MAX_VALUE }
            .thenBy { it.sortOrder }
            .thenBy { it.name }

    private fun order(
        rows: List<TrackedResource>,
        overrides: Map<String, TrackerOverride>,
        natural: Comparator<TrackedResource>,
    ): List<TrackedResource> = rows
        .asSequence()
        .filter { overrides[it.propertyId]?.hidden != true }
        .map { row -> row.copy(pinned = overrides[row.propertyId]?.pinned == true) }
        .sortedWith(
            // An explicit sortIndex wins; everything without one keeps the server order,
            // sorted after the rows the user placed by hand.
            compareBy<TrackedResource> { overrides[it.propertyId]?.sortIndex ?: Int.MAX_VALUE }
                .then(natural),
        )
        .toList()

    /**
     * Defenses are grouped by [DefenseKind] before anything else, because the section
     * renders one line per kind and a stable within-kind order is all the UI needs from
     * here. The enum's own order is ascending damage multiplier, so this is immunities
     * first (see [DefenseKind]).
     *
     * Only the *hidden* half of the override layer applies: pinning and reordering are
     * both meaningless on a read-only reference line, and v1's customize sheet offers no
     * control that could set an override on one of these rows anyway. The filter is here
     * so that the repo's "overrides are applied last, to everything" rule holds without an
     * exception, rather than because anything can currently trip it.
     */
    private fun orderDefenses(
        rows: List<DamageDefense>,
        overrides: Map<String, TrackerOverride>,
    ): List<DamageDefense> = rows
        .filter { overrides[it.propertyId]?.hidden != true }
        .sortedWith(compareBy({ it.kind }, { it.sortOrder }, { it.name }))

    /**
     * Rolls keep the **sheet's own order** — the server's `order`, then the name.
     *
     * Not alphabetical, and not grouped by kind. `order` is the sequence DiceCloud itself
     * lists these in, so a player scrolling the dropdown finds them where their sheet puts
     * them; re-sorting would make this app's list the one place they have to search rather
     * than scan. (Defenses *are* re-sorted, and for the opposite reason — see [orderDefenses]:
     * there the server's order is the order features were added, which means nothing to a
     * reader scanning three lines. Here it is the sheet's layout, which means a lot to a
     * reader scanning thirty.)
     *
     * Only the *hidden* half of the override layer applies, exactly as for defenses: pinning
     * and reordering are meaningless on a read-only reference row, and v1's customize sheet
     * offers no control that could set one on these. The filter is here so the repo's
     * "overrides are applied last, to everything" rule holds without an exception.
     */
    private fun orderRolls(
        rows: List<RollModifier>,
        overrides: Map<String, TrackerOverride>,
    ): List<RollModifier> = rows
        .filter { overrides[it.id]?.hidden != true }
        .sortedWith(compareBy({ it.sortOrder }, { it.name }))

    private fun orderToggles(
        rows: List<ConditionToggle>,
        overrides: Map<String, TrackerOverride>,
    ): List<ConditionToggle> = rows
        .asSequence()
        .filter { overrides[it.propertyId]?.hidden != true }
        // Pins are carried onto the row for the same reason resources carry theirs: the
        // default view hides *off* toggles, and `ConditionToggle.shownByDefault` needs the
        // user's "always show this one" to be part of the row rather than a lookup every
        // consumer would have to remember to do.
        .map { row -> row.copy(pinned = overrides[row.propertyId]?.pinned == true) }
        .sortedWith(
            compareBy<ConditionToggle> { overrides[it.propertyId]?.sortIndex ?: Int.MAX_VALUE }
                .thenBy { it.sortOrder }
                .thenBy { it.name },
        )
        .toList()
}
