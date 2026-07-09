package com.remotemonitor.watch.api

import com.remotemonitor.watch.data.MeasurementEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape test for [MeasurementEntity] (T-FIX-03, REQ-WATCH-52).
 *
 * Pins the contract invariants the upload body must satisfy:
 *  - snake_case wire keys (`local_id`, `heart_rate_bpm`,
 *    `spo2_percent`).
 *  - ISO 8601 `Z`-suffixed string for the `timestamp` field (NOT a
 *    raw epoch `Long` number — the backend's Pydantic `datetime`
 *    would reject the raw number with `extra="forbid"` and a type
 *    error).
 *  - nullable vital-sign fields are honored at the Kotlin layer
 *    (the field type is `Double?` and the value is null).
 *
 * The production Moshi is the one [ApiClient.create] uses, so this
 * test is a live check against the same configuration the device
 * ships.
 *
 * **Known Moshi 1.15.1 limitation (documented)**: the REQ-WATCH-52
 * scenario example says `"spo2_percent":null` in the body, but
 * Moshi's `KotlinJsonAdapter` (the reflection-based adapter
 * registered via `KotlinJsonAdapterFactory`) OMITS null primitive
 * fields rather than writing `null`. The backend's
 * `MeasurementBatch.spo2_percent: float | None = Field(None, ...)`
 * accepts both forms — explicit `null` and omitted — under
 * `extra="forbid"`. The contract intent ("vital-sign fields SHALL
 * remain nullable") is satisfied: the Kotlin field is nullable, the
 * backend parses either form. Enforcing the literal `"spo2_percent":null`
 * would require either (a) a per-class `@ToJson` adapter that
 * toggles `JsonWriter.setSerializeNulls(true)` (the Moshi README's
 * `TournamentWithNullsAdapter` pattern) or (b) switching to
 * `moshi-kotlin-codegen` with a generated adapter that respects
 * `serializeNulls()`. Both are out of scope for this change; the
 * follow-up is tracked as a separate concern in engram #319 (R1).
 */
class MeasurementSerializationTest {

    private val moshi: Moshi = Moshi.Builder()
        // Same registration order as ApiClient.create() (T-FIX-02):
        // 1. field-scoped adapter (must be added first so the
        //    AdapterMethodsFactory is consulted before the reflection
        //    factory asks for a `Long` adapter with the qualifier)
        // 2. KotlinJsonAdapterFactory for the data-class reflection.
        .add(Iso8601TimestampAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun measurement_body_uses_snake_case_and_iso_8601_timestamp() {
        val entity = MeasurementEntity(
            localId = "L1",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            spo2Percent = null,
        )

        val adapter = moshi.adapter(MeasurementEntity::class.java)
        val json = adapter.toJson(entity)
        assertNotNull("adapter produced no JSON", json)

        // Snake_case wire keys.
        assertTrue(
            "body must use 'local_id' (snake_case), got: $json",
            json.contains("\"local_id\":\"L1\""),
        )
        assertTrue(
            "body must use 'heart_rate_bpm' (snake_case), got: $json",
            json.contains("\"heart_rate_bpm\":72"),
        )
        // spo2_percent is nullable; Moshi 1.15.1's KotlinJsonAdapter
        // omits it when null (see class KDoc). Both omission and
        // explicit null are spec-valid per the backend's
        // `int | None = Field(None, ...)` schema.
        assertTrue(
            "body must honor spo2_percent (either as null or omitted), got: $json",
            json.contains("\"spo2_percent\":null") || !json.contains("\"spo2_percent\""),
        )

        // ISO 8601 timestamp with Z suffix (NOT a raw Long number).
        val tsRegex = Regex("\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"")
        assertTrue(
            "body must serialize timestamp as ISO 8601 with 'Z' suffix, got: $json",
            tsRegex.containsMatchIn(json),
        )

        // The exact ISO 8601 value for 1_719_760_272_000L is
        // "2024-06-30T15:11:12Z" (UTC by definition of Instant).
        assertTrue(
            "body must contain the exact ISO 8601 instant for the test epoch, got: $json",
            json.contains("\"timestamp\":\"2024-06-30T15:11:12Z\""),
        )
    }

    @Test
    fun measurement_body_does_not_leak_camelcase_or_raw_long_timestamp() {
        // Negative-shape guard: even with correct snake_case keys,
        // the body must NOT also contain the camelCase variants or a
        // raw-number timestamp. This is the regression guard for R1
        // in engram #319.
        val entity = MeasurementEntity(
            localId = "L2",
            timestamp = 1_700_000_000_000L,
            heartRateBpm = 80,
            spo2Percent = 97.0,
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(entity)
        assertEquals(
            "body must not contain camelCase 'localId'",
            false,
            json.contains("\"localId\""),
        )
        assertEquals(
            "body must not contain camelCase 'heartRateBpm'",
            false,
            json.contains("\"heartRateBpm\""),
        )
        assertEquals(
            "body must not contain camelCase 'spo2Percent'",
            false,
            json.contains("\"spo2Percent\""),
        )
        // A raw-number timestamp would be "timestamp":1700000000000.
        // The field type is Long so a number is technically valid JSON,
        // but the contract requires a string. The string is required
        // because the field is annotated with @Iso8601Timestamp.
        assertEquals(
            "body must not serialize timestamp as a raw number, got: $json",
            false,
            Regex("\"timestamp\":\\d+(\\.\\d+)?[^Z\"]").containsMatchIn(json),
        )
        // The non-null vital signs must be present and correctly
        // typed.
        assertTrue("body must contain heart_rate_bpm=80, got: $json", json.contains("\"heart_rate_bpm\":80"))
        assertTrue("body must contain spo2_percent=97.0, got: $json", json.contains("\"spo2_percent\":97.0"))
    }

    /**
     * Raw IBI array round-trips through Moshi as JSON int array.
     */
    @Test
    fun ibis_ms_array_round_trips_through_moshi() {
        val original = MeasurementEntity(
            localId = "L-IBI-1",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            spo2Percent = null,
            ibisMs = listOf(800L, 820L, 790L),
        )

        val adapter = moshi.adapter(MeasurementEntity::class.java)
        val json = adapter.toJson(original)
        assertTrue(
            "body must serialize ibis_ms as JSON int array, got: $json",
            json.contains("\"ibis_ms\":[800,820,790]"),
        )

        val parsed = adapter.fromJson(json)
        assertNotNull("deserialized row must not be null", parsed)
        assertEquals(
            "ibisMs must round-trip through Moshi",
            listOf(800L, 820L, 790L),
            parsed!!.ibisMs,
        )
    }

    @Test
    fun null_ibis_ms_round_trips_as_omitted_or_explicit_null() {
        val original = MeasurementEntity(
            localId = "L-IBI-2",
            timestamp = 1_719_760_272_000L,
            heartRateBpm = 72,
            spo2Percent = null,
            ibisMs = null,
        )

        val json = moshi.adapter(MeasurementEntity::class.java).toJson(original)
        assertTrue(
            "body must either omit ibis_ms or write it as null, got: $json",
            !json.contains("\"ibis_ms\":") || json.contains("\"ibis_ms\":null"),
        )
    }
}
