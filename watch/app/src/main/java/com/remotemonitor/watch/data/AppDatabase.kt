package com.remotemonitor.watch.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Android collector's local measurement store.
 *
 * - Single entity: [MeasurementEntity] (table `measurements`).
 * - Version 5 — added `ibis_status` (removed again in v9).
 * - Version 6 — added `hr_status` (removed again in v9).
 * - Version 7 — added `source` (removed again in v9).
 * - Version 8 — added nullable `ecg_uv` + `ecg_sample_rate_hz` for the
 *   Polar H10 ECG snippets.
 * - Version 9 — band-only schema: removes `spo2_percent`, `ibis_status`,
 *   `hr_status` and `source`; preserves pending band rows.
 *
 * The older migrations stay in place so an installed v8 APK upgrades
 * cleanly. [MIGRATION_8_9] recreates the table because SQLite `DROP
 * COLUMN` is not available on every supported Android release.
 *
 * Schema is exported to `app/schemas/` by the Room compiler
 * (KSP arg `room.schemaLocation` in `app/build.gradle.kts`).
 */
@Database(
    entities = [MeasurementEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        /** v4 -> v5: add the (now removed) `ibis_status` column. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN ibis_status TEXT")
            }
        }

        /** v5 -> v6: add the (now removed) `hr_status` column. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN hr_status INTEGER")
            }
        }

        /** v6 -> v7: add the (now removed) `source` discriminator. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN source TEXT NOT NULL DEFAULT 'samsung_watch'")
            }
        }

        /** v7 -> v8: add the Polar H10 ECG snippet columns. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN ecg_uv TEXT")
                db.execSQL("ALTER TABLE measurements ADD COLUMN ecg_sample_rate_hz INTEGER")
            }
        }

        /**
         * v8 -> v9 (band-only): rebuild the table without the Samsung
         * watch sensor columns. Pending band rows survive the copy;
         * the removed columns are discarded.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `measurements_new` (
                        `local_id` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `heart_rate_bpm` INTEGER,
                        `ibis_ms` TEXT,
                        `ecg_uv` TEXT,
                        `ecg_sample_rate_hz` INTEGER,
                        PRIMARY KEY(`local_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `measurements_new`
                        (`local_id`, `timestamp`, `heart_rate_bpm`, `ibis_ms`, `ecg_uv`, `ecg_sample_rate_hz`)
                    SELECT
                        `local_id`, `timestamp`, `heart_rate_bpm`, `ibis_ms`, `ecg_uv`, `ecg_sample_rate_hz`
                    FROM `measurements`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `measurements`")
                db.execSQL("ALTER TABLE `measurements_new` RENAME TO `measurements`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_measurements_timestamp` ON `measurements` (`timestamp`)",
                )
            }
        }
    }
}
