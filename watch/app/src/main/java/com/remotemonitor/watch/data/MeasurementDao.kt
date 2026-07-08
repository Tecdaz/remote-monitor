package com.remotemonitor.watch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
 *
 * T-WATCH-38: added `pendingCount(): Flow<Int>` so the home screen can
 * observe the count reactively without polling. The merge-gate test
 * uses `mockk(relaxed = true)`, so a new method does not break it.
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

    @Query("SELECT COUNT(*) FROM measurements")
    fun pendingCount(): Flow<Int>

    @Query("SELECT MIN(timestamp) FROM measurements")
    suspend fun minTimestamp(): Long?

    // wear-ui-guidelines D10 / spec cap 1: reactive sources for the home
    // vitals Flow. Both re-emit on every insert/delete (Room observes the
    // `measurements` table), so HomeScreen surfaces the latest HR + the
    // last-update time without polling. The merge-gate test mocks this
    // DAO with `mockk(relaxed = true)`, so adding methods does not break
    // it (same precedent as `pendingCount()`).

    /** Most recent non-null HR BPM, or null when no reading is stored. */
    @Query(
        "SELECT heart_rate_bpm FROM measurements " +
            "WHERE heart_rate_bpm IS NOT NULL ORDER BY timestamp DESC LIMIT 1",
    )
    fun lastHeartRate(): Flow<Int?>

    /** Timestamp (epoch ms) of the most recent measurement, or null. */
    @Query("SELECT MAX(timestamp) FROM measurements")
    fun lastTimestamp(): Flow<Long?>
}
