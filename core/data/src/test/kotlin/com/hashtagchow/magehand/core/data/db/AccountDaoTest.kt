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
 * The real [AccountDao] against real SQLite, in a plain `./gradlew test` run.
 *
 * Why Robolectric: every `androidx.room.Room.*DatabaseBuilder` overload in the
 * **Android** artifact takes a `Context` (verified with `javap` on
 * room-runtime-android 2.8.4 — the context-free KMP builder is not published for
 * the Android target). Robolectric supplies that Context plus a working SQLite,
 * which is what makes DAO coverage possible without an emulator. See
 * docs/verification/WP3.md.
 *
 * SDK 34 rather than 36: it is a long-supported Robolectric image, it is above
 * the app's minSdk 30, and nothing in this DAO is SDK-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDaoTest {

    private lateinit var database: MageHandDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.accountDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun account(
        id: String,
        serverUrl: String = "https://dnd.example.com",
        userId: String = "meteor-$id",
        username: String = "user-$id",
        addedAt: Long = 1L,
        lastUsedAt: Long = 1L,
    ) = AccountEntity(id, serverUrl, userId, username, addedAt, lastUsedAt)

    @Test
    fun `insert and read back a row`() = runTest {
        val row = account("a")
        dao.insert(row)

        assertEquals(row, dao.findById("a"))
        assertEquals(listOf(row), dao.getAll())
        assertNull(dao.findById("nope"))
    }

    @Test
    fun `rows come back most recently used first`() = runTest {
        dao.insert(account("a", userId = "u1", lastUsedAt = 10))
        dao.insert(account("b", userId = "u2", lastUsedAt = 30))
        dao.insert(account("c", userId = "u3", lastUsedAt = 20))

        assertEquals(listOf("b", "c", "a"), dao.getAll().map { it.id })
        assertEquals(listOf("b", "c", "a"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun `findByServerAndUser is the re-login lookup`() = runTest {
        dao.insert(account("a", serverUrl = "https://dicecloud.com", userId = "u1"))
        dao.insert(account("b", serverUrl = "https://dnd.example.com", userId = "u1"))

        assertEquals("a", dao.findByServerAndUser("https://dicecloud.com", "u1")?.id)
        assertEquals("b", dao.findByServerAndUser("https://dnd.example.com", "u1")?.id)
        assertNull(dao.findByServerAndUser("https://dicecloud.com", "u2"))
        // Normalization is what makes this exact match safe — the DAO does not normalize.
        assertNull(dao.findByServerAndUser("https://dicecloud.com/", "u1"))
    }

    @Test
    fun `the unique index really rejects a duplicate server plus user`() = runTest {
        // This is the invariant that keeps re-login from growing duplicate accounts.
        dao.insert(account("a", serverUrl = "https://dicecloud.com", userId = "u1"))

        val thrown = try {
            dao.insert(account("b", serverUrl = "https://dicecloud.com", userId = "u1"))
            null
        } catch (e: Exception) {
            e
        }

        assertTrue("expected a constraint violation, got $thrown", thrown != null)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `upsert replaces the row for an existing primary key`() = runTest {
        dao.upsert(account("a", username = "old", lastUsedAt = 1))
        dao.upsert(account("a", username = "new", lastUsedAt = 99))

        assertEquals(1, dao.getAll().size)
        assertEquals("new", dao.findById("a")!!.username)
        assertEquals(99L, dao.findById("a")!!.lastUsedAt)
    }

    @Test
    fun `touch updates only lastUsedAt`() = runTest {
        dao.insert(account("a", addedAt = 5, lastUsedAt = 5))
        dao.touch("a", 777)

        val row = dao.findById("a")!!
        assertEquals(777L, row.lastUsedAt)
        assertEquals("addedAt must not move", 5L, row.addedAt)
    }

    @Test
    fun `delete removes the row`() = runTest {
        dao.insert(account("a", userId = "u1"))
        dao.insert(account("b", userId = "u2"))

        dao.deleteById("a")
        assertEquals(listOf("b"), dao.getAll().map { it.id })

        dao.delete(dao.findById("b")!!)
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `deleting a missing id is a no-op`() = runTest {
        dao.insert(account("a"))
        dao.deleteById("ghost")
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `observeAll re-emits after a write`() = runTest {
        assertTrue(dao.observeAll().first().isEmpty())
        dao.insert(account("a"))
        assertEquals(listOf("a"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun `the accounts table has no column that could hold a token`() = runTest {
        // A structural guard: docs/design/05-security.md forbids tokens in Room, and
        // a future careless migration is exactly how that rule gets broken.
        val columns = mutableListOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(accounts)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }

        assertEquals(
            setOf("id", "serverUrl", "userId", "username", "addedAt", "lastUsedAt"),
            columns.toSet(),
        )
    }
}
