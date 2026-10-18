package com.remotemonitor.watch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1 polar-h10-validation: pure parser for the BLE Heart Rate
 * Measurement characteristic (GATT 0x2A37, Polar H10 compatible).
 *
 * Run from `watch/`:
 *   ./gradlew :app:testDebugUnitTest --tests "com.remotemonitor.watch.sensor.PolarHrsParserTest"
 */
class PolarHrsParserTest {

    @Test
    fun `uint8 bpm without rr parses`() {
        // flags=0x00: uint8 BPM, no RR. BPM=72.
        val sample = PolarHrsParser.parse(byteArrayOf(0x00, 72))
        assertEquals(72, sample.beatsPerMinute)
        assertEquals(emptyList<Long>(), sample.rrMs)
    }

    @Test
    fun `uint16 bpm parses`() {
        // flags=0x01: uint16 BPM little-endian. 0x012C = 300.
        val sample = PolarHrsParser.parse(byteArrayOf(0x01, 0x2C, 0x01))
        assertEquals(300, sample.beatsPerMinute)
    }

    @Test
    fun `rr intervals convert from 1-1024s to ms`() {
        // flags=0x10: RR present. BPM=60, RR raw 1024 (=1000ms) + 512 (=500ms).
        val sample = PolarHrsParser.parse(
            byteArrayOf(0x10, 60, 0x00, 0x04, 0x00, 0x02),
        )
        assertEquals(60, sample.beatsPerMinute)
        assertEquals(listOf(1000L, 500L), sample.rrMs)
    }

    @Test
    fun `energy expended is skipped before rr`() {
        // flags=0x18: energy (2 bytes) + RR. BPM=65, energy=0x000A, RR raw 1024.
        val sample = PolarHrsParser.parse(
            byteArrayOf(0x18, 65, 0x0A, 0x00, 0x00, 0x04),
        )
        assertEquals(65, sample.beatsPerMinute)
        assertEquals(listOf(1000L), sample.rrMs)
    }

    @Test
    fun `short payload returns null bpm`() {
        assertNull(PolarHrsParser.parse(byteArrayOf()).beatsPerMinute)
        assertNull(PolarHrsParser.parse(byteArrayOf(0x00)).beatsPerMinute)
    }
}
