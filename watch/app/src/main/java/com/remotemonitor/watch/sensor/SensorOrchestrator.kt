package com.remotemonitor.watch.sensor

import android.util.Log
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
     *
     * REQ-WATCH-BG-01: the collection runs in a retry loop so that a
     * Samsung SDK disconnection (e.g. when the app goes to background)
     * triggers an automatic reconnect after [RETRY_DELAY_MS]. Only a
     * [CancellationException] (from [stop]) breaks the loop.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // Track the last BPM timestamp per-collection attempt
                // so off-wrist detection resets on each reconnect.
                var lastBpmAt = clock()
                val result = runCatching {
                    heartRateSensor.readings
                        .onEach { bpmReading ->
                            val now = clock()
                            val bpm = bpmReading?.beatsPerMinute
                            // REQ-WATCH-BG-02: the Samsung SDK may emit
                            // BPM=0 during sensor recovery. A BPM of 0
                            // is physiologically invalid and would be
                            // rejected by the backend. Skip this reading
                            // entirely — don't write it to Room, don't
                            // reset the off-wrist timer.
                            if (bpm != null && bpm <= 0) return@onEach
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
                            // REQ-NOISE-WATCH-01: preserve the raw IBI array
                            // and the matching quality flags. Filtering by
                            // status now happens on the backend / frontend so
                            // clinicians can toggle raw vs. filtered views.
                            // Samsung IBI_STATUS_LIST (SDK >= 1.2.0):
                            //   0  = normal/valid beat (accept)
                            //  -1  = error/invalid beat (reject)
                            val ibis = bpmReading?.ibis
                            val ibisStatus = bpmReading?.ibisStatus?.takeIf { it.size == ibis?.size ?: 0 }
                            val row = MeasurementEntity(
                                localId = UUID.randomUUID().toString(),
                                timestamp = now,
                                heartRateBpm = bpm,
                                spo2Percent = null,
                                ibisMs = ibis,
                                ibisStatus = ibisStatus,
                            )
                            dao.insert(row)
                        }
                        .collect()
                }
                val cause = result.exceptionOrNull()
                when {
                    cause is CancellationException -> return@launch
                    cause != null -> {
                        // REQ-WATCH-BG-01: sensor collection threw
                        // (non-cancellation). Flip health to Failed,
                        // then retry after delay.
                        Log.w(TAG, "Sensor flow threw; retrying in ${RETRY_DELAY_MS}ms", cause)
                        _healthState.value = SensorHealth.Failed
                    }
                    else -> {
                        // REQ-WATCH-BG-01: flow completed normally
                        // (e.g. Samsung SDK onConnectionEnded closed it).
                        // Retry after delay so the orchestrator
                        // reconnects automatically.
                        Log.d(TAG, "Sensor flow completed; retrying in ${RETRY_DELAY_MS}ms")
                    }
                }
                if (isActive) {
                    delay(RETRY_DELAY_MS)
                }
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
        const val TAG = "SensorOrchestrator"

        /**
         * Default off-wrist grace period. If no BPM arrives within this
         * window (the sensor keeps emitting `null`), [healthState] flips
         * to [SensorHealth.OffWrist]. 30 s balances responsiveness with
         * transient-null tolerance on the wrist.
         */
        const val DEFAULT_OFF_WRIST_TIMEOUT_MS = 30_000L

        /**
         * REQ-WATCH-BG-01: delay between reconnect attempts when the
         * sensor flow ends (disconnection, SDK error, etc.). 5 s gives
         * the Samsung Health Service enough time to recover without
         * busy-looping.
         */
        const val RETRY_DELAY_MS = 5_000L
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
