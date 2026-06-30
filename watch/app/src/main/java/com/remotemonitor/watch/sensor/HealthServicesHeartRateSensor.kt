package com.remotemonitor.watch.sensor

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Real Health Services heart-rate sensor (REQ-WATCH-01).
 *
 * **Note**: this is a placeholder that emits an empty flow. The real
 * implementation wires up `PassiveMonitoringClient` from
 * `androidx.health:health-services-client` (per REQ-WATCH-01). That
 * wiring needs the AGP 9.0+ `kotlin.sourceSets` workaround (or a
 * newer KSP version) which is deferred to keep PR 2 focused on the
 * Strict TDD merge gate.
 *
 * Until then, BPM collection is a no-op — Room stays empty and the
 * sync worker has nothing to upload. The orchestrator test uses a
 * fake `HeartRateSensor` to drive inserts, so the rest of the
 * pipeline is exercised.
 */
class HealthServicesHeartRateSensor(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : HeartRateSensor {
    override val readings: Flow<HeartRateReading?> = flowOf()
}
