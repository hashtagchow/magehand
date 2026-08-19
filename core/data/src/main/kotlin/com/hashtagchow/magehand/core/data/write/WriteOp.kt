package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/** How many the insert body asks for, for the history entry's "Added 20 × Arrows". */
private fun JsonObject.quantityOrZero(): Int =
    (this["quantity"] as? JsonPrimitive)?.intOrNull ?: 0

/** The three kinds that mean "the row went down" — see [intentForMergedValue]. */
private val CONSUMPTION_KINDS = setOf(
    TrackerWriteKind.SPEND,
    TrackerWriteKind.TAKE_DAMAGE,
    TrackerWriteKind.ITEM_USE,
)

/**
 * The label a coalesced increment should carry, given the head op's [headIntent] and
 * the value the merge summed to.
 *
 * Coalescing sums increments **of either sign** — that is the point of it; a spend and
 * a restore inside the same rate-limit window are meant to cancel down to one call.
 * But the merged op used to keep the head's label, so tapping `−1` then `+3` on a
 * spell slot produced one call for a net *restore* of 2 filed in the history and on
 * the undo stack as "Spent 2". The user then reads a lie, and the snackbar offers to
 * undo something that never happened.
 *
 * Recomputing beats refusing to merge conflicting intents: refusing would break the
 * queue's third guarantee — "a pair that cancels out is dropped entirely" — which is
 * exactly the spend-then-restore case, and would send two calls where the server's
 * rate limiter wants one.
 *
 * It reads the **merged sign alone**, never "did the sign change", because
 * `takeCoalescedHead` folds one candidate at a time: a burst that passes through zero
 * on its way (`+1, −1, −1`) would leave a difference-based rule with no sign to
 * compare against on the next step. Absolute is also simply true — both methods take
 * a *consumption* amount (see [WriteOp.Companion.adjust]), so positive always means
 * the row went down, whatever route the sum took.
 *
 * Only the direction moves: `SPEND ↔ RESTORE`, `TAKE_DAMAGE ↔ HEAL` and
 * `ITEM_USE ↔ ITEM_ADD` are precisely [TrackerWriteKind.inverted]'s pairs, so a
 * spell-slot burst can never come out labelled as HP and vice versa. A zero sum keeps
 * the head's label and is academic anyway: the queue turns it into a [WriteOp.Noop],
 * which is never filed.
 */
private fun intentForMergedValue(headIntent: TrackerWriteKind?, mergedValue: Int): TrackerWriteKind? {
    if (headIntent == null || mergedValue == 0) return headIntent
    // A hand-built op labelled with a non-invertible kind (an absolute `set`'s
    // vocabulary on an `increment`): keep the caller's word rather than guess.
    val inverse = headIntent.inverted() ?: return headIntent
    return if ((headIntent in CONSUMPTION_KINDS) == (mergedValue > 0)) headIntent else inverse
}

/** `creatureProperties.damage` / `adjustQuantity` take one of these two operations. */
enum class WriteOperation(val wireValue: String) {
    SET("set"),
    INCREMENT("increment"),
}

/** `creature.methods.rest`'s `restType`. */
enum class RestType(val wireValue: String) {
    SHORT_REST("shortRest"),
    LONG_REST("longRest"),
}

/**
 * One DDP method call the tracker wants to make, with everything the [WriteQueue] needs
 * to schedule it: its rate-limit class, whether it can be merged with a neighbour, what
 * to show optimistically, and how to undo it.
 *
 * Methods, parameter shapes and server rate limits are from
 * docs/design/02-ddp-and-api.md §Method catalog; the damage/quantity semantics
 * (`value = total − damage`) are from docs/design/03-data-model.md §Write semantics.
 *
 * Build these with the [WriteOp.Companion] factories: they capture the row's *current*
 * value, which is what makes a correct inverse op possible.
 */
sealed class WriteOp {

    abstract val method: String
    abstract val params: List<JsonElement>

    /**
     * Minimum spacing between two calls in this op's rate-limit class.
     *
     * docs/design/02-ddp-and-api.md §Client rule: `damage` is limited 20/5 s (→ 250 ms),
     * everything else 5/5 s (→ 1 s). The queue enforces this per class, not globally,
     * because a rest must not be slowed down by slot taps or vice versa.
     */
    abstract val minSpacingMillis: Long

    /** Ops sharing a key may be coalesced while they sit in the queue; `null` never merges. */
    open val coalesceKey: String? = null

    /**
     * What the op writes to. Coalescing may skip *past* ops with a different target, but
     * never past one with the same target that it cannot merge with — reordering an
     * `increment` around a `set` on the same property changes the outcome.
     */
    abstract val targetId: String

    /** A barrier stops coalescing dead: a rest rewrites the whole sheet. */
    open val isBarrier: Boolean = false

    /** What to show before the server answers, or `null` for ops with no local prediction. */
    open val optimistic: OptimisticChange? = null

    /** The op that undoes this one, or `null` when the op is **not undoable**. */
    open val inverse: WriteOp? = null

    /** Merges [other] (which arrived later) into this op, or `null` if they cannot merge. */
    open fun coalesceWith(other: WriteOp): WriteOp? = null

    /** Human-readable label for error surfacing; never contains a token or an id secret. */
    open val description: String get() = method

    /**
     * The row's name at submit time, carried purely so the undo snackbar and the history
     * sheet can say *what* was spent rather than quoting a Meteor id at the user. Survives
     * coalescing because every merge is a `copy()`.
     */
    open val targetName: String get() = ""

    /**
     * What the user meant, for the history entry. Derived at the factory rather than
     * re-inferred from the sign of [Damage.value], because `damage increment -1` is
     * "restore a slot" on a spell slot and "heal 1" on the HP row, and those read
     * differently in a list of things you did.
     *
     * The factory chooses the *vocabulary* (spend/restore vs damage/heal vs item
     * use/add); only the **direction** within that vocabulary is re-derived, and only
     * where it can actually change — when coalescing sums increments of both signs.
     * See [intentForMergedValue].
     */
    open val intent: TrackerWriteKind? get() = null

    /** How much this op moved, always positive; `0` for ops with no magnitude. */
    open val magnitude: Int get() = 0

    // -----------------------------------------------------------------------

    /**
     * `creatureProperties.damage {_id, operation, value}` — the write behind slots,
     * resources and HP. Consumption is stored as damage, so **spending one charge is
     * `increment +1`** and restoring one is `increment -1`.
     */
    data class Damage(
        val propertyId: String,
        val operation: WriteOperation,
        val value: Int,
        /** The `value` the row will show once the server applies this, when known. */
        val resultingValue: Int? = null,
        /** Pre-computed inverse; `null` on a hand-built op means "not undoable". */
        val undo: WriteOp? = null,
        override val targetName: String = "",
        override val intent: TrackerWriteKind? = null,
    ) : WriteOp() {
        override val method: String get() = "creatureProperties.damage"
        override val targetId: String get() = propertyId
        override val params: List<JsonElement> get() = listOf(idOperationValue(propertyId, operation, value))
        override val minSpacingMillis: Long get() = DAMAGE_SPACING_MILLIS

        // Only increments merge. Two `set`s do not: the later one already subsumes the
        // earlier, and summing them would be nonsense.
        override val coalesceKey: String?
            get() = if (operation == WriteOperation.INCREMENT) "damage:$propertyId" else null

        override val optimistic: OptimisticChange
            get() = when (operation) {
                // damage +N ⇒ N fewer left.
                WriteOperation.INCREMENT -> OptimisticChange.ValueDelta(propertyId, -value)
                WriteOperation.SET -> resultingValue
                    ?.let { OptimisticChange.ValueAbsolute(propertyId, it) }
                    ?: OptimisticChange.None(propertyId)
            }

        override val magnitude: Int get() = kotlin.math.abs(value)

        override val inverse: WriteOp?
            get() = undo ?: when (operation) {
                WriteOperation.INCREMENT -> Damage(
                    propertyId = propertyId,
                    operation = WriteOperation.INCREMENT,
                    value = -value,
                    targetName = targetName,
                    intent = intent?.inverted(),
                )

                WriteOperation.SET -> null
            }

        override fun coalesceWith(other: WriteOp): WriteOp? {
            if (other !is Damage) return null
            if (other.propertyId != propertyId) return null
            if (operation != WriteOperation.INCREMENT || other.operation != WriteOperation.INCREMENT) return null
            val merged = value + other.value
            return copy(
                value = merged,
                resultingValue = null,
                undo = null,
                intent = intentForMergedValue(intent, merged),
            )
        }

        override val description: String get() = "damage ${operation.wireValue} $value on $propertyId"
    }

    /** `creatureProperties.adjustQuantity {_id, operation, value}` — potions, ammo, coin. */
    data class AdjustQuantity(
        val propertyId: String,
        val operation: WriteOperation,
        val value: Int,
        val resultingValue: Int? = null,
        val undo: WriteOp? = null,
        override val targetName: String = "",
        override val intent: TrackerWriteKind? = null,
    ) : WriteOp() {
        override val method: String get() = "creatureProperties.adjustQuantity"
        override val targetId: String get() = propertyId
        override val params: List<JsonElement> get() = listOf(idOperationValue(propertyId, operation, value))
        override val minSpacingMillis: Long get() = SLOW_SPACING_MILLIS

        override val coalesceKey: String?
            get() = if (operation == WriteOperation.INCREMENT) "quantity:$propertyId" else null

        override val optimistic: OptimisticChange
            get() = when (operation) {
                // `increment` is a *consumption* amount here, exactly as it is on `damage`
                // — see the sign note on [WriteOp.Companion.adjust]. +N ⇒ N fewer.
                WriteOperation.INCREMENT -> OptimisticChange.ValueDelta(propertyId, -value)
                WriteOperation.SET -> OptimisticChange.ValueAbsolute(propertyId, resultingValue ?: value)
            }

        override val magnitude: Int get() = kotlin.math.abs(value)

        override val inverse: WriteOp?
            get() = undo ?: when (operation) {
                WriteOperation.INCREMENT -> AdjustQuantity(
                    propertyId = propertyId,
                    operation = WriteOperation.INCREMENT,
                    value = -value,
                    targetName = targetName,
                    intent = intent?.inverted(),
                )

                WriteOperation.SET -> null
            }

        override fun coalesceWith(other: WriteOp): WriteOp? {
            if (other !is AdjustQuantity) return null
            if (other.propertyId != propertyId) return null
            if (operation != WriteOperation.INCREMENT || other.operation != WriteOperation.INCREMENT) return null
            val merged = value + other.value
            return copy(
                value = merged,
                resultingValue = null,
                undo = null,
                intent = intentForMergedValue(intent, merged),
            )
        }

        override val description: String get() = "adjustQuantity ${operation.wireValue} $value on $propertyId"
    }

    /** `creatureProperties.flipToggle {_id}` — condition chips. Its own inverse. */
    data class FlipToggle(
        val propertyId: String,
        /** State the toggle will be in afterwards, for the optimistic chip. */
        val resultingEnabled: Boolean? = null,
        override val targetName: String = "",
    ) : WriteOp() {
        override val method: String get() = "creatureProperties.flipToggle"
        override val targetId: String get() = propertyId
        override val params: List<JsonElement> get() = listOf(buildJsonObject { put("_id", propertyId) })
        override val minSpacingMillis: Long get() = SLOW_SPACING_MILLIS

        // Two flips of the same toggle cancel out. Coalescing them keeps a double-tap
        // from burning two of the five calls the server allows per 5 s.
        override val coalesceKey: String get() = "toggle:$propertyId"

        override val optimistic: OptimisticChange
            get() = resultingEnabled
                ?.let { OptimisticChange.ToggleTo(propertyId, it) }
                ?: OptimisticChange.None(propertyId)

        override val intent: TrackerWriteKind get() = TrackerWriteKind.TOGGLE
        override val magnitude: Int get() = 1

        override val inverse: WriteOp
            get() = FlipToggle(propertyId, resultingEnabled?.not(), targetName)

        override fun coalesceWith(other: WriteOp): WriteOp? {
            if (other !is FlipToggle || other.propertyId != propertyId) return null
            // Merging a pair of flips yields *nothing to send*. A third flip merges back
            // out of the Noop (see [Noop.coalesceWith]), so N taps cost N mod 2 calls.
            return Noop(coalesceKey)
        }

        override val description: String get() = "flipToggle $propertyId"
    }

    /**
     * `creatureProperties.equip {_id, equipped}` — the one-tap equip control (10 decision 4).
     *
     * ### The method name
     *
     * **`equip`, not `equipItem`.** docs/design/02-ddp-and-api.md named the latter from the
     * start and nothing had ever called it; the 2026-08-19 probe against the live server
     * established the real name, and the doc line is corrected in the same cycle (10 decision
     * 12's ride-along). The parameter shape the doc gave was right.
     *
     * ### What the server does with it, honestly stated
     *
     * **It reparents the property.** Equipping moves the item under the `equipment`-tagged
     * folder and unequipping moves it under `carried`; the original parent is not recorded
     * anywhere and is not restored. That is DiceCloud's own web UI's behaviour, verified live,
     * and users of that UI already live with it.
     *
     * So [inverse] is the opposite `equip` call **and nothing else**. Un-equipping an item
     * that lived in a backpack puts it in `carried`, not back in the backpack, and undoing
     * that puts it in `equipment`. The undo returns the *equipped state* the user changed; it
     * cannot return the tree, because restoring a parent needs `organizeDoc` and a memory of
     * where the item was, both of which are FR-9's territory (10 decision 12). Recording this
     * limit rather than quietly shipping a half-undo is the point of this paragraph — and
     * 10 decision 2's state-based sections are what make it invisible in practice: the
     * inventory tab never renders the folder an item sits in, so the round trip looks exactly
     * like nothing happened.
     *
     * ### Rate class
     *
     * [SLOW_SPACING_MILLIS] — the 5-calls-per-5-seconds class, the same one `adjustQuantity`,
     * `flipToggle`, `update` and `rest` are in (02 §Method catalog). Only
     * `creatureProperties.damage` gets the fast 20/5 s class, and equip is emphatically not
     * damage. The queue enforces spacing **per method name**, so equip taps and quantity taps
     * do not slow each other down even though they share a class — which is the correct
     * reading of a server that rate-limits per method.
     */
    data class Equip(
        val propertyId: String,
        /** The state to put the item into. */
        val equipped: Boolean,
        /**
         * The state it was in before this op — what [inverse] targets.
         *
         * Carried rather than derived from `!equipped` so that a **coalesced burst** still
         * inverts to where it started: four taps merge into one call, and the inverse of that
         * call is the state before the first tap, not before the last.
         */
        val previousEquipped: Boolean,
        override val targetName: String = "",
    ) : WriteOp() {
        override val method: String get() = "creatureProperties.equip"
        override val targetId: String get() = propertyId
        override val params: List<JsonElement>
            get() = listOf(
                buildJsonObject {
                    put("_id", propertyId)
                    put("equipped", equipped)
                },
            )
        override val minSpacingMillis: Long get() = SLOW_SPACING_MILLIS

        override val coalesceKey: String get() = "equip:$propertyId"

        /**
         * No prediction. The overlay's vocabulary ([OptimisticChange]) covers values and
         * toggles, and an item's equipped state is neither — it decides which *section* the
         * row renders in, which is a move rather than a change of one field. Teaching the
         * overlay to relocate rows between board sections for a call the server answers in
         * well under a second would be a lot of machinery for a flicker; 10 decision 4 asks
         * for one tap, not for latency compensation. Wave B dims the control while the write
         * is outstanding instead.
         */
        override val optimistic: OptimisticChange get() = OptimisticChange.None(propertyId)

        override val intent: TrackerWriteKind
            get() = if (equipped) TrackerWriteKind.EQUIP else TrackerWriteKind.UNEQUIP

        override val magnitude: Int get() = 1

        override val inverse: WriteOp
            get() = Equip(propertyId, equipped = previousEquipped, previousEquipped = equipped, targetName = targetName)

        /**
         * A later equip on the same item **supersedes** this one — the last state wins, and
         * the pair keeps this op's starting point so the undo stays honest.
         *
         * A round trip (on → off, or off → on) leaves nothing to say to the server, exactly
         * like a double [FlipToggle], so it collapses to a [Noop]; a third tap merges back
         * out of the Noop and N taps cost N mod 2 calls. This matters more here than it does
         * for a toggle: equip *reparents*, so two calls where one would do is two moves
         * through the folder tree rather than none.
         */
        override fun coalesceWith(other: WriteOp): WriteOp? {
            if (other !is Equip || other.propertyId != propertyId) return null
            val merged = other.copy(previousEquipped = previousEquipped)
            return if (merged.equipped == merged.previousEquipped) Noop(coalesceKey) else merged
        }

        override val description: String get() = "equip $equipped on $propertyId"
    }

    /**
     * `creatureProperties.insert {creatureProperty, parentRef}` — the catalog and custom-form
     * add path (10 decision 6), and the wallet's first increment on a coin the sheet lacks
     * (10 decision 5).
     *
     * ### `order` is mandatory
     *
     * Probe-verified failure mode, 2026-08-19: the server rejects an insert whose
     * `creatureProperty` body carries no `order`. It is not defaulted and it is not optional,
     * so [Companion.insertItem] always supplies one and this type takes it as a non-null
     * constructor parameter rather than as a nullable field with a fallback — a shape that
     * cannot be built wrong beats one that is validated late.
     *
     * ### Not undoable
     *
     * [inverse] is `null`. The inverse of creating a property is soft-removing it, and item
     * deletion is fenced out of this release entirely (10 decision 12 — it is FR-9, along with
     * container reorganization and parent restoration). Offering an UNDO that called a method
     * this app has decided not to ship would be worse than offering none.
     *
     * The history entry therefore reads as a fact rather than as an offer, which is the shape
     * [Rest] already established. The one difference from a rest: creating an item invalidates
     * **nothing** that came before it, so this is not a [isBarrier] and does not clear the
     * undo stack. Undoing a spend from before an add is still perfectly correct.
     *
     * ### No optimistic layer
     *
     * The new property's `_id` is minted **by the server**, so there is nothing to key a
     * prediction on until the call returns — and the subscription delivers the real document
     * within the same round trip anyway.
     */
    data class InsertProperty(
        /** The property document to create, already shaped for the wire. */
        val body: JsonObject,
        /** `{id, collection}` — where it goes. See [Companion.insertItem]. */
        val parentRef: JsonObject,
        /** The parent's id, so coalescing's same-target rule has something real to compare. */
        override val targetId: String,
        override val targetName: String = "",
    ) : WriteOp() {
        override val method: String get() = "creatureProperties.insert"
        override val params: List<JsonElement>
            get() = listOf(
                buildJsonObject {
                    put("creatureProperty", body)
                    put("parentRef", parentRef)
                },
            )
        override val minSpacingMillis: Long get() = SLOW_SPACING_MILLIS

        /** Never merged: two adds are two items, and there is no sense in which they sum. */
        override val coalesceKey: String? get() = null

        override val optimistic: OptimisticChange? get() = null
        override val inverse: WriteOp? get() = null
        override val intent: TrackerWriteKind get() = TrackerWriteKind.ITEM_CREATE
        override val magnitude: Int get() = body.quantityOrZero()

        override val description: String get() = "insert item under $targetId"
    }

    /**
     * `creature.methods.rest {creatureId, restType}`.
     *
     * **Not undoable** ([inverse] is `null`) and never coalesced: the server applies every
     * reset and every trigger, so there is no inverse op that could put the sheet back.
     * docs/design/03-data-model.md requires a confirm dialog instead — that is the UI's
     * job, and this type's job is to make the undo stack physically unable to hold a rest.
     *
     * No optimistic overlay either: predicting what a rest does to a server-computed sheet
     * is exactly the guesswork 06-offline-and-sync.md refuses.
     */
    data class Rest(
        val creatureId: String,
        val restType: RestType,
    ) : WriteOp() {
        override val method: String get() = "creature.methods.rest"
        override val targetId: String get() = creatureId
        override val isBarrier: Boolean get() = true
        override val params: List<JsonElement>
            get() = listOf(
                buildJsonObject {
                    put("creatureId", creatureId)
                    put("restType", restType.wireValue)
                },
            )
        override val minSpacingMillis: Long get() = SLOW_SPACING_MILLIS
        override val coalesceKey: String? get() = null
        override val optimistic: OptimisticChange? get() = null
        override val inverse: WriteOp? get() = null
        override val intent: TrackerWriteKind
            get() = when (restType) {
                RestType.SHORT_REST -> TrackerWriteKind.SHORT_REST
                RestType.LONG_REST -> TrackerWriteKind.LONG_REST
            }
        override val description: String get() = "${restType.wireValue} for $creatureId"
    }

    /**
     * The result of coalescing a pair of ops that cancel out (two flips of one toggle, or
     * increments summing to zero). Never sent; the queue drops it and completes its
     * callers successfully, because "nothing needed to change" is a success.
     */
    data class Noop(val key: String) : WriteOp() {
        override val method: String get() = "noop"
        override val targetId: String get() = key
        override val params: List<JsonElement> get() = emptyList()
        override val minSpacingMillis: Long get() = 0
        override val coalesceKey: String get() = key
        override val optimistic: OptimisticChange? get() = null
        override val inverse: WriteOp? get() = null

        /** An odd tap after a cancelling pair revives the real op — three flips are one flip. */
        override fun coalesceWith(other: WriteOp): WriteOp? = other.takeIf { it.coalesceKey == key }

        override val description: String get() = "noop ($key)"
    }

    companion object {
        /** `damage` is 20 calls / 5 s → 250 ms apart. */
        const val DAMAGE_SPACING_MILLIS: Long = 250

        /** `adjustQuantity`, `rest`, `flipToggle`, `update` are 5 / 5 s → 1 s apart. */
        const val SLOW_SPACING_MILLIS: Long = 1_000

        private fun idOperationValue(id: String, operation: WriteOperation, value: Int): JsonObject =
            buildJsonObject {
                put("_id", id)
                put("operation", operation.wireValue)
                put("value", value)
            }

        /**
         * Spend [amount] charges of a slot / resource row.
         *
         * The intent is recorded here rather than inferred later: on a spell slot
         * `damage increment +1` is "spent a slot", on the HP row the identical call is
         * "took 1 damage", and the undo list has to be able to tell the user which.
         */
        fun spend(resource: TrackedResource, amount: Int = 1): WriteOp =
            adjust(resource, -amount)

        /** Restore [amount] charges (the "un-spend" of 03's write table). */
        fun restore(resource: TrackedResource, amount: Int = 1): WriteOp =
            adjust(resource, amount)

        /**
         * Move a row's remaining value by [delta] (negative spends, positive restores).
         *
         * Items go through `adjustQuantity`; everything else through `damage`. **Both
         * methods take a consumption amount**, so the sign flips in both branches: the
         * server counts *up* as the row counts *down*.
         *
         * 03 §Write semantics says "Drink potion → `adjustQuantity {'increment', -1}`",
         * which is backwards for this server — a live probe on the test dummy moved
         * quantity 5 → 7 on two `-1` calls and 7 → 6 on one `+1`. WP7 §Deviations records
         * it; the doc is wrong, not the server.
         */
        fun adjust(resource: TrackedResource, delta: Int): WriteOp =
            if (resource.kind == TrackerKind.ITEM) {
                AdjustQuantity(
                    propertyId = resource.propertyId,
                    operation = WriteOperation.INCREMENT,
                    value = -delta,
                    targetName = resource.name,
                    intent = if (delta < 0) TrackerWriteKind.ITEM_USE else TrackerWriteKind.ITEM_ADD,
                )
            } else {
                Damage(
                    propertyId = resource.propertyId,
                    operation = WriteOperation.INCREMENT,
                    value = -delta,
                    targetName = resource.name,
                    intent = resource.spendIntent(delta),
                )
            }

        /**
         * Set a row to an absolute value.
         *
         * **`set` takes the remaining value, not the damage.** 03 §Write semantics says
         * `damage {_id, 'set', value: total − desired}`; a live probe on the test dummy
         * says otherwise — on a 20-point HP row, `set 5` produced `value: 5, damage: 15`
         * and `set 0` produced `value: 0, damage: 20`. Following 03 would set the row to
         * its own complement, which for the common "heal to full" case (`total − desired
         * == 0`) reads as "set to zero" and drops the character to 0 HP. WP7 §Deviations
         * records it; this is the corrected call.
         *
         * Both branches are therefore the same shape now — `set value: desired` — and the
         * inverse is the row's value before the write.
         */
        fun setValue(resource: TrackedResource, desired: Int): WriteOp =
            if (resource.kind == TrackerKind.ITEM) {
                AdjustQuantity(
                    propertyId = resource.propertyId,
                    operation = WriteOperation.SET,
                    value = desired,
                    resultingValue = desired,
                    undo = AdjustQuantity(
                        propertyId = resource.propertyId,
                        operation = WriteOperation.SET,
                        value = resource.value,
                        resultingValue = resource.value,
                        targetName = resource.name,
                        intent = TrackerWriteKind.ITEM_SET,
                    ),
                    targetName = resource.name,
                    intent = TrackerWriteKind.ITEM_SET,
                )
            } else {
                Damage(
                    propertyId = resource.propertyId,
                    operation = WriteOperation.SET,
                    value = desired,
                    resultingValue = desired,
                    undo = Damage(
                        propertyId = resource.propertyId,
                        operation = WriteOperation.SET,
                        value = resource.value,
                        resultingValue = resource.value,
                        targetName = resource.name,
                        intent = TrackerWriteKind.SET_VALUE,
                    ),
                    targetName = resource.name,
                    intent = TrackerWriteKind.SET_VALUE,
                )
            }

        /** Take [amount] damage (HP goes down). Same call as spending a charge. */
        fun takeDamage(hp: TrackedResource, amount: Int): WriteOp =
            Damage(
                propertyId = hp.propertyId,
                operation = WriteOperation.INCREMENT,
                value = amount,
                targetName = hp.name,
                intent = TrackerWriteKind.TAKE_DAMAGE,
            )

        /** Heal [amount]; the server clamps at full HP (03 §Write semantics). */
        fun heal(hp: TrackedResource, amount: Int): WriteOp =
            Damage(
                propertyId = hp.propertyId,
                operation = WriteOperation.INCREMENT,
                value = -amount,
                targetName = hp.name,
                intent = TrackerWriteKind.HEAL,
            )

        /**
         * Consume one of an item — the "drink potion" row of 03's write table, with 03's
         * sign corrected against the live server (see [adjust]).
         */
        fun consumeItem(item: TrackedResource, amount: Int = 1): WriteOp =
            AdjustQuantity(
                propertyId = item.propertyId,
                operation = WriteOperation.INCREMENT,
                value = amount,
                targetName = item.name,
                intent = TrackerWriteKind.ITEM_USE,
            )

        /**
         * Flip a condition toggle. Callers must check [ConditionToggle.flippable] first —
         * the server rejects a computed toggle outright, and a chip that always errors is
         * worse than a chip that does not respond.
         */
        fun flip(toggle: ConditionToggle): WriteOp =
            FlipToggle(toggle.propertyId, resultingEnabled = !toggle.enabled, targetName = toggle.name)

        /** Spending HP is taking damage; spending anything else is spending a charge. */
        private fun TrackedResource.spendIntent(delta: Int): TrackerWriteKind = when {
            kind == TrackerKind.HIT_POINTS || kind == TrackerKind.TEMP_HP ->
                if (delta < 0) TrackerWriteKind.TAKE_DAMAGE else TrackerWriteKind.HEAL

            delta < 0 -> TrackerWriteKind.SPEND
            else -> TrackerWriteKind.RESTORE
        }

        /**
         * Put an item on or take it off (10 decision 4).
         *
         * @param currentlyEquipped the item's state *now*, which is what makes a correct
         *   inverse possible — the same reason every other factory here captures the row's
         *   current value.
         */
        fun equip(
            propertyId: String,
            equipped: Boolean,
            currentlyEquipped: Boolean,
            targetName: String = "",
        ): WriteOp = Equip(
            propertyId = propertyId,
            equipped = equipped,
            previousEquipped = currentlyEquipped,
            targetName = targetName,
        )

        /**
         * Create an item from a [NewItemSpec], parented under [parentId].
         *
         * ### The body
         *
         * `type`, `name`, `quantity` and **`order`** always; `weight`, `value`, `description`
         * and `tags` only when the spec states them. Omitting rather than zero-filling is
         * deliberate and visible on the sheet: DiceCloud renders a `weight: 0` item as
         * weighing nothing, which is a claim, while an item with no weight field renders as
         * having none recorded — and a custom item the player did not weigh has not been
         * weighed. [NewItemSpec.catalogId] is never sent: it is this app's vocabulary and
         * would be meaningless to anyone opening the sheet in DiceCloud's web UI.
         *
         * `description` is sent as **`{text: "…"}`**, not as a plain string.
         *
         * This was a bare string until the 1.3.0 pre-release probe, on the reasoning that the
         * `{text, value, hash, inlineCalculations}` wrapper the server carries is an *output* of
         * its calculation pass and not an input this app is entitled to fabricate. The reasoning
         * was wrong and the server says so: `creatureProperties.insert` with a string
         * description is rejected outright with **`400: Description must be of type Object`**
         * (probe, 2026-08-19, dicecloud.com). The wrapper is the schema's input type; the
         * computed siblings are what the server fills in around `text`, which is why supplying
         * `text` alone is enough.
         *
         * Nothing on the read side changes: `InventoryEngine.descriptionText` already prefers
         * `text` inside the wrapper and falls back to a bare string, so an item created this way
         * reads back identically to one made in DiceCloud's own UI.
         *
         * Only the wallet's coin inserts escaped this — [NewItemSpec.ofCoin] states no
         * description — which is why the bug survived a green suite: the one live test that
         * sends a description is this class's own probe, and the probe is opt-in.
         *
         * ### `order`
         *
         * Mandatory (see [InsertProperty]) and supplied by the caller, because the only
         * source of a sensible value is the sheet — [DefaultOpenCharacter] passes one past
         * the current maximum so a new item lands at the end of its section rather than in the
         * middle of the list the player was just reading.
         *
         * ### The parent
         *
         * `{id, collection: "creatureProperties"}`, pointing at the `inventory`- or
         * `carried`-tagged folder (10 decision 6). A tag rather than a remembered id, resolved
         * against the sheet at call time — see `DefaultOpenCharacter.addItem`.
         */
        fun insertItem(
            spec: NewItemSpec,
            parentId: String,
            order: Int,
            parentCollection: String = COLLECTION_CREATURE_PROPERTIES,
        ): WriteOp = InsertProperty(
            body = buildJsonObject {
                put("type", "item")
                put("name", spec.name)
                put("quantity", spec.quantity)
                // Probe-verified mandatory — the server rejects the insert without it.
                put("order", order)
                spec.weightLb?.let { put("weight", it) }
                spec.valueGp?.let { put("value", it) }
                spec.description?.takeIf { it.isNotBlank() }?.let {
                    // Probe-verified: the server rejects a bare string here. See the KDoc.
                    put("description", buildJsonObject { put("text", it) })
                }
                if (spec.tags.isNotEmpty()) {
                    put("tags", JsonArray(spec.tags.map { JsonPrimitive(it) }))
                }
            },
            parentRef = buildJsonObject {
                put("id", parentId)
                put("collection", parentCollection)
            },
            targetId = parentId,
            targetName = spec.name,
        )

        /** The DDP collection name a `parentRef` names when the parent is a property. */
        const val COLLECTION_CREATURE_PROPERTIES: String = "creatureProperties"

        fun rest(creatureId: String, restType: RestType): WriteOp = Rest(creatureId, restType)

        /** `RestKind` is the UI's vocabulary; `RestType` is the wire's. */
        fun rest(creatureId: String, restKind: RestKind): WriteOp = Rest(
            creatureId = creatureId,
            restType = when (restKind) {
                RestKind.SHORT -> RestType.SHORT_REST
                RestKind.LONG -> RestType.LONG_REST
            },
        )
    }
}
