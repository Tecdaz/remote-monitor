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
 * Band-only orchestrator. Collects the Polar H10 heart-rate/RR stream
 * and, when configured, the Polar SDK ECG snippets, writing one
 * [MeasurementEntity] per emission to Room.
 *
 * The watch's own sensors are no longer read. The app runs on Wear OS,
 * phones and tablets, but the only measurement device is the band.
 *
 * **Health signal**: [healthState] exposes the band link health so the
 * home vitals surface can gate the BPM readout. It starts
 * [SensorHealth.Connecting], flips to [SensorHealth.Failed] if the
 * readings collection throws (non-cancellation), and flips to
 * [SensorHealth.OffWrist] when no BPM has arrived for
 * [offWristTimeoutMs] (the band keeps emitting `null`). A fresh
 * non-null BPM restores [SensorHealth.Healthy].
 */
class SensorOrchestrator(
    private val polarSensor: HeartRateSensor?,
    private val dao: MeasurementDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val offWristTimeoutMs: Long = DEFAULT_OFF_WRIST_TIMEOUT_MS,
    /**
     * Optional ECG snippet source (Polar SDK). Each 10 s snippet is
     * persisted as its own row with `heartRateBpm = null` and the
     * `ecgUv` / `ecgSampleRateHz` columns set. `null` = ECG disabled.
     */
    private val ecgProvider: EcgSnippetProvider? = null,
) {
    private var job: Job? = null

    private val _healthState = MutableStateFlow<SensorHealth>(SensorHealth.Connecting)

    /** Band link health, consumed by the home vitals surface. */
    val healthState: StateFlow<SensorHealth> = _healthState.asStateFlow()

    /**
     * Start collecting. Idempotent: a second call while running is a
     * no-op. The collection runs in a retry loop so a link drop
     * triggers an automatic reconnect after [RETRY_DELAY_MS]. Only a
     * [CancellationException] (from [stop]) breaks the loop.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            val band = polarSensor
            if (band == null) {
                Log.w(TAG, "no polar band configured; nothing to collect")
                return@launch
            }
            val hrJob = launch { runRetryLoop(band) }
            val ecgJob = ecgProvider?.let { ecg ->
                launch { runEcgLoop(ecg) }
            }
            hrJob.join()
            ecgJob?.join()
        }
    }

    /**
     * Collect + retry loop for ECG snippets. Each snippet becomes one
     * Room row with `heartRateBpm = null`; the backend stores it as an
     * ECG strip, not a vital sign.
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
                            ibisMs = null,
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
     * Collect + retry loop for the band's heart-rate/RR stream.
     */
    private suspend fun CoroutineScope.runRetryLoop(sensor: HeartRateSensor) {
        while (isActive) {
            var lastBpmAt = clock()
            val result = runCatching {
                sensor.readings
                    .onEach { bpmReading ->
                        val now = clock()
                        val bpm = bpmReading?.beatsPerMinute
                        // A BPM of 0 is physiologically invalid and
                        // would be rejected by the backend. Skip it
                        // without writing a row or resetting the
                        // off-wrist timer.
                        if (bpm != null && bpm <= 0) return@onEach
                        if (bpm != null) {
                            lastBpmAt = now
                            _healthState.value = SensorHealth.Healthy
                        } else if (now - lastBpmAt >= offWristTimeoutMs) {
                            if (_healthState.value != SensorHealth.Failed) {
                                _healthState.value = SensorHealth.OffWrist
                            }
                        }
                        dao.insert(
                            MeasurementEntity(
                                localId = UUID.randomUUID().toString(),
                                timestamp = now,
                                heartRateBpm = bpm,
                                ibisMs = bpmReading?.ibis,
                                ecgUv = null,
                                ecgSampleRateHz = null,
                            ),
                        )
                    }
                    .collect()
            }
            val cause = result.exceptionOrNull()
            when {
                cause is CancellationException -> return
                cause != null -> {
                    Log.w(TAG, "Band flow threw; retrying in ${RETRY_DELAY_MS}ms", cause)
                    _healthState.value = SensorHealth.Failed
                }
                else -> Log.d(TAG, "Band flow completed; retrying in ${RETRY_DELAY_MS}ms")
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
         * window (the band keeps emitting `null`), [healthState] flips
         * to [SensorHealth.OffWrist]. 30 s balances responsiveness with
         * transient-null tolerance.
         */
        const val DEFAULT_OFF_WRIST_TIMEOUT_MS = 30_000L

        /** Delay between reconnect attempts after a link drop. */
        const val RETRY_DELAY_MS = 5_000L
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
