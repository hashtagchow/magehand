package com.hashtagchow.magehand.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The four WP4 DAOs against real SQLite, same Robolectric route as `AccountDaoTest`
 * (docs/verification/WP3.md §5).
 *
 * What this buys beyond Room's compile-time query verification: the composite primary
 * keys are proven to scope rows per (account, creature), the LRU eviction statement is
 * proven to keep the *newest* N, and per-account deletion is proven not to touch a second
 * account's rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDaoTest {

    private lateinit var database: MageHandDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun character(
        accountId: String = "acc-1",
        creatureId: String,
        name: String = "creature-$creatureId",
        lastOpenedAt: Long = 0,
    ) = CharacterEntity(accountId, creatureId, name, null, "owner", true, lastOpenedAt)

    private fun snapshot(
        accountId: String = "acc-1",
        creatureId: String,
        fetchedAt: Long,
    ) = SnapshotEntity(accountId, creatureId, byteArrayOf(1, 2, 3), fetchedAt)

    // --- characters ---------------------------------------------------------

    @Test
    fun `characters are scoped per account and ordered most recently opened first`() = runTest {
        val dao = database.characterDao()
        dao.upsert(
            listOf(
                character(creatureId = "a", lastOpenedAt = 10),
                character(creatureId = "b", lastOpenedAt = 30),
                character(accountId = "acc-2", creatureId = "c", lastOpenedAt = 99),
            ),
        )

        assertEquals(listOf("b", "a"), dao.getForAccount("acc-1").map { it.creatureId })
        assertEquals(listOf("c"), dao.getForAccount("acc-2").map { it.creatureId })
    }

    @Test
    fun `the same creature under two accounts is two rows`() = runTest {
        val dao = database.characterDao()
        dao.upsert(character(accountId = "acc-1", creatureId = "shared", name = "as DM"))
        dao.upsert(character(accountId = "acc-2", creatureId = "shared", name = "as player"))

        assertEquals("as DM", dao.find("acc-1", "shared")?.name)
        assertEquals("as player", dao.find("acc-2", "shared")?.name)
    }

    @Test
    fun `deleteMissing prunes creatures the publication no longer yields`() = runTest {
        val dao = database.characterDao()
        dao.upsert(listOf(character(creatureId = "a"), character(creatureId = "b"), character(creatureId = "c")))

        dao.deleteMissing("acc-1", listOf("a", "c"))

        assertEquals(setOf("a", "c"), dao.getForAccount("acc-1").map { it.creatureId }.toSet())
    }

    @Test
    fun `touch updates lastOpenedAt and the observer re-emits`() = runTest {
        val dao = database.characterDao()
        dao.upsert(character(creatureId = "a", lastOpenedAt = 1))
        dao.upsert(character(creatureId = "b", lastOpenedAt = 2))

        dao.touch("acc-1", "a", 99)

        assertEquals(listOf("a", "b"), dao.observeForAccount("acc-1").first().map { it.creatureId })
    }

    // --- snapshots ----------------------------------------------------------

    @Test
    fun `a second snapshot for the same creature replaces the first`() = runTest {
        val dao = database.snapshotDao()
        dao.upsert(snapshot(creatureId = "a", fetchedAt = 1))
        dao.upsert(SnapshotEntity("acc-1", "a", byteArrayOf(9), 2))

        assertEquals(1, dao.countForAccount("acc-1"))
        val row = dao.find("acc-1", "a")!!
        assertEquals(2L, row.fetchedAt)
        assertTrue(byteArrayOf(9).contentEquals(row.json))
    }

    @Test
    fun `LRU eviction keeps the ten newest snapshots per account`() = runTest {
        val dao = database.snapshotDao()
        // 12 snapshots, fetched at 1..12.
        (1..12).forEach { dao.upsert(snapshot(creatureId = "c$it", fetchedAt = it.toLong())) }
        dao.upsert(snapshot(accountId = "acc-2", creatureId = "other", fetchedAt = 1))

        dao.evictBeyond("acc-1", limit = 10)

        val survivors = dao.creatureIdsByRecency("acc-1")
        assertEquals(10, survivors.size)
        assertEquals("c12", survivors.first())
        assertEquals("c3", survivors.last())
        assertNull("the two oldest must be gone", dao.find("acc-1", "c1"))
        assertNull(dao.find("acc-1", "c2"))
        // Another account's cache is not part of this account's budget.
        assertEquals(1, dao.countForAccount("acc-2"))
    }

    @Test
    fun `eviction is a no-op below the budget`() = runTest {
        val dao = database.snapshotDao()
        (1..3).forEach { dao.upsert(snapshot(creatureId = "c$it", fetchedAt = it.toLong())) }
        dao.evictBeyond("acc-1", limit = 10)
        assertEquals(3, dao.countForAccount("acc-1"))
    }

    @Test
    fun `observeFetchedAt reports null before the first sync and the time after it`() = runTest {
        val dao = database.snapshotDao()
        assertNull(dao.observeFetchedAt("acc-1", "a").first())
        dao.upsert(snapshot(creatureId = "a", fetchedAt = 42))
        assertEquals(42L, dao.observeFetchedAt("acc-1", "a").first())
    }

    // --- tracker_prefs / theme_prefs ---------------------------------------

    @Test
    fun `tracker prefs are keyed by account, creature and property`() = runTest {
        val dao = database.trackerPrefDao()
        dao.upsert(
            listOf(
                TrackerPrefEntity("acc-1", "cr-1", "p1", pinned = true, hidden = false, sortIndex = null),
                TrackerPrefEntity("acc-1", "cr-1", "p2", pinned = false, hidden = true, sortIndex = 4),
                TrackerPrefEntity("acc-1", "cr-2", "p1", pinned = false, hidden = false, sortIndex = null),
            ),
        )

        assertEquals(2, dao.get("acc-1", "cr-1").size)
        assertEquals(1, dao.get("acc-1", "cr-2").size)

        // Upsert on the same key updates rather than duplicating.
        dao.upsert(TrackerPrefEntity("acc-1", "cr-1", "p1", pinned = false, hidden = true, sortIndex = 1))
        val updated = dao.get("acc-1", "cr-1").single { it.propertyId == "p1" }
        assertTrue(updated.hidden)
        assertEquals(1, updated.sortIndex)

        dao.delete("acc-1", "cr-1", "p1")
        assertEquals(listOf("p2"), dao.get("acc-1", "cr-1").map { it.propertyId })
    }

    @Test
    fun `prefs survive a snapshot being replaced - they are a separate table by design`() = runTest {
        database.trackerPrefDao().upsert(
            TrackerPrefEntity("acc-1", "cr-1", "p1", pinned = true, hidden = false, sortIndex = null),
        )
        database.snapshotDao().upsert(snapshot(creatureId = "cr-1", fetchedAt = 1))
        database.snapshotDao().upsert(snapshot(creatureId = "cr-1", fetchedAt = 2))

        assertEquals(1, database.trackerPrefDao().get("acc-1", "cr-1").size)
    }

    @Test
    fun `theme prefs round-trip and allow a null accent`() = runTest {
        val dao = database.themePrefDao()
        dao.upsert(ThemePrefEntity("acc-1", "cr-1", "#7C4DFF"))
        assertEquals("#7C4DFF", dao.observe("acc-1", "cr-1").first()?.accentColor)

        dao.upsert(ThemePrefEntity("acc-1", "cr-1", null))
        assertNull(dao.find("acc-1", "cr-1")?.accentColor)
    }

    // --- sign-out -----------------------------------------------------------

    @Test
    fun `deleting an account's rows leaves other accounts untouched`() = runTest {
        database.characterDao().upsert(character(creatureId = "a"))
        database.characterDao().upsert(character(accountId = "acc-2", creatureId = "a"))
        database.snapshotDao().upsert(snapshot(creatureId = "a", fetchedAt = 1))
        database.snapshotDao().upsert(snapshot(accountId = "acc-2", creatureId = "a", fetchedAt = 1))
        database.trackerPrefDao().upsert(
            TrackerPrefEntity("acc-1", "a", "p", pinned = true, hidden = false, sortIndex = null),
        )
        database.themePrefDao().upsert(ThemePrefEntity("acc-1", "a", "#000000"))

        database.characterDao().deleteForAccount("acc-1")
        database.snapshotDao().deleteForAccount("acc-1")
        database.trackerPrefDao().deleteForAccount("acc-1")
        database.themePrefDao().deleteForAccount("acc-1")

        assertTrue(database.characterDao().getForAccount("acc-1").isEmpty())
        assertNull(database.snapshotDao().find("acc-1", "a"))
        assertTrue(database.trackerPrefDao().get("acc-1", "a").isEmpty())
        assertNull(database.themePrefDao().find("acc-1", "a"))

        assertEquals(1, database.characterDao().getForAccount("acc-2").size)
        assertEquals(1, database.snapshotDao().countForAccount("acc-2"))
    }

    @Test
    fun `entity mappers round-trip through the domain types`() {
        // lastOpenedAt lives only on the entity — the DAO orders by it in SQL, so the
        // domain type does not carry it and the round-trip has to be told.
        val entity = CharacterEntity("acc-1", "cr-1", "Sabriel", "pic", "owner-1", true, 7)
        assertEquals(entity, entity.toDomain().toEntity("acc-1", lastOpenedAt = 7))

        val pref = TrackerPrefEntity("acc-1", "cr-1", "p1", pinned = true, hidden = true, sortIndex = 2)
        assertEquals(pref, pref.toDomain().toEntity("acc-1", "cr-1"))
    }
}
