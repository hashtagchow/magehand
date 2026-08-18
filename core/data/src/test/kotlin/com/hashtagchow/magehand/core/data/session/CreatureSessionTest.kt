package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.db.TrackerPrefEntity
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.Fixtures
import com.hashtagchow.magehand.core.data.write.RestType
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteRefusedException
import com.hashtagchow.magehand.core.model.ConnectionState
import kotlin.time.Duration.Companion.seconds

/**
 * The session's two published values — [CreatureSession.connectionState] and
 * [CreatureSession.board] — against a scripted feed, real Room and the live capture.
 *
 * Real Room (Robolectric) rather than fake DAOs, because the snapshot fallback is the
 * whole point of the class and a fake cache would prove nothing about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreatureSessionTest {

    private val accountId = "acc-1"
    private val creatureId = Fixtures.SABRIEL_ID

    private lateinit var database: MageHandDatabase
    private lateinit var api: FakeDiceCloudApi
    private lateinit var snapshots: SnapshotStore
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeDiceCloudApi()
        snapshots = SnapshotStore(
            snapshotDao = database.snapshotDao(),
            api = api,
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        database.close()
    }

    /**
     * The session's own scope: driven by the test scheduler (so `advanceUntilIdle` runs
     * its `stateIn` collectors) but **not** a child of the test job, so `runTest` does not
     * wait forever for flows that are eager by design.
     */
    private fun TestScope.sessionScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }

    private fun TestScope.session(
        feed: FakeCreatureFeed = FakeCreatureFeed(),
        networkAvailable: MutableStateFlow<Boolean> = MutableStateFlow(true),
        config: CreatureSessionConfig = CreatureSessionConfig(),
    ): CreatureSession = CreatureSession(
        accountId = accountId,
        creatureId = creatureId,
        feed = feed,
        snapshotStore = snapshots,
        trackerPrefDao = database.trackerPrefDao(),
        scope = sessionScope(),
        networkAvailable = networkAvailable,
        config = config,
    )


    /**
     * Lets the session's eager flows run **without** advancing virtual time.
     *
     * `advanceUntilIdle()` would jump straight past the `offlineAfter` timer and report
     * OFFLINE, which is exactly the thing these tests are trying to measure.
     */
    private fun TestScope.settle() = testScheduler.runCurrent()

    /**
     * Waits for the board to satisfy [predicate].
     *
     * The tracker-preference flow comes from real Room, which answers on its own executor
     * in real time — virtual time cannot advance past that, so this alternates between
     * draining the scheduler and yielding a little real time.
     */
    private suspend fun TestScope.awaitBoard(
        session: CreatureSession,
        description: String,
        predicate: (com.hashtagchow.magehand.core.model.TrackerBoard) -> Boolean,
    ): com.hashtagchow.magehand.core.model.TrackerBoard {
        repeat(200) {
            settle()
            if (predicate(session.board.value)) return session.board.value
            withContext(Dispatchers.Default) { delay(5) }
        }
        throw AssertionError("timed out waiting for $description; board=${session.board.value}")
    }

    // -----------------------------------------------------------------------
    // Connection state — 06's four states, of which OFFLINE is ours to derive
    // -----------------------------------------------------------------------

    @Test
    fun `LIVE requires both a connection and a ready subscription`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed)
        settle()
        assertEquals(ConnectionState.CONNECTING, session.connectionState.value)

        // Connected, but the publication has not finished: not LIVE yet.
        feed.connectionState.value = ConnectionState.LIVE
        settle()
        assertEquals(ConnectionState.CONNECTING, session.connectionState.value)

        feed.isReady.value = true
        settle()
        assertEquals(ConnectionState.LIVE, session.connectionState.value)
    }

    @Test
    fun `a long reconnect becomes OFFLINE, and recovers`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed, config = CreatureSessionConfig(offlineAfter = 20.seconds))
        settle()

        advanceTimeBy(19_000)
        assertEquals("a brief hiccup must not show the offline banner", ConnectionState.CONNECTING, session.connectionState.value)

        advanceTimeBy(2_000)
        assertEquals(ConnectionState.OFFLINE, session.connectionState.value)

        feed.goLive()
        settle()
        assertEquals(ConnectionState.LIVE, session.connectionState.value)
    }

    @Test
    fun `no network is OFFLINE immediately, without waiting out the timeout`() = runTest {
        val network = MutableStateFlow(true)
        val session = session(networkAvailable = network)
        settle()
        assertEquals(ConnectionState.CONNECTING, session.connectionState.value)

        network.value = false
        settle()
        assertEquals(ConnectionState.OFFLINE, session.connectionState.value)
    }

    @Test
    fun `AUTH_FAILED is passed straight through and never becomes OFFLINE`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed, networkAvailable = MutableStateFlow(false))
        feed.connectionState.value = ConnectionState.AUTH_FAILED
        settle()
        advanceTimeBy(60_000)
        assertEquals(ConnectionState.AUTH_FAILED, session.connectionState.value)
    }

    // -----------------------------------------------------------------------
    // Board assembly and the snapshot fallback
    // -----------------------------------------------------------------------

    @Test
    fun `the cached snapshot renders before the subscription is ready`() = runTest {
        snapshots.store(accountId, creatureId, Fixtures.sabrielBody, fetchedAt = 12_345L)
        val feed = FakeCreatureFeed()
        val session = session(feed)

        session.start()
        val board = awaitBoard(session, "the cached snapshot to render") { it.slots.isNotEmpty() }

        assertTrue(feed.started)
        assertTrue(session.isShowingSnapshot.value)
        assertEquals(12_345L, session.lastSyncedAt.value)
        assertEquals(4, board.slots.single { it.name == "1st Level" }.total)
        assertEquals(109, board.allItems.single { it.name == "Gold piece" }.value)
    }

    @Test
    fun `the live mirror wins over the snapshot`() = runTest {
        // Snapshot says 4 first-level slots left; the mirror says 1.
        snapshots.store(accountId, creatureId, Fixtures.sabrielBody)
        val feed = FakeCreatureFeed()
        val session = session(feed)
        session.start()
        awaitBoard(session, "the snapshot to render") { it.slots.isNotEmpty() }

        val sheet = Fixtures.sabrielSheet()
        val firstLevelId = "zfdzM7utxt4zrGtCb"
        val patched = CreatureSheet(
            properties = sheet.properties + (
                firstLevelId to buildPatched(sheet.properties.getValue(firstLevelId), value = 1, damage = 3)
                ),
            creature = sheet.creature,
            variables = sheet.variables,
        )
        feed.publish(patched, creatureId)
        feed.goLive()
        val board = awaitBoard(session, "the mirror to win") {
            it.slots.singleOrNull { slot -> slot.name == "1st Level" }?.value == 1
        }

        assertFalse(session.isShowingSnapshot.value)
        assertEquals(1, board.slots.single { it.name == "1st Level" }.value)
    }

    @Test
    fun `an empty mirror falls back to the snapshot rather than blanking the tracker`() = runTest {
        snapshots.store(accountId, creatureId, Fixtures.sabrielBody)
        val feed = FakeCreatureFeed()
        val session = session(feed)
        session.start()
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        awaitBoard(session, "the live board") { !it.isEmpty }

        // A resync that replayed nothing must not produce an empty screen.
        feed.clear()
        settle()
        assertTrue(session.isShowingSnapshot.value)
        assertEquals(3, session.board.value.slots.size)
    }

    @Test
    fun `with neither mirror nor snapshot the board is empty rather than absent`() = runTest {
        val session = session()
        session.start()
        settle()
        assertTrue(session.board.value.isEmpty)
        assertNull(session.lastSyncedAt.value)
    }

    @Test
    fun `tracker prefs are applied to the board and update live`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        awaitBoard(session, "the live board") { it.slots.size == 3 }

        database.trackerPrefDao().upsert(
            TrackerPrefEntity(accountId, creatureId, "zfdzM7utxt4zrGtCb", pinned = false, hidden = true, sortIndex = null),
        )
        val board = awaitBoard(session, "the hidden slot to disappear") { it.slots.size == 2 }

        assertTrue(board.slots.none { it.name == "1st Level" })
    }

    @Test
    fun `pinning an item through the session surfaces it on the board`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        val board = awaitBoard(session, "the live board") { it.allItems.isNotEmpty() }

        val potion = board.allItems.single { it.name == "Potion of Healing" }
        session.setOverride(com.hashtagchow.magehand.core.model.TrackerOverride(potion.propertyId, pinned = true))
        val pinned = awaitBoard(session, "the pin to apply") { it.pinnedItems.isNotEmpty() }
        assertEquals(listOf("Potion of Healing"), pinned.pinnedItems.map { it.name })

        session.clearOverride(potion.propertyId)
        awaitBoard(session, "the pin to clear") { it.pinnedItems.isEmpty() }
    }

    // -----------------------------------------------------------------------
    // Optimistic overlay on the published board (06 §Reconciliation)
    // -----------------------------------------------------------------------

    @Test
    fun `an in-flight spend shows on the board and is reconciled away on success`() = runTest {
        val caller = com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller { testScheduler.currentTime }
        val feed = FakeCreatureFeed(caller)
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        val board = awaitBoard(session, "the live board") { it.slots.isNotEmpty() }

        val slot = board.slots.single { it.name == "1st Level" }
        assertEquals(3, slot.value)

        caller.latencyMillis = 500
        session.writeQueue.submit(WriteOp.spend(slot))
        advanceTimeBy(100)
        assertEquals("the tap must show immediately", 2, session.board.value.slots.single { it.name == "1st Level" }.value)

        advanceUntilIdle()
        // The server value has not changed in this fake mirror, so once the overlay is
        // dropped the board is back to what the mirror says — which is the point:
        // the overlay is derived, never merged into the mirror.
        assertEquals(3, session.board.value.slots.single { it.name == "1st Level" }.value)
    }

    @Test
    fun `a rejected write rolls the board back`() = runTest {
        val caller = com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller { testScheduler.currentTime }
        caller.failWith = { com.hashtagchow.magehand.core.ddp.DdpError("internal", "nope") }
        val feed = FakeCreatureFeed(caller)
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        val board = awaitBoard(session, "the live board") { it.slots.isNotEmpty() }

        val slot = board.slots.single { it.name == "1st Level" }
        val ticket = session.writeQueue.submit(WriteOp.spend(slot))
        advanceUntilIdle()

        assertTrue(runCatching { ticket.await() }.isFailure)
        assertEquals(3, session.board.value.slots.single { it.name == "1st Level" }.value)
        assertTrue(session.optimistic.value.isEmpty)
    }

    @Test
    fun `writes are refused while the session is not LIVE`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        // Connected but the subscription is not ready — 06 says LIVE means both.
        feed.connectionState.value = ConnectionState.LIVE
        session.start()
        val board = awaitBoard(session, "the board") { it.slots.isNotEmpty() }

        val slot = board.slots.first()
        val ticket = session.writeQueue.submit(WriteOp.spend(slot))
        advanceUntilIdle()
        assertTrue(runCatching { ticket.await() }.exceptionOrNull() is WriteRefusedException)
    }

    // -----------------------------------------------------------------------
    // Snapshot lifecycle (06 steps 1 and 2)
    // -----------------------------------------------------------------------

    @Test
    fun `refreshSnapshot pulls over REST and updates the cache`() = runTest {
        api.snapshotResult = { Fixtures.sabrielBody }
        val session = session()
        session.serverUrl = "https://dnd.example.com"
        session.tokenProvider = { "tok" }

        assertTrue(session.refreshSnapshot())
        val board = awaitBoard(session, "the refreshed snapshot") { it.slots.isNotEmpty() }

        assertEquals(listOf(Triple("https://dnd.example.com", "tok", creatureId)), api.snapshotCalls)
        assertEquals(4, board.slots.single { it.name == "1st Level" }.total)
        assertEquals(1_700_000_000_000L, session.lastSyncedAt.value)
    }

    @Test
    fun `refreshSnapshot is a no-op without a server or a token`() = runTest {
        val session = session()
        assertFalse("no server configured", session.refreshSnapshot())
        session.serverUrl = "https://dnd.example.com"
        session.tokenProvider = { null }
        assertFalse("no token yet", session.refreshSnapshot())
        assertTrue(api.snapshotCalls.isEmpty())
    }

    @Test
    fun `captureSnapshot writes the live mirror back to Room without any network`() = runTest {
        val feed = FakeCreatureFeed()
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        awaitBoard(session, "the live board") { !it.isEmpty }

        assertTrue(session.captureSnapshot())
        settle()

        assertTrue("captureSnapshot must not hit the network", api.snapshotCalls.isEmpty())
        val cached = snapshots.loadSheet(accountId, creatureId)!!
        assertEquals(Fixtures.sabrielSheet().properties.size, cached.properties.size)
        assertEquals(4, com.hashtagchow.magehand.core.data.tracker.TrackerEngine.build(cached).slots.single { it.name == "1st Level" }.total)
    }

    @Test
    fun `captureSnapshot refuses to overwrite a good snapshot with an empty mirror`() = runTest {
        snapshots.store(accountId, creatureId, Fixtures.sabrielBody)
        val session = session()
        session.start()
        settle()

        assertFalse(session.captureSnapshot())
        assertEquals(
            Fixtures.sabrielSheet().properties.size,
            snapshots.loadSheet(accountId, creatureId)!!.properties.size,
        )
    }

    @Test
    fun `closing the session stops the subscription and refuses further writes`() = runTest {
        val caller = com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller { testScheduler.currentTime }
        val feed = FakeCreatureFeed(caller)
        val session = session(feed)
        feed.publish(Fixtures.sabrielSheet(), creatureId)
        feed.goLive()
        session.start()
        awaitBoard(session, "the live board") { !it.isEmpty }
        val callsBeforeClose = caller.calls.size

        session.close()
        settle()

        assertTrue("the subscription must be released", feed.stopped)
        session.writeQueue.submit(WriteOp.rest(creatureId, RestType.SHORT_REST))
        advanceUntilIdle()
        assertEquals("a closed session must not keep writing", callsBeforeClose, caller.calls.size)
    }

    private fun buildPatched(
        original: kotlinx.serialization.json.JsonObject,
        value: Int,
        damage: Int,
    ): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(
        original + mapOf(
            "value" to kotlinx.serialization.json.JsonPrimitive(value),
            "damage" to kotlinx.serialization.json.JsonPrimitive(damage),
        ),
    )
}
