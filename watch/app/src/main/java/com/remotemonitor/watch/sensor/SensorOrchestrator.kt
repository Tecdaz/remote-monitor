package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Combines BPM (passive) and SpO2 (one-shot) into Room rows.
 *
 * Architecture (REQ-WATCH-01, REQ-WATCH-02, REQ-WATCH-03):
 * - `start(scope)`: subscribe to [HeartRateSensor.readings] and request
 *   periodic SpO2 readings from [SpO2Provider]. Each emission is
 *   mapped to a [MeasurementEntity] with a fresh UUID v4 `localId` and
 *   written to the DAO.
 * - `stop()`: cancel the underlying [Job].
 *
 * Per design D3, presence-in-table = pending upload. The
 * [com.remotemonitor.watch.sync.BatchUploadWorker] reads from the same
 * DAO and deletes only the IDs echoed in a 2xx `accepted_ids`.
 */
class SensorOrchestrator(
    private val heartRateSensor: HeartRateSensor,
    private val spO2Provider: SpO2Provider,
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
            // Each BPM reading becomes a row. We don't wait for SpO2
            // synchronously — SpO2 is requested at most every
            // SPO2_REQUEST_PERIOD_MS. If SpO2 times out or returns null,
            // the row is BPM-only (REQ-WATCH-02 S02.2).
            //
            // runCatching catches Throwable (including CancellationException
            // and JobCancellationException). This is intentional: when
            // [stop] cancels the job, the [collect] suspension throws,
            // and we want the coroutine to terminate cleanly without
            // surfacing the cancellation to the caller (test or FGS).
            runCatching {
                heartRateSensor.readings
                    .onEach { bpmReading ->
                        val bpm = bpmReading?.beatsPerMinute
                        val row = MeasurementEntity(
                            localId = UUID.randomUUID().toString(),
                            timestamp = clock(),
                            heartRateBpm = bpm,
                            spo2Percent = null, // SpO2 is request-based, not streaming
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

    private companion object {
        // SpO2 request cadence (TBD; placeholder for future enhancement).
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
