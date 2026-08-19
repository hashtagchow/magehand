package com.hashtagchow.magehand.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hashtagchow.magehand.core.data.local.LocalInventoryBoard
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.EquipGroup
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File

/**
 * Every migration, end to end, on real SQLite.
 *
 * No starting database here is hand-written: each one is created by replaying the **committed**
 * `core/data/schemas/…/<version>.json` — the exact schema that shipped to any device already
 * running that version. If someone edits one of those files (they must not), or edits a
 * migration so it no longer produces the next version's schema, Room's own validator fails
 * this test rather than a user's upgrade.
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

    /**
     * Replays a committed schema, including Room's `room_master_table` identity row.
     *
     * Parameterised over the version rather than copied per version: v2 → v3 needs the exact
     * same replay v1 → v2 needed, and a second copy of it is a second place for the schema
     * path to go stale.
     */
    private fun createDatabaseAtVersion(version: Int): SQLiteDatabase {
        val repoRoot = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        val schemaFile = File(
            repoRoot,
            "core/data/schemas/com.hashtagchow.magehand.core.data.db.MageHandDatabase/$version.json",
        )
        assertTrue("missing committed v$version schema at ${schemaFile.path}", schemaFile.isFile)

        val schema = (Json.parseToJsonElement(schemaFile.readText()) as JsonObject)["database"]!!.jsonObject
        assertEquals(
            "committed schema is not version $version",
            version,
            schema["version"]!!.jsonPrimitive.content.toInt(),
        )

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
        db.version = version
        return db
    }

    private fun createVersion1Database(): SQLiteDatabase = createDatabaseAtVersion(1)

    private fun createVersion2Database(): SQLiteDatabase = createDatabaseAtVersion(2)

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    /**
     * Opens at the **current** version with every migration registered. Opening is what
     * actually runs them — and Room validates the resulting schema against its compiled
     * expectation before handing the database over, which is the assertion that matters.
     */
    private fun openCurrent(): MageHandDatabase =
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

    // --- v1 → current -------------------------------------------------------

    @Test
    fun `migrating from v1 keeps every account row intact`() = runTest {
        createVersion1Database().use { v1 ->
            v1.execSQL(
                "INSERT INTO accounts (id, serverUrl, userId, username, addedAt, lastUsedAt) " +
                    "VALUES ('acc-1', 'https://dnd.example.com', 'FakeDmUser23456ab', 'DungeonMaster', 10, 20)",
            )
        }

        val db = openCurrent()
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
        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)
    }

    @Test
    fun `migrating from v1 creates the four WP4 tables`() = runTest {
        createVersion1Database().close()

        val db = openCurrent()
        val tables = tableNames(db)

        assertTrue("accounts must survive", "accounts" in tables)
        listOf("characters", "snapshots", "tracker_prefs", "theme_prefs").forEach {
            assertTrue("missing table $it after migration, got $tables", it in tables)
        }
    }

    @Test
    fun `the migrated database is usable by every new DAO`() = runTest {
        createVersion1Database().close()
        val db = openCurrent()

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

    /**
     * The two-step path. A device that never opened the 1.0.x build sitting on v2 is not the
     * only upgrade shape: one still on v1 must cross both migrations in one open, and the
     * chained result must satisfy the same validator.
     */
    @Test
    fun `migrating from v1 runs both migrations and lands on the current version`() = runTest {
        createVersion1Database().use { v1 ->
            v1.execSQL(
                "INSERT INTO accounts (id, serverUrl, userId, username, addedAt, lastUsedAt) " +
                    "VALUES ('acc-1', 'https://dnd.example.com', 'user', 'name', 1, 2)",
            )
        }

        val db = openCurrent()

        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)
        assertEquals(1, db.accountDao().getAll().size)
        assertTrue("local_characters" in tableNames(db))
    }

    // --- v2 → v3 (FR-5) -----------------------------------------------------

    /**
     * The migration 09 decision 2 specifies, against the schema that is actually in the
     * field: 1.0.3 shipped v2, so this is the upgrade every existing install will take.
     *
     * Every v2 table is populated first and read back after, byte for byte. "Additive" is a
     * claim about what did *not* happen, and the only way to test it is to have something
     * there that could have been lost.
     */
    @Test
    fun `migrating v2 to v3 preserves every existing row`() = runTest {
        createVersion2Database().use { v2 ->
            v2.execSQL(
                "INSERT INTO accounts (id, serverUrl, userId, username, addedAt, lastUsedAt) " +
                    "VALUES ('acc-1', 'https://dnd.example.com', 'FakeDmUser23456ab', 'DungeonMaster', 10, 20)",
            )
            v2.execSQL(
                "INSERT INTO characters (accountId, creatureId, name, picture, owner, isOwned, lastOpenedAt) " +
                    "VALUES ('acc-1', 'creature-1', 'Sabriel', 'pic.png', 'owner-1', 1, 55)",
            )
            v2.execSQL(
                "INSERT INTO snapshots (accountId, creatureId, json, fetchedAt) " +
                    "VALUES ('acc-1', 'creature-1', X'010203', 77)",
            )
            v2.execSQL(
                "INSERT INTO tracker_prefs (accountId, creatureId, propertyId, pinned, hidden, sortIndex) " +
                    "VALUES ('acc-1', 'creature-1', 'prop-1', 1, 0, 3)",
            )
            v2.execSQL(
                "INSERT INTO theme_prefs (accountId, creatureId, accentColor) " +
                    "VALUES ('acc-1', 'creature-1', '#7C4DFF')",
            )
        }

        val db = openCurrent()

        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)

        with(db.accountDao().getAll().single()) {
            assertEquals("acc-1", id)
            assertEquals("https://dnd.example.com", serverUrl)
            assertEquals("FakeDmUser23456ab", userId)
            assertEquals("DungeonMaster", username)
            assertEquals(10L, addedAt)
            assertEquals(20L, lastUsedAt)
        }

        with(db.characterDao().find("acc-1", "creature-1")!!) {
            assertEquals("Sabriel", name)
            assertEquals("pic.png", picture)
            assertEquals("owner-1", owner)
            assertTrue(isOwned)
            assertEquals(55L, lastOpenedAt)
        }

        with(db.snapshotDao().find("acc-1", "creature-1")!!) {
            assertTrue("snapshot bytes changed", byteArrayOf(1, 2, 3).contentEquals(json))
            assertEquals(77L, fetchedAt)
        }

        with(db.trackerPrefDao().get("acc-1", "creature-1").single()) {
            assertEquals("prop-1", propertyId)
            assertTrue(pinned)
            assertEquals(false, hidden)
            assertEquals(3, sortIndex)
        }

        assertEquals("#7C4DFF", db.themePrefDao().find("acc-1", "creature-1")?.accentColor)
    }

    @Test
    fun `migrating v2 to v3 creates both local tables and leaves the others alone`() = runTest {
        createVersion2Database().close()

        val db = openCurrent()
        val tables = tableNames(db)

        listOf("accounts", "characters", "snapshots", "tracker_prefs", "theme_prefs").forEach {
            assertTrue("v2 table $it must survive, got $tables", it in tables)
        }
        listOf("local_characters", "local_tracker_rows").forEach {
            assertTrue("missing table $it after migration, got $tables", it in tables)
        }
    }

    @Test
    fun `the migrated database is usable by the local character DAO`() = runTest {
        createVersion2Database().close()
        val db = openCurrent()
        val dao = db.localCharacterDao()

        dao.save(
            LocalCharacterEntity(
                id = "local-1",
                name = "Brambles",
                level = 3,
                strength = 8,
                dexterity = 14,
                constitution = 12,
                intelligence = 16,
                wisdom = 10,
                charisma = 13,
                maxHp = 22,
                currentHp = 22,
                armorClass = 15,
                createdAt = 100,
                updatedAt = 100,
            ),
            listOf(
                LocalTrackerRowEntity("row-1", "local-1", "slot", "1st Level", 4, 4, "longRest", 0),
                LocalTrackerRowEntity("row-2", "local-1", "item", "Healing Potion", 2, 2, "none", 1),
            ),
        )

        assertEquals("Brambles", dao.find("local-1")?.name)
        assertEquals(listOf("row-1", "row-2"), dao.getRows("local-1").map { it.id })
    }

    /**
     * The foreign key the migration writes is not decoration: Room turns `PRAGMA foreign_keys`
     * on for every connection, so deleting a character must take its rows with it. A migration
     * that dropped the clause would still pass the schema validator on some Room versions and
     * would silently start leaking orphan rows.
     */
    @Test
    fun `deleting a migrated local character cascades to its rows`() = runTest {
        createVersion2Database().close()
        val db = openCurrent()
        val dao = db.localCharacterDao()

        dao.save(
            LocalCharacterEntity(
                id = "local-1",
                name = "Brambles",
                level = null,
                strength = 10,
                dexterity = 10,
                constitution = 10,
                intelligence = 10,
                wisdom = 10,
                charisma = 10,
                maxHp = 10,
                currentHp = 10,
                armorClass = 10,
                createdAt = 1,
                updatedAt = 1,
            ),
            listOf(LocalTrackerRowEntity("row-1", "local-1", "resource", "Rage", 3, 3, "longRest", 0)),
        )
        assertEquals(1, dao.getRows("local-1").size)

        dao.delete("local-1")

        assertNull(dao.find("local-1"))
        assertTrue("rows outlived their character", dao.getRows("local-1").isEmpty())
    }

    // --- v3 → v4 (FR-8) -----------------------------------------------------

    /**
     * The upgrade every 1.2.x install will take, against the schema that is actually in the
     * field — and the first migration in this app's life that **alters existing tables**
     * rather than adding new ones.
     *
     * That is what makes populating them first non-optional: 1→2 and 2→3 could not lose data
     * because they named no existing table, while this one names both local tables. "Additive"
     * is a claim about what did not happen, and the only way to test it is to have a character
     * and their rows sitting there that could have been lost.
     */
    @Test
    fun `migrating v3 to v4 preserves every local character and every tracker row`() = runTest {
        createDatabaseAtVersion(3).use { v3 ->
            v3.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', 3, 8, 14, 12, 16, 10, 13, 22, 17, 15, 100, 200)",
            )
            v3.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, resetRule, sortIndex) " +
                    "VALUES ('row-1', 'local-1', 'slot', '1st Level', 4, 2, 'longRest', 0)",
            )
            v3.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, resetRule, sortIndex) " +
                    "VALUES ('row-2', 'local-1', 'item', 'Healing Potion', 3, 3, 'none', 1)",
            )
        }

        val db = openCurrent()
        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)

        val dao = db.localCharacterDao()
        with(dao.find("local-1")!!) {
            assertEquals("Brambles", name)
            assertEquals(3, level)
            assertEquals(8, strength)
            assertEquals(16, intelligence)
            assertEquals(22, maxHp)
            // Play state, not form state: an upgrade that silently healed the character back
            // to full would be a data loss nobody would report as one.
            assertEquals(17, currentHp)
            assertEquals(15, armorClass)
            assertEquals(100L, createdAt)
            assertEquals(200L, updatedAt)
        }

        val rows = dao.getRows("local-1")
        assertEquals(listOf("row-1", "row-2"), rows.map { it.id })
        with(rows.first()) {
            assertEquals("1st Level", label)
            assertEquals(4, total)
            assertEquals(2, current) // two slots already spent — still spent after the upgrade
            assertEquals("longRest", resetRule)
        }
    }

    /**
     * The new columns take their defaults on every row that already existed, which is what
     * `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT 0` buys — SQLite refuses the statement
     * without a default, so this is pinning a load-bearing clause rather than a nicety.
     *
     * The nullable columns take `NULL`, which is the correct reading of "the player never
     * gave this row a weight" — true of every row that predates FR-8.
     */
    @Test
    fun `the new v4 columns default correctly on rows that predate them`() = runTest {
        createDatabaseAtVersion(3).use { v3 ->
            v3.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', null, 10, 10, 10, 10, 10, 10, 10, 10, 10, 1, 1)",
            )
            v3.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, resetRule, sortIndex) " +
                    "VALUES ('row-1', 'local-1', 'item', 'Torch', 5, 5, 'none', 0)",
            )
        }

        val dao = openCurrent().localCharacterDao()

        with(dao.find("local-1")!!) {
            assertEquals("a pre-FR-8 character is broke, which they are", 0, pp)
            assertEquals(0, gp)
            assertEquals(0, sp)
            assertEquals(0, cp)
        }

        with(dao.getRows("local-1").single()) {
            assertNull("a blank weight is an absence, not a zero", weight)
            assertNull(value)
            assertNull(description)
            assertEquals(false, equipped)
        }
    }

    /** Round-trips the new columns through the real DAO on a migrated database. */
    @Test
    fun `the migrated database stores coins and item details`() = runTest {
        createDatabaseAtVersion(3).close()
        val db = openCurrent()
        val dao = db.localCharacterDao()

        dao.save(
            LocalCharacterEntity(
                id = "local-1",
                name = "Brambles",
                level = 3,
                strength = 8,
                dexterity = 14,
                constitution = 12,
                intelligence = 16,
                wisdom = 10,
                charisma = 13,
                maxHp = 22,
                currentHp = 22,
                armorClass = 15,
                pp = 1,
                gp = 109,
                sp = 57,
                cp = 351,
                createdAt = 100,
                updatedAt = 100,
            ),
            listOf(
                LocalTrackerRowEntity(
                    id = "row-1",
                    characterId = "local-1",
                    kind = "item",
                    label = "Quarterstaff",
                    total = 1,
                    current = 1,
                    resetRule = "none",
                    sortIndex = 0,
                    weight = 4.0,
                    value = 0.2,
                    description = "A simple melee weapon.",
                    equipped = true,
                ),
            ),
        )

        with(dao.find("local-1")!!) {
            assertEquals(1, pp)
            assertEquals(109, gp)
            assertEquals(57, sp)
            assertEquals(351, cp)
        }
        with(dao.getRows("local-1").single()) {
            assertEquals(4.0, weight)
            assertEquals(0.2, value)
            assertEquals("A simple melee weapon.", description)
            assertEquals(true, equipped)
        }
    }

    // --- v4 → v5 (FR-10b) ---------------------------------------------------

    /**
     * The upgrade every 1.4.x/1.5.x install will take, against the schema that is actually in
     * the field — and, like v3→v4, one that **alters a table already holding player data**.
     *
     * Both local tables are populated first for [MIGRATION_3_4]'s reason, unchanged: "additive"
     * is a claim about what did not happen, and the only way to test it is to have a character
     * and their rows sitting there that could have been lost. The v4 columns are populated too,
     * because they are the ones a careless `ALTER` on this table would take with it.
     */
    @Test
    fun `migrating v4 to v5 preserves every local character, row and v4 column`() = runTest {
        createDatabaseAtVersion(4).use { v4 ->
            v4.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, pp, gp, sp, cp, " +
                    "createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', 3, 8, 14, 12, 16, 10, 13, 22, 17, 15, 1, 109, 57, 351, 100, 200)",
            )
            v4.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                    "resetRule, sortIndex, weight, value, description, equipped) " +
                    "VALUES ('row-1', 'local-1', 'slot', '1st Level', 4, 2, 'longRest', 0, " +
                    "null, null, null, 0)",
            )
            v4.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                    "resetRule, sortIndex, weight, value, description, equipped) " +
                    "VALUES ('row-2', 'local-1', 'item', 'Quarterstaff', 1, 1, 'none', 1, " +
                    "4.0, 0.2, 'A simple melee weapon.', 1)",
            )
        }

        val db = openCurrent()
        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)

        val dao = db.localCharacterDao()
        with(dao.find("local-1")!!) {
            assertEquals("Brambles", name)
            assertEquals(3, level)
            // Play state, and money: an upgrade that healed the character or emptied their
            // purse would be a data loss nobody would report as one.
            assertEquals(17, currentHp)
            assertEquals(109, gp)
            assertEquals(351, cp)
            assertEquals(100L, createdAt)
        }

        val rows = dao.getRows("local-1")
        assertEquals(listOf("row-1", "row-2"), rows.map { it.id })
        assertEquals(2, rows.first().current) // two slots already spent — still spent
        with(rows.last()) {
            assertEquals(4.0, weight)
            assertEquals(0.2, value)
            assertEquals("A simple melee weapon.", description)
            assertEquals(true, equipped)
        }
    }

    /**
     * The new column takes its default on every row that already existed — which is what
     * `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT 'gear'` buys, SQLite refusing the statement
     * without one.
     *
     * `'gear'` and not `"gear"`: the inner single quotes are what make it a SQL **string
     * literal** rather than a column reference, and they are the one thing this migration does
     * that the v4 integer defaults did not. A missing pair fails at `ALTER` time, on a device.
     */
    @Test
    fun `the new v5 category column defaults to gear on rows that predate it`() = runTest {
        createDatabaseAtVersion(4).use { v4 ->
            v4.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, pp, gp, sp, cp, " +
                    "createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', null, 10, 10, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0, 0, 1, 1)",
            )
            v4.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                    "resetRule, sortIndex, weight, value, description, equipped) " +
                    "VALUES ('row-1', 'local-1', 'item', 'Torch', 5, 5, 'none', 0, 1.0, 0.01, null, 0)",
            )
        }

        val row = openCurrent().localCharacterDao().getRows("local-1").single()

        assertEquals(
            "a pre-FR-10b row was collected by a build that never asked, which reads as gear",
            LocalTrackerRowEntity.CATEGORY_GEAR,
            row.category,
        )
        assertEquals(CatalogCategory.GEAR, row.toDomain()!!.category)
    }

    /**
     * **13 decision 11, end to end**: the migration, then the board the player actually sees.
     *
     * This is the test the design asks for by name, and it is deliberately not a column
     * assertion. The column default *is* a behaviour change — 1.5.x rendered every local item as
     * equippable, and after this upgrade an uncategorised one is gear — so the honest question is
     * not "did the column default correctly" but **"can the player still do everything they
     * could do yesterday"**. Two rows answer it, and they are the two shapes a real 1.5.x
     * character has:
     *
     *  - a row they had **equipped**, which must still be in Equipped with its unequip control
     *    (the rule's `equipped` disjunct, which is why the disjunct exists);
     *  - a row they had **not** equipped but could have, which must still be equippable — via 11
     *    decision 2's override, whose switch has to be *offered* on it.
     *
     * Nothing they did becomes undoable, and nothing is silently promoted either: the unequipped
     * row is gear until they say otherwise, which is the claim FR-10b is entitled to make.
     */
    @Test
    fun `after the v5 upgrade an equipped row stays equipped and an unequipped one stays rescuable`() =
        runTest {
            createDatabaseAtVersion(4).use { v4 ->
                v4.execSQL(
                    "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                        "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, pp, gp, sp, cp, " +
                        "createdAt, updatedAt) " +
                        "VALUES ('local-1', 'Brambles', 3, 14, 10, 10, 10, 10, 10, 20, 20, 12, 0, 0, 0, 0, 1, 1)",
                )
                v4.execSQL(
                    "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                        "resetRule, sortIndex, weight, value, description, equipped) " +
                        "VALUES ('worn', 'local-1', 'item', 'A Small Knife', 1, 1, 'none', 0, " +
                        "1.0, 2.0, null, 1)",
                )
                v4.execSQL(
                    "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                        "resetRule, sortIndex, weight, value, description, equipped) " +
                        "VALUES ('stowed', 'local-1', 'item', 'A quill', 1, 1, 'none', 1, " +
                        "0.0, 0.02, null, 0)",
                )
            }

            val dao = openCurrent().localCharacterDao()
            val board = LocalInventoryBoard.build(
                character = dao.find("local-1")!!.toDomain(),
                rows = dao.getRows("local-1").mapNotNull { it.toDomain() },
            )

            // The equipped row: still in Equipped, still equippable, so the chip it is wearing
            // is still a control the player can tap to take it off.
            val worn = board.equipped.single()
            assertEquals("A Small Knife", worn.name)
            assertTrue("an equipped row must never lose its unequip control", worn.isEquippable)

            // The unequipped row: honestly gear now, and honestly rescuable. `isEquippable` is
            // false, which is exactly the state 11 decision 2's switch renders on — and the
            // player's override is applied one layer up, in `InventoryRowState`.
            val stowed = board.carried.single()
            assertEquals("A quill", stowed.name)
            assertEquals(EquipGroup.GEAR, stowed.equipGroup)
            assertFalse(
                "gear is what 'nobody said' reads as — the override is the way back",
                stowed.isEquippable,
            )

            // And nothing was dropped by the upgrade: both rows are still there, in order.
            assertEquals(2, board.equipped.size + board.carried.size)
        }

    /** Round-trips the new column through the real DAO on a migrated database. */
    @Test
    fun `the migrated database stores and reads back every category`() = runTest {
        createDatabaseAtVersion(4).close()
        val db = openCurrent()
        val dao = db.localCharacterDao()

        dao.save(
            LocalCharacterEntity(
                id = "local-1",
                name = "Brambles",
                level = 3,
                strength = 8,
                dexterity = 14,
                constitution = 12,
                intelligence = 16,
                wisdom = 10,
                charisma = 13,
                maxHp = 22,
                currentHp = 22,
                armorClass = 15,
                createdAt = 100,
                updatedAt = 100,
            ),
            CatalogCategory.entries.mapIndexed { index, category ->
                LocalTrackerRowEntity(
                    id = "row-$index",
                    characterId = "local-1",
                    kind = "item",
                    label = category.name,
                    total = 1,
                    current = 1,
                    resetRule = "none",
                    sortIndex = index,
                    category = category.storedValue,
                )
            },
        )

        assertEquals(
            CatalogCategory.entries.toList(),
            dao.getRows("local-1").mapNotNull { it.toDomain() }.map { it.category },
        )
    }

    /**
     * The foreign key must survive this `ALTER TABLE` too — `the cascade still works after the
     * v4 columns are added`'s assertion, re-made against the table as v5 leaves it.
     */
    @Test
    fun `the cascade still works after the v5 category column is added`() = runTest {
        createDatabaseAtVersion(4).use { v4 ->
            v4.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, pp, gp, sp, cp, " +
                    "createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', null, 10, 10, 10, 10, 10, 10, 10, 10, 10, 0, 0, 0, 0, 1, 1)",
            )
            v4.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, " +
                    "resetRule, sortIndex, weight, value, description, equipped) " +
                    "VALUES ('row-1', 'local-1', 'resource', 'Rage', 3, 3, 'longRest', 0, null, null, null, 0)",
            )
        }

        val dao = openCurrent().localCharacterDao()
        assertEquals(1, dao.getRows("local-1").size)

        dao.delete("local-1")

        assertNull(dao.find("local-1"))
        assertTrue("rows outlived their character", dao.getRows("local-1").isEmpty())
    }

    /**
     * The full chain in one open. A device still on v1 must cross every migration, and the
     * chained result has to satisfy the same validator the single-step path does — which is
     * the assertion `openCurrent()` makes on every test here just by succeeding.
     */
    @Test
    fun `migrating from v1 crosses every migration and keeps the account`() = runTest {
        createVersion1Database().use { v1 ->
            v1.execSQL(
                "INSERT INTO accounts (id, serverUrl, userId, username, addedAt, lastUsedAt) " +
                    "VALUES ('acc-1', 'https://dnd.example.com', 'user', 'name', 1, 2)",
            )
        }

        val db = openCurrent()

        assertEquals(CURRENT_VERSION, db.openHelper.readableDatabase.version)
        assertEquals(1, db.accountDao().getAll().size)
        assertTrue("local_characters" in tableNames(db))
    }

    /**
     * The foreign key must survive an `ALTER TABLE` on the child table.
     *
     * SQLite rebuilds nothing on `ADD COLUMN`, so it should — but "should" is how a migration
     * that silently starts leaking orphan rows gets shipped. The same assertion the v2→v3 test
     * makes, re-made after the table has been altered.
     */
    @Test
    fun `the cascade still works after the v4 columns are added`() = runTest {
        createDatabaseAtVersion(3).use { v3 ->
            v3.execSQL(
                "INSERT INTO local_characters (id, name, level, strength, dexterity, constitution, " +
                    "intelligence, wisdom, charisma, maxHp, currentHp, armorClass, createdAt, updatedAt) " +
                    "VALUES ('local-1', 'Brambles', null, 10, 10, 10, 10, 10, 10, 10, 10, 10, 1, 1)",
            )
            v3.execSQL(
                "INSERT INTO local_tracker_rows (id, characterId, kind, label, total, current, resetRule, sortIndex) " +
                    "VALUES ('row-1', 'local-1', 'resource', 'Rage', 3, 3, 'longRest', 0)",
            )
        }

        val dao = openCurrent().localCharacterDao()
        assertEquals(1, dao.getRows("local-1").size)

        dao.delete("local-1")

        assertNull(dao.find("local-1"))
        assertTrue("rows outlived their character", dao.getRows("local-1").isEmpty())
    }

    // --- fresh install / re-open --------------------------------------------

    @Test
    fun `a fresh install creates the current version directly and needs no migration`() = runTest {
        // No file at all: Room must be able to build the whole schema from scratch.
        val db = openCurrent()
        val tables = tableNames(db)
        assertTrue(
            tables.containsAll(
                listOf(
                    "accounts", "characters", "snapshots", "tracker_prefs", "theme_prefs",
                    "local_characters", "local_tracker_rows",
                ),
            ),
        )
        assertEquals(0, db.accountDao().getAll().size)
        assertEquals(0, db.localCharacterDao().count())
    }

    @Test
    fun `reopening an already migrated database does not run the migrations again`() = runTest {
        createVersion2Database().close()
        openCurrent().also { it.openHelper.readableDatabase }.close()
        database = null

        // Second open: still current, nothing re-created, no "table already exists".
        val reopened = openCurrent()
        assertEquals(CURRENT_VERSION, reopened.openHelper.readableDatabase.version)
        assertTrue("local_characters" in tableNames(reopened))
    }

    /**
     * `fallbackToDestructiveMigration` is forbidden (09 decision 2, and WP3's reason: dropping
     * `accounts` orphans every Keystore-held token). The way that stays true is that a version
     * bump without a migration *fails*, so this pins the chain's shape: one migration per step,
     * no gaps.
     */
    @Test
    fun `every schema step from one to the current version has a migration`() {
        val steps = MageHandDatabase.MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertEquals(
            "migration chain has a gap or an overlap",
            (1 until CURRENT_VERSION).map { it to it + 1 },
            steps,
        )
    }

    private companion object {
        /** Keep in step with `@Database(version = …)`. */
        const val CURRENT_VERSION = 5
    }
}
