package com.remotemonitor.watch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [HeartRateReading], the band-only sensor sample. */
class HeartRateReadingTest {

    @Test
    fun `HeartRateReading defaults ibis to null`() {
        val reading = HeartRateReading(beatsPerMinute = 72, timestampMillis = 1000L)
        assertEquals(72, reading.beatsPerMinute)
        assertEquals(1000L, reading.timestampMillis)
        assertNull(reading.ibis)
    }

    @Test
    fun `HeartRateReading carries the RR array when supplied`() {
        val ibis = listOf(800L, 820L, 790L)
        val reading = HeartRateReading(
            beatsPerMinute = 72,
            timestampMillis = 1000L,
            ibis = ibis,
        )
        assertEquals(ibis, reading.ibis)
    }
}
