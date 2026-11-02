package com.remotemonitor.watch.sensor

import kotlinx.coroutines.flow.Flow

/**
 * Heart-rate source backed by the Polar H10 band.
 *
 * The production implementation is [PolarHrsProvider] (standard BLE
 * Heart Rate Service, GATT 0x180D). The interface keeps the
 * [SensorOrchestrator] unit-testable with a fake.
 */
interface HeartRateSensor {
    /**
     * Continuous flow of BPM readings.
     *
     * - Emits a reading each time the band produces a new BPM value.
     * - Emits `null` when no sample is available (link retry,
     *   off-body, or a malformed notification).
     * - Never throws; failures are mapped to `null` and emitted.
     */
    val readings: Flow<HeartRateReading?>
}

/**
 * Single BPM reading at a point in time.
 *
 * [ibis] carries the RR intervals (milliseconds) that the H10 includes
 * in the same Heart Rate Measurement notification. It is `null` when the
 * band did not provide RR data for this notification.
 */
data class HeartRateReading(
    val beatsPerMinute: Int,
    val timestampMillis: Long,
    val ibis: List<Long>? = null,
)
