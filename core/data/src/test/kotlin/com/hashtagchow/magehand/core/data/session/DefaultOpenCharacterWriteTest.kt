package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.WalletRow

/**
 * FR-8's three new intents at the **[DefaultOpenCharacter] layer** — the layer where the
 * 1.3.0 pre-release review found HIGH-1 and MED-2 sitting uncovered.
 *
 * ### Why a second write test rather than more cases in [OpenCharacterWriteTest]
 *
 * Not for tidiness. That class feeds on the committed capture (`Fixtures.sabrielSheet()`),
 * which is a **private fixture absent from a public clone** — every assertion in it is
 * *skipped* where the repo is published. The tracker's write vocabulary was already pinned
 * before that mattered; the inventory's was not, and the two bugs this file exists to hold
 * down both lived in a branch no test in the suite entered. So the sheets here are synthetic
 * and self-contained: the coverage that stops HIGH-1 coming back has to run everywhere.
 *
 * The other half of the reason is that these three intents need the sheet to *change* under
 * them — `adjustCoins`' insert path only exists because a denomination is absent, and the
 * whole of HIGH-1 is about what happens between the insert going out and the sheet learning
 * about it. That is a mirror the test has to drive frame by frame, which is
 * [FakeCreatureFeed]'s job and not the capture's.
 *
 * ### What each group pins
 *
 * - **[setEquipped]** — the `creatureProperties.equip` wire shape, the no-op guard, and an
 *   undo that returns the *starting* state rather than the negation of the last call.
 * - **[addItem]** — the insert body including the probe-verified mandatory `order`, and that
 *   it files a history entry offering **no** undo (10 decision 12 fences deletion out).
 * - **[adjustCoins]** — all three branches: adjust-present, insert-absent (including the
 *   one-item-per-burst guarantee that HIGH-1 was the absence of), and the head-stack clamp
 *   that MED-2 was the absence of.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultOpenCharacterWriteTest {

    private val accountId = "acc-1"
    private val creatureId = "c1"

    /** Named once: it is the string most of the quantity-latch assertions filter on. */
    private val ADJUST = "creatureProperties.adjustQuantity"

    private lateinit var database: MageHandDatabase
    private lateinit var snapshots: SnapshotStore
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshots = SnapshotStore(
            snapshotDao = database.snapshotDao(),
            api = FakeDiceCloudApi(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        database.close()
    }

    private class Harness(
        val character: DefaultOpenCharacter,
        val session: CreatureSession,
        val feed: FakeCreatureFeed,
        val caller: FakeDdpMethodCaller,
    ) {
        /** The wallet row as the *board* currently reads it — what the UI would hand back. */
        fun walletRow(coin: CoinKind): WalletRow = session.inventory.value.wallet.row(coin)

        fun callsTo(method: String) = caller.calls.filter { it.method == method }
    }

    /**
     * A live session over a synthetic sheet.
     *
     * The sheet always names a creature *and* carries at least one property, and both halves
     * are load-bearing rather than tidy. `InventoryEngine.insertTarget` resolves the add-target
     * from the tree and returns `null` for a sheet with no creature — so an empty sheet makes
     * every insert assertion below pass by dropping the tap. And `CreatureSession` only prefers
     * the live mirror over the cached snapshot when the mirror has a non-removed property in it
     * (`CreatureSheet.hasLiveProperties`), so a mirror published with an empty property list
     * silently resolves to `CreatureSheet.EMPTY` — creature and all.
     */
    private fun TestScope.harness(vararg properties: String): Harness {
        val caller = FakeDdpMethodCaller(nowMillis = testScheduler::currentTime)
        val feed = FakeCreatureFeed(caller)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }
        val session = CreatureSession(
            accountId = accountId,
            creatureId = creatureId,
            feed = feed,
            snapshotStore = snapshots,
            trackerPrefDao = database.trackerPrefDao(),
            scope = scope,
        )
        feed.publish(sheetOf(*properties), creatureId)
        feed.goLive()
        val character = DefaultOpenCharacter(
            session = session,
            scope = scope,
            serverOrigin = "https://example.invalid",
            themePrefDao = database.themePrefDao(),
            trackerPrefDao = database.trackerPrefDao(),
        )
        advanceUntilIdle()
        return Harness(character, session, feed, caller)
    }

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    private fun item(id: String, name: String, quantity: Int = 1, extra: String = "") =
        """{"_id":"$id","type":"item","name":"$name","quantity":$quantity,"order":1$extra}"""

    /** The HP attribute, discovered by `variableName` (03 §3). */
    private fun hp(current: Int, total: Int = 20) =
        """{"_id":"hp1","type":"attribute","attributeType":"healthBar",
            "variableName":"hitPoints","name":"Hit Points","total":$total,"value":$current}"""

    /**
     * One half of FR-23's pair, discovered by `variableName` (decision 19).
     *
     * `value` is the **mark count**, not what is left — the inversion decision 19 records. The
     * `attributeType` is `spellSlot` because that is what the probe's sheets carry, and it is
     * deliberately not what discovery keys on.
     */
    private fun deathSave(id: String, variableName: String, marks: Int) =
        """{"_id":"$id","type":"attribute","attributeType":"spellSlot",
            "variableName":"$variableName","name":"$variableName","total":3,"value":$marks}"""

    private fun deathSaves(successes: Int, failures: Int) = arrayOf(
        deathSave("ds-ok", "deathSaveSuccesses", successes),
        deathSave("ds-no", "deathSaveFails", failures),
    )

    private fun coin(id: String, kind: CoinKind, quantity: Int, order: Int = 1) =
        """{"_id":"$id","type":"item","name":"${kind.itemName}","quantity":$quantity,
            "order":$order,"tags":["${kind.tag}"]}"""

    /** The mirror frame the server sends after an insert lands — see [FakeCreatureFeed]. */
    private fun Harness.serverCreates(id: String, kind: CoinKind, quantity: Int) =
        feed.changeProperty(id, Json.parseToJsonElement(coin(id, kind, quantity)) as JsonObject)

    // -----------------------------------------------------------------------
    // setEquipped (10 decision 4)
    // -----------------------------------------------------------------------

    @Test
    fun `setEquipped sends creatureProperties equip with the id and the target state`() = runTest {
        val h = harness(item("i1", "Quarterstaff"))

        h.character.setEquipped("i1", equipped = true, currentlyEquipped = false, targetName = "Quarterstaff")
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.equip", call.method)
        assertEquals("i1", call.text("_id"))
        assertEquals(true, call.bool("equipped"))
    }

    @Test
    fun `setEquipped into the state the item is already in sends nothing`() = runTest {
        // Not merely wasteful: it would burn one of the five calls the server's 5 s window
        // allows and file a history entry for a change that did not happen.
        val h = harness(item("i1", "Quarterstaff", extra = ""","equipped":true"""))

        h.character.setEquipped("i1", equipped = true, currentlyEquipped = true)
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
        assertTrue(h.character.writeHistory.value.isEmpty())
    }

    @Test
    fun `undoing an equip returns the state the item started in`() = runTest {
        val h = harness(item("i1", "Quarterstaff"))

        h.character.setEquipped("i1", equipped = true, currentlyEquipped = false, targetName = "Quarterstaff")
        advanceUntilIdle()
        assertTrue("an equip is undoable — unlike an add", h.character.canUndo.value)
        h.caller.reset()

        assertTrue(h.character.undoLastWrite())
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.equip", call.method)
        assertEquals("i1", call.text("_id"))
        assertEquals("undo of `equip true` must be `equip false`", false, call.bool("equipped"))
        assertTrue(h.character.writeHistory.value.single().undone)
    }

    // -----------------------------------------------------------------------
    // addItem (10 decision 6)
    // -----------------------------------------------------------------------

    @Test
    fun `addItem sends insert with the mandatory order and a parentRef`() = runTest {
        // `order` is probe-verified mandatory: the server rejects a body without it. The
        // resolved value is one past the highest on the sheet, so the item lands at the end.
        val h = harness(item("i1", "Bedroll"))

        h.character.addItem(NewItemSpec(name = "Torch", quantity = 5, weightLb = 1.0, valueGp = 0.01))
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.insert", call.method)
        val body = call.creatureProperty()
        assertEquals("item", body.text("type"))
        assertEquals("Torch", body.text("name"))
        assertEquals(5, body.int("quantity"))
        assertEquals("the sheet's highest order is 1, so the new item goes after it", 2, body.int("order"))
        assertEquals(creatureId, call.parentRef().text("id"))
        assertEquals("creatures", call.parentRef().text("collection"))
    }

    @Test
    fun `addItem files a history entry that offers no undo`() = runTest {
        // The inverse of an insert is a soft-remove and 10 decision 12 fences item deletion
        // out of this release. An UNDO calling a method the app has decided not to ship
        // would be worse than no UNDO at all.
        val h = harness(item("i1", "Bedroll"))

        h.character.addItem(NewItemSpec(name = "Torch", quantity = 5))
        advanceUntilIdle()

        val entry = h.character.writeHistory.value.single()
        assertEquals("Torch", entry.targetName)
        assertEquals(5, entry.amount)
        assertFalse("creating an item is not undoable", entry.undoable)
        assertFalse(h.character.canUndo.value)
        assertFalse("an add must not become undoable via the queue either", h.character.undoLastWrite())
    }

    @Test
    fun `an invalid spec never reaches the wire`() = runTest {
        val h = harness(item("i1", "Bedroll"))

        h.character.addItem(NewItemSpec(name = "   ", quantity = 1))
        h.character.addItem(NewItemSpec(name = "Torch", quantity = 0))
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    // -----------------------------------------------------------------------
    // adjustCoins branch 1: the denomination is on the sheet
    // -----------------------------------------------------------------------

    @Test
    fun `spending a coin the sheet carries is an ordinary adjustQuantity`() = runTest {
        val h = harness(coin("gp1", CoinKind.GOLD, 12))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), -4)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.adjustQuantity", call.method)
        assertEquals("gp1", call.text("_id"))
        assertEquals("increment", call.text("operation"))
        // `increment` is a *consumption* amount on this server, so spending 4 sends +4.
        assertEquals(4, call.int("value"))
    }

    @Test
    fun `gaining a coin the sheet carries sends the opposite sign`() = runTest {
        val h = harness(coin("gp1", CoinKind.GOLD, 12))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), +7)
        advanceUntilIdle()

        assertEquals(-7, h.caller.calls.single().int("value"))
    }

    // -----------------------------------------------------------------------
    // adjustCoins branch 2: the denomination is absent — HIGH-1
    // -----------------------------------------------------------------------

    @Test
    fun `the first increment on an absent denomination creates the coin item`() = runTest {
        val h = harness(item("i1", "Bedroll"))
        assertTrue("the fixture must lack gold for this test to mean anything", h.walletRow(CoinKind.GOLD).isAbsent)

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), +3)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.insert", call.method)
        val body = call.creatureProperty()
        assertEquals(CoinKind.GOLD.itemName, body.text("name"))
        assertEquals(3, body.int("quantity"))
        assertEquals("""["gold"]""", body["tags"].toString())
    }

    @Test
    fun `a decrement on an absent denomination does nothing`() = runTest {
        val h = harness(item("i1", "Bedroll"))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), -5)
        advanceUntilIdle()

        assertTrue("there is no such thing as spending coins you do not have", h.caller.calls.isEmpty())
    }

    @Test
    fun `a double tap on an absent denomination creates one item, not two`() = runTest {
        // HIGH-1, the double-tap half. Pre-fix this filed two `creatureProperties.insert`
        // calls — the op carries no coalesce key — and left two Gold piece items on the
        // sheet, which the app has no way to delete (10 decision 12).
        val h = harness(item("i1", "Bedroll"))
        val absent = h.walletRow(CoinKind.GOLD)

        h.character.adjustCoins(absent, +1)
        h.character.adjustCoins(absent, +1)
        h.serverCreates("gp1", CoinKind.GOLD, 1)
        advanceUntilIdle()

        assertEquals("exactly one item may be created", 1, h.callsTo("creatureProperties.insert").size)
        assertEquals(2, h.coinsLanded(CoinKind.GOLD, "gp1"))
    }

    @Test
    fun `a hold burst during the insert round trip still creates exactly one item`() = runTest {
        // HIGH-1 proper. `StepperButton`'s hold accelerates to a repeat every 60 ms, so a
        // real hold fires many taps inside one server round trip.
        //
        // This is also the case that rules out the alternative fix. A coalesce key on
        // `InsertProperty` would not close this window: `WriteQueue.takeCoalescedHead`
        // merges the head with entries **still queued**, and it removes the head and claims
        // it as `inFlight` before the call goes out — so a keyed burst collapses to *two*
        // calls (the one on the wire plus one merged batch), and two inserts are two items.
        // The gate below is that in-flight window, held open.
        val h = harness(item("i1", "Bedroll"))
        val absent = h.walletRow(CoinKind.GOLD)
        val onTheWire = CompletableDeferred<Unit>()
        h.caller.gate = onTheWire

        h.character.adjustCoins(absent, +1)
        advanceUntilIdle()
        assertEquals("the first tap is on the wire", 1, h.callsTo("creatureProperties.insert").size)

        // Five more repeats of the hold, all landing while the insert is unacknowledged and
        // all still holding the row object that says the denomination is absent.
        repeat(5) { h.character.adjustCoins(absent, +1) }
        advanceUntilIdle()
        assertEquals(
            "no tap during the round trip may start a second insert",
            1,
            h.callsTo("creatureProperties.insert").size,
        )

        // The server answers, and the subscription delivers the document it created. DDP
        // pushes before `updated`, which is what makes the flush's row lookup a formality.
        h.serverCreates("gp1", CoinKind.GOLD, 1)
        onTheWire.complete(Unit)
        advanceUntilIdle()

        assertEquals("still exactly one item", 1, h.callsTo("creatureProperties.insert").size)
        assertEquals("the whole hold has to land", 6, h.coinsLanded(CoinKind.GOLD, "gp1"))
    }

    @Test
    fun `a failed insert drops the burst rather than replaying it`() = runTest {
        // No item was created, and re-sending an insert whose outcome was never seen is the
        // duplicate the latch exists to prevent — the same rule WriteQueue applies to a call
        // that lost its connection. The latch must still be released: a stuck one would
        // disable this denomination's stepper for the rest of the session.
        val h = harness(item("i1", "Bedroll"))
        val absent = h.walletRow(CoinKind.GOLD)
        h.caller.failWith = { DdpError("400", "Nope") }

        h.character.adjustCoins(absent, +1)
        h.character.adjustCoins(absent, +1)
        advanceUntilIdle()

        assertEquals(1, h.callsTo("creatureProperties.insert").size)
        assertTrue("nothing may be adjusted — there is no property", h.callsTo("creatureProperties.adjustQuantity").isEmpty())

        // And the stepper still works afterwards.
        h.caller.failWith = { null }
        h.character.adjustCoins(absent, +1)
        advanceUntilIdle()
        assertEquals("the latch must not survive its own failure", 2, h.callsTo("creatureProperties.insert").size)
    }

    @Test
    fun `the latch is per denomination, so adding gold does not block adding silver`() = runTest {
        // They are different items and there is no sense in which they sum, so a single
        // global insert-outstanding flag would have swallowed the silver entirely.
        val h = harness(item("i1", "Bedroll"))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), +1)
        h.character.adjustCoins(h.walletRow(CoinKind.SILVER), +1)
        advanceUntilIdle()

        assertEquals(
            listOf(CoinKind.GOLD.itemName, CoinKind.SILVER.itemName),
            h.callsTo("creatureProperties.insert").map { it.creatureProperty().text("name") },
        )
    }

    // -----------------------------------------------------------------------
    // adjustCoins branch 3: the clamp — MED-2
    // -----------------------------------------------------------------------

    @Test
    fun `a multi stack spend clamps at the head stack and never goes negative`() = runTest {
        // MED-2. The wallet row sums both stacks (105 gp) but `propertyId` names the head
        // (5 gp), and `adjustQuantity` can only reach the head. Clamping against the sum sent
        // "spend 50" at a 5 gp item and left it at −45 on the server.
        val h = harness(
            coin("gp-head", CoinKind.GOLD, 5, order = 1),
            coin("gp-second", CoinKind.GOLD, 100, order = 2),
        )
        val row = h.walletRow(CoinKind.GOLD)
        assertEquals("the row's total is the sum across stacks", 105, row.quantity)
        assertEquals("but the write target is the head", 5, row.headQuantity)
        assertEquals("gp-head", row.propertyId)

        h.character.adjustCoins(row, -50)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("gp-head", call.text("_id"))
        assertEquals("the spend must stop at what the head stack holds", 5, call.int("value"))
    }

    @Test
    fun `a spend on an emptied head stack is dropped even when the row still reads money`() = runTest {
        val h = harness(
            coin("gp-head", CoinKind.GOLD, 0, order = 1),
            coin("gp-second", CoinKind.GOLD, 100, order = 2),
        )
        val row = h.walletRow(CoinKind.GOLD)
        assertEquals(100, row.quantity)
        assertEquals(0, row.headQuantity)

        h.character.adjustCoins(row, -10)
        advanceUntilIdle()

        assertTrue("a clamp of zero is a dropped tap, not an `increment 0`", h.caller.calls.isEmpty())
    }

    @Test
    fun `gaining coins is never clamped`() = runTest {
        val h = harness(coin("gp-head", CoinKind.GOLD, 0))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), +9)
        advanceUntilIdle()

        assertEquals(-9, h.caller.calls.single().int("value"))
    }

    // -----------------------------------------------------------------------
    // adjustItem: the quantity latch — the 1.7.x stepper burst
    // -----------------------------------------------------------------------
    //
    // The reported bug, in one paragraph. `StepperButton`'s press-and-hold repeats every 60 ms.
    // Every repeat re-read the quantity from the **inventory** board, which carries no
    // optimistic overlay (`CreatureSession.inventory` is built from the sheet alone), so it did
    // not move until the server echoed — and each tap therefore passed a clamp against the
    // pre-burst quantity. A two-second hold from 3 dispatched a burst that the queue coalesced
    // into one or several large decrements; the server *clamps* an out-of-range `increment` to
    // zero and stores it rather than refusing it, so the excess was lost silently. Worse, a `+`
    // tap arriving while that burst was still queued, in flight, or waiting out a rate-limit
    // retry merged into an op that was already net-negative past the floor, so the merged sum
    // was clamped to zero again and the `+` was swallowed whole.
    //
    // Every test below drives the stepper the way `CharacterHomeViewModel.adjustItemQuantity`
    // does — re-resolving the row from the inventory board on each tap — because reusing one
    // captured row would quietly assume away the exact staleness that caused the bug.

    @Test
    fun `a two second hold on minus sends calls summing to exactly the quantity, never more`() = runTest {
        val h = harness(item("i1", "Potion of Healing", quantity = 3))

        // ~33 repeats, which is what two seconds of hold produces at 60 ms.
        repeat(33) { h.tapQuantity("i1", -1) }
        advanceUntilIdle()

        assertEquals("a hold may spend the stack and not one more", -3, h.quantityDelta("i1"))
        assertEquals(
            "and the last call is the one that empties it — exactly, not past it",
            0,
            h.lowestQuantityAsked("i1", from = 3),
        )
    }

    /**
     * **The stick.** The half that made this bug survive a first look: the burst clamps
     * correctly *and* the `+` still vanishes, because the two meet inside one coalesced op.
     */
    @Test
    fun `a plus tap during the burst is not swallowed by the queued decrement`() = runTest {
        val h = harness(item("i1", "Potion of Healing", quantity = 3))
        val onTheWire = CompletableDeferred<Unit>()
        h.caller.gate = onTheWire

        repeat(33) { h.tapQuantity("i1", -1) }
        advanceUntilIdle()
        assertEquals("exactly one flush is on the wire", 1, h.callsTo(ADJUST).size)

        // The player sees they over-held and taps `+` — with the burst still outstanding.
        h.tapQuantity("i1", +1)
        advanceUntilIdle()

        onTheWire.complete(Unit)
        h.caller.gate = null
        advanceUntilIdle()

        assertEquals("3 spent down and 1 put back is a net 2", -2, h.quantityDelta("i1"))
        h.assertNeverOverdrawn("i1", from = 3)
    }

    @Test
    fun `a plus tap after the burst has settled is a real increment`() = runTest {
        val h = harness(item("i1", "Potion of Healing", quantity = 3))

        repeat(33) { h.tapQuantity("i1", -1) }
        advanceUntilIdle()
        // The server has answered and the subscription has delivered the emptied item.
        h.serverEchoes("i1", "Potion of Healing", quantity = 0)
        advanceUntilIdle()
        h.caller.reset()

        h.tapQuantity("i1", +1)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals(ADJUST, call.method)
        // `increment` is a consumption amount, so a `+1` in the UI is a `-1` on the wire.
        assertEquals("a post-burst plus must reach the server as an increment", -1, call.int("value"))
    }

    @Test
    fun `rapid alternating taps net out correctly`() = runTest {
        val h = harness(item("i1", "Potion of Healing", quantity = 2))

        h.tapQuantity("i1", -1)
        h.tapQuantity("i1", +1)
        h.tapQuantity("i1", -1)
        h.tapQuantity("i1", +1)
        advanceUntilIdle()

        assertEquals("four taps that cancel leave the sheet where it started", 0, h.quantityDelta("i1"))
        h.assertNeverOverdrawn("i1", from = 2)
    }

    @Test
    fun `a decrement past the floor is dropped rather than sent as a clamp for the server to eat`() =
        runTest {
            val h = harness(item("i1", "Potion of Healing", quantity = 1))

            repeat(10) { h.tapQuantity("i1", -1) }
            advanceUntilIdle()

            assertEquals("one call, for the one potion", 1, h.callsTo(ADJUST).size)
            assertEquals(-1, h.quantityDelta("i1"))
        }

    @Test
    fun `the latch is per property, so holding one stepper does not block another`() = runTest {
        // Two items are two documents and there is no sense in which they sum; a single global
        // flush-outstanding flag would have swallowed the second stepper entirely.
        val h = harness(
            item("i1", "Potion of Healing", quantity = 3),
            item("i2", "Torch", quantity = 3),
        )

        h.tapQuantity("i1", -1)
        h.tapQuantity("i2", -1)
        advanceUntilIdle()

        assertEquals(listOf("i1", "i2"), h.callsTo(ADJUST).map { it.text("_id") })
    }

    @Test
    fun `a failed flush drops the accumulation rather than replaying it, and releases the latch`() =
        runTest {
            // Re-sending an `increment` whose outcome was never seen is the corruption the
            // queue refuses by construction, and the accumulated taps were clamped against a
            // predicted sheet that never came to exist. The latch must still be released: a
            // stuck one would disable this item's stepper for the rest of the session.
            val h = harness(item("i1", "Potion of Healing", quantity = 3))
            h.caller.failWith = { DdpError("400", "Nope") }

            repeat(10) { h.tapQuantity("i1", -1) }
            advanceUntilIdle()

            assertEquals("the accumulation is dropped, not retried", 1, h.callsTo(ADJUST).size)

            h.caller.failWith = { null }
            h.tapQuantity("i1", -1)
            advanceUntilIdle()

            assertEquals("the latch must not survive its own failure", 2, h.callsTo(ADJUST).size)
        }

    /**
     * Ruling B, from the queue's side: **the coalescer has nothing to merge on this path.**
     *
     * `WriteQueue.takeCoalescedHead` merges by signed sum, which is safe only while every op
     * reaching it is already inside its property's bounds — this server stores a clamped `0`
     * rather than refusing an overdraft, so a merge that oversubtracts loses the difference and
     * takes any increment folded into it along. The guarantee is made *upstream* by the latch,
     * and this is what "upstream" means concretely: with one flush held on the wire, thirty-two
     * further repeats put **nothing** in the queue behind it. Pre-fix those repeats were thirty
     * two submissions clamped against a board that had not moved, and the merged head was the
     * `−33` the server ate.
     *
     * A queue-level "do not merge increments of opposite sign" guard is refused for the reason
     * stated on `takeCoalescedHead`: it would not fix a same-sign overdraft (which is the case
     * actually reported) and it would destroy the cancel-out behaviour the tracker's steppers
     * depend on.
     */
    @Test
    fun `a burst never puts a second adjustQuantity in the queue for the coalescer to merge`() =
        runTest {
            val h = harness(item("i1", "Potion of Healing", quantity = 3))
            val onTheWire = CompletableDeferred<Unit>()
            h.caller.gate = onTheWire

            repeat(33) { h.tapQuantity("i1", -1) }
            advanceUntilIdle()
            assertEquals("32 repeats may not reach the queue at all", 1, h.callsTo(ADJUST).size)

            onTheWire.complete(Unit)
            h.caller.gate = null
            advanceUntilIdle()

            assertEquals(
                "the flush and the accumulated remainder, sequenced — never merged",
                listOf(1, 2),
                h.callsTo(ADJUST).map { it.int("value") },
            )
        }

    // -----------------------------------------------------------------------
    // Readers
    // -----------------------------------------------------------------------

    /** Exactly the row `CharacterHomeViewModel.adjustItemQuantity` builds, from the same board. */
    private fun Harness.itemRow(propertyId: String): TrackedResource {
        val item = session.inventory.value.allItems.first { it.propertyId == propertyId }
        return TrackedResource(
            propertyId = item.propertyId,
            kind = TrackerKind.ITEM,
            name = item.name,
            value = item.quantity,
            total = item.quantity,
        )
    }

    /** One stepper repeat, resolved live — see this section's opening note on why. */
    private fun Harness.tapQuantity(propertyId: String, delta: Int) =
        character.adjustItem(itemRow(propertyId), delta)

    /** The mirror frame the server sends once an `adjustQuantity` has been applied. */
    private fun Harness.serverEchoes(id: String, name: String, quantity: Int) =
        feed.changeProperty(id, Json.parseToJsonElement(item(id, name, quantity)) as JsonObject)

    /**
     * What the run actually did to [propertyId]'s quantity, in the player's sign: every
     * `adjustQuantity` aimed at it, with `increment`'s consumption sign undone.
     */
    private fun Harness.quantityDelta(propertyId: String): Int =
        callsTo(ADJUST).filter { it.text("_id") == propertyId }.sumOf { -it.int("value") }

    /**
     * The lowest quantity any single call asked the sheet to hold, starting [from].
     *
     * Below zero is the bug: the server would accept it, clamp to `0` and lose the difference.
     * A floor of exactly `0` is the correct end of a hold that spent the whole stack.
     */
    private fun Harness.lowestQuantityAsked(propertyId: String, from: Int): Int {
        var running = from
        var lowest = from
        callsTo(ADJUST).filter { it.text("_id") == propertyId }.forEach {
            running -= it.int("value")
            lowest = minOf(lowest, running)
        }
        return lowest
    }

    /**
     * The invariant, where the exact floor is not the point: **no call ever asks for a
     * negative quantity.** A run that ends on `0` and a run that ends on `1` are both correct;
     * a run that dips below zero has already lost the difference, because this server clamps
     * and stores rather than refusing.
     */
    private fun Harness.assertNeverOverdrawn(propertyId: String, from: Int) {
        val lowest = lowestQuantityAsked(propertyId, from)
        assertTrue(
            "a call asked the sheet to hold $lowest of $propertyId; the server would clamp " +
                "that to 0 and silently lose the rest: ${callsTo(ADJUST).map { it.int("value") }}",
            lowest >= 0,
        )
    }

    /**
     * How many coins of [kind] the run actually put on the sheet: the insert's own quantity
     * plus every `adjustQuantity` aimed at [propertyId], with `increment`'s consumption sign
     * undone. This is the number the *user* sees on the stepper, which is the thing the
     * one-item guarantee is only half of.
     */
    private fun Harness.coinsLanded(kind: CoinKind, propertyId: String): Int {
        val inserted = callsTo("creatureProperties.insert")
            .filter { it.creatureProperty().text("name") == kind.itemName }
            .sumOf { it.creatureProperty().int("quantity") }
        val adjusted = callsTo("creatureProperties.adjustQuantity")
            .filter { it.text("_id") == propertyId }
            .sumOf { -it.int("value") }
        return inserted + adjusted
    }

    // -----------------------------------------------------------------------
    // removeItem (12 decision 7) and moveItem (12 decision 8) — FR-9
    // -----------------------------------------------------------------------

    @Test
    fun `removeItem sends softRemove and names the row from the board`() = runTest {
        val h = harness(item("i1", "Torch"))

        // No name passed: the implementation resolves it from the board, so the history entry
        // can never end up quoting a Meteor id at the player.
        h.character.removeItem("i1")
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.softRemove", call.method)
        assertEquals("i1", call.text("_id"))
        assertEquals("Torch", h.character.writeHistory.value.single().targetName)
    }

    /**
     * Decision 7's headline, at the layer that has to make it true: a delete files an
     * **undoable** entry, and the undo is a `restore` of the same id.
     *
     * The layer matters. `InventoryMoveDeleteWriteOpTest` proves the op's inverse is shaped
     * right; only this test proves the intent actually reaches the queue with that inverse
     * attached, that `canUndo` flips, and that the entry is marked undone afterwards — which
     * is the round trip the snackbar's UNDO button performs.
     */
    @Test
    fun `undoing a delete restores the same property`() = runTest {
        val h = harness(item("i1", "Torch"))

        h.character.removeItem("i1")
        advanceUntilIdle()
        assertTrue("a delete is undoable — unlike an add", h.character.canUndo.value)
        h.caller.reset()

        assertTrue(h.character.undoLastWrite())
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("the undo of a softRemove is a restore, not a second softRemove",
            "creatureProperties.restore", call.method)
        assertEquals("i1", call.text("_id"))
        assertTrue(h.character.writeHistory.value.single().undone)
        assertFalse("undo is not redo", h.character.canUndo.value)
    }

    /**
     * **Why the double-tap guard has to live in the dialog** (LOW-2).
     *
     * This test asserts the *hazard*, not a fix, and is written that way deliberately: it pins
     * that nothing at this layer can absorb a second delete of one item, which is the whole
     * argument for the re-entrancy latch in `DeleteConfirmDialog` being where it is.
     *
     * Both of the mechanisms that would otherwise catch it are absent by design. `RemoveProperty`
     * has no `coalesceKey` — a delete is not a stepper, and giving it one would let a delete
     * merge with the `restore` queued behind it. And `removeItem`'s stale-id guard reads the
     * board, which cannot have changed between two taps in the same frame, so the second call
     * resolves the item just as successfully as the first.
     *
     * The visible damage is the undo stack: two entries, so one UNDO restores the item and the
     * button stays lit offering to restore something already restored. If a future change makes
     * this layer idempotent, this test is the one to delete — and deleting it should be a
     * conscious act, which is why it is stated rather than left implicit.
     */
    @Test
    fun `two deletes of one item are two ops - the queue cannot absorb a double tap`() = runTest {
        val h = harness(item("i1", "Torch"))

        h.character.removeItem("i1")
        h.character.removeItem("i1")
        advanceUntilIdle()

        assertEquals(
            "no coalesceKey, and the board guard cannot see a change that has not synced",
            2,
            h.callsTo("creatureProperties.softRemove").size,
        )
        assertEquals("which is two undo entries for one deletion", 2, h.character.writeHistory.value.size)
    }

    /**
     * The vanish, end to end (10 decision 3 meeting 12 decision 7).
     *
     * Soft-removed properties are **still delivered to clients** — that is the probe fact the
     * whole removed-filter audit was written for — so "the item disappears" is not something
     * this feature implements. It is something six existing filters already do, and this pins
     * that they still do it for a document the app itself removed: the board is rebuilt from
     * the mirror frame the server sends back, and the item is gone from `allItems`, from its
     * section, and from the carried-weight sum.
     *
     * A property of the *board*, not of the write: the row would still be on screen if any one
     * of those filters were dropped, and the delete would look like it had silently failed.
     */
    @Test
    fun `a soft-removed item drops out of the rebuilt board and its weight`() = runTest {
        val h = harness(
            item("i1", "Torch", quantity = 2, extra = ""","weight":1.0"""),
            item("i2", "Bedroll", extra = ""","weight":7.0"""),
        )
        assertEquals(9.0, h.session.inventory.value.carriedWeightLb, 0.001)

        h.character.removeItem("i1")
        advanceUntilIdle()

        // The frame the server sends after `softRemove` lands: the same document, flagged.
        h.feed.changeProperty(
            "i1",
            Json.parseToJsonElement(
                item("i1", "Torch", quantity = 2, extra = ""","weight":1.0,"removed":true"""),
            ) as JsonObject,
        )
        advanceUntilIdle()

        val board = h.session.inventory.value
        assertTrue(
            "a removed item must leave every list — it is still on the wire",
            board.allItems.none { it.propertyId == "i1" },
        )
        assertEquals("and out of the sum the capacity bar is drawn against", 7.0, board.carriedWeightLb, 0.001)
    }

    /**
     * The second coin gate (decision 7). The UI omits the control on a coin row; this is the
     * same rule where the write happens, and it holds because `InventoryBoard`'s precedence
     * puts every coin-tagged item in the wallet and in no item list.
     */
    @Test
    fun `removeItem refuses a coin, because a coin is not in any item list`() = runTest {
        val h = harness(coin("g1", CoinKind.GOLD, 50), item("i1", "Torch"))

        h.character.removeItem("g1")
        advanceUntilIdle()

        assertTrue("wallet rows are stepper-managed; deleting a purse has no remedy", h.caller.calls.isEmpty())
        assertTrue(h.character.writeHistory.value.isEmpty())
        assertEquals(50, h.walletRow(CoinKind.GOLD).quantity)
    }

    @Test
    fun `removeItem on an id the sheet does not carry sends nothing`() = runTest {
        val h = harness(item("i1", "Torch"))

        h.character.removeItem("gone")
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    /**
     * **The HP row cannot be deleted**, and not because anything says so — because the
     * inventory board holds *items*, and an `attribute` is not one.
     *
     * Worth pinning rather than leaving to the type system. The UI half of decision 7 is
     * structural (delete lives in the item detail sheet, which only an inventory row opens, and
     * the tracker has no such control at all), so the only way this could ever regress is by
     * something handing `removeItem` a property id from the *tracker* board — which shares
     * `propertyId` with the inventory's rows and is the one list where HP, spell slots and
     * resources live. That call has to be a no-op, and this is the assertion that it is.
     */
    @Test
    fun `removeItem cannot reach the HP row, or any other non-item property`() = runTest {
        val h = harness(
            item("i1", "Torch"),
            """{"_id":"hp1","type":"attribute","attributeType":"hitDice","variableName":"hitPoints",
                "name":"Hit Points","total":20,"damage":0,"order":2}""",
            """{"_id":"slot1","type":"attribute","attributeType":"spellSlot",
                "name":"1st Level","total":3,"damage":0,"order":3}""",
        )

        h.character.removeItem("hp1")
        h.character.removeItem("slot1")
        advanceUntilIdle()

        assertTrue(
            "only items are deletable; the tracker's rows share an id space with the inventory's",
            h.caller.calls.isEmpty(),
        )
        assertTrue(h.character.writeHistory.value.isEmpty())
    }

    @Test
    fun `moveItem sends organizeDoc from the item's real parent to the chosen container`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            """{"_id":"bag1","type":"container","name":"Belt Pouch","order":2,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
            """{"_id":"i1","type":"item","name":"Torch","quantity":1,"order":3,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
        )

        h.character.moveItem("i1", InventoryMoveTarget.Container("bag1"))
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("organize.organizeDoc", call.method)
        assertEquals("i1", (call.body["docRef"] as JsonObject).text("id"))
        assertEquals("bag1", (call.body["parentRef"] as JsonObject).text("id"))
        // End of target: one past the sheet's highest `order`, the same rule an add follows.
        assertEquals(4, call.body.int("order"))
    }

    /**
     * The carried root is resolved from the sheet's **tags**, not remembered — `insertTarget`'s
     * preference order, re-used by `moveTarget` so a move and an add land in the same place.
     */
    @Test
    fun `moveItem to Carried resolves the carried-tagged folder`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            """{"_id":"bag1","type":"container","name":"Belt Pouch","order":2,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
            """{"_id":"i1","type":"item","name":"Torch","quantity":1,"order":3,
                "parent":{"id":"bag1","collection":"creatureProperties"}}""",
        )

        h.character.moveItem("i1", InventoryMoveTarget.Carried)
        advanceUntilIdle()

        assertEquals("f1", (h.caller.calls.single().body["parentRef"] as JsonObject).text("id"))
    }

    /**
     * The move round trip, at the layer LOW-6 asked for coverage of: the undo returns the item
     * to the parent it was in, which is the whole of what `WriteOp.Equip` could not do and
     * what decision 8 exists to add.
     */
    @Test
    fun `undoing a move puts the item back in the container it came from`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            """{"_id":"bag1","type":"container","name":"Belt Pouch","order":2,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
            """{"_id":"i1","type":"item","name":"Torch","quantity":1,"order":3,
                "parent":{"id":"bag1","collection":"creatureProperties"}}""",
        )

        h.character.moveItem("i1", InventoryMoveTarget.Carried)
        advanceUntilIdle()
        assertTrue(h.character.canUndo.value)
        h.caller.reset()

        assertTrue(h.character.undoLastWrite())
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("organize.organizeDoc", call.method)
        assertEquals("bag1", (call.body["parentRef"] as JsonObject).text("id"))
        assertEquals("the order it had, not a fresh end-of-sheet one", 3, call.body.int("order"))
        assertTrue(h.character.writeHistory.value.single().undone)
    }

    /**
     * Decision 8's fence, enforced at the write and not only in the UI: `equip` reparents on
     * the server's own schedule, so an equipped item that had also been hand-placed would have
     * two owners of one field and the next equip tap would silently undo the player's move.
     */
    @Test
    fun `moveItem refuses an equipped item`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            """{"_id":"bag1","type":"container","name":"Belt Pouch","order":2,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
            """{"_id":"i1","type":"item","name":"Longsword","quantity":1,"order":3,
                "equipped":true,"parent":{"id":"f1","collection":"creatureProperties"}}""",
        )

        h.character.moveItem("i1", InventoryMoveTarget.Container("bag1"))
        advanceUntilIdle()

        assertTrue("equip already owns an equipped item's parent", h.caller.calls.isEmpty())
    }

    @Test
    fun `moveItem refuses a coin, like removeItem does`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            coin("g1", CoinKind.GOLD, 50),
        )

        h.character.moveItem("g1", InventoryMoveTarget.Carried)
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    /**
     * A pick of the container the item is already in is dropped. Not merely wasteful — it
     * would burn one of the five calls the 5 s window allows and file an entry offering to undo
     * a move that did not happen. (The picker filters it out too; this is the second gate.)
     */
    @Test
    fun `moving an item to where it already is sends nothing`() = runTest {
        val h = harness(
            """{"_id":"f1","type":"folder","name":"Carried","order":1,"tags":["carried"]}""",
            """{"_id":"i1","type":"item","name":"Torch","quantity":1,"order":3,
                "parent":{"id":"f1","collection":"creatureProperties"}}""",
        )

        h.character.moveItem("i1", InventoryMoveTarget.Carried)
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
        assertTrue(h.character.writeHistory.value.isEmpty())
    }


    // -----------------------------------------------------------------------
    // FR-22 direct entry: the absolute shape and the latch barrier
    // (docs/design/15-polish-batch.md decisions 6 and 7)
    // -----------------------------------------------------------------------

    /**
     * The plain case: nothing outstanding, so the set goes straight out as one
     * `adjustQuantity {operation:'set'}`.
     *
     * `set` and not an `increment` computed from the board is the whole of decision 6's
     * "absolute-shaped", and it is what the next two tests need to be true before they mean
     * anything.
     */
    @Test
    fun `a direct entry on an item sends one adjustQuantity set`() = runTest {
        val h = harness(item("i1", "Torch", quantity = 5))

        h.character.adjustItem(trackedItem("i1", "Torch", 5), ExactQuantity(12))
        advanceUntilIdle()

        val call = h.callsTo(ADJUST).single()
        assertEquals("set", call.text("operation"))
        assertEquals(12, call.int("value"))
    }

    /**
     * Decision 7: quantities floor at zero. A negative target is not an error and is not
     * dropped — the dialog cannot produce one (its field takes digits only), so this is the
     * belt to that field's braces, and clamping is the same direction every other quantity
     * clamp in this class takes.
     */
    @Test
    fun `a direct entry below zero is clamped to zero rather than sent`() = runTest {
        val h = harness(item("i1", "Torch", quantity = 5))

        h.character.adjustItem(trackedItem("i1", "Torch", 5), ExactQuantity(-4))
        advanceUntilIdle()

        assertEquals(0, h.callsTo(ADJUST).single().int("value"))
    }

    /**
     * **Decision 6's barrier, and the reason it exists.**
     *
     * A press-and-hold is still settling when the player types a number. The deltas that piled
     * up behind the in-flight flush must still go out — they are taps the app accepted — and the
     * set must go out **after** them, because it is the player's latest word and the server
     * would otherwise be free to apply the two in either order.
     *
     * The assertion is on the *sequence*: increments first, one `set` last, and the set carries
     * the typed number rather than anything derived from the board (which by then is several
     * flushes stale — the exact failure a `target - item.value` implementation would have).
     */
    @Test
    fun `a direct entry arriving mid-hold flushes the pending deltas first and lands last`() = runTest {
        val h = harness(item("i1", "Torch", quantity = 5))
        val row = trackedItem("i1", "Torch", 5)
        // The in-flight window, held open — the same gate HIGH-1's burst test uses, and the
        // only way to observe a latch whose whole job is to exist between a call going out and
        // that call landing.
        val onTheWire = CompletableDeferred<Unit>()
        h.caller.gate = onTheWire

        h.character.adjustItem(row, -1)
        advanceUntilIdle()
        assertEquals("the first tap is on the wire", 1, h.callsTo(ADJUST).size)

        // A second stepper repeat, then the player gives up and types the number they want.
        h.character.adjustItem(row, -1)
        h.character.adjustItem(row, ExactQuantity(9))
        advanceUntilIdle()
        assertEquals("one op per property on the wire at a time", 1, h.callsTo(ADJUST).size)

        onTheWire.complete(Unit)
        advanceUntilIdle()

        val calls = h.callsTo(ADJUST)
        assertEquals(3, calls.size)
        assertEquals("increment", calls[0].text("operation"))
        assertEquals("the accumulated tap is not dropped", "increment", calls[1].text("operation"))
        assertEquals("the typed number is the last word", "set", calls[2].text("operation"))
        assertEquals(9, calls[2].int("value"))
    }

    /**
     * A stepper tap arriving **behind** a parked set re-bases it instead of being dispatched
     * separately.
     *
     * The player typed 9 and then pressed `+`, which means 10 — not "set 9, and separately add
     * one", which is two calls where one will do and two entries in the history for one
     * decision. One `set` carrying 10 is the whole assertion.
     */
    @Test
    fun `a stepper tap behind a parked direct entry re-bases it into one set`() = runTest {
        val h = harness(item("i1", "Torch", quantity = 5))
        val row = trackedItem("i1", "Torch", 5)

        val onTheWire = CompletableDeferred<Unit>()
        h.caller.gate = onTheWire

        h.character.adjustItem(row, -1)               // claims the latch
        advanceUntilIdle()
        h.character.adjustItem(row, ExactQuantity(9)) // parks a set behind it
        h.character.adjustItem(row, +1)               // re-bases the parked set to 10
        onTheWire.complete(Unit)
        advanceUntilIdle()

        val sets = h.callsTo(ADJUST).filter { it.text("operation") == "set" }
        assertEquals(1, sets.size)
        assertEquals(10, sets.single().int("value"))
    }

    /**
     * The wallet's absolute path on a denomination the sheet already carries.
     *
     * The head-stack arithmetic is the interesting half and it is trivial here on purpose —
     * one stack, so `target - (quantity - headQuantity)` is the target. The split-stack case is
     * the next test, and MED-2's decrement clamp is what this generalizes.
     */
    @Test
    fun `a direct entry on a present coin row sets the head stack to the typed number`() = runTest {
        val h = harness(coin("g1", CoinKind.GOLD, 12))

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), ExactQuantity(50))
        advanceUntilIdle()

        val call = h.callsTo(ADJUST).single()
        assertEquals("g1", call.text("_id"))
        assertEquals("set", call.text("operation"))
        assertEquals(50, call.int("value"))
    }

    /**
     * **MED-2's shape, re-derived for a set.**
     *
     * A 5 gp stack followed by a 100 gp stack reads 105 in the wallet, and one
     * `adjustQuantity` can only reach the first. Writing the typed 50 straight at the head
     * would leave the row reading 150 — the player would *gain* money by asking for less — so
     * the head takes the target minus what the unreachable stacks already hold, floored at
     * zero. Here that is `50 - 100 = -50`, floored to 0: the head empties and the row stops at
     * the 100 this app cannot reach, which is the same honest floor a decrement has.
     */
    @Test
    fun `a direct entry on a split coin row subtracts the stacks it cannot reach`() = runTest {
        val h = harness(
            coin("g1", CoinKind.GOLD, 5, order = 1),
            coin("g2", CoinKind.GOLD, 100, order = 2),
        )

        h.character.adjustCoins(h.walletRow(CoinKind.GOLD), ExactQuantity(50))
        advanceUntilIdle()

        val call = h.callsTo(ADJUST).single()
        assertEquals("the head stack, not the row's total", "g1", call.text("_id"))
        assertEquals(0, call.int("value"))
    }

    /**
     * A typed number on a denomination the sheet lacks **creates** the item carrying the whole
     * count — one `insert`, not an insert followed by an adjust.
     *
     * The same guarantee HIGH-1's latch buys for a hold, reached by a different gesture.
     */
    @Test
    fun `a direct entry on an absent coin row inserts one item with the typed count`() = runTest {
        val h = harness(item("i1", "Torch"))

        h.character.adjustCoins(h.walletRow(CoinKind.SILVER), ExactQuantity(37))
        advanceUntilIdle()

        val insert = h.callsTo("creatureProperties.insert").single()
        assertEquals(37, insert.creatureProperty().int("quantity"))
        assertTrue("no adjust — the insert carried the count", h.callsTo(ADJUST).isEmpty())
    }

    /**
     * Zero on an absent row writes nothing.
     *
     * Creating an item holding no coins would put a property on the sheet that this release
     * cannot delete (10 decision 12), to express a fact the four always-present wallet rows
     * already state.
     */
    @Test
    fun `a direct entry of zero on an absent coin row writes nothing`() = runTest {
        val h = harness(item("i1", "Torch"))

        h.character.adjustCoins(h.walletRow(CoinKind.COPPER), ExactQuantity(0))
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    // -----------------------------------------------------------------------
    // FR-23 death saves (decisions 18-21)
    // -----------------------------------------------------------------------

    /**
     * Discovery reaches the board, and the write is decision 19's `damage {set}`.
     *
     * The method matters as much as the value: `damage` is the 20-per-5-second class, which is
     * what makes a run of pip taps behave, and `adjustQuantity` would be the 1 s class against a
     * property that has no quantity.
     */
    @Test
    fun `setDeathSaves sends damage set for each half that moved`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 0, failures = 1))

        h.character.setDeathSaves(successes = 2, failures = 1)
        advanceUntilIdle()

        val call = h.callsTo("creatureProperties.damage").single()
        assertEquals("ds-ok", call.text("_id"))
        assertEquals("set", call.text("operation"))
        assertEquals(2, call.int("value"))
    }

    /**
     * The half that did not move is not sent — [setEquipped]'s no-op guard, applied to a pair.
     *
     * Two calls per pip tap would burn twice the rate budget and file a history entry for a
     * change that did not happen.
     */
    @Test
    fun `setDeathSaves writes nothing when neither half moved`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.character.setDeathSaves(successes = 1, failures = 2)
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    /** Decision 18: a sheet with no pair is ordinary, and a write against it is dropped. */
    @Test
    fun `setDeathSaves on a sheet without the pair writes nothing`() = runTest {
        val h = harness(hp(0), item("i1", "Torch"))

        h.character.setDeathSaves(successes = 3, failures = 0)
        advanceUntilIdle()

        assertTrue(h.caller.calls.isEmpty())
    }

    /**
     * **Decision 20's clear, attached to our own heal.**
     *
     * Healing off zero sends the heal *and* `set 0` to both marked properties, in one gesture.
     * Three calls: the `damage increment` that heals, and one `damage set 0` per marked half.
     */
    @Test
    fun `healing off zero clears both death save properties`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.character.changeHitPoints(+5)
        advanceUntilIdle()

        val calls = h.callsTo("creatureProperties.damage")
        assertEquals(3, calls.size)
        val clears = calls.filter { it.text("operation") == "set" }
        assertEquals(setOf("ds-ok", "ds-no"), clears.map { it.text("_id") }.toSet())
        assertTrue("both cleared to zero", clears.all { it.int("value") == 0 })
    }

    /**
     * **H1's pin.** The heal's `damage increment` and the two `set 0` clears all dispatch —
     * three calls, as the test above confirms — but only the heal may mint a receipt. Before
     * the fix, `clearDeathSavesForHeal` called [DefaultOpenCharacter.setDeathSaves], which
     * submits *with* a receipt; the two clears then filed their own history rows on top of
     * the heal's, newest first, so the snackbar's `history.first()` read as a death-save line
     * instead of the heal that tap actually made.
     */
    @Test
    fun `healing off zero files exactly one history entry, for the heal`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.character.changeHitPoints(+5)
        advanceUntilIdle()

        val entry = h.character.writeHistory.value.single()
        assertEquals("Hit Points", entry.targetName)
    }

    /**
     * **H1's other half.** The undo stack is LIFO; before the fix the clears' own inverses
     * were the last two pushed, so UNDO reversed a death-save clear instead of the heal. With
     * the clears submitted `recordUndo = false`, the heal's inverse is the only thing on the
     * stack — UNDO must restore hit points, and the death-save marks (already cleared, with
     * no receipt of their own) stay cleared. That is `Undoable.HitPoints`' documented cost on
     * the local path, pinned here for the server path.
     */
    @Test
    fun `undoing a heal off zero restores hit points and leaves the death saves cleared`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.character.changeHitPoints(+5)
        advanceUntilIdle()
        assertTrue("the heal is the only entry on the stack", h.character.canUndo.value)
        h.caller.reset()

        assertTrue(h.character.undoLastWrite())
        advanceUntilIdle()

        val call = h.callsTo("creatureProperties.damage").single()
        assertEquals("hp1", call.text("_id"))
        assertEquals("undo of a +5 heal must be +5 damage", "increment", call.text("operation"))
        assertEquals(5, call.int("value"))
        assertTrue("the heal's entry is the one marked undone", h.character.writeHistory.value.single().undone)
        assertFalse("nothing left on the stack — the clears never joined it", h.character.canUndo.value)
    }

    /**
     * A heal that does **not** start at zero clears nothing.
     *
     * Without this the app would file two no-op writes on every heal for the whole game, against
     * the same 20-per-5-second bucket the pip taps use.
     */
    @Test
    fun `healing a character who is already up clears nothing`() = runTest {
        val h = harness(hp(4), *deathSaves(successes = 1, failures = 2))

        h.character.changeHitPoints(+5)
        advanceUntilIdle()

        assertEquals(1, h.callsTo("creatureProperties.damage").size)
    }

    /** Damage is not a heal. The marks stay, which is the point of them. */
    @Test
    fun `taking damage at zero clears nothing`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.character.changeHitPoints(-3)
        advanceUntilIdle()

        assertEquals(1, h.callsTo("creatureProperties.damage").size)
    }

    /** FR-22's direct entry on HP is a heal too, and clears by the same path. */
    @Test
    fun `a direct entry taking hit points off zero clears the death saves`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 0, failures = 3))

        h.character.setHitPoints(11)
        advanceUntilIdle()

        val clears = h.callsTo("creatureProperties.damage")
            .filter { it.text("operation") == "set" && it.text("_id") != "hp1" }
        assertEquals(1, clears.size)
        assertEquals(0, clears.single().int("value"))
    }

    /**
     * **The observer-storm rule, as a regression test** (decision 20, in capitals).
     *
     * Another client heals the character: the mirror publishes hit points above zero and this
     * client observes the 0 → positive transition without having written anything. Nothing may
     * go out. A reactive implementation — a collector on `board.hp` — passes every other test in
     * this group and fails only this one, which is precisely why it is here: with N clients
     * watching one sheet (the DM dashboard watches six), that implementation is N duplicate
     * clears per heal against a shared bucket.
     */
    @Test
    fun `an observed heal from another client sends nothing`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 1, failures = 2))

        h.feed.changeProperty(
            "hp1",
            Json.parseToJsonElement(hp(current = 9)) as JsonObject,
        )
        advanceUntilIdle()

        assertEquals("the sheet moved; this client did not write", 9, h.session.board.value.hp?.value)
        assertTrue("no clear may be fired from observed state", h.caller.calls.isEmpty())
    }

    /** Marks already at zero need no clearing, so a heal off zero is one call. */
    @Test
    fun `healing off zero with no marks sends only the heal`() = runTest {
        val h = harness(hp(0), *deathSaves(successes = 0, failures = 0))

        h.character.changeHitPoints(+5)
        advanceUntilIdle()

        assertEquals(1, h.callsTo("creatureProperties.damage").size)
    }

    /** The `TrackedResource` the direct-entry overload is handed, as the UI would build it. */
    private fun trackedItem(id: String, name: String, quantity: Int) = TrackedResource(
        propertyId = id,
        kind = TrackerKind.ITEM,
        name = name,
        value = quantity,
        total = quantity,
    )

    private fun FakeDdpMethodCaller.Call.bool(key: String): Boolean =
        (body[key] as? JsonPrimitive)?.booleanOrNull ?: error("no boolean `$key` in $body")

    private fun FakeDdpMethodCaller.Call.creatureProperty(): JsonObject =
        body["creatureProperty"] as JsonObject

    private fun FakeDdpMethodCaller.Call.parentRef(): JsonObject = body["parentRef"] as JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("no int `$key` in $this")
}
