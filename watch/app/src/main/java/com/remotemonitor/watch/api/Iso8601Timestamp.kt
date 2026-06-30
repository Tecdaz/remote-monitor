package com.remotemonitor.watch.api

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.ToJson
import java.time.Instant

/**
 * Moshi adapter for `Long` epoch ms ↔ ISO 8601 `datetime` string,
 * field-scoped via the [@Iso8601Timestamp] qualifier (D1, D2 in
 * engram #322; REQ-WATCH-54).
 *
 * ## Why field-scoped, not global
 *
 * A global `Moshi.Builder().add(Long::class.java, ...)` would also
 * convert Room's own `Long` column values (and any other `Long`
 * field) — that's a footgun. The `@JsonQualifier` pattern from
 * Moshi's README matches `@FromJson` / `@ToJson` methods by
 * parameter annotation, so the adapter only fires for fields
 * explicitly annotated with `@Iso8601Timestamp`.
 *
 * Today the only annotated field is `MeasurementEntity.timestamp`
 * (T-FIX-04). If a second field ever needs the same treatment,
 * annotate it the same way — the adapter will pick it up
 * automatically.
 *
 * ## UTC invariant (D6)
 *
 * `Instant` is inherently UTC; `Instant.toString()` emits the
 * canonical `2024-06-30T18:31:12Z` form. There is no local-zone
 * branch — the backend's Pydantic `datetime` also requires UTC.
 *
 * ## Malformed input
 *
 * `Instant.parse` throws `DateTimeException` (wrapped as
 * `IllegalArgumentException`) on bad input. Moshi converts that to
 * `JsonDataException` when it surfaces from `fromJson`. The
 * upload worker treats this as a batch failure — see
 * `BatchUploadWorker.runOnce()`.
 */
@Retention(AnnotationRetention.RUNTIME)
@JsonQualifier
annotation class Iso8601Timestamp

/**
 * Reflection-based Moshi adapter — matches the existing
 * `KotlinJsonAdapterFactory` configuration in [ApiClient] (D4 in
 * engram #322). No KSP codegen needed for two methods.
 *
 * Moshi's `@JsonQualifier` pattern places the annotation on the
 * **parameter** for `@ToJson` and on the **method** for `@FromJson`
 * — because Moshi looks up `@FromJson` adapters by return type and
 * `@ToJson` adapters by parameter type, and pulls the qualifier set
 * from the matching position. This matches the canonical Moshi
 * `HexColor` README example.
 */
class Iso8601TimestampAdapter {

    @FromJson
    @Iso8601Timestamp
    fun fromJson(value: String): Long = Instant.parse(value).toEpochMilli()

    @ToJson
    fun toJson(@Iso8601Timestamp value: Long): String = Instant.ofEpochMilli(value).toString()
}
