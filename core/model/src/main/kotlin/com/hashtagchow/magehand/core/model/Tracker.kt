package com.hashtagchow.magehand.core.model

/**
 * The domain types the tracker screen renders, per docs/design/03-data-model.md.
 *
 * These are deliberately free of any JSON, Room or DiceCloud vocabulary: the
 * discovery rules that produce them live in `:core:data`'s `TrackerEngine`, which
 * is source-agnostic (REST snapshot **or** live DDP mirror produce the same board).
 */

/** What kind of row a [TrackedResource] is, which decides how it is rendered and written. */
enum class TrackerKind {
    /** `attribute` / `attributeType: spellSlot` — pips, written with `creatureProperties.damage`. */
    SPELL_SLOT,

    /** `attribute` / `attributeType: resource` — rage, ki, inspiration. Same write path as slots. */
    RESOURCE,

    /** `item` — potions, ammo. Written with `creatureProperties.adjustQuantity`. */
    ITEM,

    /** `attribute` with `variableName == hitPoints`. */
    HIT_POINTS,

    /** `attribute` with `variableName == tempHP` (see the delta note in docs/verification/WP4.md). */
    TEMP_HP,
}

/**
 * When the server restores a tracked value.
 *
 * `null` is meaningful, not missing: on a `spellSlot` attribute it identifies a
 * death-save counter (docs/design/02-ddp-and-api.md §Known server quirks), which is
 * why the discovery rules exclude it.
 */
enum class ResetRule {
    SHORT_REST,
    LONG_REST,
    ;

    /** The wire value DiceCloud stores in `reset`. */
    val wireValue: String
        get() = when (this) {
            SHORT_REST -> "shortRest"
            LONG_REST -> "longRest"
        }

    companion object {
        /** `"shortRest"` / `"longRest"` → the enum; anything else (including `null`) → `null`. */
        fun fromWire(value: String?): ResetRule? = when (value) {
            "shortRest" -> SHORT_REST
            "longRest" -> LONG_REST
            else -> null
        }
    }
}

/**
 * One countable row on the tracker.
 *
 * [value] is what is *left* and [total] is the maximum. DiceCloud stores consumption
 * as damage (`value = total − damage`), so a write that spends one charge is
 * `damage {_id, increment, +1}` — see docs/design/03-data-model.md §Write semantics.
 */
data class TrackedResource(
    /** `creatureProperties._id` — the write target. */
    val propertyId: String,
    val kind: TrackerKind,
    val name: String,
    /** Remaining. For [TrackerKind.ITEM] this is the item's `quantity`. */
    val value: Int,
    /** Maximum. For [TrackerKind.ITEM] this equals [value] — items have no cap. */
    val total: Int,
    val reset: ResetRule? = null,
    /** Slots only, for grouping/ordering. */
    val spellSlotLevel: Int? = null,
    /**
     * The server's `order` field. Not in 03's field list; kept because it is the only
     * stable tie-breaker the server gives us, so two rows with the same name/level do
     * not swap places between syncs.
     */
    val sortOrder: Int = 0,
    /** Set by the user-override layer (Room `tracker_prefs`), never by the server. */
    val pinned: Boolean = false,
)

/**
 * A user-flippable `toggle` property, rendered as a quick chip and written with
 * `creatureProperties.flipToggle`.
 */
data class ConditionToggle(
    val propertyId: String,
    val name: String,
    /** `true` when the toggle is currently on. */
    val enabled: Boolean,
    /**
     * Whether `creatureProperties.flipToggle` will actually accept this property.
     *
     * The server's own precondition, read out of its source and confirmed live (WP7): a
     * toggle is **manual** when the document carries `enabled` or `disabled`, and
     * **computed** — driven by its `condition` calculation — when it carries neither.
     * `flipToggle` throws `Computed toggle` for the second kind.
     *
     * This is neither 03 §5's `showUI == true` (no property on any sheet here carries
     * `showUI` at all) nor WP4 §6.2's condition-absence fallback (which accepts toggles
     * the server refuses). A chip that is discovered but not [flippable] still renders —
     * its state is real information — it just does not take taps.
     */
    val flippable: Boolean = false,
    val tags: List<String> = emptyList(),
    val sortOrder: Int = 0,
    /** Set by the user-override layer (Room `tracker_prefs`), never by the server. */
    val pinned: Boolean = false,
) {
    /**
     * **The visibility rule for toggle rows, and the only copy of it.**
     *
     * A toggle earns a place in the tracker's main list while it is *on*. Off ones are
     * build plumbing far more often than they are table state — a real sheet carries
     * "Racial ASI Disabler" and "Load Wizard Spells" alongside "Bless" — and a list that
     * shows all 55 of them buries the two that matter mid-combat.
     *
     * "Off" is not "gone": everything this hides is still discovered, still on the board,
     * and still one tap away behind the conditions section's *N inactive* expander —
     * which it has to be, because tapping an off toggle *on* is how a player raises a
     * buff in the first place.
     *
     * [pinned] is the user saying "this one, always" and wins over the default. The other
     * direction — hidden — is enforced upstream, where it already was: `TrackerEngine`'s
     * override layer drops hidden rows before the board is built, so a hidden toggle
     * never reaches this property at all and cannot be resurrected by turning it on.
     */
    val shownByDefault: Boolean get() = enabled || pinned
}

/**
 * How a creature takes one kind of damage differently from everyone else.
 *
 * DiceCloud does not store the words "immune" / "resistant" / "vulnerable" anywhere: it
 * stores a `damageMultiplier` property carrying a numeric `value`, and the word is a
 * reading of that number. [fromMultiplier] is the only place that reading happens.
 */
enum class DefenseKind {
    /** Multiplier `0` — the damage does not land at all. */
    IMMUNE,

    /** Multiplier strictly between `0` and `1`; `0.5` is the only one 5e produces. */
    RESISTANT,

    /** Multiplier above `1`; `2` is the only one 5e produces. */
    VULNERABLE,

    ;

    companion object {
        /**
         * The multiplier → word rule, derived from the field's own arithmetic rather than
         * from a table of magic constants: a multiplier of zero means no damage arrives,
         * below one means less, above one means more.
         *
         * Two values deliberately produce `null` rather than a guess:
         *
         *  - **`1.0`** — a multiplier that changes nothing is not a defense, and a
         *    "Resistant: fire" line for a property that does nothing would be a lie.
         *  - **negative** — DiceCloud's docs and the live capture contain no example, and
         *    the obvious reading ("damage heals you") is a *different feature*, not a
         *    defense. Unsourced, so left out. See `TrackerEngine.damageDefense`.
         *
         * The enum is declared — and therefore sorts — in ascending multiplier order, so
         * a board's defenses read best-news-first: immunities, then resistances, then the
         * one that costs you hit points.
         */
        fun fromMultiplier(multiplier: Double): DefenseKind? = when {
            multiplier == 0.0 -> IMMUNE
            multiplier > 0.0 && multiplier < 1.0 -> RESISTANT
            multiplier > 1.0 -> VULNERABLE
            else -> null
        }
    }
}

/**
 * One discovered `damageMultiplier` property — a resistance, immunity or vulnerability.
 *
 * Read-only at the table: there is no DiceCloud method that flips one, and the tracker
 * offers no control that would want one. It is here because "am I resistant to this?" is
 * asked once per round and answered nowhere else in the app.
 *
 * ### Why [damageTypes] is a plain string list
 *
 * The server stores whatever strings the sheet's author put there (`["radiant",
 * "necrotic"]` on the live capture). Nothing here validates them against the 13 official
 * damage types, and nothing should: a homebrew type would then vanish from the section
 * that exists to tell the player what they resist. It also means that if a sheet expresses
 * a *condition* immunity by naming a condition here, it renders with no further work —
 * see the discovery KDoc for why that is not asserted.
 */
data class DamageDefense(
    /** `creatureProperties._id`. Never written to; kept as the stable identity of the row. */
    val propertyId: String,
    val kind: DefenseKind,
    /** The wire strings, unaltered and in the server's order. Never empty. */
    val damageTypes: List<String>,
    /** The name of the feature that grants it, as the sheet spells it. Not rendered in v1. */
    val name: String,
    /** The server's `order`, the only stable tie-breaker it gives us. */
    val sortOrder: Int = 0,
)

/** What the tracker screen renders. Everything here is already override-filtered and ordered. */
data class TrackerBoard(
    val hp: TrackedResource? = null,
    val tempHp: TrackedResource? = null,
    /** Ordered by spell-slot level, then by the server's `order`. */
    val slots: List<TrackedResource> = emptyList(),
    val resources: List<TrackedResource> = emptyList(),
    /** The subset of [allItems] the user has pinned. */
    val pinnedItems: List<TrackedResource> = emptyList(),
    /**
     * Every discovered item. Not in 03's field list, but 03 §4 requires an item
     * *picker* — which needs the full list, not just the pins.
     */
    val allItems: List<TrackedResource> = emptyList(),
    /**
     * Every discovered toggle the user has not hidden — *including the off ones*, which
     * the tracker files behind its "N inactive" expander rather than dropping (see
     * [ConditionToggle.shownByDefault]). The name predates that split and is kept because
     * it is the wire between the engine, the optimistic overlay and the customize sheet;
     * "active" here has always meant "not removed / not deactivated by something else",
     * never "switched on".
     */
    val activeToggles: List<ConditionToggle> = emptyList(),
    /**
     * Every active `damageMultiplier`, ordered immunities → resistances → vulnerabilities
     * and then by the server's `order`. Empty for the many characters that have none,
     * which is what makes the section absent rather than empty.
     */
    val defenses: List<DamageDefense> = emptyList(),
    /** Name of the active concentration source, or `null`. */
    val concentratingOn: String? = null,
) {
    val isEmpty: Boolean
        get() = hp == null && tempHp == null && slots.isEmpty() && resources.isEmpty() &&
            allItems.isEmpty() && activeToggles.isEmpty() && defenses.isEmpty()

    companion object {
        val EMPTY: TrackerBoard = TrackerBoard()
    }
}

/** Which rest the user asked for (docs/design/02-ddp-and-api.md `creature.methods.rest`). */
enum class RestKind {
    SHORT,
    LONG,
    ;

    /** The [ResetRule] a rest of this kind restores. A long rest restores both. */
    fun restores(rule: ResetRule?): Boolean = when (this) {
        SHORT -> rule == ResetRule.SHORT_REST
        LONG -> rule != null
    }
}

/**
 * What one *dispatched* tracker write did, for the undo snackbar and the history sheet
 * (docs/design/04-screens-ux.md §3).
 *
 * ### Why this is per dispatched call, not per tap
 *
 * The write queue coalesces rapid taps on one property into a single `increment`
 * (docs/verification/WP4.md deviation 12), and undo is the inverse of *that* call. A
 * history that listed taps would therefore promise three undos where one exists. So one
 * entry == one server call, and [amount] is the summed amount — three quick taps on a slot
 * read back as "spent 3", which is also what actually happened to the sheet.
 *
 * Structured rather than pre-formatted so the strings stay in `:app`'s resources.
 */
data class TrackerWrite(
    /** Monotonic within a session; identifies the entry for the undo affordance. */
    val id: Long,
    val kind: TrackerWriteKind,
    /** The row's name at the time of the write, e.g. `"1st Level"`. Empty for a rest. */
    val targetName: String,
    /** How much, always positive. `1` for a toggle flip; `0` for a rest. */
    val amount: Int,
    val at: Long,
    /**
     * False when the op has no inverse (a rest), when it has already been undone, or when
     * a later rest invalidated it — undoing a spend after a long rest would apply damage
     * to a slot the server has already restored.
     */
    val undoable: Boolean,
    val undone: Boolean,
)

/** The tracker mutations the UI is allowed to make (docs/design/03-data-model.md §Write semantics). */
enum class TrackerWriteKind {
    SPEND,
    RESTORE,
    TAKE_DAMAGE,
    HEAL,
    SET_VALUE,
    ITEM_USE,
    ITEM_ADD,
    ITEM_SET,
    TOGGLE,
    SHORT_REST,
    LONG_REST,
    ;

    /**
     * What the undoing write is, so a rolled-back or undone op can still be described.
     * `null` where there is no inverse (absolute sets and rests).
     */
    fun inverted(): TrackerWriteKind? = when (this) {
        SPEND -> RESTORE
        RESTORE -> SPEND
        TAKE_DAMAGE -> HEAL
        HEAL -> TAKE_DAMAGE
        ITEM_USE -> ITEM_ADD
        ITEM_ADD -> ITEM_USE
        TOGGLE -> TOGGLE
        SET_VALUE, ITEM_SET, SHORT_REST, LONG_REST -> null
    }
}

/**
 * A write whose optimistic layer was rolled back (docs/design/04-screens-ux.md §3: "on
 * method error the pip/number rolls back with a shake animation + error snackbar").
 *
 * @param propertyId the row to shake, or `null` when the failure was not row-shaped (a rest).
 * @param reason the server's own words where it gave any; never a token or a URL.
 */
data class TrackerWriteFailure(
    val id: Long,
    val kind: TrackerWriteKind,
    val propertyId: String?,
    val targetName: String,
    val reason: String?,
    /** True when the write was refused because the connection was not live. */
    val refusedOffline: Boolean,
    /** True when the server's rate limiter rejected it even after the one allowed retry. */
    val rateLimited: Boolean,
)

/**
 * The local override layer (Room `tracker_prefs`): hide / pin / reorder any discovered
 * row. Applied *last*, on top of discovery output; it never mutates server data
 * (docs/design/03-data-model.md §6).
 */
data class TrackerOverride(
    val propertyId: String,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    /** `null` keeps the natural (server) order. Lower sorts first. */
    val sortIndex: Int? = null,
)
