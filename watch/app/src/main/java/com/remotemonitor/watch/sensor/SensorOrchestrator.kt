package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * **Health signal (wear-ui-guidelines D6)**: [healthState] exposes the
 * HR pipeline health so the home vitals Flow can gate the BPM readout.
 * It starts [SensorHealth.Healthy], flips to [SensorHealth.Failed] if
 * the `readings` collection throws (non-cancellation), and flips to
 * [SensorHealth.OffWrist] when no BPM has arrived for
 * [offWristTimeoutMs] (the sensor keeps emitting `null`). A fresh
 * non-null BPM restores [SensorHealth.Healthy].
 */
class SensorOrchestrator(
    private val heartRateSensor: HeartRateSensor,
    @Suppress("unused") private val spO2Provider: SpO2Provider,
    private val dao: MeasurementDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val offWristTimeoutMs: Long = DEFAULT_OFF_WRIST_TIMEOUT_MS,
) {
    private var job: Job? = null

    private val _healthState = MutableStateFlow<SensorHealth>(SensorHealth.Healthy)

    /**
     * HR pipeline health (wear-ui-guidelines D6). Consumed by the home
     * vitals Flow to suppress the HR readout when [SensorHealth.Failed].
     */
    val healthState: StateFlow<SensorHealth> = _healthState.asStateFlow()

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
            var lastBpmAt = clock()
            runCatching {
                heartRateSensor.readings
                    .onEach { bpmReading ->
                        val now = clock()
                        val bpm = bpmReading?.beatsPerMinute
                        if (bpm != null) {
                            // A fresh reading means the pipeline is alive
                            // and on-wrist again.
                            lastBpmAt = now
                            _healthState.value = SensorHealth.Healthy
                        } else if (now - lastBpmAt >= offWristTimeoutMs) {
                            // Sustained null readings = off-wrist (D6). We
                            // do NOT overwrite a Failed state here; only a
                            // successful reading clears Failed.
                            if (_healthState.value != SensorHealth.Failed) {
                                _healthState.value = SensorHealth.OffWrist
                            }
                        }
                        val row = MeasurementEntity(
                            localId = UUID.randomUUID().toString(),
                            timestamp = now,
                            heartRateBpm = bpm,
                            spo2Percent = null,
                            ibisMs = bpmReading?.ibis,
                        )
                        dao.insert(row)
                    }
                    .collect()
            }.onFailure { cause ->
                // Cancellation is normal teardown (see [stop]); it is NOT
                // a sensor failure and must not flip the health signal.
                if (cause is CancellationException) return@onFailure
                _healthState.value = SensorHealth.Failed
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

    private companion object {
        /**
         * Default off-wrist grace period. If no BPM arrives within this
         * window (the sensor keeps emitting `null`), [healthState] flips
         * to [SensorHealth.OffWrist]. 30 s balances responsiveness with
         * transient-null tolerance on the wrist.
         */
        const val DEFAULT_OFF_WRIST_TIMEOUT_MS = 30_000L
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
