package com.remotemonitor.watch.data

/**
 * Local measurement record (REQ-WATCH-06).
 *
 * Primary key is `localId` (UUID v4, client-generated per D5 in obs #249),
 * which is the idempotency key for the backend.
 *
 * **No sync-state column** (per design D3): presence-in-table = pending upload.
 * After a 2xx response that echoes the `localId` in `accepted_ids`, the row
 * is deleted.
 *
 * For PR 2 (T-WATCH-17) this becomes a `@Entity(tableName = "measurements")`
 * Room entity with `room.schemaLocation` export. For T-WATCH-16 (RED test)
 * it is a plain data class so the test compiles without KSP/Room.
 */
data class MeasurementEntity(
    val localId: String,
    val timestamp: Long,
    val heartRateBpm: Int?,
    val spo2Percent: Double?,
)
