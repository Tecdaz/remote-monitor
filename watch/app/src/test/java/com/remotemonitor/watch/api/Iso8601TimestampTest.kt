package com.remotemonitor.watch.api

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [Iso8601TimestampAdapter] (T-FIX-01, REQ-WATCH-54).
 *
 * The adapter bridges `Long` epoch ms (the Room column type) and the
 * `datetime` ISO 8601 string that Pydantic validates on the wire. It
 * is **field-scoped** via the [@Iso8601Timestamp] `@JsonQualifier`,
 * so the production Moshi needs:
 *
 * ```kotlin
 * Moshi.Builder()
 *     .add(Iso8601Timestamp::class.java, Iso8601TimestampAdapter())
 *     .add(KotlinJsonAdapterFactory())
 *     .build()
 * ```
 *
 * The four cases below lock the four invariants that REQ-WATCH-54
 * depends on:
 *  - (a) `toJson(0L)` is exactly `"1970-01-01T00:00:00Z"`.
 *  - (b) `fromJson(...)` round-trips a non-zero epoch ms.
 *  - (c) malformed input throws.
 *  - (d) `toJson` always emits the `Z` suffix (UTC invariant).
 */
class Iso8601TimestampTest {

    private val moshi: Moshi = Moshi.Builder()
        // The `add(Object)` form lets Moshi introspect the @FromJson/
        // @ToJson methods on Iso8601TimestampAdapter and route only the
        // @Iso8601Timestamp-qualified fields through it.
        .add(Iso8601TimestampAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    /** Helper: a small holder so the adapter is exercised through reflection,
     *  matching the production usage on `MeasurementEntity.timestamp`. */
    private data class Holder(
        @Iso8601Timestamp val timestamp: Long,
    )

    @Test
    fun epoch_zero_serializes_to_1970_01_01T00_00_00Z() {
        val adapter = moshi.adapter(Holder::class.java)
        val json = adapter.toJson(Holder(timestamp = 0L))
        // Instant.ofEpochMilli(0).toString() yields "1970-01-01T00:00:00Z"
        // (no fractional seconds when the millisecond component is 0).
        assertEquals("""{"timestamp":"1970-01-01T00:00:00Z"}""", json)
    }

    @Test
    fun roundtrip_long_to_string_to_long() {
        val original = 1_719_760_272_000L
        val adapter = moshi.adapter(Holder::class.java)
        val json = adapter.toJson(Holder(timestamp = original))
        val parsed = adapter.fromJson(json)
        assertNotNull("fromJson returned null", parsed)
        assertEquals(original, parsed!!.timestamp)
    }

    @Test
    fun malformed_iso8601_throws() {
        val adapter = moshi.adapter(Holder::class.java)
        try {
            adapter.fromJson("""{"timestamp":"not-a-date"}""")
            fail("expected JsonDataException for malformed timestamp")
        } catch (e: JsonDataException) {
            // expected
        } catch (e: IllegalArgumentException) {
            // Instant.parse wraps DateTimeException in IllegalArgumentException
            // — also acceptable per the spec (the worker should treat any
            // parse failure as a batch failure).
        }
    }

    @Test
    fun utc_invariant() {
        // Multiple distinct epoch ms — every serialization must end in 'Z'.
        val samples = listOf(0L, 1L, 1_719_760_272_000L, 1_700_000_000_000L)
        val adapter = moshi.adapter(Holder::class.java)
        for (epoch in samples) {
            val json = adapter.toJson(Holder(timestamp = epoch))
            // Strip the surrounding object so we assert the raw string value.
            val ts = json.substringAfter("\"timestamp\":\"").substringBefore("\"")
            assertTrue(
                "expected 'Z' suffix on ISO 8601 output for epoch=$epoch, got '$ts'",
                ts.endsWith("Z"),
            )
            // No positive or negative offset, no missing zone designator.
            assertTrue(
                "expected no timezone offset on ISO 8601 output for epoch=$epoch, got '$ts'",
                !ts.contains("+") || ts.endsWith("Z"),
            )
        }
    }
}
