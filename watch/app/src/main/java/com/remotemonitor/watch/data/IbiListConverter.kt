package com.remotemonitor.watch.data

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room [TypeConverter] for `List<Long>?` ↔ JSON string column.
 *
 * The `ibis_ms` column holds the raw IBI list from the Samsung sensor,
 * persisted as a JSON array string in Room. On the wire, Moshi serializes
 * `List<Long>` as a JSON int array — same shape, no extra adapter needed.
 */
class IbiListConverter {

    @TypeConverter
    fun fromIbiList(value: List<Long>?): String? =
        value?.let { JSONArray(it).toString() }

    @TypeConverter
    fun toIbiList(value: String?): List<Long>? =
        value?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        }
}

/**
 * Room [TypeConverter] for `List<Int>?` ↔ JSON string column.
 *
 * The `ibis_status` column holds the per-beat quality flags from the Samsung
 * sensor's `IBI_STATUS_LIST` (SDK >= 1.2.0). `0` = normal/valid beat;
 * `-1` = error/invalid beat (Samsung may introduce additional codes
 * later). The list is persisted as a JSON array string in Room and
 * serialized on the wire as a JSON int array by Moshi.
 */
class IbiStatusConverter {

    @TypeConverter
    fun fromIbiStatus(value: List<Int>?): String? =
        value?.let { JSONArray(it).toString() }

    @TypeConverter
    fun toIbiStatus(value: String?): List<Int>? =
        value?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getInt(it) }
        }
}
