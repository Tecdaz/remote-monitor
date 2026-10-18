package com.remotemonitor.watch.sensor

/**
 * T1 polar-h10-validation: pure parser for the BLE Heart Rate
 * Measurement characteristic (GATT 0x2A37).
 *
 * Layout (Bluetooth SIG): flags(1) + BPM(uint8|uint16 LE) +
 * [energy uint16 LE if flags bit3] + RR uint16 LE each in 1/1024 s
 * (if flags bit4). RR converts to ms via `raw * 1000 / 1024`.
 *
 * Pure Kotlin (no Android APIs) so unit tests run on the JVM.
 */
object PolarHrsParser {

    data class Sample(
        val beatsPerMinute: Int?,
        val rrMs: List<Long>,
    )

    fun parse(value: ByteArray): Sample {
        if (value.isEmpty()) return Sample(null, emptyList())
        val flags = value[0].toInt() and 0xFF
        val uint16 = (flags and 0x01) != 0
        val energyPresent = (flags and 0x08) != 0
        val rrPresent = (flags and 0x10) != 0
        var offset = 1
        val bpm: Int? = if (uint16) {
            if (value.size < offset + 2) return Sample(null, emptyList())
            (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                .also { offset += 2 }
        } else {
            if (value.size < offset + 1) return Sample(null, emptyList())
            (value[offset].toInt() and 0xFF).also { offset += 1 }
        }
        if (energyPresent) {
            if (value.size < offset + 2) return Sample(bpm, emptyList())
            offset += 2
        }
        if (!rrPresent) return Sample(bpm, emptyList())
        val rr = mutableListOf<Long>()
        while (offset + 1 < value.size) {
            val raw = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
            rr += raw * 1000L / 1024L
            offset += 2
        }
        return Sample(bpm, rr)
    }
}
