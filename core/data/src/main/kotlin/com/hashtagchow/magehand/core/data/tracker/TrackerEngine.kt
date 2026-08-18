package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ResetRule
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

    private const val ATTR_SPELL_SLOT = "spellSlot"
    private const val ATTR_RESOURCE = "resource"

    private const val VAR_HIT_POINTS = "hitPoints"

    /**
     * 03 says temp HP is `variableName == "tempHitPoints"`. The live sheet calls it
     * **`tempHP`** — both are accepted so the engine works against 03's text and against
     * the server. See docs/verification/WP4.md §Deviations.
     */
    private val TEMP_HP_VARIABLE_NAMES = setOf("tempHitPoints", "tempHP")

    private const val CONCENTRATION = "concentration"

    /**
     * The presence of either field is what makes a toggle manual rather than computed —
     * it is the exact condition `creatureProperties.flipToggle` tests before it will act.
     */
    private val FLIPPABLE_KEYS = listOf("enabled", "disabled")

    /** Matches the leading ordinal of a slot name — `"1st Level"`, `"2nd Level"`, … */
    private val LEADING_ORDINAL = Regex("""^\s*(\d+)""")

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
        val items = properties.mapNotNull { item(it) }
        val toggles = properties.mapNotNull { toggle(it) }

        val pinnedIds = overrides.values.filter { it.pinned }.map { it.propertyId }.toSet()

        return TrackerBoard(
            hp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.HIT_POINTS) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            tempHp = properties.firstNotNullOfOrNull { healthAttribute(it, TrackerKind.TEMP_HP) }
                ?.takeUnless { overrides[it.propertyId]?.hidden == true },
            slots = order(slots, overrides, SLOT_ORDER),
            resources = order(resources, overrides, NATURAL_ORDER),
            allItems = order(items, overrides, NATURAL_ORDER),
            pinnedItems = order(items.filter { it.propertyId in pinnedIds }, overrides, NATURAL_ORDER),
            activeToggles = orderToggles(toggles, overrides),
            concentratingOn = concentrationSource(properties),
        )
    }

    fun build(sheet: CreatureSheet, overrides: List<TrackerOverride>): TrackerBoard =
        build(sheet, overrides.associateBy { it.propertyId })

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    /**
     * 03 §1. `attributeType == 'spellSlot'`, excluding:
     * - `reset == null` — **death saves**. "Succeeded Saves" / "Failed Saves" are stored
     *   as spell slots with no reset rule (docs/design/02-ddp-and-api.md §Known server
     *   quirks). This is the exclusion that keeps them off the tracker.
     * - `total == 0` — slot levels the character cannot reach yet.
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
     * 03 §4. Every live `item`. Pins are a *local* concept, so discovery returns the whole
     * list (the picker needs it) and [build] splits out the pinned subset.
     *
     * `value == total == quantity`: an item has no maximum, and writes go through
     * `adjustQuantity`, not `damage`.
     */
    private fun item(p: JsonObject): TrackedResource? {
        if (p.string("type") != TYPE_ITEM || p.isSkipped()) return null
        val quantity = p.number("quantity") ?: 0
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
     * *is* the state being rendered, and hiding switched-off toggles would make them
     * unreachable.
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
     * the field), and WP4 §6.2's "no `condition` ⇒ manual" fallback matches four of
     * Sabriel's toggles, **all four of which the server rejects**. Setting `showUI: true`
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
    ): TrackedResource? = TrackedResource(
        propertyId = string("_id") ?: return null,
        kind = kind,
        name = string("name").orEmpty(),
        value = remaining(total),
        total = total,
        reset = reset,
        spellSlotLevel = level,
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

    private fun orderToggles(
        rows: List<ConditionToggle>,
        overrides: Map<String, TrackerOverride>,
    ): List<ConditionToggle> = rows
        .asSequence()
        .filter { overrides[it.propertyId]?.hidden != true }
        .sortedWith(
            compareBy<ConditionToggle> { overrides[it.propertyId]?.sortIndex ?: Int.MAX_VALUE }
                .thenBy { it.sortOrder }
                .thenBy { it.name },
        )
        .toList()
}
