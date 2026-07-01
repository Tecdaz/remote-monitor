package com.remotemonitor.watch.data

import androidx.room.TypeConverter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Room `TypeConverter` for `List<Long>?` (REQ-WATCH-HR-IBI-10).
 *
 * The `ibis_ms` column in `clinical.measurements` is `BIGINT[]`; on the
 * watch it is a `List<Long>?` on [MeasurementEntity.ibisMs]. Room
 * stores the list as a `String` column (TEXT affinity) using this
 * converter.
 *
 * **Why JSON via Moshi (not CSV / pipe-delimited)**: the design
 * (§4) deliberately routes the storage form through Moshi so the
 * stored shape is identical to the wire shape (REQ-WATCH-HR-IBI-10
 * S02: "`Long` -> JSON int wire representation is automatic via
 * Moshi's default"). The converter's private Moshi instance uses
 * the same default `KotlinJsonAdapterFactory` rules the wire layer
 * uses, so a list written by the converter and a list emitted on
 * the wire produce byte-identical JSON arrays.
 *
 * **Null semantics**: the converter preserves nulls end-to-end. A
 * row with `ibisMs = null` is stored as `NULL` in the TEXT column
 * (not the literal string `"null"`); Room recognises `@TypeConverter`
 * null-in / null-out and skips the call entirely.
 */
class IbiListConverter {

    private val moshi: Moshi = Moshi.Builder().build()

    private val adapter = moshi.adapter<List<Long>>(
        Types.newParameterizedType(
            List::class.java,
            Long::class.javaObjectType,
        )
    )

    @TypeConverter
    fun fromIbiList(value: List<Long>?): String? =
        value?.let { adapter.toJson(it) }

    @TypeConverter
    fun toIbiList(value: String?): List<Long>? =
        value?.let { adapter.fromJson(it) }
}
