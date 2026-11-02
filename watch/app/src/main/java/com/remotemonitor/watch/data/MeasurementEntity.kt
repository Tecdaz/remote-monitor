package com.remotemonitor.watch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.remotemonitor.watch.api.Iso8601Timestamp
import com.squareup.moshi.Json

/**
 * Local measurement record for the Polar H10 band.
 *
 * Primary key is `localId` (UUID v4, client-generated), which is the
 * idempotency key for the backend.
 *
 * **No sync-state column**: presence-in-table = pending upload. After a
 * 2xx response that echoes the `localId` in `accepted_ids`, the row is
 * deleted (`delete-after-echo`).
 *
 * **Band-only shape**: the Samsung watch sensor fields (`spo2_percent`,
 * `ibis_status`, `hr_status`, `source`) are removed. The H10 is the sole
 * measurement source and only emits:
 *  - `heart_rate_bpm` + `ibis_ms` from the GATT Heart Rate Service.
 *  - `ecg_uv` + `ecg_sample_rate_hz` from the Polar BLE SDK ECG stream.
 *
 * `ibis_ms` is persisted via [IbiListConverter] as a JSON string column
 * and serialized on the wire as a JSON int array by Moshi. ECG snippets
 * reuse the `List<Int>` JSON shape via [EcgListConverter].
 */
@Entity(
    tableName = "measurements",
    indices = [Index(value = ["timestamp"])],
)
@TypeConverters(IbiListConverter::class, EcgListConverter::class)
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
    @ColumnInfo(name = "ibis_ms")
    @Json(name = "ibis_ms")
    val ibisMs: List<Long>? = null,
    @ColumnInfo(name = "ecg_uv")
    @Json(name = "ecg_uv")
    val ecgUv: List<Int>? = null,
    @ColumnInfo(name = "ecg_sample_rate_hz")
    @Json(name = "ecg_sample_rate_hz")
    val ecgSampleRateHz: Int? = null,
)
