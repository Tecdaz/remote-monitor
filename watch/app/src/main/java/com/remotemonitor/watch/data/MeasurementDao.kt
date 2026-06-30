package com.remotemonitor.watch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Measurement DAO (REQ-WATCH-06).
 *
 * The merge-gate test (`BatchUploadWorkerTest`) mocks this interface via
 * MockK, so the Room-generated implementation is NOT exercised by the
 * CI merge gate. The interface stays narrow and MockK-friendly; the Room
 * compiler emits an implementation against the SQLite backend in
 * production.
 *
 * Schema export: `room.schemaLocation` (T-WATCH-18) is configured in
 * `app/build.gradle.kts`; the compiler writes the JSON to `app/schemas/`.
 */
@Dao
interface MeasurementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MeasurementEntity)

    @Query("SELECT * FROM measurements ORDER BY timestamp ASC LIMIT :limit")
    suspend fun selectPending(limit: Int): List<MeasurementEntity>

    @Query("DELETE FROM measurements WHERE local_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("SELECT MIN(timestamp) FROM measurements")
    suspend fun minTimestamp(): Long?
}
