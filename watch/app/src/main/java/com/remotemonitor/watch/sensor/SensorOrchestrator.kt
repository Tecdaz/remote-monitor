package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * HR-only orchestrator. Subscribes to [HeartRateSensor.readings] and
 * writes a [MeasurementEntity] per emission.
 *
 * **HR-only scope** (product decision 2026-07-01, user "Quiero que solo
 * se mida el HR"): the previous version also polled a one-shot
 * [SpO2Provider.read] on a 60 s cadence and merged the cached value
 * into each BPM row. That created a binder race with the continuous
 * HR provider (both used the same `HealthTrackingService` instance,
 * tearing the connection down within 15 ms of HR connect). The SpO2
 * poller is removed for now. The `fix-samsung-spo2-status-gate` cycle
 * is paused and `spO2Provider` is kept in the constructor signature
 * (still used in the wiring DI) but never invoked here. The
 * `MeasurementEntity.spo2Percent` field stays nullable and is written
 * as `null` for every row.
 *
 * Architecture (REQ-WATCH-01, REQ-WATCH-HR-IBI-01):
 * - `start(scope)`: collect `heartRateSensor.readings`; on each
 *   emission, write a [MeasurementEntity] with a fresh UUID v4
 *   `localId` and `spo2Percent = null` to the DAO.
 * - `stop()`: cancel the underlying [Job].
 *
 * Per design D3, presence-in-table = pending upload. The
 * [com.remotemonitor.watch.sync.BatchUploadWorker] reads from the same
 * DAO and deletes only the IDs echoed in a 2xx `accepted_ids`.
 */
class SensorOrchestrator(
    private val heartRateSensor: HeartRateSensor,
    @Suppress("unused") private val spO2Provider: SpO2Provider,
    private val dao: MeasurementDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null

    /**
     * Start collecting sensor data and writing to Room. Idempotent: a
     * second call while running is a no-op.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // runCatching catches Throwable (including CancellationException
            // and JobCancellationException). This is intentional: when
            // [stop] cancels the job, the [collect] suspension throws,
            // and we want the coroutine to terminate cleanly without
            // surfacing the cancellation to the caller (test or FGS).
            runCatching {
                heartRateSensor.readings
                    .onEach { bpmReading ->
                        val row = MeasurementEntity(
                            localId = UUID.randomUUID().toString(),
                            timestamp = clock(),
                            heartRateBpm = bpmReading?.beatsPerMinute,
                            spo2Percent = null,
                        )
                        dao.insert(row)
                    }
                    .collect()
            }
        }
    }

    /**
     * Stop collecting. Safe to call when not running.
     */
    fun stop() {
        job?.cancel()
        job = null
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
