package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
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

    /** The die a hit-dice row counts — `"d8"` on the live sheet. See [hitDieSize]. */
    private const val FIELD_HIT_DICE_SIZE = "hitDiceSize"

    /** Public so the contract export states the rule from the constant that implements it. */
    const val VAR_HIT_POINTS = "hitPoints"

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
        val hitDice = properties.mapNotNull { hitDice(it) }
        val items = properties.mapNotNull { item(it) }
        val toggles = properties.mapNotNull { toggle(it) }
        val defenses = properties.mapNotNull { damageDefense(it) }
        val rolls = properties.mapNotNull { abilityCheck(it) ?: skillRoll(it) }

        val pinnedIds = overrides.values.filter { it.pinned }.map { it.propertyId }.toSet()

        return TrackerBoard(
            hp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.HIT_POINTS) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            tempHp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.TEMP_HP) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            slots = order(slots, overrides, SLOT_ORDER),
            resources = order(resources, overrides, NATURAL_ORDER),
            // FR-30 decision 17. Sorted but **not** override-filtered, unlike the two lists above
            // and for `deathSaves`' reason one line down: the customize sheet builds its sections
            // from slots, resources, items and toggles, so nothing anywhere can pin, hide or
            // reorder a hit-dice row. Running them through `order` would let a stale preference
            // hide one with no control on screen able to bring it back. See `TrackerBoard.hitDice`.
            hitDice = hitDice.sortedWith(NATURAL_ORDER),
            allItems = order(items, overrides, NATURAL_ORDER),
            pinnedItems = order(items.filter { it.propertyId in pinnedIds }, overrides, NATURAL_ORDER),
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
    private fun hitDice(p: JsonObject): TrackedResource? {
        if (!p.isAttribute(ATTR_HIT_DICE) || p.isSkipped()) return null
        val total = p.number("total") ?: 0
        val value = p.remaining(total)
        if (total <= 0 && value <= 0) return null
        return p.toResource(
            TrackerKind.HIT_DICE,
            total = total,
            reset = null,
            dieSize = p.hitDieSize(),
        )
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
    ): TrackedResource? = TrackedResource(
        propertyId = string("_id") ?: return null,
        kind = kind,
        name = string("name").orEmpty(),
        value = remaining(total),
        total = total,
        reset = reset,
        spellSlotLevel = level,
        dieSize = dieSize,
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
