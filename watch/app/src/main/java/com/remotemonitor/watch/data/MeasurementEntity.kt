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
 *
 * **`ibis_status`**: per-beat quality flags matching `ibis_ms` element-for-
 * element. Persisted via [IbiStatusConverter] as a JSON string column;
 * `0` = normal/valid beat, `-1` = error/invalid beat (Samsung SDK
 * convention). `null` when the
 * SDK did not provide a matching status array.
 *
 * **`hr_status`**: per-reading lifecycle code from the Samsung
 * `HEART_RATE_STATUS` field. Documented values (Samsung API Reference
 * `ValueKey.HeartRateSet.html`): `1` = success, `0` = initial or BIA
 * operating, `-2` = movement, `-3` = off-wrist, `-8` = weak PPG,
 * `-10` = too weak, `-999` = BIA operating. `null` when the SDK did
 * not provide a status for this batch (e.g. the first sample after
 * wakeup). Added in the `feat-watch-hr-status-surface` cycle.
 *
 * **`source`**: per-row discriminator for validation
 * (`polar-h10-validation` T2). `samsung_watch` = Samsung SDK pipeline,
 * `polar_h10` = Polar H10 GATT pipeline. Non-null with a Samsung
 * default so pre-T2 rows backfill cleanly via `MIGRATION_6_7`.
 *
 * **`ecg_uv` / `ecg_sample_rate_hz`**: Polar H10 ECG snippet
 * (polar-ecg-integration T2). 10 s @ 130 Hz = 1300 voltages in µV,
 * persisted as a JSON int array reusing [IbiStatusConverter] (same
 * `List<Int>?` ↔ JSON TEXT shape as `ibis_status`; a second
 * converter for the same type would be ambiguous for Room).
 * `null` on every non-ECG row. Rows carrying ECG have
 * `heartRateBpm = null` and `source = polar_h10`.
 */
@Entity(
    tableName = "measurements",
    indices = [Index(value = ["timestamp"])],
)
@TypeConverters(IbiListConverter::class, IbiStatusConverter::class)
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
    @ColumnInfo(name = "ibis_status")
    @Json(name = "ibis_status")
    val ibisStatus: List<Int>? = null,
    @ColumnInfo(name = "hr_status")
    @Json(name = "hr_status")
    val hrStatus: Int? = null,
    @ColumnInfo(name = "source")
    @Json(name = "source")
    val source: String = SOURCE_SAMSUNG,
    @ColumnInfo(name = "ecg_uv")
    @Json(name = "ecg_uv")
    val ecgUv: List<Int>? = null,
    @ColumnInfo(name = "ecg_sample_rate_hz")
    @Json(name = "ecg_sample_rate_hz")
    val ecgSampleRateHz: Int? = null,
) {
    companion object {
        const val SOURCE_SAMSUNG = "samsung_watch"
        const val SOURCE_POLAR = "polar_h10"
    }
}
