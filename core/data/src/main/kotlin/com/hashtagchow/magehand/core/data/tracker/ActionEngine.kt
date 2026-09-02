package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
            .mapNotNull { it.toAction(childrenByParent, resources) }
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
        val riders = ridersOn(this["amount"] as? JsonObject)
        return DamageLine(
            amount = headlineOf(base, riders),
            damageType = string("damageType").orEmpty(),
            base = base,
            riders = riders,
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
     * Nothing here evaluates a calculation — an effect whose amount did not resolve to anything
     * is dropped, not guessed at.
     *
     * Each rider's `amount` goes through [text], so an integer `3` and a dice string `"2d6"`
     * both arrive as text; [DamageRider.foldsIntoHeadline] is where the two part ways.
     */
    private fun ridersOn(amount: JsonObject?): List<DamageRider> {
        val effects = amount?.get("effects") as? JsonArray ?: return emptyList()
        return effects.filterIsInstance<JsonObject>().mapNotNull { effect ->
            val value = effect.text("amount") ?: return@mapNotNull null
            DamageRider(
                name = effect.string("name").orEmpty(),
                operation = effect.string("operation").orEmpty(),
                amount = value,
            )
        }
    }

    /**
     * `d8` + `[+3]` → `"d8 + 3"`; `d6` + `[-1]` → `"d6 - 1"`; two numeric riders → `"d4 + 1 - 1"`.
     *
     * Concatenation in server order, never a sum: the sign is read off the number's own text and
     * the magnitude printed after it, which is the whole of what this function knows about
     * arithmetic. Riders that do not fold are left for the row to chip.
     */
    private fun headlineOf(base: String, riders: List<DamageRider>): String =
        riders.filter { it.foldsIntoHeadline }.fold(base) { acc, rider ->
            val n = rider.amount.toInt()
            if (n < 0) "$acc - ${-n}" else "$acc + $n"
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
     * is the rendered string and `value` is the un-substituted source. Numbers are accepted and
     * stringified so a `range` the server happened to compute as `60` still prints.
     *
     * Blank is normalised to `null` so every caller's "absent reads as absent" holds without each
     * one repeating a `takeIf`.
     */
    private fun JsonObject.text(key: String): String? = when (val element = this[key]) {
        is JsonObject -> element.string("text")
            ?: element.string("value")
            ?: element.number("value")?.toString()
        else -> string(key)
    }?.takeIf { it.isNotBlank() }
}
