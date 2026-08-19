package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.db.ThemePrefDao
import com.hashtagchow.magehand.core.data.db.ThemePrefEntity
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.db.toEntity
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.data.write.WriteFailure
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteOperation
import com.hashtagchow.magehand.core.data.write.WriteQueue
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * One opened character, as the **UI layer** sees it.
 *
 * ### Why this exists rather than handing `:app` a [CreatureSession]
 *
 * Two reasons, one structural and one about WP6's read-only posture.
 *
 * *Structural.* `:core:data` takes Room, OkHttp and DataStore as `implementation`
 * dependencies, so those types are not on `:app`'s compile classpath (the reason WP5's
 * `DataModule` refuses to bind `OkHttpClient`). [CreatureSession]'s constructor names
 * `SnapshotStore` and `TrackerPrefDao`; this interface names nothing but `:core:model`
 * types and `StateFlow`, so `:app` can hold it without the classpath leaking.
 *
 * *Write posture (WP7).* [CreatureSession.writeQueue] is still deliberately **not**
 * re-exported. WP6's posture was "the UI cannot write at all"; WP7's is narrower and has to
 * survive the tracker becoming writable: **the UI can only write through the intents named
 * below, and every one of them goes through the [WriteQueue]**. That is what keeps the
 * rate limiting, the coalescing, the optimistic overlay and the undo stack unbypassable —
 * a composable holding a `WriteQueue` could call `submit` with a hand-built op and skip
 * none of it, but a composable holding an `OpenCharacter` cannot construct a DDP method
 * call at all: the method names live in `:core:data`. `WritePostureTest` pins both halves.
 */
interface OpenCharacter {

    val accountId: String
    val creatureId: String

    /** The account's https origin — the Sheet tab's target and the REST base. */
    val serverOrigin: String

    /** What the tracker renders (docs/design/04-screens-ux.md §3). */
    val board: StateFlow<TrackerBoard>

    /**
     * [board] with the hide layer suppressed. The customize sheet lists rows the tracker
     * is hiding so they can be un-hidden — they are absent from [board] by construction.
     */
    val boardIgnoringHidden: StateFlow<TrackerBoard>

    /** 06's four-state model, including `OFFLINE`. Drives the status strip. */
    val connectionState: StateFlow<ConnectionState>

    /** Epoch millis of the cached snapshot — the "synced HH:MM" half of the strip. */
    val lastSyncedAt: StateFlow<Long?>

    /** True while [board] is coming from the Room snapshot rather than the live mirror. */
    val isShowingSnapshot: StateFlow<Boolean>

    /** The local pin / hide / reorder layer, for the customize sheet (04 §5). */
    val overrides: StateFlow<List<TrackerOverride>>

    /** `"#RRGGBB"` from `theme_prefs`, or `null` for the app default (04 §6). */
    val accentColor: StateFlow<String?>

    // --- writes (04 §3) -----------------------------------------------------

    /**
     * Whether a tap may reach the server right now — `connectionState == LIVE`, which per
     * [CreatureSession] also means the `singleCharacter` subscription is ready.
     *
     * The queue refuses non-LIVE writes anyway; this exists so the controls can be *dimmed*
     * rather than silently swallowing taps (04 §UX principles: "connection state is always
     * visible, never a surprise error dialog").
     */
    val canWrite: StateFlow<Boolean>

    /** This session's dispatched writes, newest first (04 §3's undo history sheet). */
    val writeHistory: StateFlow<List<TrackerWrite>>

    /** True while [undoLastWrite] has something to reverse. */
    val canUndo: StateFlow<Boolean>

    /** Every rolled-back write, for the shake animation and the error snackbar. */
    val writeFailures: Flow<TrackerWriteFailure>

    /** Spend [amount] charges of a slot or resource row (a filled pip tapped). */
    fun spend(row: TrackedResource, amount: Int = 1)

    /** Restore [amount] charges (an empty pip tapped). */
    fun restore(row: TrackedResource, amount: Int = 1)

    /** Move HP by [delta]: negative takes damage, positive heals. The server clamps. */
    fun changeHitPoints(delta: Int)

    /** Set the HP row to an absolute value — the number pad's third option. */
    fun setHitPoints(value: Int)

    /** Move a pinned item's quantity by [delta] (the consumable steppers). */
    fun adjustItem(item: TrackedResource, delta: Int)

    /** What the inventory tab renders (docs/design/10-inventory.md). */
    val inventory: StateFlow<InventoryBoard>

    /**
     * Put an item on or take it off — the inventory row's one-tap equip control
     * (10 decision 4).
     *
     * Takes a [propertyId] rather than a row type because both boards can name the target and
     * neither type is the obvious one: an [InventoryItem] is what the inventory tab holds, a
     * [TrackedResource] is what the tracker holds, and the id is the whole of what the write
     * needs. [currentlyEquipped] is what makes a correct inverse possible — see
     * `WriteOp.equip`.
     *
     * **The server reparents the item**, and the undo does not put the folder back. That
     * limit is stated in full on `WriteOp.Equip`; it is invisible on this screen because
     * 10 decision 2 renders sections by state and never renders the tree.
     */
    fun setEquipped(propertyId: String, equipped: Boolean, currentlyEquipped: Boolean, targetName: String = "")

    /**
     * Create an item on the sheet — the catalog pick and the custom form, as one call
     * (10 decision 6).
     *
     * **Not undoable.** The inverse would be a soft-remove and item deletion is fenced out of
     * this release (10 decision 12), so the history entry records the add and offers no UNDO.
     * See `WriteOp.InsertProperty`.
     */
    fun addItem(spec: NewItemSpec)

    /**
     * Delete an item from the character (FR-9, docs/design/12-inventory-layout.md decision 7).
     *
     * The screen has already shown a destructive confirm by the time this runs — this call
     * assumes it, the way [rest] assumes its dialog. What it does *not* assume is that the
     * confirm said the same thing on both kinds of character, because it truthfully cannot:
     *
     * | | server | local |
     * |---|---|---|
     * | mechanism | `creatureProperties.softRemove` | the Room row is deleted |
     * | undoable | **yes** — `restore {_id}` puts it back | **no** |
     *
     * That asymmetry is decision 7's, and it is the one place in this interface where the two
     * implementations differ in what the *player* can do rather than only in how it is stored.
     * The server keeps the document and merely flags it, so an undo is a real inverse; a local
     * row that has been deleted has no identity left to restore, and pretending otherwise
     * would mean keeping a hidden tombstone table to back a button. So the local path files a
     * **non-undoable** history entry — the shape [addItem] already has — and the local copy in
     * the confirm dialog says the deletion cannot be undone. Saying so before the tap beats
     * discovering it from a missing UNDO on the snackbar.
     *
     * ### Coins are not deletable, and this is the second gate
     *
     * The UI omits the control on a coin-tagged row (decision 7: wallet rows are
     * stepper-managed). This call is gated again, from the other direction: it resolves the id
     * against the inventory board's items, and `InventoryBoard`'s precedence puts every
     * coin-tagged item in the **wallet** and in no item list — so a coin id arriving here
     * resolves to nothing and the tap is dropped. Two independent gates because "delete your
     * entire gold stack" is the one mistake in this feature that a player could not usefully
     * be told about afterwards.
     *
     * @param targetName the row's name at tap time, for the history entry and the snackbar —
     *   see [WriteOp.targetName]. The implementations re-read it from the board and this is
     *   only a fallback for a caller that has one already.
     */
    fun removeItem(propertyId: String, targetName: String = "")

    /**
     * Move an item into a container, or back out to the carried root (FR-9, 12 decision 8).
     *
     * **Undoable**, and completely: the op carries the item's prior parent and order, so the
     * inverse is the move back. That is the capability `WriteOp.Equip` documents itself as
     * lacking — it named `organizeDoc` and "a memory of where the item was" as the two missing
     * pieces, and this intent is both.
     *
     * ### Only on unequipped items
     *
     * Decision 8's fence, enforced here and not only in the UI: `equip` reparents the property
     * on its own schedule, so an equipped item that had also been hand-placed would have two
     * writers of one field and the next equip tap would quietly undo the player's move. A
     * request to move an equipped item is dropped rather than sent.
     *
     * ### Server only
     *
     * A local character has no containers to move between — its items are Room rows with a
     * sort index and no tree at all — so `LocalOpenCharacter` implements this as a no-op and
     * the control is absent from the local detail sheet entirely. Absent rather than disabled,
     * because a destination picker with nothing in it is not a control.
     *
     * @param targetParent where to put it, in the player's vocabulary rather than the wire's.
     *   The `parentRef` collection, the folder id behind "Carried" and the `order` are all
     *   resolved against the live sheet by `InventoryEngine.moveTarget` — see
     *   [InventoryMoveTarget] for why the UI is not handed a `parentRef`.
     */
    fun moveItem(propertyId: String, targetParent: InventoryMoveTarget, targetName: String = "")

    /**
     * Move one denomination of coin by [delta] — the wallet steppers (10 decision 5).
     *
     * Rides the *same* `adjustQuantity` path the consumable steppers use, because coins are
     * ordinary items on this server and the rate limit, the coalescing and the undo entry
     * should all behave identically. It is a separate intent only because of the one case
     * `adjustItem` cannot express: **[WalletRow.propertyId] may be `null`**, meaning the sheet
     * carries no item for this denomination at all, and the first increment then has to
     * *create* one rather than adjust it. A `TrackedResource` has no way to say "there is no
     * property yet".
     *
     * A decrement on an absent row does nothing: there are no coins to spend.
     *
     * ### Two limits, stated rather than hidden
     *
     * **A burst on an absent denomination creates exactly one item.** Holding the stepper on a
     * coin the sheet lacks does not file one `insert` per repeat; the implementation keeps a
     * single insert on the wire and folds the rest of the hold into it, so the sheet gains one
     * coin item carrying the whole count. This matters more than it sounds: item deletion is
     * fenced out of this release (10 decision 12), so a duplicate created here could not be
     * cleaned up from inside the app.
     *
     * **A decrement can only spend the head stack.** Where a sheet carries several items with
     * one denomination's tag, [WalletRow.quantity] is their sum but [WalletRow.propertyId]
     * names the first, and a single `adjustQuantity` cannot reach past it. Spending is
     * therefore clamped at [WalletRow.headQuantity] — asking for more than the head holds
     * empties the head and stops. The row's total then still counts the other stacks, which is
     * the truth: the money is on the sheet, this app just has no v1 write that can reach it.
     * Multi-stack spending is FR-9 territory, with container reorganization.
     */
    fun adjustCoins(row: WalletRow, delta: Int)

    /** Flip a condition toggle (a chip tapped). */
    fun toggle(condition: ConditionToggle)

    /**
     * Short or long rest. **Not undoable** — the server applies every reset and every
     * trigger — so 04 §3 requires a confirm dialog first, and this call assumes the user
     * has already seen it.
     */
    fun rest(kind: RestKind)

    /** Reverses the newest undoable write. Returns false when there is nothing to undo. */
    suspend fun undoLastWrite(): Boolean

    suspend fun setOverride(override: TrackerOverride)

    /** Applies a whole reordering in one transaction, so no intermediate order ever renders. */
    suspend fun setOverrides(overrides: List<TrackerOverride>)

    suspend fun clearOverride(propertyId: String)

    suspend fun setAccentColor(hex: String?)

    /** 06 step 2: serialize the live mirror into the snapshot cache on app-background. */
    suspend fun captureSnapshot(): Boolean

    /** Stops the subscription and cancels this character's scope. Idempotent. */
    suspend fun close()
}

/**
 * Opens characters. `null` from [open] means "no account is signed in", which is a
 * navigation problem, not an error the tracker can render.
 */
interface OpenCharacterFactory {
    suspend fun open(creatureId: String): OpenCharacter?
}

/**
 * Production [OpenCharacterFactory].
 *
 * The lifecycle 04 §3 implies and WP6's brief states: **create on enter, close on exit**.
 * Each open builds a private [CoroutineScope]; [DefaultOpenCharacter.close] cancels it,
 * which is what tears down the `singleCharacter` subscription, the board's `stateIn` and
 * the (unreachable) write queue together. The [DdpClient] itself is *not* touched — it
 * belongs to the account and the character selector is still using it.
 */
class DefaultOpenCharacterFactory(
    private val connectionManager: DdpConnectionManager,
    private val accountRepository: AccountRepository,
    private val snapshotStore: SnapshotStore,
    private val trackerPrefDao: TrackerPrefDao,
    private val themePrefDao: ThemePrefDao,
    private val characterCache: CharacterCache,
    private val now: () -> Long = System::currentTimeMillis,
) : OpenCharacterFactory {

    override suspend fun open(creatureId: String): OpenCharacter {
        // Suspends until the account's socket object exists. It does not wait for the
        // socket to be *live*: rendering the Room snapshot while CONNECTING is the whole
        // point of 06's fallback.
        val connection = connectionManager.connection.filterNotNull().first()
        val account = connection.account

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("character-$creatureId"),
        )

        val session = CreatureSession(
            accountId = account.id,
            creatureId = creatureId,
            feed = DdpCreatureFeed(connection.client, creatureId, scope),
            snapshotStore = snapshotStore,
            trackerPrefDao = trackerPrefDao,
            scope = scope,
        ).apply {
            serverUrl = account.serverUrl
            tokenProvider = { accountRepository.tokenFor(account.id) }
        }

        try {
            val open = DefaultOpenCharacter(
                session = session,
                scope = scope,
                serverOrigin = account.serverUrl,
                themePrefDao = themePrefDao,
                trackerPrefDao = trackerPrefDao,
            )

            // Ordering matters: `start()` renders the cached snapshot and subscribes, so
            // the screen has content before any network round trip completes.
            session.start()

            scope.launch { characterCache.markOpened(account.id, creatureId, now()) }
            scope.launch {
                // 06 step 1's REST top-up. Read-only (`GET /api/creature/:id`) and best
                // effort: a failure here just leaves the older snapshot in place, and the
                // live subscription is the authority anyway.
                runCatching { session.refreshSnapshot() }
            }

            return open
        } catch (t: Throwable) {
            // Including cancellation: the caller navigated away mid-open, and a scope
            // nobody holds a reference to would keep the subscription alive forever.
            scope.cancel()
            throw t
        }
    }
}

internal class DefaultOpenCharacter(
    private val session: CreatureSession,
    private val scope: CoroutineScope,
    override val serverOrigin: String,
    private val themePrefDao: ThemePrefDao,
    private val trackerPrefDao: TrackerPrefDao,
) : OpenCharacter {

    override val accountId: String get() = session.accountId
    override val creatureId: String get() = session.creatureId

    override val board: StateFlow<TrackerBoard> get() = session.board
    override val boardIgnoringHidden: StateFlow<TrackerBoard> get() = session.boardIgnoringHidden
    override val connectionState: StateFlow<ConnectionState> get() = session.connectionState
    override val lastSyncedAt: StateFlow<Long?> get() = session.lastSyncedAt
    override val isShowingSnapshot: StateFlow<Boolean> get() = session.isShowingSnapshot

    override val overrides: StateFlow<List<TrackerOverride>> =
        session.overrides.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val accentColor: StateFlow<String?> =
        themePrefDao.observe(session.accountId, session.creatureId)
            .map { it?.accentColor }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // --- writes -------------------------------------------------------------

    override val canWrite: StateFlow<Boolean> = session.connectionState
        .map { it == ConnectionState.LIVE }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override val writeHistory: StateFlow<List<TrackerWrite>> get() = session.writeQueue.history
    override val canUndo: StateFlow<Boolean> get() = session.writeQueue.canUndo

    override val writeFailures: Flow<TrackerWriteFailure> =
        session.writeQueue.failures.map { it.toDomain() }

    /**
     * The clamps live here rather than in the composable so that every caller — including a
     * press-and-hold that outruns the server — gets them. Spending a row that already reads
     * zero would send `damage +1` past the floor; DiceCloud would accept it and the row
     * would come back needing two restores to show one charge.
     */
    override fun spend(row: TrackedResource, amount: Int) {
        if (amount <= 0 || row.value <= 0) return
        session.writeQueue.submit(WriteOp.spend(row, amount.coerceAtMost(row.value)))
    }

    override fun restore(row: TrackedResource, amount: Int) {
        if (amount <= 0) return
        val room = (row.total - row.value).coerceAtLeast(0)
        if (room == 0) return
        session.writeQueue.submit(WriteOp.restore(row, amount.coerceAtMost(room)))
    }

    override fun changeHitPoints(delta: Int) {
        val hp = session.board.value.hp ?: return
        when {
            delta < 0 -> session.writeQueue.submit(WriteOp.takeDamage(hp, -delta))
            delta > 0 -> session.writeQueue.submit(WriteOp.heal(hp, delta))
        }
    }

    override fun setHitPoints(value: Int) {
        val hp = session.board.value.hp ?: return
        session.writeQueue.submit(WriteOp.setValue(hp, value.coerceIn(0, hp.total)))
    }

    override fun adjustItem(item: TrackedResource, delta: Int) {
        if (delta == 0) return
        // An item cannot go below zero, and the server would happily store −1.
        if (delta < 0 && item.value <= 0) return
        val bounded = if (delta < 0) -minOf(-delta, item.value) else delta
        session.writeQueue.submit(WriteOp.adjust(item, bounded))
    }

    override val inventory: StateFlow<InventoryBoard> get() = session.inventory

    override fun setEquipped(
        propertyId: String,
        equipped: Boolean,
        currentlyEquipped: Boolean,
        targetName: String,
    ) {
        // Already in the requested state: nothing to say to the server, and sending it anyway
        // would burn one of the five calls the 5 s window allows and file a history entry for
        // a change that did not happen.
        if (equipped == currentlyEquipped) return
        session.writeQueue.submit(WriteOp.equip(propertyId, equipped, currentlyEquipped, targetName))
    }

    /**
     * Resolves the parent and the `order` against the sheet **as it is at tap time**, then
     * queues the insert.
     *
     * Resolution happens here rather than inside [WriteOp] because it is a read of live state,
     * and `WriteOp`'s whole contract is that an op is a value that already knows everything it
     * needs — see the factories' "they capture the row's current value" note. A sheet with no
     * creature in it yet (the screen is still loading) has nowhere to put an item, and the tap
     * is dropped rather than guessed at.
     */
    override fun addItem(spec: NewItemSpec) {
        if (!spec.isValid) return
        val target = InventoryEngine.insertTarget(session.currentSheet) ?: return
        session.writeQueue.submit(
            WriteOp.insertItem(spec, target.parentId, target.order, target.parentCollection),
        )
    }

    /**
     * `creatureProperties.softRemove` behind the interface's confirm assumption
     * (12 decision 7).
     *
     * ### The board lookup is the coin gate
     *
     * Resolving the id against [CreatureSession.inventory] rather than trusting the caller does
     * two jobs at once. It supplies the row's real name for the history entry, so "Deleted
     * Torch" cannot end up quoting a Meteor id — and, because `InventoryBoard`'s section
     * precedence puts every coin-tagged item in the **wallet** and in no item list, a coin id
     * resolves to nothing here and is dropped. The UI already omits the control on those rows;
     * this is the same rule enforced where the write actually happens, which is what the
     * interface's KDoc means by two independent gates.
     *
     * It also fails closed on a stale id — an item deleted on another device, or by this very
     * call arriving twice — for the reason `addItem` drops a tap on an empty sheet: a write
     * about a property that is not there is not a write anyone asked for.
     */
    override fun removeItem(propertyId: String, targetName: String) {
        val item = session.inventory.value.allItems.firstOrNull { it.propertyId == propertyId }
            ?: return
        session.writeQueue.submit(WriteOp.removeItem(propertyId, item.name.ifBlank { targetName }))
    }

    /**
     * `organize.organizeDoc`, with both ends of the move resolved against the sheet **as it is
     * at tap time** (12 decision 8).
     *
     * Three reads, each of which has to be live rather than remembered:
     *
     * 1. the **item**, from the board — its existence, its name, and its `equipped` state,
     *    which is decision 8's fence (see the interface KDoc for why an equipped item has two
     *    owners of its location);
     * 2. **where it is now**, from the sheet, because that is what the inverse move targets and
     *    nothing else in this app records it;
     * 3. **where it is going**, from `InventoryEngine.moveTarget`, which re-uses the same
     *    `carried`/`inventory`/creature preference order a new item's insert follows — the
     *    folder ids belong to the sheet's own structure and `equip` rewrites them.
     *
     * A move to the location the item is already in is dropped. Not merely wasteful: it would
     * burn one of the five calls the server's 5 s window allows and file a history entry
     * offering to undo a move that did not happen — [setEquipped]'s no-op guard, applied to a
     * location instead of a flag. The comparison is on the **parent** alone and deliberately
     * ignores `order`, because `moveTarget` always returns end-of-sheet: comparing it too
     * would make every re-pick of the current container a real call that only reorders.
     */
    override fun moveItem(propertyId: String, targetParent: InventoryMoveTarget, targetName: String) {
        val item = session.inventory.value.allItems.firstOrNull { it.propertyId == propertyId }
            ?: return
        // Decision 8: equip already owns an equipped item's parent. Dropped rather than sent.
        if (item.equipped) return

        val sheet = session.currentSheet
        val from = InventoryEngine.currentLocation(sheet, propertyId) ?: return
        val containerId = when (targetParent) {
            is InventoryMoveTarget.Carried -> null
            is InventoryMoveTarget.Container -> targetParent.propertyId
        }
        val to = InventoryEngine.moveTarget(sheet, containerId) ?: return
        if (to.parentId == from.parentId && to.parentCollection == from.parentCollection) return

        session.writeQueue.submit(
            WriteOp.moveItem(
                propertyId = propertyId,
                parentId = to.parentId,
                order = to.order,
                previousParentId = from.parentId,
                previousOrder = from.order,
                parentCollection = to.parentCollection,
                previousParentCollection = from.parentCollection,
                targetName = item.name.ifBlank { targetName },
            ),
        )
    }

    /**
     * One outstanding `creatureProperties.insert` for a denomination, and the taps that have
     * arrived since. [pending] is a signed net, because a hold on `+` followed by a tap on `−`
     * inside the same window is a perfectly ordinary thing to do.
     */
    private class CoinInsert(var pending: Int = 0)

    private val coinLock = Any()

    /**
     * The insert-outstanding latch, one slot per denomination — the fix for the duplicate-coin
     * bug described on [adjustCoins] and analysed on [insertCoin].
     *
     * Keyed per [CoinKind] rather than held globally so that adding gold does not block adding
     * silver: they are different items and there is no reason one should wait for the other.
     */
    private val coinInserts = HashMap<CoinKind, CoinInsert>()

    /** What [adjustCoins] decided to do, resolved under [coinLock] and acted on outside it. */
    private enum class CoinAction { INSERT, ACCUMULATED, ADJUST, DROP }

    /**
     * The wallet stepper, both halves (10 decision 5).
     *
     * When the sheet already has the coin item this is an ordinary `adjustQuantity` — the
     * identical call the consumable steppers make, with the identical sign convention
     * (`increment` is a *consumption* amount, so a `+1` in the UI is a `-1` on the wire; see
     * `WriteOp.adjust`).
     *
     * When it does not, an increment **creates** the item instead, tagged and priced per
     * [CoinKind]. That is the only path in the app where a stepper can insert, and it is why
     * this intent exists separately from [adjustItem]. A decrement on an absent row is
     * dropped: there is no such thing as spending coins you do not have, and creating a coin
     * item with a negative quantity to represent it would be worse than doing nothing.
     *
     * ### The three-way branch, and why the latch is checked first
     *
     * The latch is consulted **before** [WalletRow.propertyId], not after. Once the insert
     * lands, the mirror learns the new id and the row this method is handed stops being absent
     * — but the flush of the accumulated taps has not happened yet, and letting those last taps
     * take the ordinary adjust path would double-count them against the flush. While a slot is
     * held, every tap for that denomination goes into it and nothing else happens.
     */
    override fun adjustCoins(row: WalletRow, delta: Int) {
        if (delta == 0) return

        val action = synchronized(coinLock) {
            val outstanding = coinInserts[row.coin]
            when {
                outstanding != null -> {
                    outstanding.pending += delta
                    CoinAction.ACCUMULATED
                }

                row.propertyId != null -> CoinAction.ADJUST
                delta < 0 -> CoinAction.DROP
                else -> {
                    coinInserts[row.coin] = CoinInsert()
                    CoinAction.INSERT
                }
            }
        }

        when (action) {
            CoinAction.ACCUMULATED, CoinAction.DROP -> Unit
            CoinAction.INSERT -> insertCoin(row.coin, delta)
            CoinAction.ADJUST -> adjustExistingCoin(row, delta)
        }
    }

    /**
     * `adjustQuantity` against the stack [WalletRow.propertyId] names.
     *
     * ### The clamp is against the head, not the total
     *
     * [WalletRow.quantity] is the **sum across every stack** of this denomination, while
     * [WalletRow.propertyId] names the **first**. Clamping a spend against the sum and sending
     * it at the head is how a wallet reading 105 gp — a 5 gp stack followed by a 100 gp stack —
     * turned "spend 50" into a head stack of `−45` on the server: the clamp saw 105, allowed
     * the whole 50, and the item it actually hit only had 5 in it. Negative quantities are not
     * a state DiceCloud's own UI can produce, and nothing in this release can put one back.
     *
     * So the clamp reads [WalletRow.headQuantity] and the spend stops there. The row's total
     * goes on counting the stacks this app cannot reach, which is honest — the money is on the
     * sheet — and reaching them needs the multi-property write FR-9 owns.
     */
    private fun adjustExistingCoin(row: WalletRow, delta: Int) {
        val propertyId = row.propertyId ?: return
        if (delta < 0 && row.headQuantity <= 0) return
        val bounded = if (delta < 0) -minOf(-delta, row.headQuantity) else delta
        session.writeQueue.submit(
            WriteOp.AdjustQuantity(
                propertyId = propertyId,
                operation = WriteOperation.INCREMENT,
                value = -bounded,
                targetName = row.coin.itemName,
                intent = if (bounded < 0) TrackerWriteKind.ITEM_USE else TrackerWriteKind.ITEM_ADD,
            ),
        )
    }

    /**
     * Creates the coin item, with the latch for [coin] already held by the caller.
     *
     * ### Why a latch and not a coalesce key
     *
     * A stepper hold accelerates to a repeat every 60 ms, and `creatureProperties.insert`
     * carries no [WriteOp.coalesceKey] — so the pre-fix code filed one insert per repeat and a
     * two-second hold on an absent denomination put a dozen separate coin items on the sheet.
     * Irreversible from inside the app: item deletion is fenced out of this release
     * (10 decision 12).
     *
     * Giving `InsertProperty` a coalesce key would not have fixed it, and the reason is worth
     * writing down because it is the non-obvious half. `WriteQueue.takeCoalescedHead` merges
     * the head with entries **still sitting in the queue**; it removes the head and claims it
     * as `inFlight` *before* the call goes out, so nothing submitted during the server round
     * trip can merge into it. A keyed burst would therefore collapse to **two** calls — the one
     * already on the wire, plus one merged batch of everything that piled up behind it — and
     * two inserts are two coin items. Worse, every queued insert was built when the row was
     * still absent, so the second one would be a duplicate of an item that by then exists. The
     * queue's coalescing is the right machinery for repeated *adjustments*; it cannot close an
     * in-flight window, and creation is exactly the case where that window is the bug.
     *
     * ### What the latch does instead
     *
     * One insert per denomination is on the wire at a time. Every tap that arrives while it is
     * outstanding is summed into [CoinInsert.pending], and once the insert has landed **and**
     * the mirror has published the new property, the accumulated remainder is applied as a
     * single ordinary `adjustQuantity` (via [adjustCoins] re-entering its own ADJUST branch, so
     * the head-stack clamp and the intent labelling stay in one place). A double-tap and a
     * two-second hold both end at one item holding the right number.
     *
     * A sheet with nowhere to put the item releases the latch immediately rather than stranding
     * it — a latch nobody will ever clear would silently disable the denomination's stepper for
     * the rest of the session.
     */
    private fun insertCoin(coin: CoinKind, delta: Int) {
        val target = InventoryEngine.insertTarget(session.currentSheet)
        if (target == null) {
            synchronized(coinLock) { coinInserts.remove(coin) }
            return
        }
        val insert = session.writeQueue.submit(
            WriteOp.insertItem(
                NewItemSpec.ofCoin(coin, delta),
                target.parentId,
                target.order,
                target.parentCollection,
            ),
        )
        scope.launch { settleCoinInsert(coin, insert) }
    }

    /**
     * Waits out the insert, then releases the latch and flushes whatever piled up behind it.
     *
     * ### Why it waits for the mirror and not just for the method
     *
     * The flush is an `adjustQuantity`, which needs the new property's id — and the id is
     * minted by the server. Waiting for the *row* rather than for the call is what makes the
     * re-entry provably take [adjustCoins]'s ADJUST branch: [awaitCreatedCoinRow] only ever
     * returns a row that is no longer absent, so the flush can never start a second insert.
     * In practice the wait is already over when it starts — [WriteQueue] completes a submission
     * on `result` **and** `updated`, and DDP guarantees the document push precedes `updated` —
     * so this is a `StateFlow` catching up, not a network wait.
     *
     * ### What is dropped, and why that is the safe direction
     *
     * A failed insert (server rejection, a refusal off-LIVE) drops the accumulated taps. No
     * item was created, and re-sending an insert whose outcome we never saw is precisely the
     * duplicate this latch exists to prevent — the same rule [WriteQueue] applies when a call
     * loses its connection. A mirror that never publishes the row inside the timeout drops them
     * too; the item exists with the count the insert carried, the wallet will show it, and the
     * next tap adjusts it.
     */
    private suspend fun settleCoinInsert(coin: CoinKind, insert: Deferred<Unit>) {
        // The slot is released on **every** exit, including a cancellation landing between
        // `await` and the flush. Unreachable today — the only thing that cancels this
        // coroutine is `close()` cancelling the character scope, which discards the map along
        // with the object — but "unreachable because of a fact about another method" is the
        // kind of reasoning that stops being true quietly. A stranded slot would disable this
        // denomination's stepper for the rest of the session with nothing to show for it.
        var slotReleased = false
        try {
            var created = false
            try {
                insert.await()
                created = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // `created` stays false and the accumulation is dropped — see the KDoc.
            }

            val row = if (created) awaitCreatedCoinRow(coin) else null
            val pending = synchronized(coinLock) { coinInserts.remove(coin)?.pending ?: 0 }
            slotReleased = true
            if (row != null && pending != 0) adjustCoins(row, pending)
        } finally {
            if (!slotReleased) synchronized(coinLock) { coinInserts.remove(coin) }
        }
    }

    /** The denomination's row once it stops being absent, or `null` if it never does. */
    private suspend fun awaitCreatedCoinRow(coin: CoinKind): WalletRow? =
        withTimeoutOrNull(COIN_INSERT_SETTLE_MILLIS) {
            session.inventory.map { it.wallet.row(coin) }.first { !it.isAbsent }
        }

    override fun toggle(condition: ConditionToggle) {
        session.writeQueue.submit(WriteOp.flip(condition))
    }

    override fun rest(kind: RestKind) {
        session.writeQueue.submit(WriteOp.rest(session.creatureId, kind))
    }

    override suspend fun undoLastWrite(): Boolean = session.writeQueue.undo()

    private val closed = MutableStateFlow(false)

    /** Exposed for tests; nothing in the UI needs to ask. */
    val isClosed: StateFlow<Boolean> = closed.asStateFlow()

    override suspend fun setOverride(override: TrackerOverride) = session.setOverride(override)

    override suspend fun setOverrides(overrides: List<TrackerOverride>) =
        trackerPrefDao.upsert(overrides.map { it.toEntity(session.accountId, session.creatureId) })

    override suspend fun clearOverride(propertyId: String) = session.clearOverride(propertyId)

    override suspend fun setAccentColor(hex: String?) = themePrefDao.upsert(
        ThemePrefEntity(
            accountId = session.accountId,
            creatureId = session.creatureId,
            accentColor = hex,
        ),
    )

    override suspend fun captureSnapshot(): Boolean = session.captureSnapshot()

    override suspend fun close() {
        if (closed.value) return
        closed.value = true
        session.close()
        scope.cancel()
    }
}

/**
 * A queue-level failure, narrowed to what the tracker can render.
 *
 * The `reason` is the server's own words (04 §1's rule for the login screen, applied here
 * too: a DiceCloud validation message is more useful than "something went wrong"), but the
 * exception *type* is deliberately not exposed — `:app` has no business branching on
 * `DdpError` vs `DdpConnectionException`, only on the two cases it has different copy for.
 */
internal fun WriteFailure.toDomain(): TrackerWriteFailure {
    val ddp = cause as? DdpError
    return TrackerWriteFailure(
        // Monotonic, so the composable can key a shake on it: two identical failures in a
        // row must animate twice, and a data class equal to its predecessor would not.
        id = FAILURE_IDS.incrementAndGet(),
        kind = op.intent ?: TrackerWriteKind.SET_VALUE,
        // `null` for a rest, per TrackerWriteFailure's contract: a rest is not row-shaped,
        // and `WriteOp.Rest.targetId` is the *creature* id, which would send the UI
        // hunting for a row to shake that does not exist. Written as an `if` because the
        // previous `(op as? Rest)?.let { null } ?: op.targetId` could not do this — a
        // `let` returning `null` is `Nothing?`, so the elvis always took the right side.
        propertyId = if (op is WriteOp.Rest) null else op.targetId,
        targetName = op.targetName,
        reason = ddp?.reason ?: ddp?.detailsText,
        refusedOffline = isRefusal,
        rateLimited = isRateLimit,
    )
}

private val FAILURE_IDS = java.util.concurrent.atomic.AtomicLong(0)

/**
 * How long the coin-insert latch waits for the mirror to publish the property the server just
 * created, before giving up on flushing the taps that accumulated behind it.
 *
 * Generous on purpose, and not a network timeout: by the time this wait starts the method has
 * already been acknowledged with `updated`, so what is being waited for is a `StateFlow` two
 * `map`s downstream of the mirror. Two seconds is long enough that it never fires on a working
 * connection and short enough that a wedged one releases the stepper rather than disabling the
 * denomination for the rest of the session.
 */
private const val COIN_INSERT_SETTLE_MILLIS: Long = 2_000
