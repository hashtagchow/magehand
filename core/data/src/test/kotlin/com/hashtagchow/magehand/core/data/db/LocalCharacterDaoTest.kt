package com.hashtagchow.magehand.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * [LocalCharacterDao] against real SQLite, same Robolectric route as `TrackerDaoTest`
 * (docs/verification/WP3.md §5).
 *
 * What this buys beyond Room's compile-time query verification: the cascade is proven to
 * fire, the save transaction is proven to delete the rows a re-save dropped *and* to keep the
 * `current` value of the ones it kept, and 09 decision 7's rest rule is proven at the
 * statement — short leaves long-rest rows alone, and neither touches a `"none"` row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCharacterDaoTest {

    private lateinit var database: MageHandDatabase
    private lateinit var dao: LocalCharacterDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.localCharacterDao()
    }

    @After
    fun tearDown() = database.close()

    private fun character(
        id: String,
        name: String = "character-$id",
        maxHp: Int = 20,
        currentHp: Int = maxHp,
        createdAt: Long = 0,
    ) = LocalCharacterEntity(
        id = id,
        name = name,
        level = 3,
        strength = 10,
        dexterity = 12,
        constitution = 14,
        intelligence = 8,
        wisdom = 13,
        charisma = 16,
        maxHp = maxHp,
        currentHp = currentHp,
        armorClass = 15,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun row(
        id: String,
        characterId: String = "c-1",
        kind: LocalRowKind = LocalRowKind.RESOURCE,
        label: String = "row-$id",
        total: Int = 3,
        current: Int = total,
        reset: ResetRule? = null,
        sortIndex: Int = 0,
    ) = LocalTrackerRowEntity(
        id = id,
        characterId = characterId,
        kind = kind.storedValue,
        label = label,
        total = total,
        current = current,
        resetRule = reset?.wireValue ?: LocalTrackerRowEntity.RESET_NONE,
        sortIndex = sortIndex,
    )

    // --- characters ---------------------------------------------------------

    @Test
    fun `characters list newest created first`() = runTest {
        dao.upsert(character("c-1", createdAt = 10))
        dao.upsert(character("c-2", createdAt = 30))
        dao.upsert(character("c-3", createdAt = 20))

        assertEquals(listOf("c-2", "c-3", "c-1"), dao.getAll().map { it.id })
        assertEquals(listOf("c-2", "c-3", "c-1"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun `observing one character emits it and emits null when it is gone`() = runTest {
        dao.upsert(character("c-1", name = "Brambles"))
        assertEquals("Brambles", dao.observe("c-1").first()?.name)

        dao.delete("c-1")
        assertNull(dao.observe("c-1").first())
    }

    @Test
    fun `upserting an existing id updates rather than duplicating`() = runTest {
        dao.upsert(character("c-1", name = "Brambles"))
        dao.upsert(character("c-1", name = "Brambles the Second"))

        assertEquals(1, dao.count())
        assertEquals("Brambles the Second", dao.find("c-1")?.name)
    }

    /** 09 decision 1: nothing here is account-keyed, so nothing here has an account column. */
    @Test
    fun `local characters carry no account column`() = runTest {
        val columns = database.openHelper.readableDatabase
            .query("SELECT * FROM local_characters LIMIT 0")
            .use { it.columnNames.toSet() }

        assertTrue(
            "an accountId column would be the sentinel account decision 1 forbids: $columns",
            columns.none { it.contains("account", ignoreCase = true) },
        )
    }

    // --- rows ---------------------------------------------------------------

    @Test
    fun `rows are scoped per character and ordered by sortIndex`() = runTest {
        dao.upsert(character("c-1"))
        dao.upsert(character("c-2"))
        dao.upsertRows(
            listOf(
                row("r-3", sortIndex = 2),
                row("r-1", sortIndex = 0),
                row("r-2", sortIndex = 1),
                row("r-9", characterId = "c-2", sortIndex = 0),
            ),
        )

        assertEquals(listOf("r-1", "r-2", "r-3"), dao.getRows("c-1").map { it.id })
        assertEquals(listOf("r-9"), dao.getRows("c-2").map { it.id })
        assertEquals(listOf("r-1", "r-2", "r-3"), dao.observeRows("c-1").first().map { it.id })
    }

    @Test
    fun `deleting a character cascades to its rows and leaves other characters alone`() = runTest {
        dao.upsert(character("c-1"))
        dao.upsert(character("c-2"))
        dao.upsertRows(listOf(row("r-1"), row("r-2"), row("r-9", characterId = "c-2")))

        dao.delete("c-1")

        assertTrue(dao.getRows("c-1").isEmpty())
        assertEquals(listOf("r-9"), dao.getRows("c-2").map { it.id })
        assertNotNull(dao.find("c-2"))
    }

    @Test
    fun `reordering assigns dense indexes in the given order`() = runTest {
        dao.upsert(character("c-1"))
        dao.upsertRows(listOf(row("r-1", sortIndex = 0), row("r-2", sortIndex = 1), row("r-3", sortIndex = 2)))

        dao.reorderRows("c-1", listOf("r-3", "r-1", "r-2"))

        assertEquals(listOf("r-3", "r-1", "r-2"), dao.getRows("c-1").map { it.id })
        assertEquals(listOf(0, 1, 2), dao.getRows("c-1").map { it.sortIndex })
    }

    // --- save transaction ---------------------------------------------------

    @Test
    fun `save writes the character and its rows together`() = runTest {
        dao.save(character("c-1"), listOf(row("r-1"), row("r-2", sortIndex = 1)))

        assertNotNull(dao.find("c-1"))
        assertEquals(listOf("r-1", "r-2"), dao.getRows("c-1").map { it.id })
    }

    @Test
    fun `re-saving drops the rows the form no longer carries and keeps the rest`() = runTest {
        dao.save(character("c-1"), listOf(row("r-1"), row("r-2", sortIndex = 1)))
        // The player spent two charges of r-1 before re-opening the form.
        dao.setRowCurrent("r-1", 1)

        dao.save(character("c-1"), listOf(row("r-1", current = 1), row("r-3", sortIndex = 1)))

        assertEquals(listOf("r-1", "r-3"), dao.getRows("c-1").map { it.id })
        assertEquals(1, dao.findRow("r-1")?.current)
        assertNull("r-2 should be gone", dao.findRow("r-2"))
    }

    @Test
    fun `saving with no rows removes every row`() = runTest {
        dao.save(character("c-1"), listOf(row("r-1"), row("r-2", sortIndex = 1)))

        dao.save(character("c-1"), emptyList())

        assertTrue(dao.getRows("c-1").isEmpty())
        assertNotNull("the character itself must survive", dao.find("c-1"))
    }

    // --- rest (09 decision 7) -----------------------------------------------

    private suspend fun seedRestFixture() {
        dao.save(
            character("c-1"),
            listOf(
                row("short-1", label = "Ki", total = 4, current = 0, reset = ResetRule.SHORT_REST),
                row("long-1", label = "Rage", total = 3, current = 0, reset = ResetRule.LONG_REST),
                row("none-1", label = "Luck", total = 2, current = 0, reset = null),
            ),
        )
    }

    private fun shortRules() = listOf(ResetRule.SHORT_REST.wireValue)

    private fun longRules() = listOf(ResetRule.SHORT_REST.wireValue, ResetRule.LONG_REST.wireValue)

    @Test
    fun `a short rest refills only short-rest rows`() = runTest {
        seedRestFixture()

        dao.rest("c-1", shortRules(), at = 99)

        assertEquals(4, dao.findRow("short-1")?.current)
        assertEquals(0, dao.findRow("long-1")?.current)
        assertEquals(0, dao.findRow("none-1")?.current)
        assertEquals(99L, dao.find("c-1")?.updatedAt)
    }

    @Test
    fun `a long rest refills short and long rows but never a none row`() = runTest {
        seedRestFixture()

        dao.rest("c-1", longRules(), at = 99)

        assertEquals(4, dao.findRow("short-1")?.current)
        assertEquals(3, dao.findRow("long-1")?.current)
        assertEquals("a no-reset row survives every rest", 0, dao.findRow("none-1")?.current)
    }

    @Test
    fun `a rest does not reach another character's rows`() = runTest {
        seedRestFixture()
        dao.upsert(character("c-2"))
        dao.upsertRows(
            listOf(row("other", characterId = "c-2", total = 5, current = 0, reset = ResetRule.SHORT_REST)),
        )

        dao.rest("c-1", longRules(), at = 1)

        assertEquals(0, dao.findRow("other")?.current)
    }

    // --- targeted value writes ----------------------------------------------

    @Test
    fun `setting an item quantity moves total with it`() = runTest {
        dao.save(character("c-1"), listOf(row("r-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))

        dao.setRowQuantity("r-1", 7)

        with(dao.findRow("r-1")!!) {
            assertEquals(7, current)
            assertEquals("an item has no ceiling, so total tracks quantity", 7, total)
        }
    }

    @Test
    fun `setting a row current leaves its total alone`() = runTest {
        dao.save(character("c-1"), listOf(row("r-1", total = 4, current = 4)))

        dao.setRowCurrent("r-1", 1)

        with(dao.findRow("r-1")!!) {
            assertEquals(1, current)
            assertEquals(4, total)
        }
    }

    @Test
    fun `setting hit points stamps updatedAt`() = runTest {
        dao.save(character("c-1", maxHp = 20, currentHp = 20), emptyList())

        dao.setCurrentHp("c-1", 8, at = 4242)

        with(dao.find("c-1")!!) {
            assertEquals(8, currentHp)
            assertEquals(20, maxHp)
            assertEquals(4242L, updatedAt)
        }
    }
}
