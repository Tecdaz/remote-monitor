package com.remotemonitor.watch.sensor

import kotlinx.coroutines.flow.Flow

/**
 * Heart rate sensor source (REQ-WATCH-01).
 *
 * Wraps Android Health Services' `PassiveMonitoringClient` for
 * battery-efficient, always-on BPM collection. Implementation lands in
 * [HealthServicesHeartRateSensor] once the dependency tree is wired
 * (Health Services Client + Samsung SDK per REQ-WATCH-29).
 *
 * The sensor is abstracted behind an interface so the
 * [SensorOrchestrator] can be unit-tested with a fake.
 */
interface HeartRateSensor {
    /**
     * Continuous flow of BPM readings.
     *
     * - Emits a reading each time the sensor produces a new BPM value.
     * - Emits `null` when the watch is off-wrist (no BPM measurable).
     * - Never throws; failures are mapped to `null` and emitted.
     */
    val readings: Flow<HeartRateReading?>
}

/** Single BPM reading at a point in time. */
data class HeartRateReading(
    val beatsPerMinute: Int,
    val timestampMillis: Long,
)
