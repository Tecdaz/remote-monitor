package com.remotemonitor.watch.api

import com.remotemonitor.watch.data.MeasurementEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape test for [MeasurementEntity] (band-only contract).
 *
 * Pins the invariants the upload body must satisfy:
 *  - snake_case wire keys (`local_id`, `heart_rate_bpm`, `ibis_ms`,
 *    `ecg_uv`, `ecg_sample_rate_hz`),
 *  - ISO 8601 `Z`-suffixed string for `timestamp`,
 *  - nullable band fields are honored,
 *  - removed watch fields never leak onto the wire.
 */
class MeasurementSerializationTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(Iso8601TimestampAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun measurement_body_uses_snake_case_and_iso_8601_timestamp() {
        val entity = MeasurementEntity(
            localId = "L1",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(entity)
        assertTrue("body must use 'local_id', got: $json", json.contains("\"local_id\":\"L1\""))
        assertTrue(
            "body must use 'heart_rate_bpm', got: $json",
            json.contains("\"heart_rate_bpm\":72"),
        )
        val tsRegex = Regex("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"")
        assertTrue("body must serialize timestamp as ISO 8601 Z", tsRegex.containsMatchIn(json))
        assertTrue(
            "body must contain the exact epoch instant",
            json.contains("\"timestamp\":\"2024-06-30T15:11:12Z\""),
        )
        assertFalse("body must not leak camelCase localId", json.contains("\"localId\""))
        assertFalse("body must not leak camelCase heartRateBpm", json.contains("\"heartRateBpm\""))
    }

    @Test
    fun removed_watch_fields_never_leak_onto_the_wire() {
        val entity = MeasurementEntity(
            localId = "L2",
            timestamp = 1_700_000_000_000L,
            heartRateBpm = 80,
            ibisMs = listOf(800L, 820L),
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(entity)
        assertFalse("spo2_percent must not exist", json.contains("spo2"))
        assertFalse("ibis_status must not exist", json.contains("ibis_status"))
        assertFalse("hr_status must not exist", json.contains("hr_status"))
        assertFalse("source must not exist", json.contains("\"source\""))
    }

    @Test
    fun ibis_ms_array_round_trips_through_moshi() {
        val original = MeasurementEntity(
            localId = "L-IBI-1",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            ibisMs = listOf(800L, 820L, 790L),
        )

        val adapter = moshi.adapter(MeasurementEntity::class.java)
        val json = adapter.toJson(original)
        assertTrue(
            "body must serialize ibis_ms as JSON int array, got: $json",
            json.contains("\"ibis_ms\":[800,820,790]"),
        )
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(listOf(800L, 820L, 790L), parsed!!.ibisMs)
    }

    @Test
    fun null_ibis_ms_round_trips_as_omitted_or_explicit_null() {
        val original = MeasurementEntity(
            localId = "L-IBI-2",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            ibisMs = null,
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(original)
        assertTrue(
            "body must omit or null ibis_ms, got: $json",
            !json.contains("\"ibis_ms\":") || json.contains("\"ibis_ms\":null"),
        )
    }

    @Test
    fun ecg_snippet_round_trips_through_moshi() {
        val voltages = List(1300) { it - 650 }
        val original = MeasurementEntity(
            localId = "L-ECG-1",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = null,
            ecgUv = voltages,
            ecgSampleRateHz = 130,
        )

        val adapter = moshi.adapter(MeasurementEntity::class.java)
        val json = adapter.toJson(original)
        assertTrue(
            "body must carry ecg_sample_rate_hz=130, got: $json",
            json.contains("\"ecg_sample_rate_hz\":130"),
        )
        assertTrue(
            "body must serialize ecg_uv as JSON int array",
            json.contains("\"ecg_uv\":["),
        )
        val parsed = adapter.fromJson(json)
        assertNotNull(parsed)
        assertEquals(voltages, parsed!!.ecgUv)
        assertEquals(130, parsed.ecgSampleRateHz)
        assertNull(parsed.heartRateBpm)
    }

    @Test
    fun null_ecg_round_trips_as_omitted_or_explicit_null() {
        val original = MeasurementEntity(
            localId = "L-ECG-2",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            ecgUv = null,
            ecgSampleRateHz = null,
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(original)
        assertTrue(
            "HR rows must omit or null ecg_uv, got: $json",
            !json.contains("\"ecg_uv\":") || json.contains("\"ecg_uv\":null"),
        )
    }
}
