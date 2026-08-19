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
import com.hashtagchow.magehand.core.model.NewItemSpec
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
    // Readers
    // -----------------------------------------------------------------------

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

    private fun FakeDdpMethodCaller.Call.bool(key: String): Boolean =
        (body[key] as? JsonPrimitive)?.booleanOrNull ?: error("no boolean `$key` in $body")

    private fun FakeDdpMethodCaller.Call.creatureProperty(): JsonObject =
        body["creatureProperty"] as JsonObject

    private fun FakeDdpMethodCaller.Call.parentRef(): JsonObject = body["parentRef"] as JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: error("no int `$key` in $this")
}
