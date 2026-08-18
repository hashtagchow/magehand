package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The tracker tab's rendered state (docs/design/04-screens-ux.md §3).
 *
 * Everything here is derived from a [TrackerBoard] plus four session signals by
 * [toTrackerUiState], which is a pure function on purpose: the board → UI mapping and the
 * status-strip derivation are the two things most likely to go quietly wrong, and both are
 * unit-tested in `TrackerUiStateTest` without a device, a Compose runtime or a clock.
 */

/** The status strip's connection chip. 04 §3 names three; 06 adds the fourth. */
enum class ConnectionTone {
    /** DDP connected, logged in **and** the `singleCharacter` sub ready. */
    LIVE,

    /** Socket down and retrying — 04 calls this "Reconnecting". */
    RECONNECTING,

    /** No network, or retries exhausted-for-now. Read the Room snapshot. */
    OFFLINE,

    /** The token was rejected; re-login is the only way out. */
    SIGNED_OUT,
}

/**
 * 04 §3's "connection state chip (Live / Reconnecting / Offline — cached HH:MM)" plus
 * 06's snapshot-fallback banner.
 *
 * @param syncedAt `"HH:MM"` of the cached snapshot, or `null` when nothing has ever
 *   synced for this character. Formatted here rather than in the composable so the
 *   time zone is an argument and the result is assertable.
 * @param showingSnapshot true when the board being rendered came from Room, not the
 *   live mirror. Independent of [tone]: a screen can be `RECONNECTING` and still be
 *   showing a snapshot, and that is exactly when the user most needs to be told.
 */
data class StatusStripState(
    val tone: ConnectionTone = ConnectionTone.RECONNECTING,
    val syncedAt: String? = null,
    val showingSnapshot: Boolean = false,
)

/** 04 §3's HP block. The steppers and the number pad render disabled in WP6. */
data class HpState(
    val propertyId: String,
    val current: Int,
    val max: Int,
    val tempHp: Int,
) {
    val hasTempHp: Boolean get() = tempHp > 0

    /** Clamped, so a server value briefly out of range cannot draw a bar past its track. */
    val fraction: Float get() = if (max <= 0) 0f else (current.toFloat() / max).coerceIn(0f, 1f)
}

/** One pip row: a spell-slot level, or a resource. */
data class PipRowState(
    val propertyId: String,
    val label: String,
    /**
     * The server's reset rule, kept as the enum rather than as pre-rendered text: the rest
     * confirm dialog has to *filter* on it ("what does a short rest put back?"), and a
     * dialog that matched on the string "Short rest" would be one rename from resetting
     * the wrong rows. [resetLabel] is the display form.
     */
    val reset: ResetRule?,
    val value: Int,
    val total: Int,
    val pinned: Boolean,
    val kind: TrackerKind,
) {
    val spent: Int get() = (total - value).coerceAtLeast(0)

    /** "Long rest" / "Short rest", or `null` when the server gives no reset rule. */
    val resetLabel: String? get() = reset?.label()

    /**
     * Pips stop being readable — and stop fitting a 48 dp target across a phone — long
     * before the count gets silly. Above the threshold the row renders `value / total`
     * with a bar instead, which is what DiceCloud's own UI does for large resources.
     */
    val usePips: Boolean get() = total in 1..MAX_PIPS

    companion object {
        /** Eight 48 dp targets plus gaps still fit a 360 dp-wide phone. */
        const val MAX_PIPS = 8
    }
}

/** A pinned inventory item with its quantity (04 §3, "Consumables"). */
data class ConsumableState(
    val propertyId: String,
    val name: String,
    val quantity: Int,
)

/**
 * A discovered `toggle` (04 §3, "Condition chips").
 *
 * @param canFlip whether the server will accept `flipToggle` on it. A **computed** toggle
 *   — one whose state comes from its `condition` calculation — is still worth rendering
 *   ("0 HP?" being on is information), but it is not a control, and tapping it would earn
 *   a guaranteed `Computed toggle` error. See [ConditionToggle.flippable].
 */
data class ConditionChipState(
    val propertyId: String,
    val name: String,
    val enabled: Boolean,
    val canFlip: Boolean = false,
)

/**
 * One row of the undo-history sheet (04 §3), already turned into display text.
 *
 * @param canUndo true on the **single** newest reversible entry. Undo is a stack, not a
 *   basket: reversing an older spend while a newer `set` sits on top of it would apply the
 *   inverse to a value the user has since replaced. So the sheet lists everything and
 *   offers UNDO on the top of the stack only.
 */
data class HistoryRowState(
    val id: Long,
    val label: String,
    val at: String,
    val canUndo: Boolean,
    val undone: Boolean,
)

data class TrackerUiState(
    val creatureId: String = "",
    val status: StatusStripState = StatusStripState(),
    /** 04 §3: `Concentrating: Bless ✕`. */
    val concentratingOn: String? = null,
    val hp: HpState? = null,
    val slots: List<PipRowState> = emptyList(),
    val resources: List<PipRowState> = emptyList(),
    val consumables: List<ConsumableState> = emptyList(),
    val conditions: List<ConditionChipState> = emptyList(),
    /** `"#RRGGBB"` from `theme_prefs`, or `null` for the app default (04 §6). */
    val accentColor: String? = null,
    /**
     * Whether taps may reach the server. Every write control is dimmed and inert when this
     * is false — 06's rule that writes require `LIVE`, made visible instead of surprising.
     */
    val canWrite: Boolean = false,
    val canUndo: Boolean = false,
    val history: List<HistoryRowState> = emptyList(),
    /**
     * The concentration source's toggle id, when the banner's source happens to be one of
     * the discovered flippable toggles. `null` disables the ✕ — see [toTrackerUiState].
     */
    val concentrationToggleId: String? = null,
) {
    /** Nothing discovered yet — a cold open with no snapshot and no live sub. */
    val isEmpty: Boolean
        get() = hp == null && slots.isEmpty() && resources.isEmpty() &&
            consumables.isEmpty() && conditions.isEmpty()

    /**
     * Distinguishes "this character genuinely has no tracker rows" from "we have not
     * loaded anything yet", which is what stops an empty-state message flashing on every
     * open (the same rule screen 2 uses).
     */
    val isLoading: Boolean
        get() = isEmpty && status.tone != ConnectionTone.LIVE && !status.showingSnapshot
}

/**
 * Board → UI, and the status-strip derivation, in one pure step.
 *
 * @param zone injected so the formatted `HH:MM` is deterministic in tests. Production
 *   passes the device zone.
 */
fun toTrackerUiState(
    creatureId: String,
    board: TrackerBoard,
    connection: ConnectionState,
    lastSyncedAt: Long?,
    isShowingSnapshot: Boolean,
    accentColor: String? = null,
    canWrite: Boolean = false,
    canUndo: Boolean = false,
    history: List<TrackerWrite> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
): TrackerUiState {
    val conditions = board.activeToggles.map { it.toChip() }
    // Only the newest still-reversible entry gets an UNDO button; see [HistoryRowState].
    val topOfStack = history.firstOrNull { it.undoable }?.id
    return TrackerUiState(
        creatureId = creatureId,
        status = StatusStripState(
            tone = connection.toTone(),
            syncedAt = formatSyncedAt(lastSyncedAt, zone),
            showingSnapshot = isShowingSnapshot,
        ),
        concentratingOn = board.concentratingOn,
        hp = board.hp?.toHpState(tempHp = board.tempHp?.value ?: 0),
        slots = board.slots.map { it.toPipRow() },
        resources = board.resources.map { it.toPipRow() },
        consumables = board.pinnedItems.map { it.toConsumable() },
        conditions = conditions,
        accentColor = accentColor,
        canWrite = canWrite,
        canUndo = canUndo && canWrite,
        history = history.map { it.toHistoryRow(canUndo = canWrite && it.id == topOfStack, zone = zone) },
        // 03 §5 lets the concentration banner come from a `buff` as well as a `toggle`, and
        // `flipToggle` rejects anything that is not a toggle. So the ✕ is live only when the
        // banner's source is also on the chip row — i.e. when it *is* a flippable toggle.
        concentrationToggleId = board.concentratingOn
            ?.let { name -> conditions.firstOrNull { it.name == name && it.enabled && it.canFlip }?.propertyId },
    )
}

/**
 * 06's `ConnectionState` is the single source of truth; this is only the rename 04 asks
 * for on screen ("Reconnecting" reads better than "Connecting" once a sheet is already
 * on screen, and `AUTH_FAILED` is not a connection problem the user can wait out).
 */
fun ConnectionState.toTone(): ConnectionTone = when (this) {
    ConnectionState.LIVE -> ConnectionTone.LIVE
    ConnectionState.CONNECTING -> ConnectionTone.RECONNECTING
    ConnectionState.OFFLINE -> ConnectionTone.OFFLINE
    ConnectionState.AUTH_FAILED -> ConnectionTone.SIGNED_OUT
}

/** The inverse of [toTone]; lossless, because the rename is 1:1. */
fun ConnectionTone.toConnectionState(): ConnectionState = when (this) {
    ConnectionTone.LIVE -> ConnectionState.LIVE
    ConnectionTone.RECONNECTING -> ConnectionState.CONNECTING
    ConnectionTone.OFFLINE -> ConnectionState.OFFLINE
    ConnectionTone.SIGNED_OUT -> ConnectionState.AUTH_FAILED
}

/** `1755463920000` → `"14:52"`. `null` in, `null` out — never a fake "00:00". */
fun formatSyncedAt(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
    epochMillis?.takeIf { it > 0L }
        ?.let { HOUR_MINUTE.format(Instant.ofEpochMilli(it).atZone(zone)) }

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun TrackedResource.toHpState(tempHp: Int) = HpState(
    propertyId = propertyId,
    current = value,
    max = total,
    tempHp = tempHp,
)

private fun TrackedResource.toPipRow() = PipRowState(
    propertyId = propertyId,
    label = name,
    reset = reset,
    value = value.coerceAtLeast(0),
    total = total.coerceAtLeast(0),
    pinned = pinned,
    kind = kind,
)

private fun TrackedResource.toConsumable() = ConsumableState(
    propertyId = propertyId,
    name = name,
    quantity = value,
)

private fun ConditionToggle.toChip() = ConditionChipState(
    propertyId = propertyId,
    name = name,
    enabled = enabled,
    canFlip = flippable,
)

/**
 * Display text for a reset rule. Deliberately not a string resource: it is asserted in a
 * JVM unit test, and the tracker is English-only in v1 (docs/design/00-DESIGN.md).
 */
fun ResetRule.label(): String = when (this) {
    ResetRule.SHORT_REST -> "Short rest"
    ResetRule.LONG_REST -> "Long rest"
}

private fun TrackerWrite.toHistoryRow(canUndo: Boolean, zone: ZoneId) = HistoryRowState(
    id = id,
    label = describe(),
    at = formatSyncedAt(at, zone).orEmpty(),
    canUndo = canUndo,
    undone = undone,
)

/**
 * What one dispatched write reads as in the snackbar and the history sheet.
 *
 * Same "not a string resource" reasoning as [label] — these are asserted in a JVM unit
 * test with no Android context, and v1 is English-only. [TrackerWrite.amount] is the
 * *coalesced* amount, so three quick taps read as "Spent 3 × 1st Level", which is exactly
 * what one server call did.
 */
fun TrackerWrite.describe(): String = when (kind) {
    TrackerWriteKind.SPEND -> "Spent $amount × $targetName"
    TrackerWriteKind.RESTORE -> "Restored $amount × $targetName"
    TrackerWriteKind.TAKE_DAMAGE -> "Took $amount damage"
    TrackerWriteKind.HEAL -> "Healed $amount"
    TrackerWriteKind.SET_VALUE -> "Set $targetName"
    TrackerWriteKind.ITEM_USE -> "Used $amount × $targetName"
    TrackerWriteKind.ITEM_ADD -> "Added $amount × $targetName"
    TrackerWriteKind.ITEM_SET -> "Set the number of $targetName"
    TrackerWriteKind.TOGGLE -> "Toggled $targetName"
    TrackerWriteKind.SHORT_REST -> "Short rest"
    TrackerWriteKind.LONG_REST -> "Long rest"
}

/**
 * What a rolled-back write reads as. The server's own `reason` is preferred where it gave
 * one — a DiceCloud validation message tells the user more than "couldn't save" — with the
 * two cases that have their own copy handled first.
 */
fun TrackerWriteFailure.describe(): String = when {
    refusedOffline -> "Not saved — you're offline"
    rateLimited -> "Too fast — that one didn't save"
    reason != null -> "Not saved: $reason"
    else -> "That didn't save"
}
