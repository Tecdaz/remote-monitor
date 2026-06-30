package com.remotemonitor.watch.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the watch's local measurement store
 * (REQ-WATCH-06, T-WATCH-18).
 *
 * - Single entity: [MeasurementEntity] (table `measurements`).
 * - Version 1 — bump on schema change and provide a Migration.
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
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
}
