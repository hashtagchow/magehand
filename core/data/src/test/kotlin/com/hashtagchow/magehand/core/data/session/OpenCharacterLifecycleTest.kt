package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

/**
 * Who owns an open character, and what it takes to let go of one.
 *
 * ### The two defects this file exists for
 *
 * **A creature opened twice was opened twice.** FR-19's dashboard holds a party's worth of
 * sessions, and tapping a card opens the character screen on a creature the dashboard is *still
 * holding*. Nothing deduplicated them, so that was two `CreatureSession`s for one creature on
 * one socket: two `singleCharacter` subscriptions out of the 50-per-10 s bucket the whole table
 * shares, two [com.hashtagchow.magehand.core.data.write.WriteQueue]s with independent rate gates
 * and independent optimistic overlays, and two readers of the *same* mirror collection map — so
 * an `applyRemoved` driven by either one mutated state the other was rendering. Nothing in this
 * codebase establishes what the server's mergebox does with one publication subscribed twice on
 * one session, which makes "it looked fine on a tablet" luck rather than a guarantee.
 * [SharedOpenCharacters] makes it one session with a holder count.
 *
 * **Closing was cancellable, and closing is cleanup.** [DefaultOpenCharacter.close] suspends,
 * and every real caller invokes it from a scope that is *already being cancelled* — `onCleared`
 * runs after `viewModelScope` is gone, `onDispose` runs as its scope unwinds. A suspension point
 * in a cancelled coroutine throws, which used to skip `scope.cancel()` and leave the
 * subscription, the board's `stateIn` and the write queue alive for the life of the process,
 * invisibly. The last test here drives exactly that interleaving.
 *
 * ### Why a real session and not a fake `OpenCharacter`
 *
 * Both claims are about teardown actually happening. A stub whose `close()` sets a boolean would
 * assert that this file's own fake works. These build the production [DefaultOpenCharacter] over
 * [FakeCreatureFeed] — `DefaultOpenCharacterWriteTest`'s harness, narrowed — so
 * `isClosed` and the scope's liveness are the real ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenCharacterLifecycleTest {

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

    /** One live [DefaultOpenCharacter] plus the two things a teardown assertion needs. */
    private class Built(
        val character: DefaultOpenCharacter,
        val scope: CoroutineScope,
        val feed: SlowStoppingFeed,
    )

    /**
     * A session over a one-property synthetic sheet.
     *
     * The sheet needs a creature *and* a property or `CreatureSession` resolves it to
     * `CreatureSheet.EMPTY` — `DefaultOpenCharacterWriteTest.harness` explains why. Nothing here
     * reads the board, but a session with no sheet is not the object under test.
     */
    private fun TestScope.build(feed: SlowStoppingFeed = SlowStoppingFeed()): Built {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }
        val session = CreatureSession(
            accountId = accountId,
            creatureId = creatureId,
            feed = feed,
            snapshotStore = snapshots,
            trackerPrefDao = database.trackerPrefDao(),
            scope = scope,
        )
        feed.publish(sheet(), creatureId)
        feed.goLive()
        val character = DefaultOpenCharacter(
            session = session,
            scope = scope,
            serverOrigin = "https://example.invalid",
            themePrefDao = database.themePrefDao(),
            trackerPrefDao = database.trackerPrefDao(),
        )
        advanceUntilIdle()
        return Built(character, scope, feed)
    }

    private fun sheet(): CreatureSheet = CreatureSheet.fromSnapshotJson(
        """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
           "creatureProperties":[{"_id":"p1","type":"item","name":"Rope","quantity":1,"order":1}],
           "creatureVariables":[{"_id":"v1"}]}""",
    )

    // -----------------------------------------------------------------------
    // M8 — one session per creature, reference counted
    // -----------------------------------------------------------------------

    @Test
    fun `a second open of the same creature returns the same session`() = runTest {
        val registry = SharedOpenCharacters()
        var builds = 0
        val builder: suspend () -> DefaultOpenCharacter = { builds++; build().character }

        val dashboard = registry.acquire("$accountId/$creatureId", builder)
        val card = registry.acquire("$accountId/$creatureId", builder)
        advanceUntilIdle()

        // Identity, not equality: the two screens must be looking at *one* mirror, one write
        // queue and one subscription. Two equal-but-distinct sessions is the defect.
        assertSame("the second open must adopt the first session, not build a second", dashboard, card)
        assertEquals("the builder must run exactly once", 1, builds)
        assertEquals(1, registry.size)
    }

    @Test
    fun `closing one holder leaves the session live for the other`() = runTest {
        val registry = SharedOpenCharacters()
        val built = build()
        val dashboard = registry.acquire("k") { built.character }
        val card = registry.acquire("k") { built.character }
        advanceUntilIdle()

        // The card is dismissed; the dashboard behind it is still on screen.
        card.close()
        advanceUntilIdle()

        assertFalse(
            "closing one holder must not stop the subscription the other is rendering",
            built.character.isClosed.value,
        )
        assertFalse("the feed must not have been stopped", built.feed.stopped)
        assertTrue("the session's scope must still be running", built.scope.isActive)
        assertEquals("the entry is still held", 1, registry.size)
        assertSame(dashboard, card)
    }

    @Test
    fun `closing the last holder tears the session down`() = runTest {
        val registry = SharedOpenCharacters()
        val built = build()
        val dashboard = registry.acquire("k") { built.character }
        val card = registry.acquire("k") { built.character }
        advanceUntilIdle()

        card.close()
        dashboard.close()
        advanceUntilIdle()

        assertTrue("the last close must be a real close", built.character.isClosed.value)
        assertTrue("the subscription must be stopped", built.feed.stopped)
        assertFalse("the character's scope must be cancelled", built.scope.isActive)
        assertEquals("and the registry must not keep a dead entry", 0, registry.size)
    }

    @Test
    fun `a released creature is rebuilt rather than resurrected`() = runTest {
        val registry = SharedOpenCharacters()
        val first = build()
        val second = build()
        var builds = 0

        val a = registry.acquire("k") { if (builds++ == 0) first.character else second.character }
        a.close()
        advanceUntilIdle()

        val b = registry.acquire("k") { if (builds++ == 0) first.character else second.character }
        advanceUntilIdle()

        // The count reaching zero *ends* the session — its scope is cancelled and its
        // subscription stopped — so a later open must not hand that corpse back.
        assertNotSame("a released session must never be handed out again", a, b)
        assertEquals(2, builds)
        assertFalse(second.character.isClosed.value)
    }

    @Test
    fun `closing more times than opening is a no-op, not a crash`() = runTest {
        val registry = SharedOpenCharacters()
        val built = build()
        val handle = registry.acquire("k") { built.character }
        advanceUntilIdle()

        // Reachable: a screen closes on its own path and `onCleared` then closes it again.
        // `OpenCharacter.close` is documented idempotent and both view models rely on it.
        handle.close()
        handle.close()
        handle.close()
        advanceUntilIdle()

        assertTrue(built.character.isClosed.value)
        assertEquals(0, registry.size)
    }

    // -----------------------------------------------------------------------
    // M9 — closing is cleanup, so cancellation must not skip it
    // -----------------------------------------------------------------------

    /**
     * The interleaving every production caller actually produces.
     *
     * `DmViewViewModel.onCleared` and `CharacterHomeViewModel.onCleared` both close sessions
     * from a scope the framework has just cancelled, and `DisposableEffect`'s `onDispose` does
     * the same. Here the coroutine cancels *itself* and then closes, which is that situation
     * made deterministic: no timing, no second thread, just a cancelled `Job` in the context
     * when the suspending teardown starts.
     *
     * Without `withContext(NonCancellable)` the first suspension inside `session.close()` throws
     * `CancellationException`, `scope.cancel()` never runs, and the subscription plus the
     * board's `stateIn` outlive the screen for the life of the process. The `closed` flag would
     * *still be true*, which is what makes the leak invisible — so this asserts the scope and
     * the feed, not the flag.
     */
    @Test
    fun `close from a cancelled coroutine still tears the session down`() = runTest {
        val built = build()

        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.job.cancel()
            built.character.close()
        }
        advanceUntilIdle()
        closer.join()

        assertTrue("the feed must have been stopped despite the cancellation", built.feed.stopped)
        assertFalse("the character's scope must have been cancelled", built.scope.isActive)
        assertTrue(built.character.isClosed.value)
    }
}

/**
 * A [FakeCreatureFeed] whose `stop()` genuinely suspends.
 *
 * The plain fake's `stop()` is a `suspend fun` with no suspension point in it, so it cannot be
 * cancelled and the M9 test would pass against the broken code. The production
 * `DdpCreatureFeed.stop()` reaches `DdpSubscription.stop()`, which hops onto the DDP client's
 * own dispatcher — a real, cancellable suspension. One [yield] models exactly that, and nothing
 * more: the point is that a suspension point exists, not what happens at it.
 */
private class SlowStoppingFeed(
    private val delegate: FakeCreatureFeed = FakeCreatureFeed(),
) : CreatureFeed by delegate {

    val stopped: Boolean get() = delegate.stopped

    override suspend fun stop() {
        yield()
        delegate.stop()
    }

    // Forwarded rather than delegated: these are the fake's own script controls, not part of
    // the `CreatureFeed` interface, so `by delegate` does not carry them.
    fun publish(sheet: CreatureSheet, creatureId: String) = delegate.publish(sheet, creatureId)

    fun goLive() = delegate.goLive()
}
