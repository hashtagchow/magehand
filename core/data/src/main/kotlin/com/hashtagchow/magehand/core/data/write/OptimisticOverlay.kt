package com.hashtagchow.magehand.core.data.write

import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind

/** What one in-flight [WriteOp] predicts about a single property. */
sealed interface OptimisticChange {
    val propertyId: String

    /** Shift the row's remaining value by [amount] (signed). Several of these add up. */
    data class ValueDelta(override val propertyId: String, val amount: Int) : OptimisticChange

    /** Pin the row's remaining value to [value]. A later absolute wins over earlier deltas. */
    data class ValueAbsolute(override val propertyId: String, val value: Int) : OptimisticChange

    /** Show a toggle as [enabled] until the server confirms. */
    data class ToggleTo(override val propertyId: String, val enabled: Boolean) : OptimisticChange

    /** The op touches this property but we cannot predict the result — show nothing. */
    data class None(override val propertyId: String) : OptimisticChange
}

/**
 * Standard latency compensation, per docs/design/06-offline-and-sync.md §Reconciliation:
 *
 * > Mirror state always wins over snapshot; optimistic ops are re-applied on top of
 * > incoming server state until their method call resolves. If a method errors, its
 * > optimistic layer is dropped → UI rollback.
 *
 * So this is a *derived* value, not a cache: it is recomputed from the still-unresolved
 * ops every time one is added or removed, and [applyTo] runs on whatever board the engine
 * last produced. Rollback is therefore not a code path — it is simply the op leaving the
 * list. That is why there is no `undoOptimistic` anywhere.
 */
data class OptimisticOverlay(
    val valueDeltas: Map<String, Int> = emptyMap(),
    val valueAbsolutes: Map<String, Int> = emptyMap(),
    val toggles: Map<String, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = valueDeltas.isEmpty() && valueAbsolutes.isEmpty() && toggles.isEmpty()

    /** The value this overlay predicts for [propertyId], given the server's [serverValue]. */
    fun valueFor(propertyId: String, serverValue: Int): Int =
        (valueAbsolutes[propertyId] ?: serverValue) + (valueDeltas[propertyId] ?: 0)

    /**
     * The board as the user should see it *right now* — server state with the unresolved
     * predictions layered on top.
     *
     * `copy` rather than a field-by-field `TrackerBoard(...)` rebuild, and that is a
     * correctness rule rather than a style preference: an overlay transforms exactly the
     * countable rows and the toggles, and a constructor call silently defaults **every**
     * field it forgets. That is not hypothetical — the constructor form dropped
     * [TrackerBoard.defenses] the day that field was added, so the tracker's Defenses
     * section vanished for as long as any write was in flight and came back when it
     * resolved. `copy` carries every future field by construction, so a new board field is
     * pass-through until someone deliberately teaches the overlay to change it.
     */
    fun applyTo(board: TrackerBoard): TrackerBoard {
        if (isEmpty) return board
        return board.copy(
            hp = board.hp?.let(::apply),
            tempHp = board.tempHp?.let(::apply),
            slots = board.slots.map(::apply),
            resources = board.resources.map(::apply),
            pinnedItems = board.pinnedItems.map(::apply),
            allItems = board.allItems.map(::apply),
            activeToggles = board.activeToggles.map(::apply),
        )
    }

    private fun apply(row: TrackedResource): TrackedResource {
        val predicted = valueFor(row.propertyId, row.value)
        if (predicted == row.value) return row
        // Clamp: the server clamps healing at full and consumption at zero, and a UI
        // that briefly shows 5/4 or -1 before the server corrects it looks broken.
        //
        // Items are exempt from the *upper* clamp, and have to be: an item row carries
        // `total == value == quantity` (`TrackedResource.total`: "items have no cap",
        // which is what `TrackerEngine.item()` builds), so `maxOf(total, value)` on an
        // item is just its current quantity. Clamping to that erased every optimistic
        // increase — picking up a potion showed 3 until the server answered and then
        // jumped to 4, which is exactly the "looks broken" this clamp exists to prevent.
        // The floor still applies: quantity is never negative.
        val upper = when {
            row.kind == TrackerKind.ITEM -> Int.MAX_VALUE
            row.total > 0 -> maxOf(row.total, row.value)
            else -> Int.MAX_VALUE
        }
        return row.copy(value = predicted.coerceIn(0, upper))
    }

    private fun apply(toggle: ConditionToggle): ConditionToggle =
        toggles[toggle.propertyId]?.let { toggle.copy(enabled = it) } ?: toggle

    companion object {
        val EMPTY: OptimisticOverlay = OptimisticOverlay()

        /** Folds the pending changes, oldest first, into one overlay. */
        fun of(changes: List<OptimisticChange>): OptimisticOverlay {
            if (changes.isEmpty()) return EMPTY
            val deltas = LinkedHashMap<String, Int>()
            val absolutes = LinkedHashMap<String, Int>()
            val toggles = LinkedHashMap<String, Boolean>()
            for (change in changes) {
                when (change) {
                    is OptimisticChange.ValueDelta ->
                        deltas[change.propertyId] = (deltas[change.propertyId] ?: 0) + change.amount
                    is OptimisticChange.ValueAbsolute -> {
                        // An absolute supersedes everything queued before it for that row.
                        absolutes[change.propertyId] = change.value
                        deltas.remove(change.propertyId)
                    }
                    is OptimisticChange.ToggleTo -> toggles[change.propertyId] = change.enabled
                    is OptimisticChange.None -> Unit
                }
            }
            return OptimisticOverlay(deltas.filterValues { it != 0 }, absolutes, toggles)
        }
    }
}
