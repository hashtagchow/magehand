package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.characters.CharacterCache
import com.hashtagchow.magehand.core.data.connection.AccountConnection
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.data.db.ThemePrefDao
import com.hashtagchow.magehand.core.data.db.ThemePrefEntity
import com.hashtagchow.magehand.core.data.db.TrackerPrefDao
import com.hashtagchow.magehand.core.data.db.toEntity
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.ActionEngine
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.data.write.WriteFailure
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteOperation
import com.hashtagchow.magehand.core.data.write.WriteQueue
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.Account
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.DeathSaves
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
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

    /**
     * What the Actions surface renders (docs/design/16-actions-and-feed.md, FR-26).
     *
     * ### A read `val`, and why it costs `WritePostureTest` nothing
     *
     * 16 decision 7 is *"zero new writes … no `WritePostureTest` edits"*, and this addition
     * honours it rather than skirting it. That test's name-set and signature assertions both
     * filter `^(get|is)[A-Z].*` — a Kotlin `val` compiles to `getActions()`, so it is dropped
     * before either assertion runs, exactly as `board`, `inventory` and the other eleven read
     * flows already are. The catalog and its two lists are untouched by this wave.
     *
     * That is a property of the *shape*, not a loophole: the filter is safe precisely because no
     * intent is named like a getter, and this is a getter carrying an immutable domain type. It
     * adds nothing `:app` can send. The fifth assertion — no `core.ddp` type in any signature —
     * also still holds: [ActionBoard] is `:core:model`, like every other type on this interface.
     */
    val actions: StateFlow<ActionBoard>

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

    /**
     * Move an item's quantity by [delta] — the consumable steppers on the tracker and the
     * quantity stepper on the inventory detail sheet, as one intent.
     *
     * ### The clamp is the caller's floor, not the server's
     *
     * DiceCloud stores a quantity of `0` for any `increment` that would go below zero: it
     * clamps and forgets. So an over-decrement is not merely wasteful, it is *lossy* — the
     * amount past the floor vanishes, and a later increment that the same call carried
     * vanishes with it. Implementations therefore hold a **pending-delta latch, one slot per
     * property**, structurally identical to [adjustCoins]' insert latch:
     *
     * - at most one `adjustQuantity` per property is on the wire at a time;
     * - taps arriving while one is outstanding accumulate into the **next** flush;
     * - the accumulation is clamped as it accumulates, against the quantity the property will
     *   hold once everything dispatched *and* accumulated has landed — so the flushed delta
     *   can never take the property below zero, and a `+` tap can never be absorbed into a
     *   decrement that the server is going to clamp away.
     *
     * Concretely: a two-second press-and-hold on `−` from quantity 3 sends calls summing to
     * exactly `−3`, never more; a `+` arriving during or after that burst is a real `+1` in
     * the total the server ends up with; and rapid alternating taps net out correctly.
     *
     * ### The cost, stated
     *
     * Taps that are accumulating are **not** visible until their flush goes out — the
     * optimistic overlay only knows about ops the queue has been handed. A long hold
     * therefore steps the displayed number in flushes rather than in taps. That is the price
     * of "one op per property on the wire", which is also what makes the queue's coalescing a
     * no-op for this path — see `WriteQueue.takeCoalescedHead`.
     */
    fun adjustItem(item: TrackedResource, delta: Int)

    /**
     * Set an item's quantity to an absolute number — FR-22's direct entry
     * (docs/design/15-polish-batch.md decisions 5 and 6).
     *
     * ### Why this is an overload and not a new intent
     *
     * Decision 9 is binding: *"no new DDP methods, no new intents — direct entry composes
     * existing intents. If the wave believes otherwise it stops."* `WritePostureTest`'s third
     * assertion is the catalog, and what it asserts is the **set of method names** on this
     * interface. An overload adds no name, so the catalog is untouched by construction rather
     * than by an edit that says it is — which is the property decision 9 is protecting. The
     * player's intent is the one this method already named ("adjust this item"); only the way
     * they expressed it is new, and [ExactQuantity] is what carries that difference.
     *
     * ### Why absolute and not `adjustItem(item, target - item.value)`
     *
     * That would have needed nothing new at all, and it is wrong in exactly the case direct
     * entry is most useful. [adjustItem]'s latch means a burst of taps is *not* on the board
     * yet — the KDoc above states it: *"taps that are accumulating are not visible until their
     * flush goes out"* — so [TrackedResource.value] read a frame after a press-and-hold is a
     * number the sheet has already left behind. A delta computed against it lands the row
     * somewhere nobody asked for. An absolute target owes the board nothing.
     *
     * ### The latch treats a set as a barrier for its property
     *
     * A set submitted while an `adjustQuantity increment` is outstanding would be two writes to
     * one property with no defined order — the server may apply them either way round, and the
     * one the player watched themselves type is the one that must land last. So the latch holds
     * the set until the property's own queue has drained: **pending deltas flush first, then the
     * set goes out**, and taps arriving while a set is pending fold into it (the set is
     * re-based) rather than being dispatched behind it. See `DefaultOpenCharacter.adjustItem`.
     *
     * @param target clamped at zero by the implementation. There is no ceiling: an item has no
     *   maximum (see [TrackedResource.total]).
     */
    fun adjustItem(item: TrackedResource, target: ExactQuantity)

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
     * ### Two latches, one pattern
     *
     * This intent's latch and [adjustItem]'s are deliberately the same shape — one slot per
     * key, taps accumulated while a call is outstanding, the accumulation flushed once the
     * outstanding call has landed — because they close the same class of bug: a stepper that
     * repeats faster than the server can answer, dispatching against state that has not moved
     * yet. They differ only in what the outstanding call *is*. Here it is a
     * `creatureProperties.insert` and the hazard is **duplicate items**; on [adjustItem] it is
     * an `adjustQuantity` and the hazard is an **over-decrement the server clamps away**. Read
     * either one and you have read the other.
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

    /**
     * Set a denomination to an absolute count — FR-22's direct entry on a wallet row
     * (docs/design/15-polish-batch.md decisions 5 and 6).
     *
     * An overload for [adjustItem]'s reason, whole: decision 9 forbids a new intent, and the
     * name set `WritePostureTest` pins is unchanged by a second shape of an existing one.
     *
     * ### The three branches, and how they differ from the stepper's
     *
     * - **An insert is outstanding.** The target folds into the latch exactly as a delta does,
     *   and for a stronger reason: the row this method is handed is still absent, so nothing
     *   here could name a property to set.
     * - **The sheet has no such coin.** A positive target *creates* the item carrying the whole
     *   count — one `insert`, not an insert followed by an adjust. A target of zero is dropped:
     *   creating an empty coin item to express "you have no silver" would put a property on the
     *   sheet that this release has no way to remove (10 decision 12).
     * - **The sheet has the coin.** One `adjustQuantity {operation:'set'}` at
     *   [WalletRow.propertyId].
     *
     * ### The head-stack limit, restated for a set
     *
     * [adjustCoins]' *"a decrement can only spend the head stack"* applies here in the shape a
     * set takes. [WalletRow.quantity] sums every stack of the denomination; [WalletRow.propertyId]
     * names the first, and one call cannot reach past it. So the head is set to
     * `target − (quantity − headQuantity)` — the target minus what the unreachable stacks
     * already hold — clamped at zero. On the ordinary single-stack sheet that is exactly
     * `target`; on a split one the row lands on the target when it can and stops at the
     * unreachable sum when it cannot, which is the same honest floor a decrement has.
     *
     * @param target clamped at zero. No ceiling — coins have none.
     */
    fun adjustCoins(row: WalletRow, target: ExactQuantity)

    /**
     * Set the death-save marks (FR-23, docs/design/15-polish-batch.md decisions 19–21).
     *
     * ### A new intent, authorized in writing
     *
     * Decision 21 overrides decision 9 **for FR-23 only**: *"FR-23 adds OpenCharacter intents
     * `setDeathSaves(successes, failures)` … `WritePostureTest`'s catalog is DELIBERATELY
     * extended per its own 'adding one is an edit to this list' rule."* FR-22 remains
     * zero-new-intents. The allow-list edit is where the reasoning is repeated, because that is
     * the file a future reader will be looking at when they wonder why the catalog grew.
     *
     * It could not have been composed from what was already there. `spend`/`restore` are
     * increments against a row that goes *down*, `setHitPoints` names one property, and neither
     * can express "these two properties, together, to these two absolutes" — which is what
     * decision 20's clear is, and what one tap on a pip is.
     *
     * ### Absolute, idempotent, and clamped by the server
     *
     * Two `creatureProperties.damage {_id, operation:'set', value:n}` calls, one per property,
     * in the **20-per-5-seconds** class (`WriteOp.Damage`'s own rate class — decision 19's fast
     * lane, and the reason a burst of pip taps does not queue behind the 1 s gate everything
     * else uses). The probe established the server clamps natively, so a value past three is
     * refused there rather than corrupting the sheet; this clamps too, at the range
     * `0..DeathSaves.MAX`, because a UI asking for seven is a bug worth stopping locally rather
     * than discovering from a server that quietly fixed it.
     *
     * A half that is unchanged is **not sent**. Setting successes when only failures moved
     * would burn a rate-limit slot and file a history entry for a change that did not happen —
     * [setEquipped]'s no-op guard, applied to a pair.
     *
     * ### What this does NOT do
     *
     * It does not clear on its own. Decision 20 is emphatic that the client's `set 0` is
     * attached to **a heal write this client performs**, never fired from observed state — see
     * `DefaultOpenCharacter.changeHitPoints` for the observer-storm argument. A caller wanting
     * "clear them" calls this with `0, 0`, which is a player's tap, not a reaction.
     *
     * A character whose sheet carries no death-save pair drops the call. There is nothing to
     * write to and decision 18 has already established that such sheets are ordinary.
     */
    fun setDeathSaves(successes: Int, failures: Int)

    /**
     * Which rows have a Use on the wire right now — decision 5's single-flight, as state the
     * button can read (FR-28, docs/design/17-use-action.md decision 5).
     *
     * A read `val`, so `WritePostureTest`'s getter filter drops it and the catalog is untouched
     * by it — the same property `actions` relies on, for the same reason.
     *
     * ### The guard is here, not in the composable
     *
     * Decision 5: *"the Use button disables on tap until the call completes AND the client-derived
     * state reflects the spend"*. A `remember { mutableStateOf(false) }` in the sheet would
     * disable the button and would not be a guard: a second tap landing in the same frame as the
     * first, a rotation, a sheet re-composed from a new board — each gets a fresh `false`, and
     * probe U3's burst is exactly the gesture that produces them. The latch behind this flow is
     * in `:core:data`, below every UI lifetime, and it drops the second call rather than
     * disabling a button that has already been pressed.
     *
     * The set is keyed by **property id**, not held globally: two different features being used
     * in quick succession is an ordinary thing to do at a table, and a global latch would make
     * the second one a dropped tap.
     */
    val usesInFlight: StateFlow<Set<String>>

    /**
     * Use an action — `creatureProperties.doAction` (FR-28,
     * docs/design/17-use-action.md decisions 3, 5 and 6).
     *
     * ### A new intent, authorized in writing
     *
     * 16 decision 7 was *"zero new writes … If the wave believes otherwise it stops."* 17 decision
     * 7 supersedes it for this feature and says so: *"new OpenCharacter intents `useAction` and
     * `castSpell` — WritePostureTest's name AND signature catalogs deliberately extended"*. The
     * allow-list edit repeats the reasoning, because that is the file a reader will be looking at
     * when they wonder why the catalog grew — the shape [setDeathSaves] established.
     *
     * It is composable from nothing that was already here. Every other intent on this interface
     * writes **a number to a property this app can name**; a use asks the server to run an effect
     * tree whose contents this app deliberately does not know. There is no `spend` that could
     * express it, because working out what to spend is the thing being delegated.
     *
     * ### Three gates, and what each one is for
     *
     * 1. **The id must name a live, usable action.** Implementations resolve it against the
     *    `actions` board and drop the call otherwise. Decision 6 asks for id validation because a
     *    bogus id is an opaque 500 (probe U3); the *usability* half of the same lookup is
     *    decision 2's prepared/inactive gate, enforced a second time below the UI so that a stale
     *    frame cannot get a switched-off row through. `UseTarget` is the first gate; this is the
     *    second, the way `removeItem` has two.
     * 2. **Single-flight.** A row already in [usesInFlight] drops the call. Probe U3's rapid
     *    double-tap put three uses of a one-use ability on the wire, and the server's honour-system
     *    checking accepted all three.
     * 3. **The queue's LIVE check**, as for every other intent.
     *
     * ### It is not undoable and it is confirmed instead
     *
     * Decision 4 requires a confirm dialog before **every** use, and this call assumes the user has
     * seen it — [rest]'s contract exactly, for the same reason and one step stronger: a rest has no
     * inverse, and a use has no inverse *and* two side effects outside the sheet (the party log,
     * and a Discord post where the sheet is wired to one — probe U4). The history entry says what
     * happened and points at the activity feed; it offers no UNDO because there is none to offer.
     *
     * @return `true` when the wire call was actually dispatched; `false` when either gate above
     *   dropped it. [M3/M4, architect ruling] a caller uses this to tell a genuinely dropped tap
     *   from a dispatched one — the two need different UI: a snackbar for the drop (nothing is
     *   pending), and the settle-window watch only for the dispatch (nothing is pending to watch
     *   for otherwise).
     */
    fun useAction(actionId: String): Boolean

    /**
     * Cast a spell — `creatureProperties.doCastSpell` (FR-28, 17 decisions 3, 5 and 6).
     *
     * [useAction]'s twin: the same three gates, the same no-undo, the same confirm-first
     * contract. Two differences.
     *
     * **The slot is the caller's choice.** [slotId] is a spell-slot property id the player picked
     * out of `spellSlotOptions` — slots of a high enough level with charges left, derived from the
     * live tracker rows. `null` means "no slot": a cantrip, or a ritual cast. Implementations do
     * **not** second-guess it against the board; a slot that emptied between the picker rendering
     * and the tap is the one case `doCastSpell` refuses cleanly and verbatim, which is a better
     * answer than a silently dropped tap.
     *
     * **Refusals arrive as errors.** Unlike `doAction`, `doCastSpell` raises an atomic
     * `Meteor.Error` before writing anything (probe U2), so a refusal reaches [writeFailures] with
     * the server's own `reason` on it. Nothing here has to interpret it.
     *
     * @param ritual the honest checkbox of decision 3 — `true` casts without consuming a slot.
     *   Passed to the server as its own flag; this app does not decide what a ritual costs.
     * @return see [useAction]'s.
     */
    fun castSpell(spellId: String, slotId: String?, ritual: Boolean): Boolean

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

    /**
     * Stops the subscription and cancels this character's scope. Idempotent, and safe to call
     * from a scope that is *already cancelled* — which is where it is normally called from
     * (`onCleared`, `onDispose`). Implementations must not let the caller's cancellation skip
     * the teardown.
     */
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
 *
 * ### One session per creature, reference-counted
 *
 * FR-19 made "one screen, one open character" false. The DM dashboard opens a party's worth of
 * creatures at once, and tapping a card opens the character screen **on a creature the
 * dashboard is still holding** — the two screens overlap for as long as the card is up. Built
 * naively
 * that is two `CreatureSession`s for one creature on one connection: two `singleCharacter`
 * subscriptions out of the 50-per-10 s bucket the whole table shares, two [WriteQueue]s with
 * independent rate gates and independent optimistic overlays, and two consumers of the *same*
 * [MongoMirror] collection map.
 *
 * That last one is the part that was working by luck rather than by design. Both sessions read
 * the one mirror, so an `applyRemoved` driven by either — quiescence after a reconnect, a
 * property soft-removed on the server — mutates state the other is rendering, and nothing in
 * this codebase establishes what DiceCloud's mergebox does when the same publication is
 * subscribed twice on one session. "It looked fine on a tablet" is not a guarantee; it is an
 * absence of one.
 *
 * So a creature has exactly one session while anybody holds it. [open] returns the *same*
 * object to the second caller and increments a count; [OpenCharacter.close] decrements, and the
 * session is torn down when the count reaches zero. Both callers keep the contract they had —
 * open on enter, close on exit — and neither has to know the other exists.
 *
 * ### The key includes the account
 *
 * Creature ids are globally unique on this server, but the *session* is not: it binds an
 * account's socket, token and snapshot rows at build time. Keying on the creature alone would
 * hand a second account the first one's session after a sign-in switch. The account comes off
 * the connection, which is read before the cache is consulted for exactly that reason.
 *
 * ### Why the whole build is under the lock
 *
 * Two coroutines opening the same creature at the same instant must not both build one, and the
 * only way to promise that is to hold the lock across the build. It costs: the dashboard's six
 * opens serialize behind each other. They serialize on a Room read and a `sub` frame — the DDP
 * client confines *its* work to one thread anyway — so the cost is microseconds against an open
 * that is already waiting on a socket. Being cheap is not why it is correct; the alternative
 * (build first, deduplicate after) throws away a session that has already subscribed, which
 * spends a slot of the shared bucket to fix a race.
 *
 * Requires a single instance per process to mean anything — see `DataModule`.
 */
class DefaultOpenCharacterFactory(
    private val connectionManager: DdpConnectionManager,
    private val accountRepository: AccountRepository,
    private val snapshotStore: SnapshotStore,
    private val trackerPrefDao: TrackerPrefDao,
    private val themePrefDao: ThemePrefDao,
    private val characterCache: CharacterCache,
    /**
     * The configuration every session this factory builds is given.
     *
     * Present so that `DataModule` can attach a debug-only write-queue log sink
     * (`DebugLogSinks`) at the one place that knows whether this is a debug build. The default
     * is the production one — every timing knob at its documented value, the sink at `{}` —
     * so a test or a caller with no DI graph gets exactly what it got before this parameter
     * existed.
     */
    private val sessionConfig: CreatureSessionConfig = CreatureSessionConfig(),
    private val now: () -> Long = System::currentTimeMillis,
) : OpenCharacterFactory {

    private val sessions = SharedOpenCharacters()

    override suspend fun open(creatureId: String): OpenCharacter {
        // Suspends until the account's socket object exists. It does not wait for the
        // socket to be *live*: rendering the Room snapshot while CONNECTING is the whole
        // point of 06's fallback. Read before the cache is consulted — the account is half
        // the cache key.
        val connection = connectionManager.connection.filterNotNull().first()
        val account = connection.account

        return sessions.acquire("${account.id}/$creatureId") {
            build(connection, account, creatureId)
        }
    }

    /** One real session, unconditionally. Called under [SharedOpenCharacters]' lock. */
    private suspend fun build(
        connection: AccountConnection,
        account: Account,
        creatureId: String,
    ): OpenCharacter {
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
            config = sessionConfig,
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

/**
 * The reference count behind [DefaultOpenCharacterFactory]'s "one session per creature".
 *
 * Split out from the factory because it is the part with a *rule* in it — acquire, release,
 * tear down at zero — and the factory's own dependencies (a live DDP connection, Room, an
 * account repository) would otherwise have to be stood up to test three lines of arithmetic.
 * `SharedOpenCharacterTest` drives this directly.
 *
 * Not a general-purpose cache: entries are never evicted on their own. A session lives exactly
 * as long as somebody holds it, which is the property the callers already promise by calling
 * [OpenCharacter.close] on the way out of a screen.
 */
internal class SharedOpenCharacters {

    private val lock = Mutex()
    private val entries = mutableMapOf<String, SharedOpenCharacter>()

    /** Live entries. For tests and for the leak this class exists to make visible. */
    val size: Int get() = entries.size

    /**
     * The session for [key], building one with [build] only if there is none.
     *
     * Returns the **same object** to every holder, so a caller comparing identity across two
     * opens sees one session — which is the whole point, and what makes the count meaningful:
     * a wrapper handed out per-caller would have to be closed by the right one.
     */
    suspend fun acquire(key: String, build: suspend () -> OpenCharacter): OpenCharacter =
        lock.withLock {
            entries[key]?.let { existing ->
                existing.refs++
                return@withLock existing
            }
            val shared = SharedOpenCharacter(build()) { release(key, it) }
            shared.refs = 1
            entries[key] = shared
            shared
        }

    /**
     * One holder let go. Tears down at zero, and only then.
     *
     * The teardown happens *outside* the lock: [OpenCharacter.close] cancels a scope and stops
     * a subscription, and holding the registry's lock across that would block every other
     * screen's open on an unrelated character's shutdown. Removing the entry first is what
     * makes that safe — an open racing the teardown builds a fresh session rather than
     * adopting one mid-close.
     *
     * A close beyond zero is a no-op rather than an error. `OpenCharacter.close` is documented
     * idempotent and both view models call it from `onCleared`, which can run after a screen
     * has already closed on its own path.
     */
    private suspend fun release(key: String, shared: SharedOpenCharacter) {
        val teardown = lock.withLock {
            when {
                shared.refs <= 0 -> false
                else -> {
                    shared.refs--
                    if (shared.refs == 0) {
                        if (entries[key] === shared) entries.remove(key)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        if (teardown) shared.shutdown()
    }
}

/**
 * One [OpenCharacter] with a holder count in front of its [close].
 *
 * Everything but [close] is delegation, deliberately: this type must not become a place where
 * behaviour accumulates. It answers exactly one question — *is anybody still holding this?* —
 * and `WritePostureTest`'s allow-list is unchanged by it precisely because it adds no intent.
 */
internal class SharedOpenCharacter(
    private val delegate: OpenCharacter,
    private val onRelease: suspend (SharedOpenCharacter) -> Unit,
) : OpenCharacter by delegate {

    /** Holder count. Guarded by [SharedOpenCharacters]' lock; never read outside it. */
    var refs: Int = 0

    /** The real teardown, run once the count reaches zero. */
    suspend fun shutdown() = delegate.close()

    override suspend fun close() = onRelease(this)
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
            delta > 0 -> {
                session.writeQueue.submit(WriteOp.heal(hp, delta))
                clearDeathSavesForHeal(from = hp.value, to = (hp.value + delta).coerceAtMost(hp.total))
            }
        }
    }

    override fun setHitPoints(value: Int) {
        val hp = session.board.value.hp ?: return
        val desired = value.coerceIn(0, hp.total)
        session.writeQueue.submit(WriteOp.setValue(hp, desired))
        clearDeathSavesForHeal(from = hp.value, to = desired)
    }

    /**
     * FR-23 decision 20: **the reset is ours, and only on our own heal.**
     *
     * The server never clears death saves — the probe found the reset triggers are children of
     * the "0 HP?" toggle and event-gated, so nothing fires on a plain `damage` call. So MageHand
     * does it, and the whole decision is *where the code that does it lives*.
     *
     * ### Why this hangs off the write path and not off the board
     *
     * The obvious implementation is a collector: watch `board.hp`, and when it crosses 0 →
     * positive, send `set 0` to both properties. It is also the one decision 20 forbids in
     * capitals, and the reason is the DM dashboard. **N clients observing one sheet would each
     * see the same transition and each send the same two writes** — a party of six with the
     * dashboard open is twelve redundant calls against a 20-per-5-second bucket the whole table
     * shares, every time anybody is healed off zero. Worse, they are *unattributable*: nothing
     * in the burst says which client decided, so a rate-limit refusal lands on a write no user
     * made.
     *
     * Attaching it to the write means exactly one client can ever fire it — the one whose user
     * tapped heal — and it fires once, in the same gesture. Its undo entry semantics are
     * deliberately *not* the same as everything else that tap did: the clears are submitted
     * without a receipt of their own (`WriteQueue.submitWithoutReceipt`), so they file no
     * history row and push nothing onto the undo stack. UNDO on the snackbar therefore reverses
     * the heal — the entry the tap actually produced — and not the marks; see
     * [clearDeathSavesForHeal] for the rest of that argument.
     *
     * ### The honest cost, stated
     *
     * A sheet healed off zero **by another client** keeps its marks. Decision 20 accepts that
     * and says why: *"a stale-marks sheet healed by another client shows its marks honestly
     * until someone clears them (pips are tappable; one tap fixes)"*. Showing three failures on
     * a character who is up is wrong-looking but *true* — it is what the sheet says — and the
     * alternative is the storm above. The block stays reachable because it renders on HP 0 and
     * the pips take taps whenever it is on screen.
     *
     * ### The condition
     *
     * `from == 0 && to > 0`. Not `to > 0` alone: healing 4 → 9 has no death saves to clear and
     * would file two no-op writes per heal for the whole game. `from` is the board's value,
     * which is overlay-adjusted — the same number the player is looking at — so a second heal
     * arriving while the first is in flight sees a non-zero `from` and does not re-send.
     *
     * ### Why this does not call [setDeathSaves]
     *
     * `setDeathSaves` submits with a receipt — a history row and, since `setValue` carries an
     * inverse, an undo-stack push. Two of those land *after* the heal's own submission and the
     * undo stack is LIFO: UNDO would reverse the more recent clear instead of the heal, and the
     * snackbar's `history.first()` would read as a death-save line instead of the heal it was.
     * The clears still have to reach the wire, so they go through
     * `WriteQueue.submitWithoutReceipt` directly — same two `damage {set}` calls, no receipt of
     * their own, exactly as `LocalOpenCharacter.clearDeathSavesForHeal` writes its columns
     * inside the heal's own critical section with no journal entry of their own.
     */
    private fun clearDeathSavesForHeal(from: Int, to: Int) {
        if (from != 0 || to <= 0) return
        val saves = session.board.value.deathSaves ?: return
        if (saves.successes == 0 && saves.failures == 0) return
        submitDeathSaves(saves, successes = 0, failures = 0, recordUndo = false)
    }

    /**
     * Two `damage {set}` calls, one per property, and only for the halves that moved.
     *
     * The op is `WriteOp.setValue` against a synthetic [TrackedResource] carrying the property's
     * **current** mark count, which is what makes the undo entry a real inverse: `setValue`
     * builds its own `undo` from `resource.value`, so UNDO on the snackbar puts the pip back
     * where it was rather than clearing the row.
     *
     * `TrackerKind.RESOURCE` and not `ITEM`: the branch inside `setValue` chooses `damage` for
     * everything that is not an item, and `damage` is the method decision 19 specifies. The kind
     * is otherwise inert here — nothing renders this row — but naming it wrongly would silently
     * route the write to `adjustQuantity`, which is a different method against a property that
     * has no quantity.
     */
    override fun setDeathSaves(successes: Int, failures: Int) {
        val saves = session.board.value.deathSaves ?: return
        submitDeathSaves(saves, successes, failures, recordUndo = true)
    }

    /**
     * @param recordUndo `true` for a player's own tap on a pip ([setDeathSaves]), which gets a
     *   real receipt — a history row and an undo entry. `false` for
     *   [clearDeathSavesForHeal]'s pair, which must reach the wire without minting either: see
     *   that function's KDoc for why.
     */
    private fun submitDeathSaves(saves: DeathSaves, successes: Int, failures: Int, recordUndo: Boolean) {
        val nextSuccesses = successes.coerceIn(0, DeathSaves.MAX)
        val nextFailures = failures.coerceIn(0, DeathSaves.MAX)

        if (nextSuccesses != saves.successes) {
            submitDeathSave(saves.successesPropertyId, DEATH_SAVE_SUCCESS_NAME, saves.successes, nextSuccesses, recordUndo)
        }
        if (nextFailures != saves.failures) {
            submitDeathSave(saves.failuresPropertyId, DEATH_SAVE_FAILURE_NAME, saves.failures, nextFailures, recordUndo)
        }
    }

    private fun submitDeathSave(propertyId: String, name: String, current: Int, desired: Int, recordUndo: Boolean) {
        val op = WriteOp.setValue(
            TrackedResource(
                propertyId = propertyId,
                kind = TrackerKind.RESOURCE,
                name = name,
                value = current,
                total = DeathSaves.MAX,
            ),
            desired,
        )
        if (recordUndo) session.writeQueue.submit(op) else session.writeQueue.submitWithoutReceipt(op)
    }

    /**
     * One outstanding `creatureProperties.adjustQuantity` for a property, and the taps that
     * have arrived since. The twin of [CoinInsert], and named to read like it.
     *
     * [predicted] is the quantity the sheet will hold **once everything already dispatched and
     * everything in [pending] has landed** — which is the only number a tap may be clamped
     * against. Clamping against the board instead is the bug: the inventory board is the raw
     * mirror (`CreatureSession.inventory` applies no optimistic overlay), so it does not move
     * until the server echoes, and every repeat of a press-and-hold therefore re-read the same
     * pre-burst quantity and passed the clamp.
     *
     * [pending] is a signed net, for [CoinInsert.pending]'s reason: a hold on `−` followed by a
     * tap on `+` inside one window is an ordinary thing to do.
     *
     * [pendingSet] is FR-22's barrier (decision 6). Non-null means *"a direct entry is waiting
     * for this property's queue to drain"*: the accumulated [pending] deltas go out first, then
     * this absolute value goes out last and wins. It is an absolute rather than another signed
     * net because that is the whole point of the shape — see [adjustItem]'s `ExactQuantity`
     * overload.
     *
     * [valueBeforeSet] is what the row will read *just before* [pendingSet] lands — i.e. the
     * predicted quantity at the moment the direct entry was armed. It exists because a `set`'s
     * inverse is "the value it replaced", and by the time the set is dispatched the board is
     * several flushes out of date; without this the UNDO on the snackbar would restore a number
     * the player never saw. Meaningless while [pendingSet] is null.
     */
    private class QuantityFlush(
        var predicted: Int,
        var pending: Int = 0,
        var pendingSet: Int? = null,
        var valueBeforeSet: Int = 0,
    )

    private val quantityLock = Any()

    /**
     * The flush-outstanding latch, one slot per **property** — the fix for the burst
     * over-decrement described on [adjustItem] and analysed on [flushItemQuantity].
     *
     * Keyed per property rather than held globally for [coinInserts]' reason: two items are
     * two documents and there is no sense in which one should wait for the other.
     */
    private val quantityFlushes = HashMap<String, QuantityFlush>()

    /** What [adjustItem] decided to do, resolved under [quantityLock] and acted on outside it. */
    private enum class QuantityAction { FLUSH, FLUSH_SET, ACCUMULATED, DROP }

    /**
     * The item steppers, both boards (04 §3 and 10 decision 3).
     *
     * The whole of the decision is the three-way branch below, and it is [adjustCoins]' branch
     * with the roles swapped — see that method's "two latches, one pattern" note.
     *
     * ### Why the clamp reads the latch first and the row second
     *
     * [TrackedResource.value] arrives from whichever board the caller rendered, and the two
     * disagree exactly when it matters: the tracker's board carries the queue's optimistic
     * overlay, the inventory's does not (`CreatureSession.inventory` is built from the sheet
     * alone). While no flush is outstanding the two agree by construction — the latch is the
     * only writer of item quantity in this class, so with the slot free there is nothing for an
     * overlay to add — and that is what makes the row a sound base for the *first* tap of a
     * burst. Every tap after it is clamped against [QuantityFlush.predicted] instead, which is
     * the app's own arithmetic and owes the mirror nothing.
     *
     * A tap clamped to zero is **dropped**, not sent as `increment 0`: the queue would coalesce
     * it away anyway, and a dropped tap costs nothing while a dispatched one costs a slot of
     * the five calls the server's 5 s window allows.
     *
     * The one writer that is not this latch is [undoLastWrite], which submits an inverse op
     * straight to the queue. An undo landing while a flush is outstanding puts two
     * `adjustQuantity` ops for one property in the queue and they may merge — which is
     * arithmetically exact (an undo is by construction the reverse of a call the server already
     * applied) and is the only case in which this path relies on the queue's coalescing at all.
     */
    override fun adjustItem(item: TrackedResource, delta: Int) {
        if (delta == 0) return

        var toFlush = 0
        val action = synchronized(quantityLock) {
            val latch = quantityFlushes[item.propertyId]
            val headroom = (latch?.predicted ?: item.value).coerceAtLeast(0)
            // An item cannot go below zero, and the server would happily accept the call and
            // store `0` — silently losing the difference.
            val bounded = if (delta < 0) -minOf(-delta, headroom) else delta
            when {
                bounded == 0 -> QuantityAction.DROP
                latch != null -> {
                    // FR-22: a tap arriving while a direct entry is queued re-bases the *set*
                    // rather than being dispatched behind it. Sending it separately would put
                    // two writes for one property back on the wire, which is the whole thing
                    // the barrier exists to prevent — and the player who typed 12 and then
                    // tapped `+` means 13, not "12, and separately one more".
                    val queuedSet = latch.pendingSet
                    if (queuedSet != null) {
                        val rebased = (queuedSet + bounded).coerceAtLeast(0)
                        latch.pendingSet = rebased
                        latch.predicted = rebased
                    } else {
                        latch.pending += bounded
                        latch.predicted += bounded
                    }
                    QuantityAction.ACCUMULATED
                }

                else -> {
                    quantityFlushes[item.propertyId] = QuantityFlush(predicted = item.value + bounded)
                    toFlush = bounded
                    QuantityAction.FLUSH
                }
            }
        }

        when (action) {
            QuantityAction.ACCUMULATED, QuantityAction.DROP -> Unit
            QuantityAction.FLUSH -> flushItemQuantity(item, toFlush)
            // Unreachable from this overload — the delta branch never asks for a set — and
            // handled rather than left to `else` so the `when` stays exhaustive by name.
            QuantityAction.FLUSH_SET -> flushItemQuantitySet(item, toFlush)
        }
    }

    /**
     * Direct entry on an item quantity (FR-22 decisions 5 and 6), through the same latch.
     *
     * ### The barrier, stated as a rule
     *
     * The interface KDoc says *"pending deltas flush first, then the set goes out"*. Two cases
     * implement it:
     *
     * - **The slot is free.** Nothing is on the wire for this property, so there is nothing to
     *   wait behind: the set is dispatched immediately and claims the slot, which is what stops
     *   a tap arriving one millisecond later from racing it.
     * - **The slot is held.** The target is parked in [QuantityFlush.pendingSet] and
     *   [settleItemQuantity] releases it *after* the accumulated deltas have gone — the deltas
     *   are the player's earlier taps and dropping them would be a silent loss, while the set is
     *   their latest word and has to land last.
     *
     * [QuantityFlush.predicted] moves to the target either way, because that is what the sheet
     * will hold once everything dispatched and parked has landed — the invariant every clamp in
     * this class reads. It is the reason a `−` tap arriving behind a set of 3 clamps at 3 and
     * not at whatever the board still says.
     *
     * A target equal to the predicted quantity is **not** a no-op and is deliberately still
     * sent when the slot is free. [setEquipped]'s no-op guard is safe because a flag the server
     * already holds cannot have drifted; a *quantity* is exactly the field that drifts — the
     * player is typing a number precisely because the sheet and the table disagree — so
     * refusing the write on the strength of a possibly-stale board would make the one gesture
     * that exists to correct drift the one gesture that cannot.
     */
    override fun adjustItem(item: TrackedResource, target: ExactQuantity) {
        val desired = target.value.coerceAtLeast(0)

        var toFlush = 0
        val action = synchronized(quantityLock) {
            val latch = quantityFlushes[item.propertyId]
            if (latch != null) {
                // Recorded before `predicted` moves: this is the number the set replaces, and
                // therefore the number its UNDO restores. A second direct entry arriving before
                // the first has flushed keeps the *original* — the row only ever moved once.
                if (latch.pendingSet == null) latch.valueBeforeSet = latch.predicted
                latch.pendingSet = desired
                latch.predicted = desired
                QuantityAction.ACCUMULATED
            } else {
                quantityFlushes[item.propertyId] = QuantityFlush(predicted = desired)
                toFlush = desired
                QuantityAction.FLUSH_SET
            }
        }

        when (action) {
            QuantityAction.ACCUMULATED, QuantityAction.DROP, QuantityAction.FLUSH -> Unit
            QuantityAction.FLUSH_SET -> flushItemQuantitySet(item, toFlush)
        }
    }

    /**
     * Sends one `adjustQuantity`, with the latch for this property already held by the caller.
     *
     * ### Why a latch and not the queue's coalescing
     *
     * [WriteQueue] already merges rapid taps on one property into a single summed
     * `increment`, and for a burst that stays inside one coalesce window that is enough. What
     * it cannot do is close the **in-flight window**: `takeCoalescedHead` removes the head and
     * claims it as `inFlight` before the call goes out, so a burst longer than one round trip
     * becomes *several* merged calls, and each of them was built from taps that were clamped
     * against a board the server had not answered yet. Two consequences, both observed live:
     *
     * 1. the calls sum to more than the property holds, and the server clamps the stored
     *    quantity at zero rather than rejecting them — the excess is lost, not refused;
     * 2. a `+` tap arriving while that burst is still queued, in flight, or waiting out a
     *    `too-many-requests` retry merges into an op that is *already* net-negative past the
     *    floor, so the merged sum is still clamped to zero and the `+` is swallowed whole.
     *
     * (2) is the reason this is a latch rather than a better clamp. A clamp fixes the number a
     * tap contributes; only an outstanding-call gate can stop a later tap being merged into an
     * earlier op's overdraft. With the gate, a property has at most one `adjustQuantity` in the
     * queue at any moment, so there is nothing for the queue to merge it *with* — which is the
     * invariant `WriteQueue.takeCoalescedHead` records from its side.
     *
     * ### What the latch does instead
     *
     * One flush per property is on the wire at a time. Every tap that arrives while it is
     * outstanding is summed into [QuantityFlush.pending] — clamped as it accumulates, against
     * [QuantityFlush.predicted] — and once the flush has landed the accumulated remainder goes
     * out as a single further `adjustQuantity`, which re-arms the latch. A hold ends when the
     * taps stop, having sent one call per round trip and never one call too many.
     */
    private fun flushItemQuantity(item: TrackedResource, delta: Int) {
        val flush = session.writeQueue.submit(WriteOp.adjust(item, delta))
        scope.launch { settleItemQuantity(item, flush) }
    }

    /**
     * Sends one `adjustQuantity {operation:'set'}`, with the latch for this property already
     * held by the caller — [flushItemQuantity]'s twin for FR-22's absolute shape.
     *
     * The op is `WriteOp.setValue`, which is the *same* factory the HP number pad's Set button
     * has used since WP7 and which carries a real inverse (the value the row held), so a direct
     * entry gets the same UNDO snackbar every other write does. `WriteOp.AdjustQuantity` gives a
     * `set` no `coalesceKey`, so it cannot merge with anything — which is the queue-side half of
     * the barrier: the latch guarantees at most one op per property is in flight, and the null
     * key guarantees the queue will not fold a later `increment` into this one on its way out.
     *
     * [item] is passed with its **predicted** value rather than the board's, so the inverse the
     * op records is the quantity the sheet will actually hold when the set lands. Handing it the
     * stale board value would file an undo that restores a number the player never saw.
     */
    private fun flushItemQuantitySet(item: TrackedResource, desired: Int) {
        val flush = session.writeQueue.submit(WriteOp.setValue(item, desired))
        scope.launch { settleItemQuantity(item, flush) }
    }

    /**
     * Waits out the flush, then either re-arms the latch with whatever piled up behind it or
     * releases it.
     *
     * ### Why it does not wait for the mirror
     *
     * [settleCoinInsert] has to, because the id it needs is minted by the server. This one
     * does not: the property already exists, and [QuantityFlush.predicted] is the app's own
     * arithmetic — it was decremented when the tap was accepted, not when the server agreed.
     * Waiting for the echo would add a `StateFlow` hop to every repeat of a hold and change
     * nothing about the number sent.
     *
     * ### What is dropped, and why that is the safe direction
     *
     * A failed flush (a server rejection, a refusal off-LIVE, a socket death) drops the
     * accumulation and releases the slot. The op did not land, so [QuantityFlush.predicted] is
     * describing a sheet that does not exist and every tap behind it was clamped against a
     * fiction; the optimistic overlay has already rolled the display back to the truth, and the
     * next tap re-reads the board and starts a fresh latch. Re-sending instead would replay an
     * `increment` whose outcome we never saw, which is the corruption [WriteQueue] refuses by
     * construction.
     *
     * The slot is released on **every** exit, cancellation included, for the reason spelled out
     * on [settleCoinInsert]: a stranded latch would disable this item's stepper for the rest of
     * the session with nothing on screen to explain it.
     *
     * ### The order the re-arm takes, which is FR-22's barrier
     *
     * Deltas before sets, always (decision 6). A direct entry parked in
     * [QuantityFlush.pendingSet] is the player's most recent word about this row, so it has to
     * be the *last* thing on the wire; the deltas ahead of it are earlier taps that were
     * accepted and must not be silently dropped. Each release re-arms the slot, so a set parked
     * behind a burst waits out however many flushes the burst takes and then goes out alone.
     */
    private suspend fun settleItemQuantity(item: TrackedResource, flush: Deferred<Unit>) {
        var slotReleased = false
        try {
            var landed = false
            try {
                flush.await()
                landed = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // `landed` stays false and the accumulation is dropped — see the KDoc.
            }

            var next = 0
            var setUndoBase = 0
            val rearmed = synchronized(quantityLock) {
                val latch = quantityFlushes[item.propertyId]
                when {
                    latch == null -> QuantityAction.DROP
                    // A failed flush drops everything behind it, the parked set included — see
                    // the KDoc. `predicted` was describing a sheet that does not exist, so an
                    // absolute built on top of it is no more trustworthy than a delta.
                    !landed -> {
                        quantityFlushes.remove(item.propertyId)
                        QuantityAction.DROP
                    }

                    // FR-22's barrier, released in order: deltas first…
                    latch.pending != 0 -> {
                        next = latch.pending
                        latch.pending = 0
                        QuantityAction.FLUSH
                    }

                    // …then the set, last, so it is the write that decides the number.
                    latch.pendingSet != null -> {
                        next = latch.pendingSet ?: 0
                        setUndoBase = latch.valueBeforeSet
                        latch.pendingSet = null
                        QuantityAction.FLUSH_SET
                    }

                    else -> {
                        quantityFlushes.remove(item.propertyId)
                        QuantityAction.DROP
                    }
                }
            }
            slotReleased = true
            when (rearmed) {
                QuantityAction.FLUSH -> flushItemQuantity(item, next)
                QuantityAction.FLUSH_SET -> flushItemQuantitySet(item.copy(value = setUndoBase), next)
                QuantityAction.ACCUMULATED, QuantityAction.DROP -> Unit
            }
        } finally {
            if (!slotReleased) synchronized(quantityLock) { quantityFlushes.remove(item.propertyId) }
        }
    }

    override val inventory: StateFlow<InventoryBoard> get() = session.inventory

    override val actions: StateFlow<ActionBoard> get() = session.actions

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
    private class CoinInsert(var pending: Int = 0) {
        /**
         * FR-22's barrier for the wallet (decision 6): a direct entry that arrived while the
         * `insert` was still on the wire.
         *
         * Non-null wins over [pending] when the latch releases — the two are the same player's
         * taps and a typed number is their conclusion, not another increment. It cannot simply
         * be applied at arrival time for the reason [adjustCoins]' three-way branch already
         * gives: until the insert lands there is no property id to set.
         */
        var pendingSet: Int? = null
    }

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
                    // A stepper tap behind a parked direct entry re-bases it rather than being
                    // summed separately, exactly as `adjustItem` does: the player typed 12 and
                    // then pressed `+`, which is 13 — not "set 12, and also add one".
                    val queuedSet = outstanding.pendingSet
                    if (queuedSet != null) {
                        outstanding.pendingSet = (queuedSet + delta).coerceAtLeast(0)
                    } else {
                        outstanding.pending += delta
                    }
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
     * Direct entry on a wallet row (FR-22 decisions 5 and 6) — [adjustCoins]' branch, re-read
     * as an absolute.
     *
     * The latch is consulted first for the identical reason the delta version gives: while an
     * insert is outstanding the row is still absent, so there is no id to set, and a tap that
     * slipped past would double-count against the flush.
     *
     * ### Why an existing row needs no barrier of its own
     *
     * Unlike [adjustItem], the wallet's ordinary path has never held a latch — `adjustQuantity`
     * increments go straight to the [WriteQueue] and rely on its coalescing. That is enough
     * here, and it is worth saying why rather than adding a second latch out of symmetry: the
     * queue is FIFO per property and `WriteOp.AdjustQuantity` gives a `set` **no coalesce key**,
     * so a set cannot merge with a neighbouring increment and cannot be reordered around one.
     * Increments queued *before* the set apply first and are then overwritten by it — which is
     * exactly what the player asked for — and increments arriving *after* it are taps they made
     * after typing, which belong on top. The barrier `adjustItem` needs is a barrier against its
     * own latch, and this path has none.
     */
    override fun adjustCoins(row: WalletRow, target: ExactQuantity) {
        val desired = target.value.coerceAtLeast(0)

        val action = synchronized(coinLock) {
            val outstanding = coinInserts[row.coin]
            when {
                outstanding != null -> {
                    outstanding.pendingSet = desired
                    CoinAction.ACCUMULATED
                }

                row.propertyId != null -> CoinAction.ADJUST
                // No property, and nothing to create: an item holding zero coins is a property
                // this release cannot delete (10 decision 12), filed to express a fact the four
                // always-present wallet rows already state.
                desired == 0 -> CoinAction.DROP
                else -> {
                    coinInserts[row.coin] = CoinInsert()
                    CoinAction.INSERT
                }
            }
        }

        when (action) {
            CoinAction.ACCUMULATED, CoinAction.DROP -> Unit
            // The insert carries the whole typed count in one call — the same "one item, not one
            // per repeat" guarantee [insertCoin] makes for a hold.
            CoinAction.INSERT -> insertCoin(row.coin, desired)
            CoinAction.ADJUST -> setExistingCoin(row, desired)
        }
    }

    /**
     * `adjustQuantity {operation:'set'}` against the stack [WalletRow.propertyId] names.
     *
     * ### The head-stack arithmetic, and why the target is not written straight through
     *
     * [adjustExistingCoin]'s clamp exists because [WalletRow.quantity] sums every stack of a
     * denomination while [WalletRow.propertyId] names only the first. The same fact bites a
     * `set` harder: writing "50" at the head of a 5 + 100 split leaves the row reading 150, so a
     * player who typed 50 would *gain* money. So the head is set to the target minus what the
     * stacks this call cannot reach already hold, floored at zero.
     *
     * On the ordinary sheet — one stack per denomination — `quantity == headQuantity` and this
     * is the target, exactly. On a split one the row lands on the target when the head can carry
     * it and stops at the unreachable sum when it cannot, which is the same honest floor a
     * decrement has and the same limit FR-9's multi-property write would be needed to lift.
     */
    private fun setExistingCoin(row: WalletRow, desired: Int) {
        val propertyId = row.propertyId ?: return
        val unreachable = (row.quantity - row.headQuantity).coerceAtLeast(0)
        val head = (desired - unreachable).coerceAtLeast(0)
        session.writeQueue.submit(
            WriteOp.AdjustQuantity(
                propertyId = propertyId,
                operation = WriteOperation.SET,
                value = head,
                resultingValue = head,
                // The head's own previous count, so UNDO restores the stack this call touched
                // rather than the row's total — which no single `adjustQuantity` could write.
                undo = WriteOp.AdjustQuantity(
                    propertyId = propertyId,
                    operation = WriteOperation.SET,
                    value = row.headQuantity,
                    resultingValue = row.headQuantity,
                    targetName = row.coin.itemName,
                    intent = TrackerWriteKind.ITEM_SET,
                ),
                targetName = row.coin.itemName,
                intent = TrackerWriteKind.ITEM_SET,
            ),
        )
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
     * That last sentence turned out to understate it. The in-flight window is a bug for
     * repeated adjustments too, just a quieter one — see [flushItemQuantity], which is this
     * latch generalized to `adjustQuantity` after the same hold produced an over-decrement the
     * server clamped away.
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
            val latch = synchronized(coinLock) { coinInserts.remove(coin) }
            slotReleased = true
            if (row != null && latch != null) {
                // FR-22's barrier for the wallet: a parked direct entry is the player's
                // conclusion and **replaces** the deltas that accumulated with it, rather than
                // queueing behind them. That differs from `adjustItem`'s order deliberately —
                // there the deltas are already reflected in a running `predicted` that the set
                // was clamped against, so dropping them would lose taps; here nothing has been
                // dispatched at all and the typed number is a complete statement of the count.
                val queuedSet = latch.pendingSet
                when {
                    queuedSet != null -> adjustCoins(row, ExactQuantity(queuedSet))
                    latch.pending != 0 -> adjustCoins(row, latch.pending)
                }
            }
        } finally {
            if (!slotReleased) synchronized(coinLock) { coinInserts.remove(coin) }
        }
    }

    /** The denomination's row once it stops being absent, or `null` if it never does. */
    private suspend fun awaitCreatedCoinRow(coin: CoinKind): WalletRow? =
        withTimeoutOrNull(COIN_INSERT_SETTLE_MILLIS) {
            session.inventory.map { it.wallet.row(coin) }.first { !it.isAbsent }
        }

    // --- FR-28: Use (docs/design/17-use-action.md) --------------------------------

    private val _usesInFlight = MutableStateFlow<Set<String>>(emptySet())

    override val usesInFlight: StateFlow<Set<String>> = _usesInFlight.asStateFlow()

    /**
     * Decision 5's single-flight latch — the actual guard, and the one probe U3 measured the
     * need for.
     *
     * Guarded by [quantityLock]'s sibling rather than by [_usesInFlight]'s own atomicity,
     * because "is it already there, and if not put it there" has to be **one** step: a
     * `StateFlow` read followed by a write is two, and two taps a frame apart both read `false`.
     * That is precisely the burst the probe produced, so the check-and-set is synchronized and
     * the flow is a *publication* of the latch rather than the latch itself.
     */
    private val useLock = Any()

    /** `true` when this call claimed the row; `false` when a use was already outstanding. */
    private fun claimUse(propertyId: String): Boolean = synchronized(useLock) {
        if (propertyId in _usesInFlight.value) return@synchronized false
        _usesInFlight.value = _usesInFlight.value + propertyId
        true
    }

    private fun releaseUse(propertyId: String) = synchronized(useLock) {
        _usesInFlight.value = _usesInFlight.value - propertyId
    }

    /**
     * Holds the latch until the call has resolved **and** the sheet has moved (decision 5:
     * *"until the call completes AND the client-derived state reflects the spend"*).
     *
     * The settle half is a bounded wait on the `actions` board changing at all — not on a
     * particular field, because a use may spend an attribute, an item, a charge, or several,
     * and enumerating which would be the client-side model of the effect tree this whole feature
     * exists to avoid. Any change to the board is evidence the fast-path write landed; the
     * timeout is what stops a use with no visible effect (a free action that logs and nothing
     * else — probe U4 says those exist) from wedging the button forever.
     *
     * The latch is released in a `finally` under [NonCancellable]: a screen popped mid-use must
     * not leave a row permanently unusable for the *other* holder of this shared session, which
     * on a DM's tablet is a real second screen and not a hypothetical.
     *
     * ### It reads the flow, where the gate builds the board
     *
     * The asymmetry is deliberate and both halves are right. A gate is a **question asked at an
     * instant** and cannot depend on somebody else collecting — see [usableActionName]. A settle
     * is a **wait for a change**, which is what a flow is for; and collecting `session.actions`
     * for the settle window is exactly what its `WhileSubscribed` posture is built to serve.
     * Polling `currentSheet` on a timer to avoid one subscription would be the worse shape.
     */
    private suspend fun settleUse(propertyId: String, call: Deferred<Unit>) {
        try {
            runCatching { call.await() }
            val before = ActionEngine.build(session.currentSheet)
            withTimeoutOrNull(USE_SETTLE_MILLIS) {
                session.actions.first { it != before }
            }
        } finally {
            withContext(NonCancellable) { releaseUse(propertyId) }
        }
    }

    /**
     * Gate 1 of decision 6's *"validate ids against the live board before calling"*, plus
     * decision 2's prepared/inactive gate enforced below the UI.
     *
     * Returns the row's name for the history entry, or `null` when the id names nothing usable.
     * Dropping rather than sending is the whole point: a bogus id is an opaque 500 the player
     * cannot act on (probe U3), and an unprepared spell is a slot the server would burn.
     *
     * ### It builds the board rather than reading [CreatureSession.actions], and it must
     *
     * `session.actions` is `stateIn(WhileSubscribed)` — deliberately, because nothing derives it
     * unless an Actions surface is on screen (see its KDoc). Its `.value` is therefore
     * `ActionBoard.EMPTY` whenever nobody is collecting, and a gate reading it would drop **every
     * use** in exactly that state. In production the Actions pane usually *is* collecting when
     * the button is pressed, which is what makes this the worst kind of bug: it works on the
     * happy path and fails on a shared session whose other holder — a DM card — is the only
     * subscriber. `DefaultOpenCharacterWriteTest` found it by having no subscriber at all.
     *
     * So the gate builds from [CreatureSession.currentSheet], which is `Eagerly` shared and
     * exists for precisely this class of read: *the writes that have to read the sheet before
     * they can be built*. It runs the engine once per **tap**, not once per mirror frame, which
     * is the cost the flow's laziness was protecting against and is not this.
     */
    private fun usableActionName(actionId: String): String? =
        ActionEngine.build(session.currentSheet).actions
            .firstOrNull { it.propertyId == actionId }
            ?.takeIf { it.isUsable }
            ?.name

    private fun usableSpellName(spellId: String): String? =
        ActionEngine.build(session.currentSheet).spells
            .firstOrNull { it.propertyId == spellId }
            ?.takeIf { it.isUsable }
            ?.name

    override fun useAction(actionId: String): Boolean {
        val name = usableActionName(actionId) ?: return false
        if (!claimUse(actionId)) return false
        val call = session.writeQueue.submit(WriteOp.useAction(actionId, targetName = name))
        scope.launch { settleUse(actionId, call) }
        return true
    }

    /**
     * The spell half.
     *
     * [slotId] is passed through unexamined — see [OpenCharacter.castSpell] for why a slot that
     * emptied under the picker is better refused verbatim by the server than dropped here.
     */
    override fun castSpell(spellId: String, slotId: String?, ritual: Boolean): Boolean {
        val name = usableSpellName(spellId) ?: return false
        if (!claimUse(spellId)) return false
        val call = session.writeQueue.submit(
            WriteOp.castSpell(spellId = spellId, slotId = slotId, ritual = ritual, targetName = name),
        )
        scope.launch { settleUse(spellId, call) }
        return true
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

    /**
     * Stops the subscription and cancels this character's scope. Idempotent.
     *
     * ### Why [NonCancellable]
     *
     * [CreatureSession.close] suspends (`withContext(Dispatchers.IO)` for the final snapshot
     * write and an `unsub` on the client dispatcher), and a suspension point in a **cancelled**
     * coroutine throws immediately. Every realistic caller is exactly that: `onCleared` runs
     * after `viewModelScope` is gone, a `DisposableEffect`'s `onDispose` runs as its scope is
     * being torn down, and the DM view closes N sessions from a scope the navigation just
     * ended. Without this the throw skips `scope.cancel()` — so the `singleCharacter`
     * subscription, the board's `stateIn` and the write queue all survive the screen that owned
     * them, invisibly, until the process dies. Closing is *cleanup*: it must not be cancellable
     * by the same event that asked for it.
     *
     * The flag is set before the body so a cancelled second call still short-circuits, and
     * `NonCancellable` covers `session.close()` and `scope.cancel()` together rather than only
     * the suspending half — a partial teardown is the leak this exists to prevent.
     */
    override suspend fun close() {
        if (closed.value) return
        closed.value = true
        withContext(NonCancellable) {
            session.close()
            scope.cancel()
        }
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

/**
 * How long the single-flight latch waits, **after** a Use has been acknowledged, for the sheet to
 * show the spend (FR-28, docs/design/17-use-action.md decision 5).
 *
 * Two seconds, chosen the same way [COIN_INSERT_SETTLE_MILLIS] was and not by the same
 * measurement. Probe U3 timed the fast-path fields at 0.1–0.35 s from acknowledgement, so this is
 * roughly six times the observed worst case — long enough that it never fires on a working
 * connection, short enough that the one case where the wait *cannot* succeed releases the button
 * inside a turn at the table.
 *
 * That case is real and is why this is a timeout rather than a wait: a use whose effect tree
 * changes nothing this app renders — probe U4 found free actions that only log — will never move
 * the `actions` board, so there is no state change to observe and the latch would otherwise hold
 * for the life of the session. Erring toward releasing early is right here: the latch's job is to
 * stop a double-tap burst, which happens inside a second, not to enforce a cooldown.
 */
private const val USE_SETTLE_MILLIS: Long = 2_000

/**
 * What a death-save write calls itself in the history sheet and the undo snackbar.
 *
 * Not `strings.xml`, and that is the standing split rather than an oversight: `WriteOp.targetName`
 * is `:core:data`'s and is used the same way `WalletRow.coin.itemName` already is — the row's own
 * name, carried so the snackbar can say *what* moved instead of quoting a Meteor id. The sheet's
 * own property names are not read for it because the pair is discovered by `variableName` and a
 * sheet is free to call the properties anything (decision 19); a fixed pair of words is the one
 * thing that cannot come back as "Succeeded Saves (do not rename)".
 */
private const val DEATH_SAVE_SUCCESS_NAME = "Death save successes"
private const val DEATH_SAVE_FAILURE_NAME = "Death save failures"
