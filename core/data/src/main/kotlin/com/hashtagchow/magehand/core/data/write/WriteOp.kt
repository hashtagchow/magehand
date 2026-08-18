package com.hashtagchow.magehand.core.data.write

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

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
