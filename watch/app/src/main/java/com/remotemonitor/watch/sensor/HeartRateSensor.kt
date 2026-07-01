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

/**
 * Single BPM reading at a point in time.
 *
 * [ibis] and [ibisStatus] are added by the `feat-watch-samsung-hr-ibi`
 * cycle (REQ-WATCH-HR-IBI-07) to carry inter-beat-interval data from
 * the Samsung `HEART_RATE_CONTINUOUS` tracker. Both are defaulted to
 * `null` at the END of the parameter list so existing call sites
 * (`HealthServicesHeartRateSensor`, `SensorOrchestratorTest`,
 * `HealthServicesHeartRateSensorTest`) keep compiling without changes.
 */
data class HeartRateReading(
    val beatsPerMinute: Int,
    val timestampMillis: Long,
    val ibis: List<Long>? = null,
    val ibisStatus: List<Int>? = null,
)
