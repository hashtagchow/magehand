package com.hashtagchow.magehand.ui.screens.characterhome

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.data.session.OpenCharacterFactory
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure

/**
 * A hand-driven [OpenCharacter].
 *
 * The whole reason `OpenCharacter` is an interface over `:core:model` types is that this
 * class can exist: a `CreatureSession` needs Room, a DDP socket and a `SnapshotStore`, and
 * none of that belongs in a `:app` unit test about state mapping.
 */
class FakeOpenCharacter(
    override val accountId: String = "acct",
    override val creatureId: String = "FakeCreature23456",
    override val serverOrigin: String = "https://dnd.example.com",
) : OpenCharacter {

    override val board = MutableStateFlow(TrackerBoard.EMPTY)
    override val boardIgnoringHidden = MutableStateFlow(TrackerBoard.EMPTY)
    override val connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    override val lastSyncedAt = MutableStateFlow<Long?>(null)
    override val isShowingSnapshot = MutableStateFlow(false)
    override val overrides = MutableStateFlow<List<TrackerOverride>>(emptyList())
    override val accentColor = MutableStateFlow<String?>(null)

    override val canWrite = MutableStateFlow(false)
    override val writeHistory = MutableStateFlow<List<TrackerWrite>>(emptyList())
    override val canUndo = MutableStateFlow(false)
    override val writeFailures = MutableSharedFlow<TrackerWriteFailure>(extraBufferCapacity = 8)

    val written = mutableListOf<TrackerOverride>()
    val cleared = mutableListOf<String>()
    var snapshotsCaptured = 0
        private set
    var closedCount = 0
        private set

    /**
     * Every write intent this fake was asked for, in order.
     *
     * Recorded as text on purpose: the assertions are about *which intent with which
     * arguments*, and a string is readable in a failure message. The real implementation's
     * job — turning an intent into a `WriteOp` on the queue — is `:core:data`'s to prove.
     */
    val writes = mutableListOf<String>()
    var undoCount = 0
        private set
    var undoResult = true

    override fun spend(row: TrackedResource, amount: Int) {
        writes += "spend ${row.propertyId} $amount"
    }

    override fun restore(row: TrackedResource, amount: Int) {
        writes += "restore ${row.propertyId} $amount"
    }

    override fun changeHitPoints(delta: Int) {
        writes += "hp $delta"
    }

    override fun setHitPoints(value: Int) {
        writes += "hp= $value"
    }

    override fun adjustItem(item: TrackedResource, delta: Int) {
        writes += "item ${item.propertyId} $delta"
    }

    // FR-22's absolute shape, recorded with a distinct verb (`item=`, as `hp=` already reads)
    // so a test asserting these strings can tell "move it by 3" from "make it say 3" — which is
    // the one difference the overload exists to carry.
    override fun adjustItem(item: TrackedResource, target: ExactQuantity) {
        writes += "item= ${item.propertyId} ${target.value}"
    }

    override val inventory = MutableStateFlow(InventoryBoard.EMPTY)

    override val actions = MutableStateFlow(ActionBoard.EMPTY)

    override fun setEquipped(
        propertyId: String,
        equipped: Boolean,
        currentlyEquipped: Boolean,
        targetName: String,
    ) {
        writes += "equip $propertyId $equipped"
    }

    override fun addItem(spec: NewItemSpec) {
        writes += "add ${spec.name} ${spec.quantity}"
    }

    override fun removeItem(propertyId: String, targetName: String) {
        writes += "remove $propertyId"
    }

    override fun moveItem(propertyId: String, targetParent: InventoryMoveTarget, targetName: String) {
        // The destination is spelled out rather than `toString()`d so a test reading these
        // strings can tell "the carried root" from "a container that happens to be named
        // Carried" — which is the one distinction the two branches exist to keep apart.
        val where = when (targetParent) {
            is InventoryMoveTarget.Carried -> "carried"
            is InventoryMoveTarget.Container -> targetParent.propertyId
        }
        writes += "move $propertyId $where"
    }

    override fun adjustCoins(row: WalletRow, delta: Int) {
        writes += "coins ${row.coin} $delta"
    }

    override fun adjustCoins(row: WalletRow, target: ExactQuantity) {
        writes += "coins= ${row.coin} ${target.value}"
    }

    override fun setDeathSaves(successes: Int, failures: Int) {
        writes += "deathsaves $successes/$failures"
    }

    override fun toggle(condition: ConditionToggle) {
        writes += "toggle ${condition.propertyId}"
    }

    override fun rest(kind: RestKind) {
        writes += "rest $kind"
    }

    override suspend fun undoLastWrite(): Boolean {
        undoCount++
        return undoResult
    }

    override suspend fun setOverride(override: TrackerOverride) {
        written += override
        applyLocally(listOf(override))
    }

    override suspend fun setOverrides(overrides: List<TrackerOverride>) {
        written += overrides
        applyLocally(overrides)
    }

    override suspend fun clearOverride(propertyId: String) {
        cleared += propertyId
        this.overrides.value = this.overrides.value.filterNot { it.propertyId == propertyId }
    }

    override suspend fun setAccentColor(hex: String?) {
        accentColor.value = hex
    }

    override suspend fun captureSnapshot(): Boolean {
        snapshotsCaptured++
        return true
    }

    override suspend fun close() {
        closedCount++
    }

    /** Mimics Room re-emitting: what was written comes back out of [overrides]. */
    private fun applyLocally(rows: List<TrackerOverride>) {
        val merged = overrides.value.associateBy { it.propertyId }.toMutableMap()
        rows.forEach { merged[it.propertyId] = it }
        overrides.value = merged.values.toList()
    }
}

class FakeOpenCharacterFactory(
    private val character: OpenCharacter? = FakeOpenCharacter(),
) : OpenCharacterFactory {

    var opened = 0
        private set

    override suspend fun open(creatureId: String): OpenCharacter? {
        opened++
        return character
    }
}

/** Convenience for tests that only ever look at one board field. */
fun StateFlow<TrackerBoard>.current(): TrackerBoard = value
