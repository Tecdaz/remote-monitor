package com.remotemonitor.watch.data

/**
 * Measurement DAO (REQ-WATCH-06).
 *
 * Plain Kotlin interface for T-WATCH-16 (RED test). Converted to a Room
 * `@Dao`-annotated interface in T-WATCH-18 (with `room.schemaLocation`
 * export per REQ-WATCH-13).
 */
interface MeasurementDao {
    suspend fun insert(entity: MeasurementEntity)
    suspend fun selectPending(limit: Int): List<MeasurementEntity>
    suspend fun deleteByIds(ids: List<String>)
}
