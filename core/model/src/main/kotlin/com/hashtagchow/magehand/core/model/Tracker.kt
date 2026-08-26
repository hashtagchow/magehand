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
 * The number a player typed into FR-22's direct-entry dialog — an **absolute target**, not a
 * nudge (docs/design/15-polish-batch.md decision 6).
 *
 * ### Why a type and not an `Int`
 *
 * Because it is the *only* thing distinguishing "move this item by 3" from "make this item say
 * 3", and both are `adjustItem`. Decision 9 is binding — *"no new DDP methods, no new intents…
 * direct entry composes existing intents"* — so direct entry cannot introduce a
 * `setItemQuantity` alongside `adjustItem`; `WritePostureTest`'s allow-list is the catalog and
 * this wave does not widen it. What it can do is give the existing intent a second **shape**,
 * and an overload needs a parameter type the delta overload cannot be confused with. A boolean
 * flag would have compiled just as well and read as nothing at the call site.
 *
 * Deliberately **not** a `@JvmInline value class`: Kotlin mangles the JVM name of any function
 * taking one (`adjustItem-hbXcOEA`), which would change `OpenCharacter`'s method-name set and
 * fail the very posture assertion this type exists to keep true. One allocation per typed
 * number is not a cost worth that.
 *
 * ### Why it is in `:core:model`
 *
 * `WritePostureTest` asserts that `:app` holds no reference to `core.data.write`, so the
 * vocabulary a composable uses to ask for an absolute write cannot live beside `WriteOperation`.
 * It lives here, with `TrackedResource` and `WalletRow` — the two rows it is ever paired with.
 *
 * @param value what the row should read afterwards. Callers clamp before constructing; the
 *   implementations clamp again at the floor, because a negative quantity is a state neither
 *   DiceCloud's own UI nor this app can produce and nothing in this release could undo one.
 */
data class ExactQuantity(val value: Int)

/**
 * The death-save pair — three successes and three failures (FR-23,
 * docs/design/15-polish-batch.md decisions 18–21).
 *
 * ### One type for two properties, and why
 *
 * On the wire these are two independent `creatureProperties`, and the app could have carried two
 * [TrackedResource]s. It does not, because **nothing in this feature is ever true of one of them
 * alone**: the block renders iff *both* were discovered (decision 18), stable and dead are
 * derived by comparing the two counts against the same cap, and the clear-on-heal write sends
 * `set 0` to **both** (decision 20). A pair of nullable rows would have made every one of those
 * a two-null check at the call site, and "one arrived and the other did not" a state the UI had
 * to have an opinion about.
 *
 * ### Storage is inverted, and this type is where that stops being true
 *
 * Decision 19: the sheet stores `value` = **marks** and `damage` = `3 − value`, which is the
 * reverse of every other attribute in this app (where `value` is what is *left* and `damage` is
 * what was spent). [successes] and [failures] are marks — the number of filled pips — so that
 * nothing above `TrackerEngine` has to remember the inversion. The write is `damage
 * {operation:'set', value:n}` with `n` in the same units, because `set` takes the resulting
 * `value` (the deviation `WriteOp.setValue` records).
 *
 * ### Not a `TrackedResource`, deliberately
 *
 * A `TrackedResource` means "spend this and it goes down"; every control that consumes one — the
 * pip row, the steppers, the `spend`/`restore` intents — is built on that reading. Death saves
 * go *up* as things get worse, have no reset rule the client may act on (decision 20: the server
 * never clears them), and are written with an absolute set rather than an increment. Reusing the
 * type would have put all four of those exceptions inside code that assumes none of them.
 *
 * @param successesPropertyId the `deathSaveSuccesses` property, for the write.
 * @param failuresPropertyId the `deathSaveFails` property.
 * @param successes filled success pips, 0..[MAX].
 * @param failures filled failure pips, 0..[MAX].
 */
data class DeathSaves(
    val successesPropertyId: String,
    val failuresPropertyId: String,
    val successes: Int,
    val failures: Int,
) {
    /**
     * Three successes: the character is stable.
     *
     * A **derivation**, not a stored flag — decision 19 says so in as many words. `creature.
     * deathSave` exists in DiceCloud's schema and the probe found it vestigial and unwritable,
     * so a client that read a "stabilized" boolean off the sheet would be reading a field
     * nothing maintains. Counting pips is the only honest answer.
     */
    val isStable: Boolean get() = successes >= MAX

    /** Three failures. The other derivation, for the same reason. */
    val isDead: Boolean get() = failures >= MAX

    /**
     * Whether the block should still offer taps.
     *
     * It should, even at three-and-out: decision 20's *"pips are tappable; one tap fixes"* is
     * the remedy for a sheet another client healed without clearing, and a block that locked
     * itself at three failures would be a block a player could not correct. Kept as a named
     * property anyway so a future rule change has one place to live.
     */
    val isEditable: Boolean get() = true

    companion object {
        /** 5e: three of either ends it. Also the properties' `total` on every sheet seen. */
        const val MAX: Int = 3
    }
}

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

/**
 * Whether a d20 roll is made with advantage, with disadvantage, or straight.
 *
 * DiceCloud does not store these words either (the same shape as [DefenseKind]): a computed
 * roll carries a **numeric** `advantage` field, fed by effects whose `operation` is literally
 * `"advantage"` / `"disadvantage"`, and the word is a reading of that number's sign.
 * [fromWire] is the only place that reading happens.
 */
enum class RollAdvantage {
    /** No effect currently pushes this roll either way. The overwhelmingly common case. */
    NONE,
    ADVANTAGE,
    DISADVANTAGE,
    ;

    companion object {
        /**
         * Sign → word. Zero, `null` and a missing field all mean [NONE].
         *
         * **The sign, not a magic constant.** The field is a *rollup*: an ability or skill
         * accumulates contributions from every active effect aimed at it, so the honest
         * reading of "greater than zero" is "something is pushing this up", not "it equals
         * one". Both directions cancelling to zero is then [NONE] for free, which is also
         * what 5e says happens when a character has both.
         *
         * `null` is the field being absent, which is the normal state of a property whose
         * kind simply does not express advantage — see `TrackerEngine.rollModifier`. It is
         * deliberately not distinguished from zero here: "no field" and "the field says
         * nothing is pushing" are the same fact to a player reading the row.
         *
         * **What the sign cannot tell us, and why that is fine.** DiceCloud pre-collapses
         * every advantage contribution into this one integer before it reaches the wire, so
         * the sign is the only reading available — a roll carrying one of each arrives as `0`,
         * indistinguishable from a roll carrying neither. RAW's "advantage and disadvantage
         * cancel" is therefore handled upstream of this app or not at all, and mapping
         * net-zero to [NONE] is the correct answer either way: it is what 5e says the result
         * is, and it is the only answer the data supports.
         */
        fun fromWire(value: Int?): RollAdvantage = when {
            value == null || value == 0 -> NONE
            value > 0 -> ADVANTAGE
            else -> DISADVANTAGE
        }
    }
}

/**
 * One d20 roll the character can be asked to make, and the number they add to it.
 *
 * Ability checks, saving throws and skills all land here, because to the player at the table
 * they are one question — *"what do I add, and do I roll twice?"* — and the sheet answers all
 * three of them the same way: a name and a computed total.
 *
 * ### Read-only, like [DamageDefense]
 *
 * There is no DiceCloud method that changes a modifier, and the tracker offers no control
 * that would want one: this is reference, not state. What makes it worth a section anyway is
 * that it is the single most frequently asked question in a session and the app answers it
 * nowhere else — the alternative is the player leaving the tracker for the Sheet tab, mid-turn.
 *
 * ### Why [modifier] is an `Int` and [advantage] is separate
 *
 * The server computes the total for us, so nothing here re-derives `abilityMod + proficiency`
 * — a second implementation of the sheet's own arithmetic would be a second thing to be wrong,
 * and it would silently drop every bonus a feature adds. Advantage is a *different axis*: it
 * is not worth a number of any kind, and folding it into the modifier would be a lie.
 */
data class RollModifier(
    /**
     * The stable identity of the row — `creatureProperties._id` for a discovered roll, and an
     * app-minted constant for a local character's six checks. Never written to; it is what the
     * dropdown's remembered selection points at.
     */
    val id: String,
    /** As the sheet spells it. */
    val name: String,
    /** The whole number added to the d20, already totalled by the source. May be negative. */
    val modifier: Int,
    val advantage: RollAdvantage = RollAdvantage.NONE,
    /** The server's `order`, or the local sheet order. The only stable tie-breaker. */
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
    /**
     * Every roll the source expressed, in the order the sheet lists them. Empty only for a
     * character whose data names none — which is what makes the Rolls section absent rather
     * than an empty dropdown.
     */
    val rolls: List<RollModifier> = emptyList(),
    /** Name of the active concentration source, or `null`. */
    val concentratingOn: String? = null,
    /**
     * The discovered death-save pair, or `null` when this sheet carries no such subtree
     * (FR-23 decision 18: *"Sheets without the subtree exist (the Dummy) — no pair, no block,
     * no error"*).
     *
     * **Presence here is discovery, not visibility.** Whether the block *renders* also needs
     * the HP row to read zero, and that gate lives in `TrackerUiState` rather than here — for
     * the reason `TrackerEngine.build` produces this board **before** the optimistic overlay is
     * applied: `hp` on *that* board can still read zero for the frame after a heal is tapped.
     * The type is reused for the overlay-adjusted board too — `CreatureSession.board`, what a
     * consumer like `DefaultOpenCharacter` actually holds, is `overlay.applyTo(...)` of this
     * same class — so "this object" is not a safe stand-in for "the sheet's value" in general;
     * only the pre-overlay instance the engine builds is. Gating discovery on the pre-overlay
     * `hp` would leave the block on screen through an optimistic heal and take it away again on
     * the rollback.
     */
    val deathSaves: DeathSaves? = null,
) {
    val isEmpty: Boolean
        get() = hp == null && tempHp == null && slots.isEmpty() && resources.isEmpty() &&
            allItems.isEmpty() && activeToggles.isEmpty() && defenses.isEmpty() &&
            rolls.isEmpty()

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

    /** An item put on (FR-8). Its inverse is [UNEQUIP] — the same method, the other value. */
    EQUIP,

    /** An item taken off. See [EQUIP]. */
    UNEQUIP,

    /**
     * A brand-new item added to the sheet (FR-8's catalog and custom form).
     *
     * **Not invertible**, and deliberately: the inverse would be a soft-remove, which
     * docs/design/10-inventory.md decision 12 fences out of this release along with the rest
     * of item deletion. So the history entry says what happened and offers no UNDO, the same
     * shape a rest already has — with the one difference that creating an item invalidates
     * nothing before it, so it does not clear the stack.
     */
    ITEM_CREATE,

    /**
     * An item deleted from the sheet (FR-9, docs/design/12-inventory-layout.md decision 7).
     *
     * **Invertible on a server character and not on a local one**, and the enum deliberately
     * does not try to say which: [inverted] answers "what would the undoing write be called",
     * and the answer is [ITEM_RESTORE] in both cases. Whether an undo is *offered* is decided
     * by whether the op carries an inverse — `WriteOp.RemoveProperty` does,
     * `LocalOpenCharacter.removeItem` does not — because that is the one place that knows
     * whether there is anything left to restore.
     */
    ITEM_DELETE,

    /**
     * A deleted item put back (FR-9). The inverse half of [ITEM_DELETE], and the **first
     * write in this app whose inverse is a different server method** — `softRemove` undone by
     * `restore` rather than by the same call with the other argument.
     */
    ITEM_RESTORE,

    /**
     * An item moved into a container or back to the carried root (FR-9, 12 decision 8).
     *
     * Self-inverting like [TOGGLE], and for the same reason: undoing a move is a move. The
     * *destination* differs between the two, but the vocabulary does not, so a history entry
     * for the undo reads "Moved X" — which is what happened.
     */
    ITEM_MOVE,
    TOGGLE,
    SHORT_REST,
    LONG_REST,
    ;

    /**
     * What the undoing write is, so a rolled-back or undone op can still be described.
     * `null` where there is no inverse (absolute sets, rests, and item creation).
     */
    fun inverted(): TrackerWriteKind? = when (this) {
        SPEND -> RESTORE
        RESTORE -> SPEND
        TAKE_DAMAGE -> HEAL
        HEAL -> TAKE_DAMAGE
        ITEM_USE -> ITEM_ADD
        ITEM_ADD -> ITEM_USE
        EQUIP -> UNEQUIP
        UNEQUIP -> EQUIP
        ITEM_DELETE -> ITEM_RESTORE
        ITEM_RESTORE -> ITEM_DELETE
        ITEM_MOVE -> ITEM_MOVE
        TOGGLE -> TOGGLE
        SET_VALUE, ITEM_SET, ITEM_CREATE, SHORT_REST, LONG_REST -> null
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
