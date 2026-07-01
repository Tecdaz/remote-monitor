package com.remotemonitor.watch.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for the watch's local measurement store
 * (REQ-WATCH-06, T-WATCH-18).
 *
 * - Single entity: [MeasurementEntity] (table `measurements`).
 * - Version 2 — bumped from v1 to add the `ibis_ms` column
 *   (REQ-WATCH-HR-IBI-10). Destructive migration is in effect
 *   (see [com.remotemonitor.watch.WatchApplication.database]); a
 *   real `Migration(1, 2)` is deferred (REQ-WATCH-HR-IBI-15 out-of-scope).
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
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
}
