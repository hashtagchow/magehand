package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.SyntheticCreature

/**
 * Two [CreatureSession]s over **one** mirror — the DM dashboard's arrangement, shrunk to two.
 *
 * ### The structural blindness this closes
 *
 * Every other session test builds `FakeCreatureFeed()` fresh, and the fake used to own a
 * private collection map. That is not what production does. `DefaultOpenCharacterFactory.build`
 * hands every session a `DdpCreatureFeed(connection.client, …)` over the account's *single*
 * `DdpClient`, and `DdpCreatureFeed.documents` returns `client.mirror.documentsFlow(collection)`
 * — literally the same `StateFlow` object for all of them. So the suite's mental model was "one
 * session, one mirror" while the DM dashboard's is "six sessions, one mirror", and no amount of
 * additional single-session tests could have found a partitioning defect.
 *
 * Nothing here asserts a *new* rule. It asserts the rule the character screen has always
 * relied on — a session publishes its own creature's state — in the one arrangement where the
 * code did not deliver it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedMirrorPartitionTest {

    private val accountId = "acc-1"
    private val alpha = "creature-alpha"
    private val bravo = "creature-bravo"

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

    /**
     * The reproduction, at the level the tablet showed it: two live sessions, one connection,
     * and each card must read its own hit points.
     *
     * Before the fix both boards reported 20/20 — Alpha's row, because Alpha's documents were
     * added first — which is the uniform-cards signature exactly. Whose row wins is an artifact
     * of `added`-frame order, which is why the DM saw the numbers *drift between opens* while
     * staying identical within one.
     */
    @Test
    fun `two sessions on one mirror each publish their own creature`() = runTest {
        val mirror = FakeMirror()
        val alphaFeed = FakeCreatureFeed(mirror = mirror)
        val bravoFeed = FakeCreatureFeed(mirror = mirror)

        val alphaSession = session(alpha, alphaFeed)
        val bravoSession = session(bravo, bravoFeed)

        // Both subscriptions land on the one socket, in order, exactly as six do on entry.
        mirror.add(SyntheticCreature.sheet(alpha, "Alpha", hitPoints = 20), alpha)
        mirror.add(SyntheticCreature.sheet(bravo, "Bravo", hitPoints = 7), bravo)
        alphaFeed.goLive()
        bravoFeed.goLive()
        settle()

        assertEquals(20, alphaSession.board.value.hp?.total)
        assertEquals(7, bravoSession.board.value.hp?.total)
        assertNotEquals(
            "both sessions published the same HP row — every DM card would render identically",
            alphaSession.board.value.hp,
            bravoSession.board.value.hp,
        )
    }

    /**
     * Arrival order must not decide what a card shows.
     *
     * The same two creatures, added the other way round. A session that reads the union answers
     * differently here than above, which is the drift the DM reported; a session that reads its
     * own creature answers identically.
     */
    @Test
    fun `subscription arrival order does not change what a session publishes`() = runTest {
        val mirror = FakeMirror()
        val alphaFeed = FakeCreatureFeed(mirror = mirror)
        val bravoFeed = FakeCreatureFeed(mirror = mirror)

        val alphaSession = session(alpha, alphaFeed)
        val bravoSession = session(bravo, bravoFeed)

        mirror.add(SyntheticCreature.sheet(bravo, "Bravo", hitPoints = 7), bravo)
        mirror.add(SyntheticCreature.sheet(alpha, "Alpha", hitPoints = 20), alpha)
        alphaFeed.goLive()
        bravoFeed.goLive()
        settle()

        assertEquals(20, alphaSession.board.value.hp?.total)
        assertEquals(7, bravoSession.board.value.hp?.total)
    }

    /**
     * The contamination that outlives the screen.
     *
     * `captureSnapshot` re-serializes the mirror into Room on every app-background, and
     * `DmViewViewModel.captureSnapshots` does it for all six cards at once. Reading the union
     * meant each creature's cached snapshot was overwritten with every creature's documents —
     * so the next *cold* open, offline and with no subscription to correct it, rendered the
     * soup from disk.
     */
    @Test
    fun `a captured snapshot holds only its own creature`() = runTest {
        val mirror = FakeMirror()
        val feed = FakeCreatureFeed(mirror = mirror)
        val session = session(alpha, feed)

        mirror.add(SyntheticCreature.sheet(alpha, "Alpha", hitPoints = 20), alpha)
        mirror.add(SyntheticCreature.sheet(bravo, "Bravo", hitPoints = 7), bravo)
        settle()

        assertEquals(true, session.captureSnapshot())

        val cached = requireNotNull(snapshots.load(accountId, alpha)).sheet
        assertEquals(
            SyntheticCreature.sheet(alpha, "Alpha", hitPoints = 20).properties.keys,
            cached.properties.keys,
        )
        assertEquals(alpha, cached.creatureId)
    }

    // --- harness, borrowed from CreatureSessionTest --------------------------------

    private fun TestScope.sessionScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }

    private fun TestScope.session(creatureId: String, feed: FakeCreatureFeed): CreatureSession =
        CreatureSession(
            accountId = accountId,
            creatureId = creatureId,
            feed = feed,
            snapshotStore = snapshots,
            trackerPrefDao = database.trackerPrefDao(),
            scope = sessionScope(),
            now = { 1_700_000_000_000L },
        )

    /** Runs the sessions' eager flows without advancing past the offline timers. */
    private fun TestScope.settle() = testScheduler.runCurrent()
}
