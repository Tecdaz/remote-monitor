package com.remotemonitor.watch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.remotemonitor.watch.api.Iso8601Timestamp
import com.squareup.moshi.Json

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
 * The Room schema is exported to `app/schemas/` via the `room.schemaLocation`
 * KSP argument configured in `app/build.gradle.kts` (T-WATCH-18).
 *
 * **`ibis_ms`**: the raw IBI array from the Samsung sensor, delivered as-is
 * without any transformation. Persisted via [IbiListConverter] as a JSON
 * string column; serialized on the wire as a JSON int array by Moshi.
 */
@Entity(
    tableName = "measurements",
    indices = [Index(value = ["timestamp"])],
)
@TypeConverters(IbiListConverter::class)
data class MeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id")
    @Json(name = "local_id")
    val localId: String,
    @ColumnInfo(name = "timestamp")
    @Json(name = "timestamp")
    @Iso8601Timestamp
    val timestamp: Long,
    @ColumnInfo(name = "heart_rate_bpm")
    @Json(name = "heart_rate_bpm")
    val heartRateBpm: Int?,
    @ColumnInfo(name = "spo2_percent")
    @Json(name = "spo2_percent")
    val spo2Percent: Double?,
    @ColumnInfo(name = "ibis_ms")
    @Json(name = "ibis_ms")
    val ibisMs: List<Long>? = null,
)
