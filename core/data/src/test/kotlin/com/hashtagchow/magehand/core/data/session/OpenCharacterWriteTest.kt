package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.Fixtures
import com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller
import com.hashtagchow.magehand.core.data.write.OptimisticOverlay
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.ddp.DdpError
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * The `:core:data` half of WP7's write-posture claim.
 *
 * `WritePostureTest` in `:app` proves the UI *cannot* build a DiceCloud call. This proves
 * the other half — that the intents it calls instead **land on the [WriteQueue]** — and it
 * does so by observing properties only the queue has:
 *
 * - a write is refused unless the session is LIVE,
 * - two taps on one row become **one** server call with the summed value,
 * - the optimistic value appears before the server answers and rolls back when it errors,
 * - the undo stack holds the exact inverse,
 * - a rest empties that stack.
 *
 * A hypothetical implementation that reached the socket directly would pass none of them.
 *
 * The wire shapes themselves are 03 §Write semantics' table, asserted call-by-call, so this
 * is also where "spend = damage increment +1" is pinned end-to-end from the UI's vocabulary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenCharacterWriteTest {

    private val accountId = "acc-1"
    private val creatureId = Fixtures.SABRIEL_ID

    private lateinit var database: MageHandDatabase
    private lateinit var snapshots: SnapshotStore
    private val scopes = mutableListOf<CoroutineScope>()

    private val slot = TrackedResource(
        propertyId = "slot-1",
        kind = TrackerKind.SPELL_SLOT,
        name = "1st Level",
        value = 3,
        total = 3,
    )
    private val potion = TrackedResource(
        propertyId = "item-1",
        kind = TrackerKind.ITEM,
        name = "Potion of Healing",
        value = 5,
        total = 5,
    )
    private val bless = ConditionToggle(propertyId = "toggle-1", name = "Bless", enabled = false)

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
        val character: OpenCharacter,
        val session: CreatureSession,
        val feed: FakeCreatureFeed,
        val caller: FakeDdpMethodCaller,
    )

    private fun TestScope.harness(live: Boolean = true): Harness {
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
        // The board has to be real for `changeHitPoints`, which resolves the HP row itself.
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        if (live) feed.goLive()
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

    // --- 03 §Write semantics, one row of the table per test ---------------------

    @Test
    fun `spending a slot sends damage increment plus one`() = runTest {
        val h = harness()
        h.character.spend(slot)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.damage", call.method)
        assertEquals("slot-1", call.text("_id"))
        assertEquals("increment", call.text("operation"))
        assertEquals(1, call.int("value"))
    }

    @Test
    fun `restoring a spent slot sends damage increment minus one`() = runTest {
        val h = harness()
        h.character.restore(slot.copy(value = 1))
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.damage", call.method)
        assertEquals(-1, call.int("value"))
    }

    @Test
    fun `using a potion sends adjustQuantity increment plus one`() = runTest {
        // The sign that cost WP7 a probe: `adjustQuantity`'s `increment` is a *consumption*
        // amount, exactly like `damage`'s. Live, `-1` twice moved the dummy's potions
        // 5 → 7 and `+1` once moved 7 → 6. 03 §Write semantics has this backwards.
        val h = harness()
        h.character.adjustItem(potion, -1)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.adjustQuantity", call.method)
        assertEquals("item-1", call.text("_id"))
        assertEquals(1, call.int("value"))
    }

    @Test
    fun `adding an item sends the opposite sign`() = runTest {
        val h = harness()
        h.character.adjustItem(potion, +2)
        advanceUntilIdle()
        assertEquals(-2, h.caller.calls.single().int("value"))
    }

    @Test
    fun `an item's optimistic value moves the way the user expects`() = runTest {
        val h = harness()
        val op = WriteOp.consumeItem(potion, 2)
        // Guards the pair of sign flips against being "fixed" in only one place: the wire
        // value is +2 (consume two) and the overlay must still show two *fewer*.
        assertEquals(4, OptimisticOverlay.of(listOf(op.optimistic!!)).valueFor("item-1", 6))
    }

    @Test
    fun `flipping a condition sends flipToggle with the id alone`() = runTest {
        val h = harness()
        h.character.toggle(bless)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.flipToggle", call.method)
        assertEquals("toggle-1", call.text("_id"))
    }

    @Test
    fun `a long rest sends creature methods rest for this creature`() = runTest {
        val h = harness()
        h.character.rest(RestKind.LONG)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creature.methods.rest", call.method)
        assertEquals(creatureId, call.text("creatureId"))
        assertEquals("longRest", call.text("restType"))
    }

    @Test
    fun `taking damage and healing are the same method with opposite signs`() = runTest {
        val h = harness()
        h.character.changeHitPoints(-4)
        advanceUntilIdle()
        h.character.changeHitPoints(+4)
        advanceUntilIdle()

        assertEquals(listOf("creatureProperties.damage", "creatureProperties.damage"), h.caller.methods())
        assertEquals(4, h.caller.calls[0].int("value"))
        assertEquals(-4, h.caller.calls[1].int("value"))
    }

    // --- the queue's guarantees, observed through the intents --------------------

    @Test
    fun `no intent reaches the server while the session is not live`() = runTest {
        val h = harness(live = false)

        h.character.spend(slot)
        h.character.adjustItem(potion, -1)
        h.character.toggle(bless)
        h.character.rest(RestKind.SHORT)
        h.character.changeHitPoints(-1)
        advanceUntilIdle()

        assertTrue(
            "a write escaped while the session was not LIVE: ${h.caller.methods()}",
            h.caller.calls.isEmpty(),
        )
        assertFalse(h.character.canWrite.value)
    }

    @Test
    fun `two taps on one row become one call with the summed value`() = runTest {
        val h = harness()
        h.character.spend(slot)
        h.character.spend(slot)
        advanceUntilIdle()

        // Coalescing is a WriteQueue property; a direct-to-socket implementation would
        // have sent two.
        val call = h.caller.calls.single()
        assertEquals(2, call.int("value"))
        assertEquals(1, h.character.writeHistory.value.size)
        assertEquals(2, h.character.writeHistory.value.single().amount)
    }

    @Test
    fun `a successful write becomes an undoable history entry`() = runTest {
        val h = harness()
        h.character.spend(slot)
        advanceUntilIdle()

        val entry = h.character.writeHistory.value.single()
        assertEquals(TrackerWriteKind.SPEND, entry.kind)
        assertEquals("1st Level", entry.targetName)
        assertEquals(1, entry.amount)
        assertTrue(entry.undoable)
        assertFalse(entry.undone)
        assertTrue(h.character.canUndo.value)
    }

    @Test
    fun `undo sends the exact inverse and marks the entry undone`() = runTest {
        val h = harness()
        h.character.spend(slot, 2)
        advanceUntilIdle()
        h.caller.reset()

        assertTrue(h.character.undoLastWrite())
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("creatureProperties.damage", call.method)
        assertEquals("undo of `spend 2` must be `damage increment -2`", -2, call.int("value"))

        val entry = h.character.writeHistory.value.single()
        assertTrue(entry.undone)
        assertFalse(entry.undoable)
        assertFalse(h.character.canUndo.value)
        assertFalse("undo must not be undoable — undo is not redo", h.character.canUndo.value)
    }

    @Test
    fun `a rest clears the undo stack and greys out every earlier entry`() = runTest {
        val h = harness()
        h.character.spend(slot)
        advanceUntilIdle()
        h.character.rest(RestKind.LONG)
        advanceUntilIdle()

        val history = h.character.writeHistory.value
        assertEquals(2, history.size)
        assertEquals(TrackerWriteKind.LONG_REST, history.first().kind)
        assertFalse("a rest is never undoable", history.first().undoable)
        assertFalse(
            "restoring a slot the server has already reset would overfill it",
            history.last().undoable,
        )
        assertFalse(h.character.canUndo.value)
    }

    @Test
    fun `a failed write rolls its optimistic value back and reports the failure`() = runTest {
        val h = harness()
        h.caller.failWith = { DdpError("400", "Nope") }

        val failures = mutableListOf<TrackerWriteFailure>()
        val collector = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            .also { scopes += it }
        collector.launchCollect(h.character) { failures += it }
        advanceUntilIdle()

        h.character.spend(slot)
        advanceUntilIdle()

        assertTrue("the overlay must be empty once the op resolved", h.session.optimistic.value.isEmpty)
        assertTrue("a failed write must not become undoable", h.character.writeHistory.value.isEmpty())
        assertFalse(h.character.canUndo.value)
        val failure = failures.singleOrNull()
        assertNotNull("the UI needs a failure to shake and to explain", failure)
        assertEquals("slot-1", failure!!.propertyId)
        assertEquals("Nope", failure.reason)
    }

    @Test
    fun `a failed rest reports no propertyId, because a rest is not row-shaped`() = runTest {
        // TrackerWriteFailure.propertyId is "the row to shake, or null when the failure
        // was not row-shaped (a rest)". A rest's targetId is the *creature* id, so
        // leaking it here sends the UI looking for a row that cannot exist.
        val h = harness()
        h.caller.failWith = { DdpError("400", "Nope") }

        val failures = mutableListOf<TrackerWriteFailure>()
        val collector = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            .also { scopes += it }
        collector.launchCollect(h.character) { failures += it }
        advanceUntilIdle()

        h.character.rest(RestKind.SHORT)
        advanceUntilIdle()

        val failure = failures.singleOrNull()
        assertNotNull("a failed rest still has to reach the UI", failure)
        assertEquals(TrackerWriteKind.SHORT_REST, failure!!.kind)
        // Not merely "not the creature id": null is the contract, and the creature id is
        // what the old code handed over instead.
        assertNull("a rest has no row to shake", failure.propertyId)
    }

    @Test
    fun `the clamps stop a burst writing past a row's floor`() = runTest {
        val h = harness()
        h.character.spend(slot.copy(value = 0))
        h.character.restore(slot.copy(value = 3, total = 3))
        h.character.adjustItem(potion.copy(value = 0), -1)
        advanceUntilIdle()

        assertTrue(
            "an intent aimed past a row's bounds must never reach the wire: ${h.caller.methods()}",
            h.caller.calls.isEmpty(),
        )
    }

    @Test
    fun `hit points resolve against the live board rather than a caller supplied row`() = runTest {
        val h = harness()
        val hp = h.session.board.value.hp
        assertNotNull("the fixture must have an HP row for this test to mean anything", hp)

        h.character.changeHitPoints(-3)
        advanceUntilIdle()

        assertEquals(hp!!.propertyId, h.caller.calls.single().text("_id"))
    }

    @Test
    fun `setting hit points sends the desired value, not its complement`() = runTest {
        // 03 §Write semantics says `set value: total − desired`. Live, `set 5` on a
        // 20-point row produced `value: 5, damage: 15` — `set` takes the *remaining*
        // value. Following 03 would have sent 15 and set the row to 15 by accident, and
        // "heal to full" (`total − desired == 0`) would have zeroed the character.
        val h = harness()
        val hp = h.session.board.value.hp!!

        h.character.setHitPoints(hp.total - 5)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("set", call.text("operation"))
        assertEquals(hp.total - 5, call.int("value"))
    }

    @Test
    fun `setting a row to full is not silently dropped`() = runTest {
        // The regression this cost a probe to find: 03's arithmetic makes "heal to full"
        // `set 0`, which the queue's zero-check would also have been happy to swallow.
        val h = harness()
        val hp = h.session.board.value.hp!!

        h.character.setHitPoints(hp.total)
        advanceUntilIdle()

        val call = h.caller.calls.single()
        assertEquals("set", call.text("operation"))
        assertEquals(hp.total, call.int("value"))
    }

    @Test
    fun `the write op factory and the intent agree on the wire shape`() {
        // Belt and braces: if someone re-implements an intent inline, the op factory is
        // still the documented shape and this catches the two drifting apart.
        val op = WriteOp.spend(slot) as WriteOp.Damage
        assertEquals("creatureProperties.damage", op.method)
        assertEquals(1, op.value)
        assertEquals(TrackerWriteKind.SPEND, op.intent)
        assertEquals("1st Level", op.targetName)
    }

    private fun CoroutineScope.launchCollect(
        character: OpenCharacter,
        onEach: (TrackerWriteFailure) -> Unit,
    ) = launch { character.writeFailures.collect(onEach) }
}
