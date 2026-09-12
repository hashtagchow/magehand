package com.hashtagchow.magehand.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * The app's one Room database.
 *
 * | Version | Owner | Tables |
 * |---|---|---|
 * | 1 | WP3 | `accounts` |
 * | 2 | WP4 | + `characters`, `snapshots`, `tracker_prefs`, `theme_prefs` |
 * | 3 | FR-5 | + `local_characters`, `local_tracker_rows` |
 * | 4 | FR-8 | + coin and inventory **columns** on those two (no new table) |
 * | 5 | FR-10b | + `local_tracker_rows.category` (one column, no new table) |
 * | 6 | FR-23 | + `local_characters.deathSuccesses` / `.deathFailures` |
 * | 7 | FR-29 | + `local_tracker_rows.costRowId` / `.costAmount` (a local action's cost) |
 * | 8 | FR-49 | + eleven `local_tracker_rows` columns (a local spell or attack), and the first back-fill |
 *
 * Every **shipped** version's exported JSON under `core/data/schemas/` is **immutable** —
 * each one is the input to the migration that leaves it and to
 * `MageHandDatabaseMigrationTest`. Every migration so far is additive: 1→2 and 2→3 add tables
 * and name no existing one, and 3→4 through 7→8 add columns with `ALTER TABLE` and re-create
 * nothing. 7→8 is the first that also **writes** — one `UPDATE` filling in a level for slot rows
 * whose label already carried one (see [MIGRATION_7_8]) — which adds no column-loss risk of its
 * own: it touches one new column of one kind of row, and it runs inside the same transaction as
 * the `ALTER`s above it. So no account or token binding can be lost on upgrade, and
 * (docs/design/09-local-characters.md decision 10) sign-out cannot reach the local tables
 * because they carry no `accountId` to key on.
 *
 * (Version 1 replaced WP1's throwaway `ScaffoldDatabase` — see
 * docs/verification/WP1.md deviation #3 and docs/verification/WP3.md §4.)
 */
@Database(
    entities = [
        AccountEntity::class,
        CharacterEntity::class,
        SnapshotEntity::class,
        TrackerPrefEntity::class,
        ThemePrefEntity::class,
        LocalCharacterEntity::class,
        LocalTrackerRowEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class MageHandDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun characterDao(): CharacterDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun trackerPrefDao(): TrackerPrefDao
    abstract fun themePrefDao(): ThemePrefDao
    abstract fun localCharacterDao(): LocalCharacterDao

    companion object {
        /** On-disk file name; referenced by the Hilt provider and by the migration test. */
        const val NAME: String = "magehand.db"

        /** Every migration, in order. Hand this to `RoomDatabase.Builder.addMigrations`. */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
            )
    }
}
