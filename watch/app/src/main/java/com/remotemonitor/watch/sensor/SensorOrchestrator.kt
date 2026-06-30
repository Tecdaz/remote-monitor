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
 * Combines BPM (passive) and SpO2 (one-shot) into Room rows.
 *
 * Architecture (REQ-WATCH-01, REQ-WATCH-02, REQ-WATCH-03, REQ-WATCH-66,
 * REQ-WATCH-67):
 * - `start(scope)`: subscribe to [HeartRateSensor.readings] and request
 *   periodic SpO2 readings from [SpO2Provider] every
 *   [SPO2_REQUEST_PERIOD_MS] (default 60 s, overridable via the
 *   [spO2RequestPeriodMs] ctor param for tests). Each BPM emission
 *   is mapped to a [MeasurementEntity] with a fresh UUID v4 `localId`
 *   and the most-recent cached SpO2 reading, written to the DAO.
 * - `stop()`: cancel the underlying [Job].
 *
 * Per design D3, presence-in-table = pending upload. The
 * [com.remotemonitor.watch.sync.BatchUploadWorker] reads from the same
 * DAO and deletes only the IDs echoed in a 2xx `accepted_ids`.
 *
 * **SpO2 cache (REQ-WATCH-66, REQ-WATCH-67)**: a background coroutine
 * polls [SpO2Provider.read] at the configured cadence and updates the
 * `@Volatile latestSpO2Percent` field. The BPM consumer reads it on
 * every tick. `combine` was rejected because it would drop BPM ticks
 * until BOTH flows have a value (and BPM is the primary signal;
 * skipping a tick loses user coverage).
 */
class SensorOrchestrator(
    private val heartRateSensor: HeartRateSensor,
    private val spO2Provider: SpO2Provider,
    private val dao: MeasurementDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val spO2RequestPeriodMs: Long = SPO2_REQUEST_PERIOD_MS,
) {
    /**
     * The most recent successful SpO2 reading, expressed as a
     * percentage (0..100). `null` until the first successful poll
     * (REQ-WATCH-67 S02.2). `@Volatile` gives us reference-write
     * visibility across the two coroutines: the BPM consumer only
     * reads, the SpO2 poller only writes.
     */
    @Volatile
    private var latestSpO2Percent: Double? = null

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
            // [spO2RequestPeriodMs] by the side-channel poller. If
            // SpO2 times out or returns null, the row is BPM-only
            // (REQ-WATCH-02 S02.2).
            //
            // runCatching catches Throwable (including CancellationException
            // and JobCancellationException). This is intentional: when
            // [stop] cancels the job, the [collect] suspension throws,
            // and we want the coroutine to terminate cleanly without
            // surfacing the cancellation to the caller (test or FGS).
            runCatching {
                // Side-channel SpO2 poller. Updates latestSpO2Percent
                // on every successful read. Failures (null return)
                // are tolerated: the cache keeps its previous value.
                launch {
                    while (isActive) {
                        latestSpO2Percent = spO2Provider.read()?.percent
                        delay(spO2RequestPeriodMs)
                    }
                }
                heartRateSensor.readings
                    .onEach { bpmReading ->
                        val bpm = bpmReading?.beatsPerMinute
                        val row = MeasurementEntity(
                            localId = UUID.randomUUID().toString(),
                            timestamp = clock(),
                            heartRateBpm = bpm,
                            spo2Percent = latestSpO2Percent,
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

    companion object {
        /**
         * SpO2 request cadence (REQ-WATCH-66). Discrete, on-demand —
         * not a stream. Defaults to 60 s; tunable via the
         * [spO2RequestPeriodMs] ctor param for tests. A 60 s budget
         * gives ~1 SpO2 reading per BPM emission cluster without
         * burning the sensor's battery.
         */
        const val SPO2_REQUEST_PERIOD_MS: Long = 60_000L
    }
}

/** Tiny extension to make `.onEach { ... }.collect()` readable. */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collect() = collect { /* no-op */ }
