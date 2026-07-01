package com.remotemonitor.watch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [HeartRateReading] (REQ-WATCH-HR-IBI-07).
 *
 * Verifies the new `ibis` and `ibisStatus` fields are present on the data
 * class, defaulted to `null` for source compatibility with existing call
 * sites, and correctly surfaced when supplied.
 *
 * RED proof: the test file fails to compile against the current
 * `HeartRateReading(beatsPerMinute, timestampMillis)` because `ibis` and
 * `ibisStatus` are not yet declared. GREEN proof (WU-1.2): the data
 * class adds the two fields at the end of the ctor with default `null`,
 * and the assertions hold.
 */
class HeartRateReadingTest {

    @Test
    fun `HeartRateReading defaults ibis and ibisStatus to null`() {
        val reading = HeartRateReading(beatsPerMinute = 72, timestampMillis = 1000L)
        assertEquals(72, reading.beatsPerMinute)
        assertEquals(1000L, reading.timestampMillis)
        assertNull("ibis must default to null (source compat)", reading.ibis)
        assertNull("ibisStatus must default to null (source compat)", reading.ibisStatus)
    }

    @Test
    fun `HeartRateReading carries ibis and ibisStatus when supplied`() {
        val ibis = listOf(800L, 820L, 790L)
        val ibisStatus = listOf(1, 1, 1)
        val reading = HeartRateReading(
            beatsPerMinute = 72,
            timestampMillis = 1000L,
            ibis = ibis,
            ibisStatus = ibisStatus,
        )
        assertEquals(ibis, reading.ibis)
        assertEquals(ibisStatus, reading.ibisStatus)
    }
}
