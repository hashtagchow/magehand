package com.hashtagchow.magehand.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1 → v2 migration, end to end, on real SQLite.
 *
 * The v1 database is not hand-written here: it is created by replaying the **committed**
 * `core/data/schemas/…/1.json` — the exact schema WP3 shipped to any device that already
 * has the app. If someone edits that file (they must not), or edits [MIGRATION_1_2] so it
 * no longer produces v2's schema, Room's own validator fails this test rather than a
 * user's upgrade.
 *
 * Robolectric for the same reason as `AccountDaoTest`: every `Room.*databaseBuilder`
 * overload in the Android artifact needs a `Context` (docs/verification/WP3.md §5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MageHandDatabaseMigrationTest {

    private val databaseName = "migration-test.db"
    private lateinit var context: Context
    private lateinit var databaseFile: File
    private var database: MageHandDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        deleteDatabaseFiles()
    }

    @After
    fun tearDown() {
        database?.close()
        deleteDatabaseFiles()
    }

    private fun deleteDatabaseFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { File(databaseFile.path + it).delete() }
    }

    /** Replays the committed v1 schema, including Room's `room_master_table` identity row. */
    private fun createVersion1Database(): SQLiteDatabase {
        val repoRoot = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        val schemaFile = File(
            repoRoot,
            "core/data/schemas/com.hashtagchow.magehand.core.data.db.MageHandDatabase/1.json",
        )
        assertTrue("missing committed v1 schema at ${schemaFile.path}", schemaFile.isFile)

        val schema = (Json.parseToJsonElement(schemaFile.readText()) as JsonObject)["database"]!!.jsonObject
        assertEquals("committed schema is not version 1", 1, schema["version"]!!.jsonPrimitive.content.toInt())

        val db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val table = entity.jsonObject
            val name = table["tableName"]!!.jsonPrimitive.content
            db.execSQL(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            (table["indices"] as? JsonArray).orEmpty().forEach { index ->
                db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            }
        }
        schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = 1
        return db
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    private fun openVersion2(): MageHandDatabase =
        Room.databaseBuilder(context, MageHandDatabase::class.java, databaseName)
            .addMigrations(*MageHandDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    private fun tableNames(db: MageHandDatabase): Set<String> =
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table'")
            .use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

    @Test
    fun `migrating v1 to v2 keeps every account row intact`() = runTest {
        createVersion1Database().use { v1 ->
            v1.execSQL(
                "INSERT INTO accounts (id, serverUrl, userId, username, addedAt, lastUsedAt) " +
                    "VALUES ('acc-1', 'https://dnd.example.com', 'FakeDmUser23456ab', 'DungeonMaster', 10, 20)",
            )
        }

        // Opening with the migration registered is what actually runs it — and Room
        // validates the resulting schema against v2 before handing the database over.
        val db = openVersion2()
        val accounts = db.accountDao().getAll()

        assertEquals(1, accounts.size)
        with(accounts.single()) {
            assertEquals("acc-1", id)
            assertEquals("https://dnd.example.com", serverUrl)
            assertEquals("FakeDmUser23456ab", userId)
            assertEquals("DungeonMaster", username)
            assertEquals(10L, addedAt)
            assertEquals(20L, lastUsedAt)
        }
        assertEquals(2, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `migrating v1 to v2 creates the four WP4 tables`() = runTest {
        createVersion1Database().close()

        val db = openVersion2()
        val tables = tableNames(db)

        assertTrue("accounts must survive", "accounts" in tables)
        listOf("characters", "snapshots", "tracker_prefs", "theme_prefs").forEach {
            assertTrue("missing table $it after migration, got $tables", it in tables)
        }
    }

    @Test
    fun `the migrated database is usable by every new DAO`() = runTest {
        createVersion1Database().close()
        val db = openVersion2()

        db.characterDao().upsert(
            CharacterEntity("acc-1", "creature-1", "Sabriel", null, "owner-1", true, 5),
        )
        db.snapshotDao().upsert(SnapshotEntity("acc-1", "creature-1", byteArrayOf(1, 2, 3), 7))
        db.trackerPrefDao().upsert(TrackerPrefEntity("acc-1", "creature-1", "prop-1", true, false, 3))
        db.themePrefDao().upsert(ThemePrefEntity("acc-1", "creature-1", "#7C4DFF"))

        assertEquals("Sabriel", db.characterDao().find("acc-1", "creature-1")?.name)
        assertNotNull(db.snapshotDao().find("acc-1", "creature-1"))
        assertEquals(3, db.trackerPrefDao().get("acc-1", "creature-1").single().sortIndex)
        assertEquals("#7C4DFF", db.themePrefDao().find("acc-1", "creature-1")?.accentColor)
    }

    @Test
    fun `a fresh install creates v2 directly and needs no migration`() = runTest {
        // No v1 file at all: Room must be able to build the whole schema from scratch.
        val db = openVersion2()
        val tables = tableNames(db)
        assertTrue(tables.containsAll(listOf("accounts", "characters", "snapshots", "tracker_prefs", "theme_prefs")))
        assertEquals(0, db.accountDao().getAll().size)
    }

    @Test
    fun `reopening an already migrated database does not run the migration again`() = runTest {
        createVersion1Database().close()
        openVersion2().also { it.openHelper.readableDatabase }.close()
        database = null

        // Second open: still version 2, nothing re-created, no "table already exists".
        val reopened = openVersion2()
        assertEquals(2, reopened.openHelper.readableDatabase.version)
        assertTrue("characters" in tableNames(reopened))
    }
}
