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
    private val polarSensor: HeartRateSensor? = null,
    /**
     * Band-only mode (polar-ecg-integration T6): when false the Samsung
     * pipeline is paused and the Polar strap becomes the sole source.
     * The Polar loop then owns `healthState` so the home vitals Flow
     * keeps working against the band.
     */
    private val samsungEnabled: Boolean = true,
    /**
     * T2 polar-ecg-integration: optional ECG snippet source (Polar SDK).
     * Each 10 s snippet is persisted as its own `polar_h10` row with
     * `heartRateBpm = null` and the `ecgUv` / `ecgSampleRateHz`
     * columns set. `null` = ECG disabled (HR-only pipelines).
     */
    private val ecgProvider: EcgSnippetProvider? = null,
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
            // T2 polar-h10-validation: Samsung stays the primary pipeline
            // (owns healthState); the Polar strap collects concurrently as
            // an independent validation source. Both retry independently.
            // T6 polar-ecg-integration (band-only): with samsungEnabled=false
            // the Samsung loop is paused and Polar becomes the sole source
            // (and owns healthState so the UI keeps reflecting the band).
            if (!samsungEnabled) {
                Log.i(TAG, "band-only mode: samsung pipeline paused")
            }
            if (!samsungEnabled && polarSensor == null) {
                Log.w(TAG, "band-only mode with no polar sensor configured; nothing to collect")
                return@launch
            }
            val samsungJob = if (samsungEnabled) {
                launch {
                    runRetryLoop(heartRateSensor, MeasurementEntity.SOURCE_SAMSUNG, updateHealth = true)
                }
            } else {
                null
            }
            val polarJob = polarSensor?.let { polar ->
                launch {
                    runRetryLoop(polar, MeasurementEntity.SOURCE_POLAR, updateHealth = !samsungEnabled)
                }
            }
            // T2 polar-ecg-integration: ECG snippets persist as their
            // own rows (no BPM). Same retry semantics as the HR loops.
            val ecgJob = ecgProvider?.let { ecg ->
                launch {
                    runEcgLoop(ecg)
                }
            }
            samsungJob?.join()
            polarJob?.join()
            ecgJob?.join()
        }
    }

    /**
     * T2 polar-ecg-integration: collect+retry loop for ECG snippets.
     * Each 10 s snippet becomes one `polar_h10` Room row with
     * `heartRateBpm = null`; the backend treats it as a validation
     * strip, not a vital sign. Failures retry after [RETRY_DELAY_MS]
     * like the HR loops; only cancellation breaks the loop.
     */
    private suspend fun CoroutineScope.runEcgLoop(
        ecg: EcgSnippetProvider,
    ) {
        while (isActive) {
            val result = runCatching {
                ecg.snippets.collect { snippet ->
                    if (snippet == null) return@collect
                    if (snippet.voltagesUv.isEmpty()) return@collect
                    dao.insert(
                        MeasurementEntity(
                            localId = UUID.randomUUID().toString(),
                            timestamp = clock(),
                            heartRateBpm = null,
                            spo2Percent = null,
                            source = MeasurementEntity.SOURCE_POLAR,
                            ecgUv = snippet.voltagesUv,
                            ecgSampleRateHz = snippet.sampleRateHz,
                        ),
                    )
                }
            }
            val cause = result.exceptionOrNull()
            when {
                cause is CancellationException -> return
                cause != null -> Log.w(TAG, "ECG flow threw; retrying in ${RETRY_DELAY_MS}ms", cause)
                else -> Log.d(TAG, "ECG flow completed; retrying in ${RETRY_DELAY_MS}ms")
            }
            if (isActive) {
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * T2: shared collect+retry loop for one sensor source. Extracted
     * verbatim from the former Samsung-only `start` body; the only
     * additions are the [source] tag on each row and the [updateHealth]
     * gate (only Samsung drives `healthState`/off-wrist).
     */
    private suspend fun CoroutineScope.runRetryLoop(
        sensor: HeartRateSensor,
        source: String,
        updateHealth: Boolean,
    ) {
        while (isActive) {
            // Track the last BPM timestamp per-collection attempt
            // so off-wrist detection resets on each reconnect.
            var lastBpmAt = clock()
            val result = runCatching {
                sensor.readings
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
                            if (updateHealth) _healthState.value = SensorHealth.Healthy
                        } else if (updateHealth && now - lastBpmAt >= offWristTimeoutMs) {
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
                            // feat-watch-hr-status-surface: pass the Samsung
                            // per-reading lifecycle status through to Room
                            // verbatim. The provider already filters out the
                            // null/zero cases upstream; we propagate whatever
                            // the SDK gave us (success = 1, off-wrist = -3,
                            // BIA = -999/0, etc.) so the backend gets the
                            // full picture for analytics.
                            val hrStatus = bpmReading?.hrStatus
                            val row = MeasurementEntity(
                                localId = UUID.randomUUID().toString(),
                                timestamp = now,
                                heartRateBpm = bpm,
                                spo2Percent = null,
                                ibisMs = ibis,
                                ibisStatus = ibisStatus,
                                hrStatus = hrStatus,
                                source = source,
                            )
                            dao.insert(row)
                        }
                        .collect()
                }
                val cause = result.exceptionOrNull()
                when {
                    cause is CancellationException -> return
                    cause != null -> {
                        // REQ-WATCH-BG-01: sensor collection threw
                        // (non-cancellation). Only the Samsung pipeline
                        // owns healthState; a Polar failure stays local
                        // to its retry loop.
                        Log.w(TAG, "Sensor flow threw; retrying in ${RETRY_DELAY_MS}ms", cause)
                        if (updateHealth) _healthState.value = SensorHealth.Failed
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

    /** Stop collecting. Safe to call when not running. */
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
