package com.remotemonitor.watch.data

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room [TypeConverter] for `List<Long>?` <-> JSON string column.
 *
 * The `ibis_ms` column holds the raw RR intervals from the Polar H10,
 * persisted as a JSON array string in Room. On the wire, Moshi serializes
 * `List<Long>` as a JSON int array, the same shape the backend expects.
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
 * Room [TypeConverter] for `List<Int>?` <-> JSON string column.
 *
 * The `ecg_uv` column holds a Polar H10 ECG snippet (10 s at 130 Hz,
 * voltages in microvolts), persisted as a JSON array string in Room and
 * serialized as a JSON int array by Moshi.
 */
class EcgListConverter {

    @TypeConverter
    fun fromEcgList(value: List<Int>?): String? =
        value?.let { JSONArray(it).toString() }

    @TypeConverter
    fun toEcgList(value: String?): List<Int>? =
        value?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getInt(it) }
        }
}
