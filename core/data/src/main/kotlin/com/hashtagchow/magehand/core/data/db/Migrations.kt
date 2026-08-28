package com.hashtagchow.magehand.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: adds WP4's four tables (docs/design/03-data-model.md §Room schema).
 *
 * **Purely additive.** `accounts` is not touched — not renamed, not re-created, no
 * column added or dropped — so an upgrade cannot orphan an account row from its
 * Keystore-held token (which is keyed by `accounts.id`, docs/design/05-security.md).
 *
 * The statements below are byte-identical to the `createSql` Room exports for schema
 * version 2 (`core/data/schemas/…MageHandDatabase/2.json`), with `${TABLE_NAME}`
 * substituted. That is not a coincidence to be trusted: `MageHandDatabaseMigrationTest`
 * builds a real v1 database from the committed v1 JSON, runs this migration, and lets
 * Room's own schema validator compare the result against v2. A drifted statement fails
 * the test rather than a user's device.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `characters` (" +
                "`accountId` TEXT NOT NULL, " +
                "`creatureId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`picture` TEXT, " +
                "`owner` TEXT NOT NULL, " +
                "`isOwned` INTEGER NOT NULL, " +
                "`lastOpenedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `creatureId`))",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snapshots` (" +
                "`accountId` TEXT NOT NULL, " +
                "`creatureId` TEXT NOT NULL, " +
                "`json` BLOB NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`accountId`, `creatureId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snapshots_accountId_fetchedAt` " +
                "ON `snapshots` (`accountId`, `fetchedAt`)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracker_prefs` (" +
                "`accountId` TEXT NOT NULL, " +
                "`creatureId` TEXT NOT NULL, " +
                "`propertyId` TEXT NOT NULL, " +
                "`pinned` INTEGER NOT NULL, " +
                "`hidden` INTEGER NOT NULL, " +
                "`sortIndex` INTEGER, " +
                "PRIMARY KEY(`accountId`, `creatureId`, `propertyId`))",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `theme_prefs` (" +
                "`accountId` TEXT NOT NULL, " +
                "`creatureId` TEXT NOT NULL, " +
                "`accentColor` TEXT, " +
                "PRIMARY KEY(`accountId`, `creatureId`))",
        )
    }
}

/**
 * v2 → v3: adds 09's two local-character tables (docs/design/09-local-characters.md
 * decision 2 — "the first Room schema change of the app's life").
 *
 * **Purely additive, again.** None of the five existing tables is named below: no account,
 * no cached character, no snapshot, no tracker or theme preference is re-created, altered or
 * dropped, so an upgrade cannot orphan a Keystore-held token or discard a player's pins.
 *
 * The statements are byte-identical to the `createSql` Room exports for schema version 3
 * (`core/data/schemas/…MageHandDatabase/3.json`), `${TABLE_NAME}` substituted — and, as with
 * [MIGRATION_1_2], that is proven rather than trusted: `MageHandDatabaseMigrationTest` builds
 * a real v2 database from the committed v2 JSON, populates it, runs this migration and lets
 * Room's own validator compare the result against v3.
 *
 * Note the foreign key clause. Room emits it as part of the child table's `CREATE TABLE`, so
 * it must be reproduced here exactly or the validator rejects the migrated schema. It is
 * only *enforced* while `PRAGMA foreign_keys` is on, which Room sets on every connection.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_characters` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`level` INTEGER, " +
                "`strength` INTEGER NOT NULL, " +
                "`dexterity` INTEGER NOT NULL, " +
                "`constitution` INTEGER NOT NULL, " +
                "`intelligence` INTEGER NOT NULL, " +
                "`wisdom` INTEGER NOT NULL, " +
                "`charisma` INTEGER NOT NULL, " +
                "`maxHp` INTEGER NOT NULL, " +
                "`currentHp` INTEGER NOT NULL, " +
                "`armorClass` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `local_tracker_rows` (" +
                "`id` TEXT NOT NULL, " +
                "`characterId` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`total` INTEGER NOT NULL, " +
                "`current` INTEGER NOT NULL, " +
                "`resetRule` TEXT NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`characterId`) REFERENCES `local_characters`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_local_tracker_rows_characterId` " +
                "ON `local_tracker_rows` (`characterId`)",
        )
    }
}

/**
 * v3 → v4: the inventory tab's local columns (docs/design/10-inventory.md decision 10).
 *
 * ```
 * local_characters    += pp, gp, sp, cp        INTEGER NOT NULL DEFAULT 0
 * local_tracker_rows  += weight, value         REAL
 *                     += description           TEXT
 *                     += equipped              INTEGER NOT NULL DEFAULT 0
 * ```
 *
 * **Purely additive, and additive in the narrower sense the previous two were not**: those
 * created new tables and touched nothing existing, while this one alters two tables that
 * already hold player data. `ALTER TABLE … ADD COLUMN` is the whole of it — no table is
 * re-created, so there is no copy step that could drop a row, no temporary table, and no
 * window in which the foreign key on `local_tracker_rows` does not exist. The commonly
 * written "create new, copy, drop old, rename" dance is what loses data on a failed upgrade,
 * and none of these four changes needs it.
 *
 * ### Why every `NOT NULL` column names a `DEFAULT`
 *
 * SQLite refuses `ADD COLUMN … NOT NULL` without one on a table that already has rows — there
 * would be no value to put in them. The defaults here are therefore load-bearing, not
 * decorative, and the matching `@ColumnInfo(defaultValue = "0")` on
 * [LocalCharacterEntity] / [LocalTrackerRowEntity] is what makes the exported v4 schema say
 * the same thing (see the KDoc there). The nullable columns take no default: `NULL` is the
 * correct reading of "the player never gave a weight", which is true of every row that
 * existed before this migration.
 *
 * As with [MIGRATION_1_2] and [MIGRATION_2_3], this is proven rather than trusted:
 * `MageHandDatabaseMigrationTest` builds a real v3 database from the **committed** v3 JSON,
 * populates both local tables, runs this migration, and lets Room's own validator compare the
 * result against the compiled v4 expectation.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 10 decision 10: four integer columns, not four item rows — a local character has no
        // tag machinery to discover currency with, and inventing some would be modelling
        // DiceCloud's limitation rather than the money.
        for (coin in listOf("pp", "gp", "sp", "cp")) {
            db.execSQL(
                "ALTER TABLE `local_characters` ADD COLUMN `$coin` INTEGER NOT NULL DEFAULT 0",
            )
        }

        db.execSQL("ALTER TABLE `local_tracker_rows` ADD COLUMN `weight` REAL")
        db.execSQL("ALTER TABLE `local_tracker_rows` ADD COLUMN `value` REAL")
        db.execSQL("ALTER TABLE `local_tracker_rows` ADD COLUMN `description` TEXT")
        db.execSQL(
            "ALTER TABLE `local_tracker_rows` ADD COLUMN `equipped` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * v4 → v5: the local item category
 * (docs/design/13-collapsible-sections-local-gear.md decision 8).
 *
 * ```
 * local_tracker_rows += category TEXT NOT NULL DEFAULT 'gear'
 * ```
 *
 * **Additive in [MIGRATION_3_4]'s narrower sense**: one `ALTER TABLE … ADD COLUMN` against a
 * table that already holds player data. No table is re-created, so there is no copy step that
 * could drop a row, no temporary table, and no window in which `local_tracker_rows`' foreign key
 * to `local_characters` does not exist. Nothing on `local_characters` is touched at all.
 *
 * ### What the default means, and why it is not a data loss
 *
 * Every existing row was written by a build that never asked what kind of thing the item was, so
 * there is no answer to migrate — only a reading to choose. 13 decision 8 chooses **gear**,
 * which is the honest reading of "never collected" and is what 11 decision 2's override toggle
 * has let a player correct since 1.4.0.
 *
 * The reading a careless migration would choose instead is "everything stays equippable", to
 * preserve 1.4.x's `LocalInventoryBoard` behaviour — and that is precisely the interim this
 * change retires, so encoding it in the column would make the retirement unreachable. Decision
 * 11's honesty requirement is met by the **rule** rather than by the column: `category != gear
 * || equipped || override` keeps every equipped row in Equipped with its unequip control, and
 * leaves every other row one toggle away from the control it had.
 * `MageHandDatabaseMigrationTest` pins both halves through the real board.
 *
 * ### Why the `DEFAULT` is load-bearing
 *
 * [MIGRATION_3_4]'s reason, unchanged: SQLite refuses `ADD COLUMN … NOT NULL` without one on a
 * table that already has rows. The matching `@ColumnInfo(defaultValue = "'gear'")` on
 * [LocalTrackerRowEntity] is what makes the exported v5 schema say the same thing — note the
 * inner single quotes, which are what turns a bare identifier into a SQL string literal and are
 * the one difference from the v4 integer defaults.
 *
 * As with every migration before it, this is proven rather than trusted:
 * `MageHandDatabaseMigrationTest` builds a real v4 database from the **committed** v4 JSON,
 * populates both local tables, runs this migration, and lets Room's own validator compare the
 * result against the compiled v5 expectation.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `local_tracker_rows` " +
                "ADD COLUMN `category` TEXT NOT NULL DEFAULT '${LocalTrackerRowEntity.CATEGORY_GEAR}'",
        )
    }
}

/**
 * v5 → v6: local death saves (FR-23, docs/design/15-polish-batch.md decision 13).
 *
 * ```
 * local_characters += deathSuccesses INTEGER NOT NULL DEFAULT 0
 * local_characters += deathFailures  INTEGER NOT NULL DEFAULT 0
 * ```
 *
 * **Additive in [MIGRATION_4_5]'s sense**, and against `local_characters` this time rather than
 * `local_tracker_rows`: two `ALTER TABLE … ADD COLUMN`s, no table re-created, so there is no copy
 * step that could drop a character, no temporary table, and no window in which
 * `local_tracker_rows`' foreign key points at a table that does not exist. Nothing on
 * `local_tracker_rows` is touched at all.
 *
 * ### What the default means, and why it is not a data loss
 *
 * There is nothing to migrate. Every existing row was written by a build with no death-save
 * feature in it, so no character has ever had a mark to preserve — `0` is not a chosen reading
 * standing in for a lost fact (the way [MIGRATION_4_5]'s `'gear'` is), it is the fact.
 *
 * ### Why the `DEFAULT` is load-bearing
 *
 * [MIGRATION_3_4]'s reason, unchanged: SQLite refuses `ADD COLUMN … NOT NULL` without one on a
 * table that already has rows. The matching `@ColumnInfo(defaultValue = "0")` on
 * [LocalCharacterEntity] is what makes the exported v6 schema say the same thing as this
 * statement, which is the claim `MageHandDatabaseMigrationTest` checks against Room's own
 * validator rather than by reading either.
 *
 * ### Two statements, one migration
 *
 * SQLite's `ALTER TABLE` adds one column per statement, so this is two `execSQL`s and not a
 * style choice. They are not individually atomic against each other, and they do not need to be:
 * Room runs a migration inside a transaction, so a failure between them rolls both back and the
 * database stays at v5 rather than landing half-migrated.
 *
 * As with every migration before it, this is proven rather than trusted:
 * `MageHandDatabaseMigrationTest` builds a real v5 database from the **committed** v5 JSON,
 * populates both local tables, runs this migration, and lets Room's validator compare the result
 * against the compiled v6 expectation.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_characters` ADD COLUMN `deathSuccesses` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `local_characters` ADD COLUMN `deathFailures` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v6 → v7: a local action's cost (FR-29, docs/design/18-table-pack.md decision 1).
 *
 * ```
 * local_tracker_rows += costRowId  TEXT
 * local_tracker_rows += costAmount INTEGER
 * ```
 *
 * **Additive in [MIGRATION_4_5]'s sense**, back on `local_tracker_rows` this time: two
 * `ALTER TABLE … ADD COLUMN`s, no table re-created, so there is no copy step that could drop a
 * row, no temporary table, and no window in which the foreign key to `local_characters` does not
 * exist. Nothing on `local_characters` is touched at all.
 *
 * ### The first migration in this file whose columns take **no** `DEFAULT`
 *
 * Every column added since v4 has carried one, and the KDoc on each says why: SQLite refuses
 * `ADD COLUMN … NOT NULL` without one on a populated table. These two are **nullable**, so the
 * refusal does not apply and `NULL` is what the existing rows get — which is also the only honest
 * value. No row that predates v7 is an action row (the kind did not exist), so none of them has a
 * cost to preserve or to guess at. That makes this migration the *simplest* kind rather than a
 * departure: there is no reading to choose, unlike [MIGRATION_4_5]'s `'gear'`.
 *
 * The matching absence of `@ColumnInfo(defaultValue = …)` on
 * [LocalTrackerRowEntity.costRowId] / [LocalTrackerRowEntity.costAmount] is what keeps the
 * exported v7 schema saying the same thing as these two statements — the same discipline, read
 * from the other end.
 *
 * ### No `FOREIGN KEY` on `costRowId`, deliberately
 *
 * It names another row in this same table, and a self-referencing FK with `ON DELETE CASCADE`
 * would delete an action when the resource it costs is deleted — "delete Rage" silently taking
 * "Rage (action)" with it, which is not what the player asked for. `ON DELETE SET NULL` would be
 * closer but would leave a `costAmount` naming nothing, which is the half-a-cost state
 * `LocalTrackerRowEntity.toDomain` already normalises away. So the reference is *soft* and the
 * dangling case is handled where it is read — see [LocalTrackerRow.costRowId].
 *
 * ### Two statements, one migration
 *
 * [MIGRATION_5_6]'s note, unchanged: SQLite's `ALTER TABLE` adds one column per statement, and
 * Room runs a migration inside a transaction, so a failure between them rolls both back and the
 * database stays at v6 rather than landing half-migrated.
 *
 * As with every migration before it, this is proven rather than trusted:
 * `MageHandDatabaseMigrationTest` builds a real v6 database from the **committed** v6 JSON,
 * populates both local tables, runs this migration, and lets Room's own validator compare the
 * result against the compiled v7 expectation.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `local_tracker_rows` ADD COLUMN `costRowId` TEXT")
        db.execSQL("ALTER TABLE `local_tracker_rows` ADD COLUMN `costAmount` INTEGER")
    }
}
