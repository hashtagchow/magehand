package com.hashtagchow.magehand.core.data.local

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import java.util.concurrent.atomic.AtomicLong

/**
 * One opened **local** character, speaking the same [OpenCharacter] intent surface the
 * tracker already consumes (docs/design/09-local-characters.md decision 5).
 *
 * ### Why there is no interface extraction here
 *
 * [OpenCharacter] was already an interface, and `DefaultOpenCharacter` was already only one
 * implementation of it — that is precisely what `WritePostureTest`'s third assertion bought:
 * a named, pinned, minimal seam between the UI and whatever is behind it. So the whole of
 * wave A's "reuse the tracker screen" claim costs one more `: OpenCharacter` and nothing
 * else. Neither the interface, nor `DefaultOpenCharacter`, nor the `WriteQueue`, nor
 * `WritePostureTest`'s allow-list is touched; a widened seam would have shown up as a failure
 * in that test, and none was needed.
 *
 * ### What differs from the server implementation, and why
 *
 * | | `DefaultOpenCharacter` | here |
 * |---|---|---|
 * | write path | `WriteQueue` → DDP method | straight to Room |
 * | rate limits / coalescing | yes (the server's) | none — there is no server to protect |
 * | optimistic overlay | yes | none — there is nothing to be optimistic *about*: the write has already landed by the time Room re-emits |
 * | rollback / failures | on method error | none — [writeFailures] never emits |
 * | undo | queue's stack, coalesced per call | session-scoped, one entry per intent (below) |
 * | connection | four-state, from the socket | [ConnectionState.LIVE], constant (below) |
 * | snapshots | REST + Room cache | none — Room *is* the source of truth |
 *
 * ### Undo (09 decision 5)
 *
 * "Session-scoped history is acceptable if persistent history costs real complexity", and it
 * does — a persistent journal is a third table, a retention rule and a migration, to make a
 * history sheet survive a process death the player did not ask it to survive. So: **in
 * memory, for the life of this instance**, and it stores each write's *previous absolute
 * value* rather than an inverse increment. Undo then means "put it back exactly as it was",
 * which cannot drift against a clamp the way replaying an inverse can.
 *
 * One entry per intent call, not per coalesced dispatch. The server path's [TrackerWrite]
 * contract is "one entry == one server call" because the queue merges rapid taps; nothing
 * merges here, so one entry == one tap == one Room write, and the history says exactly what
 * happened.
 *
 * ### Where the clamps live
 *
 * Here, mirroring `DefaultOpenCharacter`'s clamps method for method — two implementations of
 * one interface that behaved differently at the edges would make "the same screen" a lie at
 * the only moment it matters. The difference is what they clamp *against*: the server path
 * reads its optimistic overlay, which is already current; this one re-reads the row inside
 * the write's own critical section, so a press-and-hold clamps against committed truth rather
 * than against a board Room has not re-emitted yet.
 */
class LocalOpenCharacter(
    override val creatureId: String,
    private val dao: LocalCharacterDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : OpenCharacter {

    /**
     * **Not an account id, and deliberately not a usable one.**
     *
     * 09 decision 1 forbids a sentinel account: `serverUrl="local"` would leak into every
     * account-keyed query and into sign-out's semantics. [OpenCharacter] names this field, so
     * it has to return something — and the empty string is the one value that *fails closed*.
     * No `AccountEntity` can carry it, so any query mistakenly keyed on it matches nothing
     * rather than matching another account's rows. Nothing local reads it.
     */
    override val accountId: String = NO_ACCOUNT

    /**
     * Empty: there is no server. Wave B suppresses the Sheet tab entirely for a local
     * character (09 decision 8 — "no WebView instantiated"), so nothing should ever load it;
     * an empty origin makes a mistake here a visibly broken URL rather than a request to
     * somebody's DiceCloud.
     */
    override val serverOrigin: String = ""

    // --- reads --------------------------------------------------------------

    private val characterFlow = dao.observe(creatureId).map { it?.toDomain() }
    private val rowsFlow = dao.observeRows(creatureId)
        .map { rows -> rows.mapNotNull { it.toDomain() } }

    override val board: StateFlow<TrackerBoard> =
        combine(characterFlow, rowsFlow) { character, rows -> LocalTrackerBoard.build(character, rows) }
            .stateIn(scope, SharingStarted.Eagerly, TrackerBoard.EMPTY)

    /**
     * Identical to [board]. There is no hide layer for a local character: the player added
     * every row by hand, so "hide this row" and "delete this row" are the same intent, and
     * the form already offers the second one. 09 decision 8's "ONE mechanism" is
     * `local_tracker_rows.sortIndex` — reorder, and nothing else.
     */
    override val boardIgnoringHidden: StateFlow<TrackerBoard> get() = board

    /**
     * Constant [ConnectionState.LIVE].
     *
     * 09 decision 8: connection status is meaningless locally and **must not render**; wave B
     * omits the strip and the dot. This value therefore exists only to satisfy the interface,
     * and `LIVE` is the one constant that leaves every *derived* affordance correct if wave B
     * ever renders something from it by accident — a local write always lands, which is what
     * `LIVE` means to everything downstream. `OFFLINE` would be the more poetic answer and the
     * worse one: it dims the controls of a character that has nothing to be offline from.
     */
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.LIVE).asStateFlow()

    /** Never synced, because there is nowhere to sync to. Renders as no "synced HH:MM" half. */
    override val lastSyncedAt: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

    /**
     * Always false. A snapshot is a *stale copy of a server's state*; Room is not a copy of
     * anything here, it is the state. Saying `true` would put the tracker's "showing cached
     * data" affordance on a character whose data is as live as data gets.
     */
    override val isShowingSnapshot: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    /**
     * The row order, as the customize sheet's vocabulary.
     *
     * Only [TrackerOverride.sortIndex] is meaningful — see [setOverride] for pin and hide.
     */
    override val overrides: StateFlow<List<TrackerOverride>> = rowsFlow
        .map { rows -> rows.map { TrackerOverride(propertyId = it.id, sortIndex = it.sortIndex) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * `null` — the app default palette.
     *
     * `theme_prefs` is keyed by `(accountId, creatureId)`, so storing a local accent there
     * needs the sentinel account 09 decision 1 forbids. Per-character accents for local
     * characters are a 1.2 question, not a reason to put a fake account in the database.
     */
    override val accentColor: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

    // --- writes -------------------------------------------------------------

    /** Always true. There is no connection to lose and no rate limiter to refuse a tap. */
    override val canWrite: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    private val history = MutableStateFlow<List<TrackerWrite>>(emptyList())
    override val writeHistory: StateFlow<List<TrackerWrite>> = history.asStateFlow()

    /**
     * Written synchronously by [updateUndoStack], never derived through an async collector.
     * The original `history.map { … }.stateIn(scope, Eagerly, …)` republished on the
     * character's scope, so `awaitIdle()` — which waits for `inFlight == 0`, a guarantee
     * that releases *after* [writeLock] does — could observe a `canUndo` computed from the
     * pre-write history. Root-caused from a 1-in-5 flake in
     * `a rest is not undoable and invalidates everything above it`; reproduced 2 000×/run
     * pristine, 12 000 iterations clean with this shape. The predicate is unchanged:
     * `undoStack.isNotEmpty()` ≡ `history.any { it.undoable && !it.undone }`.
     */
    private val undoAvailable = MutableStateFlow(false)
    override val canUndo: StateFlow<Boolean> = undoAvailable.asStateFlow()

    /** Never emits: a Room write against a row this instance owns has no failure mode the
     * player can act on, and a shake animation for one would be theatre. */
    override val writeFailures: Flow<TrackerWriteFailure> = emptyFlow()

    private val writeIds = AtomicLong(0)

    /** Newest first, keyed by [TrackerWrite.id] — the undo stack's own memory. */
    private val undoStack = MutableStateFlow<List<Undoable>>(emptyList())

    /**
     * The one door to [undoStack]: every mutation republishes [undoAvailable] in the same
     * breath, and every caller already holds [writeLock] — which is what makes
     * `awaitIdle()`'s guarantee cover [canUndo] deterministically.
     */
    private fun updateUndoStack(transform: (List<Undoable>) -> List<Undoable>) {
        undoStack.value = transform(undoStack.value)
        undoAvailable.value = undoStack.value.isNotEmpty()
    }

    /**
     * Serializes writes so a press-and-hold's read-modify-write pairs cannot interleave.
     *
     * `Mutex` and not a queue: 09 decision 5 rules out the `WriteQueue` (and with it the rate
     * gates, the coalescing and the overlay), but *ordering* is not one of those things — it
     * is the difference between two taps spending two charges and two taps spending one.
     * Combined with [CoroutineStart.UNDISPATCHED] below, whose effect is that each intent
     * reaches `lock()` on the caller's thread in call order, the mutex's FIFO waiter queue
     * makes the commit order the tap order.
     */
    private val writeLock = Mutex()

    /** Dispatched-but-not-yet-committed intents; see [awaitIdle]. */
    private val inFlight = MutableStateFlow(0)

    override fun spend(row: TrackedResource, amount: Int) {
        if (amount <= 0) return
        dispatch {
            val stored = dao.findRow(row.propertyId) ?: return@dispatch
            if (stored.current <= 0) return@dispatch
            val spent = amount.coerceAtMost(stored.current)
            writeRowCurrent(stored, stored.current - spent)
            journal(TrackerWriteKind.SPEND, stored.label, spent, Undoable.Row(stored.id, stored.current))
        }
    }

    override fun restore(row: TrackedResource, amount: Int) {
        if (amount <= 0) return
        dispatch {
            val stored = dao.findRow(row.propertyId) ?: return@dispatch
            val room = (stored.total - stored.current).coerceAtLeast(0)
            if (room == 0) return@dispatch
            val restored = amount.coerceAtMost(room)
            writeRowCurrent(stored, stored.current + restored)
            journal(TrackerWriteKind.RESTORE, stored.label, restored, Undoable.Row(stored.id, stored.current))
        }
    }

    override fun changeHitPoints(delta: Int) {
        if (delta == 0) return
        dispatch {
            val character = dao.find(creatureId) ?: return@dispatch
            val next = (character.currentHp + delta).coerceIn(0, character.maxHp)
            if (next == character.currentHp) return@dispatch
            dao.setCurrentHp(creatureId, next, now())
            val kind = if (delta < 0) TrackerWriteKind.TAKE_DAMAGE else TrackerWriteKind.HEAL
            journal(
                kind = kind,
                targetName = LocalTrackerBoard.HP_ROW_NAME,
                // What actually happened, not what was asked for: taking 40 damage at 12 HP
                // is 12 damage, and a history entry saying 40 would offer an undo that heals
                // the character past its maximum.
                amount = kotlin.math.abs(next - character.currentHp),
                undo = Undoable.HitPoints(character.currentHp),
            )
        }
    }

    override fun setHitPoints(value: Int) {
        dispatch {
            val character = dao.find(creatureId) ?: return@dispatch
            val next = value.coerceIn(0, character.maxHp)
            if (next == character.currentHp) return@dispatch
            dao.setCurrentHp(creatureId, next, now())
            journal(
                kind = TrackerWriteKind.SET_VALUE,
                targetName = LocalTrackerBoard.HP_ROW_NAME,
                amount = next,
                // An absolute set has no inverse *increment*, which is why the server path
                // marks SET_VALUE non-undoable. Here the previous value is simply known, so
                // the entry can honestly offer an undo the other path cannot.
                undo = Undoable.HitPoints(character.currentHp),
            )
        }
    }

    /**
     * Item quantities are unbounded above, exactly as on the server path: an item has no
     * maximum, only a floor at zero.
     */
    override fun adjustItem(item: TrackedResource, delta: Int) {
        if (delta == 0) return
        dispatch {
            val stored = dao.findRow(item.propertyId) ?: return@dispatch
            if (delta < 0 && stored.current <= 0) return@dispatch
            val next = (stored.current + delta).coerceAtLeast(0)
            if (next == stored.current) return@dispatch
            dao.setRowQuantity(stored.id, next)
            dao.touch(creatureId, now())
            val kind = if (delta < 0) TrackerWriteKind.ITEM_USE else TrackerWriteKind.ITEM_ADD
            journal(kind, stored.label, kotlin.math.abs(next - stored.current), Undoable.Item(stored.id, stored.current))
        }
    }

    override val inventory: StateFlow<InventoryBoard> =
        combine(characterFlow, rowsFlow) { character, rows -> LocalInventoryBoard.build(character, rows) }
            .stateIn(scope, SharingStarted.Eagerly, InventoryBoard.EMPTY)

    /**
     * Local equip: a **plain flag** (10 decision 10).
     *
     * The server path's `creatureProperties.equip` reparents the property and its undo cannot
     * put the folder back (see `WriteOp.Equip`). There are no folders here, so there is
     * nothing to lose and the undo is complete rather than partial — the same shape
     * [setHitPoints] already has, and for the same reason: the local path genuinely knows
     * more, so it honestly offers more.
     *
     * [currentlyEquipped] and [targetName] are the interface's, and are re-read rather than
     * trusted: the state is taken from the committed row inside the write's own critical
     * section, exactly as [spend] re-reads. A caller's idea of the current state can be one
     * frame stale; the row cannot.
     */
    override fun setEquipped(
        propertyId: String,
        equipped: Boolean,
        currentlyEquipped: Boolean,
        targetName: String,
    ) {
        dispatch {
            val stored = dao.findRow(propertyId) ?: return@dispatch
            if (stored.equipped == equipped) return@dispatch
            dao.setRowEquipped(stored.id, equipped)
            dao.touch(creatureId, now())
            journal(
                kind = if (equipped) TrackerWriteKind.EQUIP else TrackerWriteKind.UNEQUIP,
                targetName = stored.label,
                amount = 1,
                undo = Undoable.Equipped(stored.id, stored.equipped),
            )
        }
    }

    /**
     * Adds an item row (10 decisions 6 and 10). The catalog and the custom form, one path.
     *
     * **Not undoable, and that is a decision rather than a limitation.** This implementation
     * knows perfectly well how to reverse the write — delete the row it just inserted — and
     * that is precisely why it must not: item deletion is fenced out of this release entirely
     * (10 decision 12 puts it in FR-9), and an UNDO button that deleted a row would ship the
     * fenced capability through the one door nobody was watching. The server path reaches the
     * same answer from the other direction (its inverse would be a `softRemove` it does not
     * call), so the two behave identically at the edge — which is the whole of 09 decision 5's
     * "same screen" claim.
     *
     * The row is a [LocalRowKind.ITEM] with `total == current == quantity`, matching what the
     * tracker's own item rows are and what `setRowQuantity` maintains.
     */
    override fun addItem(spec: NewItemSpec) {
        if (!spec.isValid) return
        dispatch {
            // Fails closed on a character deleted underneath an open screen: the FK would
            // reject the insert anyway, and checking is cheaper than catching.
            dao.find(creatureId) ?: return@dispatch
            val sortIndex = (dao.maxSortIndex(creatureId) ?: -1) + 1
            dao.upsertRows(
                listOf(
                    LocalTrackerRowEntity(
                        id = newRowId(),
                        characterId = creatureId,
                        kind = LocalRowKind.ITEM.storedValue,
                        label = spec.name,
                        total = spec.quantity,
                        current = spec.quantity,
                        resetRule = LocalTrackerRowEntity.RESET_NONE,
                        sortIndex = sortIndex,
                        weight = spec.weightLb,
                        value = spec.valueGp,
                        description = spec.description?.takeIf { it.isNotBlank() },
                        equipped = false,
                    ),
                ),
            )
            dao.touch(creatureId, now())
            journal(TrackerWriteKind.ITEM_CREATE, spec.name, spec.quantity, undo = null)
        }
    }

    /**
     * Deletes the row (FR-9, docs/design/12-inventory-layout.md decision 7).
     *
     * ### Honestly not undoable
     *
     * The server path's delete *is* reversible, because DiceCloud offers no hard delete at all:
     * `softRemove` only sets a flag and `restore` clears it, so the document survives and the
     * inverse op is real. Here the row is gone. Making this undoable would mean keeping the
     * deleted row somewhere — a `removed` column and a filter on every local query, or a
     * tombstone table — which is a schema migration bought to back one button, on a screen
     * whose whole design premise (09 decision 5) is that local characters do not pay for
     * server machinery they have no server for.
     *
     * So the history entry offers no UNDO, exactly as [addItem]'s does, and the confirm dialog
     * says the deletion cannot be undone **before** the tap rather than leaving the player to
     * infer it from a snackbar with no button. This is the one place where the two
     * implementations of this interface differ in what the player can do rather than only in
     * how it is stored, and it is stated on `OpenCharacter.removeItem` for that reason.
     *
     * Note what is *not* here: no undo-stack invalidation. Deleting a row does not falsify the
     * entries above it the way a rest does — an undo that restores some other row's value is
     * still perfectly correct — and the one entry that could name this row is skipped by
     * [undoLastWrite]'s existing "a row deleted by that same edit is skipped" branch, which
     * the form's delete already exercised.
     */
    override fun removeItem(propertyId: String, targetName: String) {
        dispatch {
            val stored = dao.findRow(propertyId) ?: return@dispatch
            // **Items only.** `local_tracker_rows` is one table holding slots, resources and
            // items alike, and `findRow` is keyed on the id alone — so without this line the
            // one irreversible operation in the app would delete a spell-slot row for any
            // caller that handed it a tracker id, and the two boards share an id space
            // (`TrackedResource.propertyId` == `InventoryItem.propertyId`). The server path
            // gets this gate for free twice over: its board lookup only holds items, and its
            // delete is a reversible `softRemove` in any case. This path has neither, so the
            // gate is written out. Refused rather than journalled: nothing happened.
            if (LocalRowKind.fromStored(stored.kind) != LocalRowKind.ITEM) return@dispatch
            dao.deleteRow(stored.id)
            dao.touch(creatureId, now())
            journal(TrackerWriteKind.ITEM_DELETE, stored.label, amount = 1, undo = null)
        }
    }

    /**
     * No-op. 12 decision 8: **local characters have no containers**, so there is nowhere to
     * move an item to.
     *
     * The same shape [toggle] has, and for the same kind of reason: [OpenCharacter] names the
     * intent, the local data model expresses nothing it could act on, and doing nothing is the
     * honest implementation of "move an item into a container that cannot exist". Wave 2's UI
     * omits the control entirely on a local character rather than dimming it — a destination
     * picker with no destinations is not a control — so nothing can reach this in practice; it
     * is here so that the interface is satisfied without a `TODO` or a thrown exception, and
     * so that a future local container model has one obvious place to land.
     *
     * `local_tracker_rows.sortIndex` is not touched either. Reordering is the customize
     * sheet's mechanism (09 decision 8's "ONE mechanism"), and quietly reinterpreting a *move
     * between containers* as a *reorder within one list* would be this class inventing a
     * feature the design fenced out.
     */
    override fun moveItem(propertyId: String, targetParent: InventoryMoveTarget, targetName: String) = Unit

    /**
     * The wallet stepper (10 decision 10): four integer columns, no items and no tags.
     *
     * [WalletRow.propertyId] is never `null` for a local character — the column exists whether
     * it reads zero or not — so the server path's "first increment creates the item" branch has
     * no counterpart here. The row is identified by its [WalletRow.coin] rather than by that
     * id, because the id is a synthetic minted by `LocalInventoryBoard` and the column is the
     * real thing.
     *
     * Clamped against a **fresh read** inside the critical section, like every other write in
     * this class: a press-and-hold on "−" must stop at zero against committed truth, not
     * against a board Room has not re-emitted yet.
     */
    override fun adjustCoins(row: WalletRow, delta: Int) {
        if (delta == 0) return
        dispatch {
            val character = dao.find(creatureId)?.toDomain() ?: return@dispatch
            val previous = character.coins.count(row.coin)
            val next = (previous + delta).coerceAtLeast(0)
            if (next == previous) return@dispatch
            val purse = character.coins.with(row.coin, next)
            dao.setCoins(creatureId, purse.platinum, purse.gold, purse.silver, purse.copper, now())
            journal(
                kind = if (delta < 0) TrackerWriteKind.ITEM_USE else TrackerWriteKind.ITEM_ADD,
                targetName = row.coin.itemName,
                amount = kotlin.math.abs(next - previous),
                undo = Undoable.Coins(row.coin, previous),
            )
        }
    }

    /**
     * No-op. 09 decision 4: local characters have no toggles — the form offers no field for
     * one, so [board] carries none and nothing can be tapped to reach this. It is here
     * because [OpenCharacter] names it, and doing nothing is the honest implementation of
     * "flip a toggle that does not exist".
     */
    override fun toggle(condition: ConditionToggle) = Unit

    /**
     * 09 decision 7, and it is [RestKind.restores] doing the deciding — the same function the
     * server's own behaviour is described by, so short-resets-short and long-resets-both is
     * written once for both kinds of character.
     *
     * Not undoable, matching the server path: a rest touches every qualifying row at once and
     * reversing it would need per-row memory the confirm dialog already makes unnecessary.
     * A rest also invalidates the entries above it, for the reason [TrackerWrite.undoable]
     * gives — undoing a spend after a rest would apply damage to a row already refilled.
     */
    override fun rest(kind: RestKind) {
        dispatch {
            dao.rest(creatureId, kind.storedResetRules(), now())
            val restKind =
                if (kind == RestKind.SHORT) TrackerWriteKind.SHORT_REST else TrackerWriteKind.LONG_REST
            invalidateUndoStack()
            journal(restKind, targetName = "", amount = 0, undo = null)
        }
    }

    /**
     * Puts the newest reversible write's value back — **clamped against the ceiling as it is
     * now**, not as it was when the write happened.
     *
     * The class KDoc's case for storing absolute previous values is that they "cannot drift
     * against a clamp the way replaying an inverse can", and that holds for everything the
     * tracker itself can do. What it did not account for is the *form*: 09 decision 4 makes
     * re-opening the form the editor, and an edit may lower a row's total or the character's
     * max HP between a write and its undo. Spend a 4/4 slot, edit the row down to 2, come
     * back, tap UNDO, and the unclamped restore writes 4 into a row whose total is 2 — a
     * board reading "4 / 2", which every clamp in this class exists to make impossible.
     *
     * Re-read inside the lock rather than clamped against a remembered ceiling, for the same
     * reason [spend] re-reads: the committed row is the only thing that is certainly current.
     * A row deleted by that same edit is skipped — there is nothing to restore it into — but
     * the entry still leaves the stack and is marked undone, because the user's undo *was*
     * answered as fully as it can be, and an entry that stayed offering UNDO forever would be
     * a button that does nothing.
     *
     * Items are the exception, and deliberately: [adjustItem] gives an item no ceiling ("an
     * item has no maximum, only a floor at zero"), so clamping a quantity against `total`
     * would invent a limit the write path does not have — `setRowQuantity` moves both fields
     * together precisely because for an item they are one number.
     */
    override suspend fun undoLastWrite(): Boolean = writeLock.withLock {
        val entry = undoStack.value.firstOrNull() ?: return@withLock false
        when (entry) {
            is Undoable.Row -> dao.findRow(entry.rowId)?.let { row ->
                dao.setRowCurrent(row.id, entry.previous.coerceIn(0, row.total))
            }
            is Undoable.Item -> dao.findRow(entry.rowId)?.let { row ->
                dao.setRowQuantity(row.id, entry.previous.coerceAtLeast(0))
            }
            is Undoable.HitPoints -> dao.find(creatureId)?.let { character ->
                dao.setCurrentHp(creatureId, entry.previous.coerceIn(0, character.maxHp), now())
            }
            // No clamp: a boolean has no ceiling to drift against, which is the one case the
            // class KDoc's "absolute previous value" story is trivially true for.
            is Undoable.Equipped -> dao.findRow(entry.rowId)?.let { row ->
                dao.setRowEquipped(row.id, entry.previous)
            }
            // Floored, not clamped to a ceiling: coins have no maximum, exactly as items have
            // none (see [adjustItem]), so inventing one for an undo would be a limit the write
            // path does not have.
            is Undoable.Coins -> dao.find(creatureId)?.toDomain()?.let { character ->
                val purse = character.coins.with(entry.coin, entry.previous)
                dao.setCoins(creatureId, purse.platinum, purse.gold, purse.silver, purse.copper, now())
            }
        }
        dao.touch(creatureId, now())
        updateUndoStack { it.drop(1) }
        history.update { entries ->
            entries.map { if (it.id == entry.writeId) it.copy(undone = true, undoable = false) else it }
        }
        true
    }

    // --- customize sheet ----------------------------------------------------

    /**
     * Reorder only.
     *
     * [TrackerOverride.pinned] and [TrackerOverride.hidden] are ignored, and that is the
     * decision rather than an omission: a local row exists because the player typed it in, so
     * pinning it says nothing (every one of them is already on the tracker — see
     * `LocalTrackerBoard`) and hiding it is what deleting it in the form does properly. 09
     * decision 8 asks for **one** mechanism; `local_tracker_rows.sortIndex` is it, and adding
     * a second, half-meaningful one to satisfy a field name would be the exact thing that
     * decision rules out.
     */
    override suspend fun setOverride(override: TrackerOverride) {
        val sortIndex = override.sortIndex ?: return
        writeLock.withLock { dao.setRowSortIndex(creatureId, override.propertyId, sortIndex) }
    }

    /** Applies a whole reordering in one transaction, so no intermediate order ever renders. */
    override suspend fun setOverrides(overrides: List<TrackerOverride>) = writeLock.withLock {
        val ordered = overrides
            .sortedBy { it.sortIndex ?: Int.MAX_VALUE }
            .map { it.propertyId }
        dao.reorderRows(creatureId, ordered)
    }

    /**
     * No-op, for the same reason [setOverride] ignores two of its three fields: there is no
     * separate override layer over local rows to clear. "Natural order" for a local character
     * *is* `sortIndex`, so there is no earlier order to fall back to.
     */
    override suspend fun clearOverride(propertyId: String) = Unit

    /**
     * No-op returning success-shaped nothing: `theme_prefs` is account-keyed (see
     * [accentColor]). Wave B does not offer the control for a local character.
     */
    override suspend fun setAccentColor(hex: String?) = Unit

    /**
     * `false` — nothing to capture. 06's snapshot exists so a character survives the network
     * going away; a local character has no network to lose, and serializing Room into Room
     * would be a copy that could only ever be staler than the original.
     */
    override suspend fun captureSnapshot(): Boolean = false

    private val closed = MutableStateFlow(false)

    /** Exposed for tests; nothing in the UI needs to ask. */
    val isClosed: StateFlow<Boolean> = closed.asStateFlow()

    /**
     * Stops the board's collectors and cancels this character's scope. Idempotent.
     *
     * `cancelAndJoin`, not the bare `cancel` the server path uses: cancellation is a request,
     * and the flows being torn down here are **Room** flows. Returning while an invalidation
     * collector is still unwinding leaves a window in which it can touch a database the
     * caller is entitled to close the moment this returns — which is exactly what `close()`
     * means to a screen that is going away. The server path can be laxer because what it is
     * unwinding is a socket subscription, and a late unsubscribe on a closed socket is a
     * no-op rather than an `IllegalStateException`.
     */
    override suspend fun close() {
        if (closed.value) return
        closed.value = true
        scope.coroutineContext.job.cancelAndJoin()
    }

    // --- internals ----------------------------------------------------------

    /**
     * Runs [block] as this character's next write.
     *
     * [CoroutineStart.UNDISPATCHED] is load-bearing, not an optimization: it makes the body
     * begin on the calling thread and run until its first real suspension, which is
     * [writeLock]'s `lock()`. Two taps therefore enter the mutex's waiter queue in tap order.
     * Dispatching normally would hand both to the scheduler and let them race, and a
     * press-and-hold would spend a different number of charges on different runs.
     *
     * [inFlight] is incremented *before* the launch — synchronously, in the caller — so
     * [awaitIdle] cannot observe zero for a tap that has already happened.
     */
    private fun dispatch(block: suspend () -> Unit) {
        inFlight.update { it + 1 }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                writeLock.withLock { block() }
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    /**
     * Test seam: suspends until every dispatched intent has committed.
     *
     * The intents are `fun`, not `suspend fun` — [OpenCharacter] declares them that way
     * because a composable's `onClick` cannot suspend — so "the write landed" is not something
     * a caller can await through the interface. Production code does not need to: it observes
     * [board], which re-emits when Room does. A test asserting on the *database* does need it,
     * and the alternative (polling, or sleeping) is how flaky tests are written.
     */
    internal suspend fun awaitIdle() {
        inFlight.first { it == 0 }
    }

    private suspend fun writeRowCurrent(stored: LocalTrackerRowEntity, current: Int) {
        dao.setRowCurrent(stored.id, current)
        dao.touch(creatureId, now())
    }

    /** Records one dispatched intent. Called inside [writeLock], after the write landed. */
    private fun journal(
        kind: TrackerWriteKind,
        targetName: String,
        amount: Int,
        undo: Undoable.Pending?,
    ) {
        val id = writeIds.incrementAndGet()
        history.update { entries ->
            listOf(
                TrackerWrite(
                    id = id,
                    kind = kind,
                    targetName = targetName,
                    amount = amount,
                    at = now(),
                    undoable = undo != null,
                    undone = false,
                ),
            ) + entries
        }
        if (undo != null) updateUndoStack { listOf(undo.withWriteId(id)) + it }
    }

    /** A rest refilled rows; every entry above it now describes a value that is gone. */
    private fun invalidateUndoStack() {
        updateUndoStack { emptyList() }
        history.update { entries -> entries.map { it.copy(undoable = false) } }
    }

    /**
     * A write's previous absolute value — see the class KDoc for why undo restores rather
     * than inverts.
     */
    private sealed interface Undoable {
        val writeId: Long

        /** The half a caller can build before the write's id exists. */
        sealed interface Pending {
            fun withWriteId(id: Long): Undoable
        }

        data class Row(val rowId: String, val previous: Int, override val writeId: Long = 0) :
            Undoable, Pending {
            override fun withWriteId(id: Long) = copy(writeId = id)
        }

        data class Item(val rowId: String, val previous: Int, override val writeId: Long = 0) :
            Undoable, Pending {
            override fun withWriteId(id: Long) = copy(writeId = id)
        }

        data class HitPoints(val previous: Int, override val writeId: Long = 0) :
            Undoable, Pending {
            override fun withWriteId(id: Long) = copy(writeId = id)
        }

        /** An item's equipped flag before the write (10 decision 10). */
        data class Equipped(val rowId: String, val previous: Boolean, override val writeId: Long = 0) :
            Undoable, Pending {
            override fun withWriteId(id: Long) = copy(writeId = id)
        }

        /** One denomination's count before the write. */
        data class Coins(val coin: CoinKind, val previous: Int, override val writeId: Long = 0) :
            Undoable, Pending {
            override fun withWriteId(id: Long) = copy(writeId = id)
        }
    }

    /**
     * A fresh row id.
     *
     * A UUID for the same reason [LocalCharacter.id] is one: it has to be unique against rows
     * this instance cannot see (another character's, or one added on another screen), and a
     * counter or a timestamp would collide the first time two of those happened close
     * together. Never a Meteor-shaped id, so a local row can never be mistaken for a server
     * property in a log or a crash report.
     */
    private fun newRowId(): String = java.util.UUID.randomUUID().toString()

    companion object {
        /** See [accountId]: an id no account can hold, so a query keyed on it fails closed. */
        const val NO_ACCOUNT: String = ""

        /**
         * The stored `resetRule` values a rest of this kind refills, derived from
         * [RestKind.restores] rather than restated — one rule, both kinds of character.
         * `"none"` is absent from the result by construction, which is what makes a
         * no-reset row survive every rest.
         */
        internal fun RestKind.storedResetRules(): List<String> =
            ResetRule.entries.filter { restores(it) }.map { it.wireValue }
    }
}

/**
 * Opens local characters.
 *
 * Deliberately **not** an `OpenCharacterFactory`: that interface's `null` means "no account
 * is signed in", and a local character has no account to be signed in to — reusing it would
 * make the one interesting thing it says meaningless. `null` here means the id names no local
 * character, which is a navigation problem in the same way but for a different reason.
 *
 * The lifecycle is the one 04 §3 implies and `DefaultOpenCharacterFactory` follows: **create
 * on enter, close on exit**. Each open builds a private scope; [LocalOpenCharacter.close]
 * cancels it, which tears down the board's `stateIn` and the session-scoped history together.
 */
class LocalOpenCharacterFactory(
    private val dao: LocalCharacterDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun open(characterId: String): LocalOpenCharacter? {
        if (dao.find(characterId) == null) return null

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("local-character-$characterId"),
        )
        return LocalOpenCharacter(
            creatureId = characterId,
            dao = dao,
            scope = scope,
            now = now,
        )
    }
}
