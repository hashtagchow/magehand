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
 *
 * Version 1's exported JSON under `core/data/schemas/` is **immutable** — it is the
 * input to [MIGRATION_1_2] and to `MageHandDatabaseMigrationTest`. Version 2 is purely
 * additive: `accounts` is untouched, so no account or token binding can be lost on
 * upgrade.
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
    ],
    version = 2,
    exportSchema = true,
)
abstract class MageHandDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun characterDao(): CharacterDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun trackerPrefDao(): TrackerPrefDao
    abstract fun themePrefDao(): ThemePrefDao

    companion object {
        /** On-disk file name; referenced by the Hilt provider and by the migration test. */
        const val NAME: String = "magehand.db"

        /** Every migration, in order. Hand this to `RoomDatabase.Builder.addMigrations`. */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2)
    }
}
