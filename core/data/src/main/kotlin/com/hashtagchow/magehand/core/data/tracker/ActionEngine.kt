package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionCost
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.ActionUses
import com.hashtagchow.magehand.core.model.CostLine
import com.hashtagchow.magehand.core.model.DamageLine
import com.hashtagchow.magehand.core.model.DamageRider
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.SpellListHeader
import com.hashtagchow.magehand.core.model.WeaponMastery

/**
 * Turns one creature's raw properties into an [ActionBoard] (docs/design/16-actions-and-feed.md,
 * FR-26).
 *
 * Pure, like [TrackerEngine] and [InventoryEngine]: no I/O, no coroutines, no clock, same input
 * → same output whether the input came from the REST snapshot or the live DDP mirror. It is the
 * third engine over the same [CreatureSheet] seam, and it reads it the same way.
 *
 * ### Read-only by construction
 *
 * 16 decision 7: *"Zero new writes … Read-only surface end to end."* Nothing in this file names a
 * DiceCloud method, builds a write op or produces anything a caller could send. The `prepared`
 * write path (`creatureProperties.update path:['prepared']`) is probed and recorded for v2 and
 * is deliberately absent here.
 *
 * ### What this engine does NOT do, and why that is the whole design
 *
 * It computes nothing. Every number it emits is one the server already published — the dice
 * string, the attack bonus, the save DC, the uses remaining. 10 decision 3's grand-total lesson
 * generalised: a client that re-derives a number the server also publishes will eventually
 * disagree with the sheet, and the disagreement is silent. See [SpellEntry]'s missing hit bonus
 * for the case where *not* computing is the single most important thing this file does.
 */
object ActionEngine {

    // --- DiceCloud vocabulary (verified against the live capture, 2026-08-24) ---
    private const val TYPE_SPELL = "spell"
    private const val TYPE_ACTION = "action"
    private const val TYPE_ITEM = "item"
    private const val TYPE_SPELL_LIST = "spellList"
    private const val TYPE_BRANCH = "branch"
    private const val TYPE_DAMAGE = "damage"

    // --- FR-47 weapon-mastery vocabulary (live probe, 2026-09-07 — docs/dicecloud-api.md,
    // "Weapon items and their 'Mastery' line") ---
    private const val TYPE_FEATURE = "feature"
    private const val TYPE_TRIGGER = "trigger"
    private const val TYPE_PROPERTY_SLOT = "propertySlot"
    private const val SLOT_TAGS = "slotTags"
    private const val TARGET_TAGS = "targetTags"
    private const val TAGS = "tags"

    /**
     * The tag that identifies the *"choose your weapon masteries"* slot — **not** its name.
     *
     * FR-47 R6 in as many words: *"match the tag, never the name"*. The probe found the same slot
     * called two different things on two sheets of the same edition, because the name is written
     * by whichever class entry the library happened to build it from. `slotTags` is the machine
     * half of that property and is the same on both.
     */
    private const val WEAPON_MASTERY_SLOT_TAG = "weaponMastery"

    /**
     * The branch kind that carries an attack's on-hit damage.
     *
     * The capture holds four `branchType`s — `hit`, `failedSave`, `successfulSave` and `if` —
     * and **only `hit` has `damage` children on it**. So this constant is not a narrowing that
     * costs coverage; it is the only kind that has anything to contribute, and naming it keeps a
     * future save-rider branch from silently being read as on-hit damage.
     */
    private const val BRANCH_HIT = "hit"

    // --- FR-28 cost vocabulary (docs/design/17-use-action.md decision 1) ---
    private const val RESOURCES = "resources"
    private const val ATTRIBUTES_CONSUMED = "attributesConsumed"
    private const val ITEMS_CONSUMED = "itemsConsumed"
    private const val QUANTITY = "quantity"
    private const val VARIABLE_NAME = "variableName"
    private const val ITEM_ID = "itemId"
    private const val USES = "uses"
    private const val USES_USED = "usesUsed"

    /**
     * How deep [damageFor] will walk. The real shapes are one and two levels; this is the guard
     * against a cyclic `parent` chain in malformed data turning a render into a hang.
     */
    private const val MAX_DAMAGE_DEPTH = 4

    /**
     * How far the three mastery walks will go: up from an action to its `item`, down from that
     * item to its `feature`s, and up from a `trigger` to its `propertySlot`.
     *
     * The same number as [MAX_DAMAGE_DEPTH] and for the same reason — the real shapes are one and
     * two levels deep (`item → folder → {feature, action}`, `propertySlot → folder → trigger`),
     * and the bound is the guard against a cyclic `parent` chain turning a render into a hang.
     * One constant rather than three because a sheet deep enough to need a different answer for
     * one of them is a sheet this engine has never seen.
     */
    private const val MAX_MASTERY_DEPTH = 4

    /**
     * R1's line, in the weapon `feature`'s `summary.text`: *"Versatile\nMastery: Topple"*.
     *
     * Applied per line and anchored at both ends, so *"Mastery"* appearing mid-sentence in a
     * feature's prose is not a match. The optional trailing `.` is there because the sheets carry
     * both *"Mastery: Vex"* and *"Mastery: Vex."*, and a badge reading *"Mastery: Vex."* would be
     * this app inventing punctuation.
     *
     * ### `^Mastery:` is anchored at column zero, deliberately
     *
     * Review LOW-5. A summary line written *"  Mastery: Vex"* — with leading whitespace — does not
     * match and the row stays silent. This is R1's regex character for character (*"a line matching
     * `^Mastery:\s*(.+?)\.?\s*$`"*), so widening it to `^\s*Mastery:` would be a new deviation from
     * a ledger ruling rather than a fix, and no probed sheet writes the line indented. It is
     * recorded here so a live miss is diagnosable without re-deriving the regex: if a sheet ever
     * indents the line, this anchor is the reason and the ledger is where the change starts.
     */
    private val MASTERY_SUMMARY_LINE = Regex("""^Mastery:\s*(.+?)\.?\s*$""")

    /**
     * R7's bullet, in the same feature's `description.text`:
     * `- **Mastery: Nick.** When you make the extra attack…`
     *
     * The library writes `**Mastery: Vex.**` on these and `**Finesse:**` / `**Two-Handed.**` on the
     * weapon's other properties, so both lead punctuations are accepted. Group 1 is the bullet's
     * own indentation — R7's regex has a bare `\s*` there, and capturing it is what lets
     * [masteryBulletText] tell a *"next top-level bullet"* (which ends the mastery's text) from a
     * nested `  - ` sub-bullet (which does not).
     */
    private val MASTERY_BULLET = Regex("""^(\s*)-\s*\*\*Mastery:\s*(.+?)[.:]?\*\*[.:]?\s*(.*)$""")

    /**
     * Any markdown list item, with its indentation captured. See [masteryBulletText].
     *
     * The lookahead is what keeps this in step with [MASTERY_BULLET] (review LOW-5). That regex
     * accepts `-\s*\*\*Mastery:`, so the library's occasional unspaced `-**Versatile.**` **is** a
     * bullet to it; a bare `-\s` here would not have recognised the same line as the next property
     * and would have swallowed it into the mastery's rules text. `\s|\*\*` covers exactly the
     * shapes [MASTERY_BULLET] can start with — every line that one matches, this one matches too —
     * while still declining `---` (a rule) and `-5 to hit` (prose), neither of which is a bullet
     * and neither of which should end the text.
     */
    private val ANY_BULLET = Regex("""^(\s*)-(?=\s|\*\*)""")

    /**
     * The `**`/`*` emphasis runs [masteryBulletText] and [masteryFromFeature] strip.
     *
     * 16 decision 4: *"plain text (no markdown rendering v1)"*. Stripping is the honest half of
     * that ruling — leaving the asterisks in would print `**Nick.**` at a player, and rendering
     * them would be the markdown renderer the decision declines.
     *
     * ### Only asterisks that hug their content
     *
     * Review LOW-5: a bare `\*+` also ate a literal asterisk in prose, so *"2 \* your level"*
     * rendered as *"2  your level"* — the app deleting a character the server sent. A markdown
     * delimiter always touches the text it emphasises on at least one side, and a multiplication
     * sign is spaced on both, so the two alternatives here ("preceded by non-space" or "followed by
     * non-space") strip every `**bold**` and `*italic*` run and leave a standalone `*` alone.
     */
    private val MARKDOWN_EMPHASIS = Regex("""(?<=\S)\*+|\*+(?=\S)""")

    /**
     * Builds the board.
     *
     * @param sheet the same input [TrackerEngine.build] and [InventoryEngine.build] take, from
     *   either source.
     */
    fun build(sheet: CreatureSheet): ActionBoard {
        // `livePropertyList`, not `propertyList`: 16 decision 2 filters `removed` and
        // deliberately does NOT filter `inactive` — see `inactiveIsRendered` below. This is the
        // accessor `CreatureSheet` added for exactly this class of new consumer.
        val properties = sheet.livePropertyList
        val childrenByParent = properties.groupBy { it.parentId() }
        val resources = ResourceIndex.of(properties)
        // FR-47 R6: the chosen masteries and the per-item words are BOTH computed once here, for
        // `ResourceIndex`'s stated reason — a board rebuilds on every mirror change, and a
        // per-row scan of the sheet for a property slot on the other side of it is the shape of
        // cost that only shows up on a DM dashboard watching six creatures.
        val masteries = masteryIndexOf(properties, childrenByParent)

        val spells = properties
            .filter { it.string("type") == TYPE_SPELL }
            .mapNotNull { it.toSpell(childrenByParent, resources) }
            // Decision 3: *"sorted by `order` then STABLE-sorted by `level`"*. Two passes, in
            // that sequence, because that is what "stable" buys: `sortedBy` is stable in Kotlin,
            // so sorting by level second preserves the `order` sequence *within* each level.
            // One `compareBy(level, order)` would give the same answer here and would stop
            // doing so the moment a level's rows needed any other tie-break — the two-pass form
            // states the rule the design states.
            .sortedBy { it.sortOrder }
            .sortedBy { it.level }

        val actions = properties
            .filter { it.string("type") == TYPE_ACTION }
            .mapNotNull { it.toAction(childrenByParent, resources, masteries) }
            // Decision 3: group order first (the enum's own declaration order — see
            // `ActionGroup`), then the sheet's `order` inside a group. Name is the final
            // tie-break so a rebuild of the same sheet cannot reshuffle two rows that share an
            // `order`, which is `TrackerEngine`'s NATURAL_ORDER rule applied here.
            .sortedWith(compareBy({ it.group }, { it.sortOrder }, { it.name }))

        val spellLists = properties
            .filter { it.string("type") == TYPE_SPELL_LIST }
            .mapNotNull { it.toSpellList() }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))

        return ActionBoard(spells = spells, actions = actions, spellLists = spellLists)
    }

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * A spell row (16 decisions 3, 4 and 5).
     *
     * ### `attackRoll` is read here and thrown away — deliberately, and there is no field for it
     *
     * This function has the spell's `attackRoll` in its hand and does not touch it. 16 decision
     * 4: *"NEVER render a spell hit bonus from `attackRoll.value`"*. The live capture is
     * unambiguous about why — every spell's `attackRoll.calculation` reads
     * `#spellList.attackRollBonus`, a reference into a casting context that does not exist at
     * rest, so the published `value` is `0` on all three spells that carry one and will stay `0`
     * forever. [SpellEntry] has no property to put it in, which is what makes this a rule the
     * type system enforces rather than a comment someone has to read.
     *
     * The honest number is the spell **list**'s DC and ability modifier — see [toSpellList].
     */
    private fun JsonObject.toSpell(
        childrenByParent: Map<String?, List<JsonObject>>,
        resources: ResourceIndex,
    ): SpellEntry? {
        val id = string("_id") ?: return null
        return SpellEntry(
            propertyId = id,
            name = string("name").orEmpty(),
            // Absent `level` reads as a cantrip, which is what DiceCloud means by omitting it —
            // and the header it drives ("Cantrips") is the one a reader can verify at a glance.
            level = number("level") ?: 0,
            concentration = isTrue("concentration"),
            ritual = isTrue("ritual"),
            // The FIELDS, per decision 5. `inactive` is carried beside them, never instead of
            // them — see `SpellEntry.showsUnpreparedBadge` for the Animate Dead case that makes
            // the difference load-bearing.
            prepared = isTrue("prepared"),
            alwaysPrepared = isTrue("alwaysPrepared"),
            inactive = isTrue("inactive"),
            castingTime = text("castingTime"),
            range = text("range"),
            description = text("description"),
            summary = text("summary"),
            damage = damageFor(id, childrenByParent),
            cost = costFor(resources),
            uses = usesFor(),
            sortOrder = number("order") ?: 0,
        )
    }

    /**
     * An action or attack row (16 decisions 3 and 4).
     *
     * `attackRoll.value` **is** read here, and that asymmetry with [toSpell] is the point: a
     * weapon's calculation resolves against the character's own attributes
     * (`max(daggerWeapon,simpleMeleeWeapon)` → `3` in the capture), so the server's number is
     * true at rest. `ActionEngineTest` pins the pair.
     */
    private fun JsonObject.toAction(
        childrenByParent: Map<String?, List<JsonObject>>,
        resources: ResourceIndex,
        masteries: MasteryIndex,
    ): ActionEntry? {
        val id = string("_id") ?: return null
        return ActionEntry(
            propertyId = id,
            name = string("name").orEmpty(),
            type = ActionType.fromWire(string("actionType")),
            attackRoll = number("attackRoll"),
            usesLeft = number("usesLeft"),
            usesMax = number("uses"),
            insufficientResources = isTrue("insufficientResources"),
            inactive = isTrue("inactive"),
            description = text("description"),
            summary = text("summary"),
            damage = damageFor(id, childrenByParent),
            cost = costFor(resources),
            uses = usesFor(),
            mastery = masteryFor(masteries),
            sortOrder = number("order") ?: 0,
        )
    }

    // -----------------------------------------------------------------------
    // FR-28 — cost and uses (docs/design/17-use-action.md decision 1)
    // -----------------------------------------------------------------------

    /**
     * The live sheet, indexed the two ways a cost line needs to be looked up.
     *
     * ### Why an index rather than a scan per line
     *
     * A sheet carries hundreds of properties and a caster's board carries dozens of rows, each
     * with up to a handful of cost lines. Scanning the property list per line is O(rows × lines ×
     * properties) on every board rebuild, and the board rebuilds on **every mirror change** — a
     * DM dashboard watching six creatures rebuilds constantly. Two maps built once per build make
     * it O(properties + rows × lines), which is what [ActionEngine.build]'s existing
     * `childrenByParent` already does for the damage walk. Same pattern, same reason.
     *
     * @property attributeValues `variableName` → the attribute's own `value`.
     * @property itemQuantities `_id` → the item's own `quantity`.
     */
    private class ResourceIndex(
        val attributeValues: Map<String, Int>,
        val itemQuantities: Map<String, Int>,
        val names: Map<String, String>,
    ) {
        companion object {
            /**
             * Both maps in one pass over the live properties.
             *
             * A **duplicate `variableName` keeps the first** it meets, which is the sheet's own
             * order. DiceCloud allows two properties to declare one variable name and resolves
             * the collision by its own rules, which this app does not implement; picking the
             * first is at least stable across rebuilds, and picking the *largest* — the tempting
             * "be generous" choice — would let a stale duplicate unlock a use the sheet cannot
             * fund. See [CostLine.satisfied] for what happens when the lookup misses entirely.
             */
            fun of(properties: List<JsonObject>): ResourceIndex {
                val attributes = HashMap<String, Int>()
                val quantities = HashMap<String, Int>()
                val names = HashMap<String, String>()
                for (property in properties) {
                    val id = property.string("_id") ?: continue
                    property.string("name")?.takeIf { it.isNotBlank() }?.let { names[id] = it }
                    property.string(VARIABLE_NAME)?.takeIf { it.isNotBlank() }?.let { variable ->
                        property.number("value")?.let { attributes.putIfAbsent(variable, it) }
                        names.putIfAbsent(variable, property.string("name").orEmpty())
                    }
                    // `quantity` absent reads as 1 — `quantityRule` in the contract export, and
                    // the same reading `InventoryEngine` uses. An item with no quantity field is
                    // one of that item, not none.
                    if (property.string("type") == TYPE_ITEM) {
                        quantities[id] = property.number(QUANTITY) ?: 1
                    }
                }
                return ResourceIndex(attributes, quantities, names)
            }
        }
    }

    /**
     * 17 decision 1's **Cost**, joined against the live sheet.
     *
     * ### What is read, and what is deliberately not
     *
     * Each `attributesConsumed` entry carries a `variableName`, a `quantity` calculation, and —
     * on a recomputed sheet — a `statName` and an `available` rollup. This reads the first two
     * and **ignores `available`**, joining `variableName` against the attribute property's own
     * `value` instead. `itemsConsumed` gets the same treatment: `itemId` joined against the
     * item's `quantity`, never the entry's `available`.
     *
     * That is 17 decision 1's whole instruction (*"the server fields are a slow confirmation
     * only"*) and probe U5 is the measurement behind it: `available` and `insufficientResources`
     * are recomputed on a debounced pass 4–10 s behind the write, while `value` and `quantity`
     * are written synchronously. Reading the rollup would mean a Use button that stays lit for
     * ten seconds after the last charge is gone — which is the exact window probe U3's burst
     * lives in.
     *
     * The **display name** comes from the resolved property, falling back to the entry's own
     * `statName` and then to the raw `variableName`. Never straight to `variableName` when
     * anything better exists: "rage" is not what the sheet calls the row, and a dialog that lists
     * `rageResource: 1` as what a tap will spend is asking the player to trust a word they have
     * never seen.
     */
    private fun JsonObject.costFor(resources: ResourceIndex): ActionCost {
        val block = this[RESOURCES] as? JsonObject ?: return ActionCost.FREE

        val attributes = block.entries(ATTRIBUTES_CONSUMED).mapNotNull { entry ->
            val variable = entry.string(VARIABLE_NAME)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CostLine(
                name = resources.names[variable]?.takeIf { it.isNotBlank() }
                    ?: entry.string("statName")?.takeIf { it.isNotBlank() }
                    ?: variable,
                amount = entry.number(QUANTITY) ?: 1,
                available = resources.attributeValues[variable],
            )
        }

        val items = block.entries(ITEMS_CONSUMED).mapNotNull { entry ->
            val itemId = entry.string(ITEM_ID)?.takeIf { it.isNotBlank() }
            CostLine(
                name = itemId?.let { resources.names[it] }?.takeIf { it.isNotBlank() }
                    ?: entry.string("itemName")?.takeIf { it.isNotBlank() }
                    ?: entry.string("tag")?.takeIf { it.isNotBlank() }
                    // A consumed item the sheet has not been told the identity of is a real
                    // shape — the entry exists with a tag and no `itemId` until the player picks
                    // one — and it has no name to print, so the line is dropped rather than
                    // rendered as a blank bullet in a confirm dialog.
                    ?: return@mapNotNull null,
                amount = entry.number(QUANTITY) ?: 1,
                available = itemId?.let { resources.itemQuantities[it] },
            )
        }

        return if (attributes.isEmpty() && items.isEmpty()) {
            ActionCost.FREE
        } else {
            ActionCost(attributes = attributes, items = items)
        }
    }

    /**
     * 17 decision 1's **Uses** — `uses.value − usesUsed`, expressed as the pair rather than the
     * difference.
     *
     * `null` when the row states no `uses` at all, which is what an unlimited action looks like
     * and is why [ActionUses] is nullable rather than defaulting to zero: a `max` of 0 would read
     * as "exhausted" and hide the Use button on every unlimited row on the sheet.
     *
     * `usesUsed` absent reads as **0**, not as null-propagating: a limited row the player has
     * never used carries no counter, and treating that as "unknown" would suppress the uses line
     * on exactly the rows where it is most obviously true.
     *
     * See [ActionUses] for why this pair and not the server's own `usesLeft`.
     */
    private fun JsonObject.usesFor(): ActionUses? {
        val max = number(USES) ?: return null
        return ActionUses(max = max, used = number(USES_USED) ?: 0)
    }

    /** The objects in an array-valued field; empty for a field that is absent or the wrong shape. */
    private fun JsonObject.entries(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /**
     * A `spellList` header — the DC and ability modifier the surface shows *instead of* a
     * per-spell hit bonus (16 decision 4).
     *
     * Both numbers are nullable and neither is defaulted. A spell list whose `dc` the server did
     * not compute renders without one; printing `DC 0` or `DC 10` would be an invented fact
     * about a number players read off the screen and quote at the table.
     */
    private fun JsonObject.toSpellList(): SpellListHeader? = SpellListHeader(
        propertyId = string("_id") ?: return null,
        name = string("name").orEmpty(),
        dc = number("dc"),
        abilityMod = number("abilityMod"),
        sortOrder = number("order") ?: 0,
    )

    /**
     * The damage rollups hanging off one action or spell (16 decision 4).
     *
     * ### The design says "branch(hit) → damage"; the sheet says BOTH shapes, and this walks both
     *
     * Decision 4's wording is *"walk descendants for `branch(branchType:'hit')` → `damage`
     * children"*, and read as a literal two-step path — action, then hit-branch, then damage —
     * it renders **no damage at all for the majority of rows**. The live capture settles it:
     *
     * ```text
     *   damage properties: 17 total
     *     parent is the action/spell itself   →  9   ("direct")
     *     parent is a branch(branchType:hit)  →  8
     * ```
     *
     * and the split is not by row kind either — of the five `actionType: 'attack'` rows, **three
     * carry their damage directly and two carry it under a hit-branch**, on the same sheet. A
     * literal reading would have printed a full damage line for two weapons and nothing for the
     * other three, which is the shape of bug that looks like missing data rather than a wrong
     * rule.
     *
     * So this is a *descendant walk* (the word decision 4 also uses) in which a `branch` is a
     * node you pass **through**, not a node you require. Direct `damage` children are collected;
     * `branch` children are descended into only when their `branchType` is `hit`.
     *
     * ### Why the other three branch kinds are not descended into
     *
     * `failedSave`, `successfulSave` and `if` are conditional riders, not the row's damage — a
     * save-or-suck spell's failed-save damage is a different question from "what does this hit
     * for". Following decision 4's named kind costs nothing on the evidence: **no `damage`
     * property in the capture hangs under any of the three**, so this exclusion removes zero
     * rows today and keeps a future save-rider from being mislabelled as on-hit damage.
     *
     * ### Filtering matches the row's own filter, not the tracker's
     *
     * `removed` only — the same rule [build] applies to the rows themselves, and for the same
     * reason (decision 2: *"`removed`, but NOT `inactive`"*). A dimmed, switched-off action still
     * shows what it *would* do; suppressing its damage line would leave a row that is visibly
     * present and silently incomplete, which is worse than either showing it or hiding the row.
     */
    private fun damageFor(
        ownerId: String,
        childrenByParent: Map<String?, List<JsonObject>>,
    ): List<DamageLine> {
        val collected = mutableListOf<JsonObject>()

        fun walk(parentId: String, depth: Int) {
            if (depth > MAX_DAMAGE_DEPTH) return
            for (child in childrenByParent[parentId].orEmpty()) {
                val childId = child.string("_id") ?: continue
                when (child.string("type")) {
                    TYPE_DAMAGE -> collected += child
                    TYPE_BRANCH -> if (child.string("branchType") == BRANCH_HIT) {
                        walk(childId, depth + 1)
                    }
                    else -> Unit
                }
            }
        }
        walk(ownerId, 0)

        return collected
            .sortedBy { it.number("order") ?: 0 }
            .mapNotNull { it.toDamageLine() }
    }

    /**
     * One `damage` property → a printable line.
     *
     * `amount` is read as **text**, not as a number: it is a `_calculation` whose `value` is a
     * dice string like `"2d4 + 2"`, and [number] would either fail or — worse — succeed on the
     * leading digit and print *"2 necrotic"* for `2d8`. See [text].
     *
     * The base is now the server's own characters in the numeric case too (BUG-10, fixed in
     * 1.14.2): [text]'s wrapped-number branch reads the `value` primitive's `content` rather
     * than routing it through [number], so a `{"amount": {"value": 2.5}}` row renders *"2.5
     * radiant"* and no longer truncates to a number the sheet never published. That is the rule
     * [riderAmount] already applied to the riders, applied to the line they hang off.
     *
     * A damage row with no computed amount is dropped rather than rendered as a bare damage
     * type: *"necrotic"* on its own is not information a player can act on, and an empty line
     * beside a real one reads as a rendering fault.
     *
     * ### RECORDED FINDING: `amount.value` is not uniformly resolved at rest
     *
     * 16 decision 4 says *"render `amount.value` dice string"* and this function does exactly
     * that — but the capture shows `value` is **not** always the finished string the wording
     * implies. All four shapes below are real, on one sheet:
     *
     * ```text
     *   calculation "2d4 + 2"                  → value "2d4 + 2"            fully resolved
     *   calculation "1d6"                      → value "d6"                 leading 1 elided
     *   calculation "(floor((level+1)/6)+1)d8" → value "d8"                 count NOT resolved
     *   calculation "magicMissileDamage"       → value "magicMissileDamage" symbol unresolved
     * ```
     *
     * The middle two are the same family as [toSpell]'s `attackRoll` trap: a value that only
     * fully resolves in a cast context, published at rest in a partially-evaluated form. `d6`
     * for `1d6` is harmless (they are the same notation), but `d8` for a cantrip that scales to
     * `2d8` understates the damage, and a row reading *"magicMissileDamage force"* is not a
     * sentence.
     *
     * **This engine still renders `value` verbatim**, because the alternative is worse and the
     * design forbids it in as many words: parsing `calculation` and substituting the character's
     * own variables is client dice math — a second implementation of the sheet's rules engine,
     * which is the thing 16 decision 4 and 10 decision 3 both exist to prevent. Falling back to
     * `calculation` when `value` "looks unresolved" would be a heuristic guessing at which of
     * two server fields is more true, and it would print the raw formula at players in exactly
     * the cases it fired wrongly.
     *
     * So the honest position is: show what the server computed, and record that on some rows
     * that is less than the whole answer. `ActionEngineTest` pins all four shapes so the next
     * wave sees the evidence rather than rediscovering it, and the wave report raises it for the
     * architect — a design amendment (or a probe of what the official UI shows here) is a
     * decision above this engine's pay grade.
     */
    private fun JsonObject.toDamageLine(): DamageLine? {
        val base = text("amount")?.takeIf { it.isNotBlank() } ?: return null
        return DamageLine.of(
            base = base,
            damageType = string("damageType").orEmpty(),
            riders = ridersOn(this["amount"] as? JsonObject),
        )
    }

    /**
     * The effects the server attached to one damage `amount` (FR-36).
     *
     * ### This is the rollup the verbatim ruling was missing
     *
     * Beside `amount.value`, a damage `_calculation` carries `effects`: every `effect` property on
     * the sheet whose `targetTags` match this damage row, each already resolved against the
     * character — `{name: "Finesse Modifiers", operation: "add", amount: {value: 3}}`. The
     * attack roll folds its own `effects` into `attackRoll.value` (a Rogue's finesse Rapier on a live sheet: 0 + 3 + 2 =
     * 5); the damage roll does **not**, so `value` reads `d8` while the roll DiceCloud makes is
     * `1d8 + 3 + 2d6`. Reading the array is reading a server answer, the same as reading `value`.
     * Nothing here evaluates a calculation.
     *
     * ### An amount-less effect is dropped only when it is an `add`
     *
     * Review finding 4: the first cut dropped **every** effect whose amount did not resolve,
     * which silently deleted the operations that legitimately carry no amount. A
     * `{operation: "conditional", text: "undead"}` on a damage row means the damage is
     * conditional, and a row that renders it as nothing is a row asserting the damage is
     * unconditional — the one thing the 16 addendum's *"an unknown operation is stated in
     * words"* was written to prevent. So: an `add` with nothing to add contributes nothing and
     * goes; a *named* operation with no amount survives with a blank [DamageRider.amount] and
     * chips as *name · operation*.
     *
     * ### An effect with neither an operation nor an amount says nothing, so it is dropped
     *
     * Pre-release review M4. The rule above, read literally, kept `{"_id": "e1"}` — no operation,
     * no amount — as a rider whose label is the empty string, which the row then drew as an
     * empty chip and TalkBack read as a pause in the middle of the sentence. The survivor case
     * exists because *"conditional"* is a fact worth stating; an effect that names no operation
     * and resolves to no amount states nothing at all, and a blank operation is exactly the
     * shape whose meaning this build cannot even guess at. Dropped when the amount is
     * unresolved and the operation is blank or `add`; [DamageLine.chips] declines a blank label
     * as well, so no other route to an empty chip survives either.
     *
     * Each rider's amount is read by [riderAmount] — the primitive's own text, not [text] and
     * emphatically not [number] — and [DamageRider.foldsIntoHeadline] is where a foldable one
     * parts ways from a chip.
     */
    private fun ridersOn(amount: JsonObject?): List<DamageRider> {
        val effects = amount?.get("effects") as? JsonArray ?: return emptyList()
        return effects.filterIsInstance<JsonObject>().mapNotNull { effect ->
            val operation = effect.string("operation").orEmpty()
            val value = effect.riderAmount()
            val statesNothing = operation.isBlank() || operation == DamageRider.OPERATION_ADD
            if (value == null && statesNothing) return@mapNotNull null
            DamageRider(
                name = effect.string("name").orEmpty(),
                operation = operation,
                amount = value.orEmpty(),
            )
        }
    }

    /**
     * One effect's `amount.value` as **the server's own characters** — `"3"`, `"-1"`, `"1.5"`,
     * `"2d6"` — or `null` when it did not resolve.
     *
     * ### Why this does not reuse [text] (review finding 1, BUG-8)
     *
     * [text] falls through to [number] for a numeric `value`, and [number] is an `Int` reader
     * whose `toDoubleOrNull()?.toInt()` truncates: a `1.5` rider arrived as `"1"` and **folded**,
     * so the row printed `d8 + 1` for a bonus the sheet says is one-and-a-half. That is the app
     * publishing its own number, which is the single thing 16 decision 4 forbids, and it is the
     * hazard `CreatureSheet.decimal`'s KDoc already exists to name. Taking the [JsonPrimitive]'s
     * `content` keeps every shape verbatim: `1.5` fails `toIntOrNull()` in
     * [DamageRider.foldsIntoHeadline] and chips with the true value beside its name.
     *
     * `value` and only `value`, and this is a **deliberate behaviour change from 1.13.0**: [text]
     * preferred `text` over `value`, so an effect published as `{"amount": {"text": "3"}}` used
     * to resolve; here it reads as unresolved and the `add` carrying it is dropped. A
     * `_calculation`'s `text` is the un-substituted source and this is the one field where the
     * *resolved* answer is the whole point — folding a source expression into a damage headline
     * would print a formula at a player. Recorded in BUG-8's ledger cell. A bare `"amount": 3` —
     * no wrapper — is accepted, because a primitive is a primitive.
     *
     * Surrounding whitespace is trimmed (review L1): `{"value": " 3"}` is the server saying
     * three, and a rider that chips instead of folding because of a space would be this app
     * reporting a formatting artefact as a fact about the roll. Only the padding goes; the
     * characters are the server's.
     */
    private fun JsonObject.riderAmount(): String? {
        val element = when (val amount = this["amount"]) {
            is JsonObject -> amount["value"]
            else -> amount
        }
        return (element as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    // -----------------------------------------------------------------------
    // FR-47 — weapon mastery (rulings R1, R6 and R7 on the ledger row)
    // -----------------------------------------------------------------------

    /**
     * The two facts a mastery badge is a join of, both computed once per [build].
     *
     * ### Why the chosen set is a list of tag-SETS and not a set of names
     *
     * R6: the badge shows only when *"some chosen-mastery trigger's `targetTags` are **all**
     * present in the action's `tags`"* — DiceCloud's own trigger semantics. A build's *Battleaxe
     * Mastery* carries `['battleaxeWeapon', 'strengthAttack']`, so it fires on a strength attack
     * with a battleaxe and not on a hypothetical finesse one with the same weapon. Reducing that
     * to a set of weapon names would have thrown away the second tag, which is the half that
     * makes the rule mean anything.
     *
     * A trigger publishing **no** `targetTags` is dropped rather than kept as an empty set. An
     * empty set is a subset of every action's tags, so keeping one would badge every attack on the
     * sheet with that mastery's word — the failure mode being loudest exactly where the data is
     * thinnest. R6 describes a chosen mastery as one *"carrying `targetTags`"*; one that carries
     * none has not said what it applies to.
     *
     * ### Why the item→word map is eager
     *
     * A weapon has three attack rows and they share one `feature`, so a per-row walk would parse
     * the same summary three times. The items are walked once instead. It is skipped entirely
     * when nothing is chosen, because then no row can show a badge whatever its item says.
     *
     * @property byId every live property by `_id` — the parent chain both upward walks follow.
     * @property masteryByItem an `item`'s `_id` → the word (and rules text) its features name.
     * @property chosenTargetTags one entry per live chosen-mastery `trigger`.
     */
    private class MasteryIndex(
        val byId: Map<String, JsonObject>,
        val masteryByItem: Map<String, WeaponMastery>,
        val chosenTargetTags: List<Set<String>>,
    )

    /**
     * [MasteryIndex], built from the same live property list [build] hands everything else.
     *
     * Not a `companion object.of` like [ResourceIndex]'s, and the reason is mechanical rather than
     * a taste call: this needs [text] and [parentId], which are private member extensions of this
     * object and are therefore out of scope inside a nested class. The shape a reader should take
     * from it is the same one — one pass, per build, never per row.
     *
     * `properties` is `livePropertyList`, so **`removed` is already gone** — a soft-removed
     * trigger (which is what an *unchosen* mastery looks like on the wire) never reaches the
     * filter below, and a soft-removed feature is not among the item's descendants. `inactive` is
     * the case that has to be decided here, and the two answers differ:
     *
     *  - an `inactive` **trigger** is dropped: R6's chosen set is what the build has switched on.
     *  - an `inactive` **feature** is read: the feature goes inactive when the weapon is
     *    unequipped, and the row is dimmed already — the mastery is a fact about the weapon, not
     *    about whether it is in hand right now (R1).
     */
    private fun masteryIndexOf(
        properties: List<JsonObject>,
        childrenByParent: Map<String?, List<JsonObject>>,
    ): MasteryIndex {
        val byId = properties.mapNotNull { property ->
            property.string("_id")?.let { it to property }
        }.toMap()

        val chosen = properties
            .filter { it.string("type") == TYPE_TRIGGER && !it.isTrue("inactive") }
            .filter { it.underWeaponMasterySlot(byId) }
            .map { it.strings(TARGET_TAGS).toSet() }
            .filter { it.isNotEmpty() }

        val masteryByItem = if (chosen.isEmpty()) {
            emptyMap()
        } else {
            properties
                .filter { it.string("type") == TYPE_ITEM }
                .mapNotNull { item ->
                    val id = item.string("_id") ?: return@mapNotNull null
                    masteryUnder(id, childrenByParent)?.let { id to it }
                }
                .toMap()
        }

        return MasteryIndex(byId = byId, masteryByItem = masteryByItem, chosenTargetTags = chosen)
    }

    /**
     * Whether this `trigger` hangs under the *Weapon Masteries* slot — R6's *"match the tag, never
     * the name"*.
     *
     * The live shape is `propertySlot → folder → trigger`, so this is a two-hop walk with
     * [MAX_MASTERY_DEPTH]'s guard on it. It asks every ancestor rather than only the grandparent
     * because the folder layer is the library's packaging and not something the wire promises.
     */
    private fun JsonObject.underWeaponMasterySlot(byId: Map<String, JsonObject>): Boolean {
        var current = this
        repeat(MAX_MASTERY_DEPTH) {
            val parent = byId[current.parentId()] ?: return false
            if (parent.string("type") == TYPE_PROPERTY_SLOT &&
                WEAPON_MASTERY_SLOT_TAG in parent.strings(SLOT_TAGS)
            ) {
                return true
            }
            current = parent
        }
        return false
    }

    /**
     * R1's word for one `item`: the first of its `feature` descendants, **by `order` then name**,
     * whose `summary` carries a *"Mastery: X"* line.
     *
     * ### First, and only ever one
     *
     * R1: *"Two qualifying features under one item: first by `order` then name, one word rendered;
     * a second different word is a builder finding to ledger, never a second chip."* `order` then
     * name is [build]'s own NATURAL_ORDER tie-break, so two features sharing an `order` cannot
     * reshuffle between two rebuilds of the same sheet and make the badge flicker.
     *
     * Both halves of the comparator are pinned against the wire order rather than with it: the
     * fixtures in `two features under one item render only the first by order` and `two features
     * sharing one order fall back to the name` declare the *losing* feature first, so the answer
     * they assert is one only this `sortedWith` can produce. Review MEDIUM-1 found the earlier
     * fixture agreed with declaration order, which left the whole sort deletable with the suite
     * green.
     *
     * ### A nested `item` is not descended into
     *
     * A container holding another weapon is a real inventory shape, and its contents' features
     * belong to *that* weapon. Descending would give a backpack the mastery of the axe inside it,
     * and — worse — would give the axe's own rows the backpack's answer if the backpack won on
     * `order`. Every other property type is walked through, because the layer between an item and
     * its feature is `folder` on the sheets probed and there is no promise it stays that.
     *
     * The rule is stated here because R1 does not state it: R1 says *"walk up… to the first
     * `item`, then take that item's `feature` descendants"*, and "descendants" is silent about a
     * second item in the middle. **Neither lends nor steals** is the answer, and both halves are
     * pinned by `a nested item neither lends nor steals a mastery` — removing the `TYPE_ITEM`
     * branch turns that test red in both directions (review MEDIUM-3, which found the branch
     * deletable with the suite green).
     */
    private fun masteryUnder(
        itemId: String,
        childrenByParent: Map<String?, List<JsonObject>>,
    ): WeaponMastery? {
        val features = mutableListOf<JsonObject>()

        fun walk(parentId: String, depth: Int) {
            if (depth > MAX_MASTERY_DEPTH) return
            for (child in childrenByParent[parentId].orEmpty()) {
                val childId = child.string("_id") ?: continue
                when (child.string("type")) {
                    TYPE_FEATURE -> features += child
                    TYPE_ITEM -> Unit
                    else -> walk(childId, depth + 1)
                }
            }
        }
        walk(itemId, 0)

        return features
            .sortedWith(compareBy({ it.number("order") ?: 0 }, { it.string("name").orEmpty() }))
            .firstNotNullOfOrNull { it.masteryFromFeature() }
    }

    /**
     * One `feature` → its mastery, or `null` when it names none.
     *
     * `summary` and **not** `description`, which is R1 stated as code: the sheet's Features pane
     * shows `summary`, the operator named that pane, and it is the line that follows a renamed or
     * homebrewed weapon. The `**Mastery: X.**` bullet in an `item`'s own `description` is a
     * different, library-stock-only field that the probe caught *disagreeing* with the feature on
     * a live sheet — so it is not read here, and no fallback to it exists. The feature's own
     * `description` is read, but only for [masteryBulletText]'s rules sentence, never for the word.
     *
     * ### The word is markdown-stripped too
     *
     * Review LOW-5. A hand-written summary line `Mastery: **Topple**` used to cost twice over: the
     * badge read *"Mastery: \*\*Topple\*\*"*, and the rules sentence vanished as well, because the
     * bullet's plain `Topple` could never equal the starred word the name check compared it
     * against. [MARKDOWN_EMPHASIS] runs on the word for the same reason it runs on the body — 16
     * decision 4's plain text — and the trailing period the regex already trims is trimmed again
     * afterwards, since `**Topple.**` hides its `.` behind the closing asterisks.
     */
    private fun JsonObject.masteryFromFeature(): WeaponMastery? {
        val summary = text("summary") ?: return null
        val name = summary.lineSequence()
            .firstNotNullOfOrNull { MASTERY_SUMMARY_LINE.find(it)?.groupValues?.get(1) }
            ?.replace(MARKDOWN_EMPHASIS, "")
            ?.trim()
            ?.removeSuffix(".")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return WeaponMastery(name = name, text = masteryBulletText(name))
    }

    /**
     * R7's rules sentence: the `- **Mastery: X.** …` bullet of this feature's `description`.
     *
     * ### Where the bullet ends
     *
     * *"the rest of that line and any following lines until the next top-level `- ` bullet or the
     * end"*. "Top-level" is read against **the mastery bullet's own indentation** rather than
     * against column zero: a following line that starts a bullet at the same indent or less is the
     * next property, and a more-indented `  - ` is a sub-bullet belonging to this one. That reading
     * gives R7's stated cases exactly — a nested sub-bullet under the *preceding* property stays
     * out because the scan starts below the mastery bullet — and it also survives a description
     * whose whole list happens to be indented, which a literal column-zero test would not.
     *
     * ### The weapon's other properties are not shown
     *
     * *Finesse*, *Versatile*, *Two-Handed* are in the same list and are deliberately left there.
     * The operator asked for the mastery; the whole property block is FR-47d if the table wants it.
     *
     * ### A name mismatch drops the TEXT and keeps the summary's word
     *
     * R7 says the bullet's name is confirmed against R1's summary line and *"the summary wins on a
     * mismatch"*. What that leaves open is what happens to the sentence, and the answer here is
     * that it goes: a sheet whose summary says one mastery and whose bullet describes another is
     * a sheet this engine cannot reconcile, and printing the second one's rules under the first
     * one's heading would be a confident, wrong statement about what the weapon does. The heading
     * alone is the honest remainder, and it is a shape the detail sheet already draws. The
     * mismatch is a builder finding to ledger, not a runtime signal — there is nowhere on a badge
     * to say "these two disagree" that would help a player at a table.
     *
     * ### The FIRST bullet that names this mastery, not the first bullet that says "Mastery"
     *
     * Review LOW-5. Taking `indexOfFirst { MASTERY_BULLET… }` and only then checking the name meant
     * a description with two `**Mastery: …**` bullets could never reach the second: the first one
     * decided the answer, and if it named something else the sentence was dropped without looking
     * further. The scan now carries the name check, so the mismatch rule above still holds exactly
     * — a description in which *no* bullet names the summary's mastery yields `null` — while a
     * description that does carry the right bullet is read wherever in the list it sits.
     *
     * ### Continuation lines are de-indented against the bullet
     *
     * Review LOW-6 / NIT-7. Everything below the mastery bullet is kept verbatim apart from having
     * the bullet's own indentation removed, so a description whose whole list is indented renders
     * the same text as one written at column zero — the "relative to the bullet" reading stated
     * above, applied to the output and not only to where the text stops.
     *
     * A continuation sub-bullet keeps its `- `, and that asymmetry with the mastery bullet — whose
     * own `- **Mastery: X.**` lead *is* consumed — is deliberate rather than an oversight. The lead
     * is consumed because the sheet draws it: R7's block prints *"Mastery: Topple"* as its heading,
     * so repeating it in the body would say the same thing twice. A sub-bullet has no heading
     * anywhere, and its dash is the only mark left that it is a nested point rather than another
     * sentence of the paragraph above it — dropping that would flatten a rules exception into the
     * rule. 16 decision 4 declines a markdown renderer, not the structure the text came with.
     */
    private fun JsonObject.masteryBulletText(name: String): String? {
        val lines = text("description")?.lines() ?: return null
        val start = lines.indices.firstOrNull { index ->
            val candidate = MASTERY_BULLET.find(lines[index])
            candidate != null && candidate.groupValues[2].trim().equals(name, ignoreCase = true)
        } ?: return null

        val bullet = MASTERY_BULLET.find(lines[start]) ?: return null
        val indent = bullet.groupValues[1].length

        val body = mutableListOf(bullet.groupValues[3])
        for (index in start + 1 until lines.size) {
            val next = ANY_BULLET.find(lines[index])
            if (next != null && next.groupValues[1].length <= indent) break
            body += lines[index].dropIndent(indent)
        }

        return body.joinToString("\n")
            .replace(MARKDOWN_EMPHASIS, "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Drops up to [indent] leading whitespace characters — never more, and never a non-blank one.
     *
     * The `coerceAtMost` is the whole point: a continuation line indented *less* than the bullet
     * (ragged hand-written prose) keeps what it has rather than losing a character of its text.
     */
    private fun String.dropIndent(indent: Int): String =
        drop(takeWhile { it.isWhitespace() }.length.coerceAtMost(indent))

    /**
     * R6's gate, and R1's walk, for one action row — the only entry point the rest of the engine
     * uses.
     *
     * ### The gate is asked first, and that ordering is the cheap half
     *
     * Two tag lookups settle most rows on a sheet with no masteries at all, before any tree is
     * walked. It also states the rule in the order the ledger does: *a mastery the build did not
     * choose is not shown*, whatever the weapon's feature says, and no dimmed or greyed variant
     * exists for it (R6: *"A line present but not chosen is **not** an error state"*).
     *
     * ### `extraTags` is ignored, deliberately
     *
     * R6 again. A trigger's `extraTags` is DiceCloud's recursion guard (the NOT-`cleaveAttack`
     * exclusion on a mastery that grants an extra attack), not part of what the mastery applies
     * to. Reading it would suppress the badge on the very row the mastery is about.
     *
     * ### The trigger that opens the gate and the word that gets printed are not cross-checked
     *
     * Review LOW-4, and this is a declined fix rather than an unnoticed one. The gate answers *"has
     * the build chosen **a** mastery that applies to this row?"* and the word then comes from the
     * row's own weapon; nothing verifies that the trigger which opened the gate is the one naming
     * that word. On every probed sheet each trigger is weapon-specific
     * (`['battleaxeWeapon','strengthAttack']`, `['rapierWeapon']`), so the two cannot come apart —
     * but an action carrying two weapon tags, or a trigger targeting only a non-weapon tag such as
     * `strengthAttack`, would badge a row with a mastery chosen for a different weapon.
     *
     * There is no by-shape link to check it with. A `trigger` publishes `targetTags` and nothing
     * else about *which* mastery it is: its `description` and `summary` are empty (the probe found
     * the body is a `reference` into the library), and the only thing that carries the word is its
     * own `name` or its folder's — *"Battleaxe Mastery"*. R6 forbids exactly that reading
     * (*"match the tag, never the name"*, [underWeaponMasterySlot]'s rule), and a name match here
     * would reintroduce it one field over, so the check is not implementable without changing the
     * ruling. It is left as R6 states it and recorded here: a re-probe that finds a `targetTags`
     * list which is not weapon-specific is the signal that this needs a ruling, not a patch.
     */
    private fun JsonObject.masteryFor(masteries: MasteryIndex): WeaponMastery? {
        if (masteries.chosenTargetTags.isEmpty() || masteries.masteryByItem.isEmpty()) return null

        val tags = strings(TAGS).toSet()
        if (masteries.chosenTargetTags.none { tags.containsAll(it) }) return null

        return masteries.masteryByItem[nearestItemId(masteries.byId)]
    }

    /**
     * R1's upward walk: through `folder`s and `toggle`s (the dual-wielding shape) to the row's
     * nearest `item` ancestor, or `null` when there is none within [MAX_MASTERY_DEPTH].
     *
     * No ancestor means no weapon, which is a real and common answer rather than a gap — a
     * feature-granted action, a potion, an unarmed strike. Nothing about the intervening types is
     * asserted: this passes *through* whatever it finds and stops at the first `item`, so a shape
     * the probe has not seen costs nothing as long as it still ends at one.
     */
    private fun JsonObject.nearestItemId(byId: Map<String, JsonObject>): String? {
        var current = this
        repeat(MAX_MASTERY_DEPTH) {
            val parent = byId[current.parentId()] ?: return null
            if (parent.string("type") == TYPE_ITEM) return parent.string("_id")
            current = parent
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Shared readers
    // -----------------------------------------------------------------------

    /** The id of this property's parent, or `null` at the root of the tree. */
    private fun JsonObject.parentId(): String? = (this["parent"] as? JsonObject)?.string("id")

    /**
     * A field that is either a plain string or one of DiceCloud's wrapper objects.
     *
     * Three shapes reach this, and all three are real on the live sheet:
     *
     *  - `"range": "60 ft"` — a plain string.
     *  - `"description": { "text": "…", "value": "…" }` — an inline-calculation wrapper, whose
     *    rendered form is under `text`.
     *  - `"amount": { "calculation": "(floor((level+1)/6)+1)d8", "value": "2d8" }` — a
     *    `_calculation`, whose answer is under `value`.
     *
     * `text` is preferred over `value` because where both exist (`description`, `summary`) `text`
     * is the rendered string and `value` is the un-substituted source.
     *
     * ### Numbers are stringified only INSIDE a wrapper — and that is deliberate
     *
     * Review finding 9 caught this KDoc claiming more than the code does. What is true: a
     * **wrapped** number stringifies (`{"value": 60}` → `"60"`), and that path is live — two of
     * the capture's seventeen damage rows publish `amount.value` as a JSON number rather than a
     * dice string. What is not true is that a **bare** `"range": 60` does the same: the `else`
     * branch is [string], which requires an actual JSON string, so an unwrapped number reads as
     * absent.
     *
     * The asymmetry is recorded rather than "fixed". Every one of the seven call sites here
     * (`castingTime`, `range`, `description` ×2, `summary` ×2, `damage.amount`) is a text field
     * on the wire *or* a `_calculation` wrapper, the unwrapped-number shape has never been seen
     * on a capture, and widening the `else` branch would change what all seven render on
     * evidence nobody has. If a sheet ever publishes one, this is the note saying where the
     * one-line change goes.
     *
     * ### The wrapped number is the server's own characters (BUG-10, fixed 1.14.2)
     *
     * That path used to run through [number], an `Int` reader whose `toDoubleOrNull()?.toInt()`
     * truncates: a wrapped `2.5` became `"2"` and the row printed a number the server never
     * published — BUG-8's hazard, on the *base* rather than on a rider. It now takes the
     * `value` primitive's own `content`, exactly as `riderAmount` does: `2.5` renders `2.5`,
     * `60` still renders `60`, and nothing is re-formatted, because nothing is parsed and
     * re-printed. A `decimal` route was declined for that reason — a `Double` rendered back is
     * `2.0` for a server `2`, which is the app publishing its own number by another door.
     *
     * The guard is `toDoubleOrNull() != null`, so the branch still answers only for *numbers*:
     * a wrapped `true` reads as absent exactly as it did before, and a wrapped object (a nested
     * wrapper, which no capture has ever shown) is absent too rather than being unwrapped a
     * second time. The fix widens to all seven call sites deliberately — every one of them
     * truncated a fractional wrapped number, because the defect was in the reader and not in
     * the row.
     *
     * Blank is normalised to `null` so every caller's "absent reads as absent" holds without each
     * one repeating a `takeIf`.
     */
    private fun JsonObject.text(key: String): String? = when (val element = this[key]) {
        is JsonObject -> element.string("text")
            ?: element.string("value")
            ?: element.numberText("value")
        else -> string(key)
    }?.takeIf { it.isNotBlank() }

    /**
     * A **non-string** numeric primitive, as the characters the server sent (BUG-10).
     *
     * The last resort of [text]'s wrapper branch, reached only once `text` and a string `value`
     * are both absent. `toDoubleOrNull()` is the whole type test: it says "this is a number"
     * without ever producing one, so `2.5` stays `"2.5"` and `60` stays `"60"` — no `Int`
     * truncation, no `Double` re-rendering, no re-formatting of any kind. `true` and `null` fail
     * it and read as absent, which is what they did before the fix.
     *
     * This is `riderAmount`'s **spirit**, not its rule, and the two differences are deliberate
     * (review NIT-3). `riderAmount` accepts a *string* primitive and trims it, because an effect
     * publishing `{"value": " 3"}` is the server saying three and a rider that chipped over a
     * space would be reporting a formatting artefact as a fact. Neither applies here: a string
     * `value` never reaches this function — [text]'s own `string("value")` answered one branch
     * earlier, untrimmed, as it has since 1.9.1 — so this sees non-strings only, and a JSON
     * number has no padding to trim.
     */
    private fun JsonObject.numberText(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it !is JsonNull && !it.isString }
        ?.content
        ?.takeIf { it.toDoubleOrNull() != null }
}
