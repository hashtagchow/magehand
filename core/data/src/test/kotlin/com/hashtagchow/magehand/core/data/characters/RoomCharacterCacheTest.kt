package com.hashtagchow.magehand.core.data.characters

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.model.CharacterSummary

/**
 * The Room-backed character cache — WP5's `TODO(WP4)` seam, closed in WP6.
 *
 * The two behaviours worth a real SQLite round trip are the two an in-memory map got for
 * free and this implementation could plausibly get wrong: `lastOpenedAt` surviving a
 * re-sync (or the start destination silently stops working), and a creature the
 * publication stopped yielding actually disappearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCharacterCacheTest {

    private lateinit var database: MageHandDatabase
    private lateinit var cache: RoomCharacterCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Robolectric's main-thread SQLite plus the test dispatcher: keep the DAO calls
        // on the caller's thread rather than hopping to Dispatchers.IO.
        cache = RoomCharacterCache(database.characterDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = database.close()

    private fun summary(id: String, name: String = "Character $id") = CharacterSummary(
        creatureId = id,
        name = name,
        alignment = null,
        gender = null,
        picture = null,
        owner = "owner-1",
        isOwnedByMe = false,
    )

    @Test
    fun `an empty table reads as null, not as an empty list`() = runTest {
        // The distinction screen 2 needs: "no cache" is a spinner, "cached empty" is not.
        assertNull(cache.read("acc-1"))
    }

    @Test
    fun `what is written comes back`() = runTest {
        cache.write("acc-1", listOf(summary("a"), summary("b")), at = 1_000)

        val cached = assertNotNull(cache.read("acc-1")).let { cache.read("acc-1")!! }
        assertEquals(listOf("a", "b"), cached.characters.map { it.creatureId }.sorted())
        assertEquals(1_000L, cached.cachedAt)
    }

    @Test
    fun `rows are scoped per account`() = runTest {
        cache.write("acc-1", listOf(summary("a")), at = 1)
        cache.write("acc-2", listOf(summary("z")), at = 1)

        assertEquals(listOf("a"), cache.read("acc-1")!!.characters.map { it.creatureId })
        assertEquals(listOf("z"), cache.read("acc-2")!!.characters.map { it.creatureId })
    }

    @Test
    fun `a re-sync does not reset lastOpenedAt`() = runTest {
        cache.write("acc-1", listOf(summary("a"), summary("b")), at = 1)
        cache.markOpened("acc-1", "b", at = 5_000)

        // The publication re-emits — with a renamed character, to prove the row is
        // genuinely rewritten rather than skipped.
        cache.write("acc-1", listOf(summary("a"), summary("b", name = "Renamed")), at = 2)

        assertEquals("b", cache.lastOpenedCreatureId("acc-1"))
        assertEquals(
            "Renamed",
            cache.read("acc-1")!!.characters.single { it.creatureId == "b" }.name,
        )
    }

    @Test
    fun `a creature the publication stopped yielding disappears`() = runTest {
        cache.write("acc-1", listOf(summary("a"), summary("b")), at = 1)
        cache.write("acc-1", listOf(summary("a")), at = 2)

        assertEquals(listOf("a"), cache.read("acc-1")!!.characters.map { it.creatureId })
    }

    @Test
    fun `an account that has never opened a character has no last-opened id`() = runTest {
        cache.write("acc-1", listOf(summary("a")), at = 1)
        // Every row's lastOpenedAt is 0; a zero stamp must not be mistaken for a visit.
        assertNull(cache.lastOpenedCreatureId("acc-1"))
    }

    @Test
    fun `the most recently opened character wins`() = runTest {
        cache.write("acc-1", listOf(summary("a"), summary("b"), summary("c")), at = 1)
        cache.markOpened("acc-1", "a", at = 100)
        cache.markOpened("acc-1", "c", at = 300)
        cache.markOpened("acc-1", "b", at = 200)

        assertEquals("c", cache.lastOpenedCreatureId("acc-1"))
    }

    @Test
    fun `signing out clears only that account`() = runTest {
        cache.write("acc-1", listOf(summary("a")), at = 1)
        cache.write("acc-2", listOf(summary("z")), at = 1)

        cache.clear("acc-1")

        assertNull(cache.read("acc-1"))
        assertNotNull(cache.read("acc-2"))
    }

    @Test
    fun `a cold read reports an unknown cache time rather than inventing one`() = runTest {
        cache.write("acc-1", listOf(summary("a")), at = 7_000)
        assertEquals(7_000L, cache.read("acc-1")!!.cachedAt)

        // A new process sees the same rows and no write time — `characters` has no
        // cachedAt column, and `null` says so honestly.
        val afterRestart = RoomCharacterCache(database.characterDao(), Dispatchers.Unconfined)
        val cached = afterRestart.read("acc-1")!!
        assertTrue(cached.characters.isNotEmpty())
        assertNull(cached.cachedAt)
    }
}
