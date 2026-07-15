package com.remotemonitor.watch.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the watch's local measurement store
 * (REQ-WATCH-06, T-WATCH-18).
 *
 * - Single entity: [MeasurementEntity] (table `measurements`).
 * - Version 5 — added `ibis_status` JSON text column for per-beat quality
 *   flags (REQ-NOISE-WATCH-03). Real [MIGRATION_4_5] preserves pending rows;
 *   [fallbackToDestructiveMigration] in [com.remotemonitor.watch.WatchApplication]
 *   is a safety net only.
 * - Schema is exported to `app/schemas/` by the Room compiler
 *   (KSP arg `room.schemaLocation` in `app/build.gradle.kts`).
 *
 * The DAO is exposed as `abstract fun measurementDao()` so Room can
 * generate the implementation. In production, the database is created
 * by [com.remotemonitor.watch.WatchApplication] via `Room.databaseBuilder`
 * and the generated DAO is wired into the ServiceLocator. The merge-gate
 * test (`BatchUploadWorkerTest`) mocks [MeasurementDao] directly via
 * MockK, so the generated Room implementation is not exercised by tests
 * — it is exercised on-device by `SensorOrchestrator`.
 */
@Database(
    entities = [MeasurementEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        /**
         * REQ-NOISE-WATCH-03: explicit migration from v4 to v5 adds the
         * `ibis_status` column as a JSON text column. Room stores the
         * `List<Int>?` field via [IbiStatusConverter] as `TEXT`, mirroring
         * the historical `ibis_ms` column type.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN ibis_status TEXT")
            }
        }
    }
}
