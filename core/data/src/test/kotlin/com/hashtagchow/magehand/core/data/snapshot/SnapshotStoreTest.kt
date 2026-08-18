package com.hashtagchow.magehand.core.data.snapshot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
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
import com.hashtagchow.magehand.core.data.api.ApiException
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.tracker.Fixtures
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine

/**
 * The snapshot pipeline end to end: `fetch → gzip → Room → inflate → TrackerEngine`
 * (docs/design/06-offline-and-sync.md §Snapshot lifecycle), against real SQLite and the
 * real 1.1 MB live capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotStoreTest {

    private lateinit var database: MageHandDatabase
    private lateinit var api: FakeDiceCloudApi
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeDiceCloudApi()
    }

    @After
    fun tearDown() = database.close()

    private fun store(maxPerAccount: Int = SnapshotStore.DEFAULT_MAX_PER_ACCOUNT) = SnapshotStore(
        snapshotDao = database.snapshotDao(),
        api = api,
        // Unconfined, not a TestDispatcher: a TestDispatcher built outside `runTest` has
        // its own scheduler that nothing here advances, so `withContext` would deadlock.
        ioDispatcher = Dispatchers.Unconfined,
        now = { clock },
        maxPerAccount = maxPerAccount,
    )

    @Test
    fun `gzip round-trips the live capture byte for byte`() {
        val original = Fixtures.sabrielBody
        val deflated = Gzip.deflate(original)
        assertEquals(original, Gzip.inflate(deflated))
        assertTrue(
            "expected the 1.1 MB sheet to compress hard, got ${deflated.size} bytes",
            deflated.size < original.length / 5,
        )
    }

    @Test
    fun `gzip handles an empty body and non-ascii names`() {
        assertEquals("", Gzip.inflate(Gzip.deflate("")))
        val text = """{"name":"Elowen — Brightmantle ✦"}"""
        assertEquals(text, Gzip.inflate(Gzip.deflate(text)))
    }

    @Test
    fun `fetch, gzip, store, inflate, engine`() = runTest {
        api.snapshotResult = { Fixtures.sabrielBody }
        val store = store()

        val fetched = store.refresh("acc-1", "https://dnd.example.com", "tok", Fixtures.SABRIEL_ID)

        assertEquals(
            listOf(Triple("https://dnd.example.com", "tok", Fixtures.SABRIEL_ID)),
            api.snapshotCalls,
        )
        assertEquals(1_000L, fetched.fetchedAt)

        // Stored gzipped, not raw.
        val row = database.snapshotDao().find("acc-1", Fixtures.SABRIEL_ID)!!
        assertTrue(
            "snapshot was not compressed: ${row.json.size} vs ${Fixtures.sabrielBody.length}",
            row.json.size < Fixtures.sabrielBody.length / 5,
        )

        // Inflates back into exactly the board the REST path produced.
        val sheet = store.loadSheet("acc-1", Fixtures.SABRIEL_ID)!!
        assertEquals(TrackerEngine.build(Fixtures.sabrielSheet()), TrackerEngine.build(sheet))
        assertEquals(4, TrackerEngine.build(sheet).slots.single { it.name == "1st Level" }.total)
    }

    @Test
    fun `a failed fetch leaves the previous snapshot alone`() = runTest {
        api.snapshotResult = { Fixtures.sabrielBody }
        val store = store()
        store.refresh("acc-1", "https://s", "tok", Fixtures.SABRIEL_ID)

        api.snapshotResult = { throw ApiException.ServerUnreachable("https://s", null) }
        val failure = runCatching { store.refresh("acc-1", "https://s", "tok", Fixtures.SABRIEL_ID) }

        assertTrue(failure.exceptionOrNull() is ApiException.ServerUnreachable)
        // Still the good one, still readable.
        assertEquals(1_000L, store.load("acc-1", Fixtures.SABRIEL_ID)!!.fetchedAt)
        assertEquals(31, TrackerEngine.build(store.loadSheet("acc-1", Fixtures.SABRIEL_ID)!!).allItems.size)
    }

    @Test
    fun `the cache holds at most ten characters per account, LRU`() = runTest {
        val store = store()
        repeat(12) { index ->
            clock = 1_000L + index
            store.store("acc-1", "creature-$index", """{"creatureProperties":[]}""")
        }
        clock = 1L
        store.store("acc-2", "elsewhere", """{"creatureProperties":[]}""")

        val cached = store.cachedCreatureIds("acc-1")
        assertEquals(SnapshotStore.DEFAULT_MAX_PER_ACCOUNT, cached.size)
        assertEquals("creature-11", cached.first())
        assertNull(store.load("acc-1", "creature-0"))
        assertNull(store.load("acc-1", "creature-1"))
        // Per account, not global.
        assertEquals(listOf("elsewhere"), store.cachedCreatureIds("acc-2"))
    }

    @Test
    fun `re-storing an existing character refreshes its place in the LRU`() = runTest {
        val store = store(maxPerAccount = 3)
        listOf("a", "b", "c").forEachIndexed { index, id ->
            clock = 100L + index
            store.store("acc-1", id, "{}")
        }
        // Touch "a" so it is newest, then add a fourth — "b" must be the one evicted.
        clock = 200
        store.store("acc-1", "a", "{}")
        clock = 201
        store.store("acc-1", "d", "{}")

        assertEquals(listOf("d", "a", "c"), store.cachedCreatureIds("acc-1"))
        assertNull(store.load("acc-1", "b"))
    }

    @Test
    fun `loading a character that was never synced returns null rather than throwing`() = runTest {
        assertNull(store().load("acc-1", "never-seen"))
        assertNull(store().loadSheet("acc-1", "never-seen"))
    }

    @Test
    fun `clearing an account removes its snapshots only`() = runTest {
        val store = store()
        store.store("acc-1", "a", "{}")
        store.store("acc-2", "a", "{}")

        store.clearAccount("acc-1")

        assertTrue(store.cachedCreatureIds("acc-1").isEmpty())
        assertEquals(listOf("a"), store.cachedCreatureIds("acc-2"))
    }

    @Test
    fun `deleting one cached character keeps the rest`() = runTest {
        val store = store()
        store.store("acc-1", "a", "{}")
        store.store("acc-1", "b", "{}")
        store.delete("acc-1", "a")
        assertEquals(listOf("b"), store.cachedCreatureIds("acc-1"))
        assertFalse(store.cachedCreatureIds("acc-1").contains("a"))
    }
}
