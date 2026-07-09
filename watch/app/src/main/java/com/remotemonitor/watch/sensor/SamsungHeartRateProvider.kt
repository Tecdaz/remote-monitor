package com.remotemonitor.watch.sensor

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real [HeartRateSensor] backed by the Samsung Health Sensor SDK AAR
 * v1.4.1, `HEART_RATE_CONTINUOUS` tracker (REQ-WATCH-HR-IBI-01..09).
 *
 * **Why continuous, not one-shot (deviation from `SamsungSpO2Provider`)**:
 * [HeartRateSensor.readings] is a `Flow<HeartRateReading?>` consumed by
 * [SensorOrchestrator] for the lifetime of the app process. The
 * `callbackFlow` shape (cold flow, register on first collect, release on
 * `awaitClose`) matches `HealthServicesHeartRateSensor`'s pattern,
 * substituting the Samsung binder for the Health Services binder.
 *
 * **Defensive binder lifecycle** (skill hard rules #4, #5, #7):
 * - One `HealthTrackingService` per `callbackFlow` collect (single-shot
 *   binder, not reusable after `disconnectService()`).
 * - `getHealthTracker` wrapped in `runCatching`; null result -> close.
 * - `disconnectService` in `awaitClose`, gated on a
 *   `connectionEstablished` flag so pre-`onConnectionSuccess` cancel
 *   does NOT call disconnect on a not-yet-live service (REQ-WATCH-HR-IBI-05
 *   S02 — design §17 WARNING).
 *
 * **IBI batching rule (skill "Critical batching rule")**:
 * `HEART_RATE_CONTINUOUS` accumulates inter-beat intervals into the
 * **first** `DataPoint` of each `onDataReceived` callback. With display
 * on, 1 DataPoint per call; with display off, multiple DataPoints per
 * call but only the first carries `IBI_LIST`. We read the first
 * DataPoint and drop the rest.
 *
 * **Type conversion (Int -> Long)**: the AAR exposes `IBI_LIST` as
 * `List<Integer>`; we convert to `List<Long>` at the read boundary for
 * cleaner downstream HRV math (RMSSD is a sum of squared `Double`
 * diffs; the conversion avoids a sneaky overflow if the consumer
 * later extends the unit to microseconds for raw PPG-derived IBIs).
 *
 * @param context Android [Context] used by the SDK to perform
 *   `bindService` on `com.samsung.android.service.health`.
 * @param serviceFactory factory for the [HealthTrackingService].
 *   Defaults to `::HealthTrackingService`; tests inject a mock.
 * @param clock wall-clock supplier for `HeartRateReading.timestampMillis`.
 *   Tests inject a fixed lambda; production: `System::currentTimeMillis`.
 */
class SamsungHeartRateProvider(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
        ::HealthTrackingService,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeartRateSensor {

    override val readings: Flow<HeartRateReading?> = callbackFlow {
        // Capture the producer scope so ConnectionListener callbacks
        // can close the flow when the SDK drops the connection.
        val producer = this@callbackFlow
        lateinit var service: HealthTrackingService
        // REQ-WATCH-HR-IBI-05 S02: track whether the binder ever
        // reached `onConnectionSuccess`. Pre-connection cancel must
        // NOT call `disconnectService` because the binder was never
        // live (calling it on a not-yet-connected service is a
        // no-op per the AAR, but spec S02 mandates "NOT called").
        // Post-connection cancel MUST call it (binder is live).
        var connectionEstablished: Boolean = false

        // REQ-WATCH-BG-01: track the last time data arrived from the
        // SDK. If the SDK goes silent (ambient mode, background, etc.)
        // without calling onConnectionEnded, the idle monitor closes
        // the flow so the orchestrator can reconnect.
        var lastDataAt: Long = clock()
        var idleMonitor: Job? = null

        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                connectionEstablished = true
                // REQ-WATCH-HR-IBI-06: wrap getHealthTracker in
                // runCatching; on exception or null, emit null and
                // close. The `close()` triggers `awaitClose` to
                // run, which is where we call `disconnectService` —
                // no need to call it twice here.
                val tracker = runCatching {
                    service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                }.getOrNull()
                if (tracker == null) {
                    trySend(null)
                    close()
                    return
                }

                // REQ-WATCH-BG-01: start the idle monitor. If no
                // data arrives for IDLE_TIMEOUT_MS, close the flow
                // so SensorOrchestrator's retry loop reconnects.
                idleMonitor = launch {
                    while (isActive) {
                        delay(IDLE_CHECK_INTERVAL_MS)
                        if (clock() - lastDataAt > IDLE_TIMEOUT_MS) {
                            Log.d(TAG, "No sensor data for ${IDLE_TIMEOUT_MS}ms; closing flow for reconnect")
                            producer.close()
                            return@launch
                        }
                    }
                }

                tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        // Reset the idle timer on every data batch.
                        lastDataAt = clock()
                        // REQ-WATCH-HR-IBI-02: only the first DataPoint
                        // carries IBI_LIST (and IBI_STATUS_LIST).
                        // Subsequent DataPoints in the same callback are
                        // dropped (their IBI is null).
                        val first = dataPoints.firstOrNull() ?: return
                        val rawBpm = first.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: return
                        // REQ-WATCH-BG-02: the Samsung SDK may emit
                        // HEART_RATE=0 during connection establishment
                        // or sensor recovery. Treat it as a null
                        // reading — the orchestrator will handle it
                        // via off-wrist detection. We still emit the
                        // reading so the idle timer resets and the flow
                        // doesn't get prematurely closed.
                        val validBpm: Int? = if (rawBpm <= 0) null else rawBpm
                        // REQ-WATCH-HR-IBI-03: Int -> Long at the read
                        // boundary (cleaner downstream HRV math).
                        val ibis = first.getValue(ValueKey.HeartRateSet.IBI_LIST)
                            ?.map { it.toLong() }
                        val ibisStatus = first.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                        trySend(
                            HeartRateReading(
                                beatsPerMinute = validBpm ?: 0,
                                timestampMillis = clock(),
                                ibis = ibis,
                                ibisStatus = ibisStatus,
                            ),
                        )
                    }

                    override fun onError(error: HealthTracker.TrackerError) {
                        // REQ-WATCH-HR-IBI-04: TrackerError -> null
                        // emission, then close. awaitClose releases the
                        // binder.
                        trySend(null)
                        close()
                    }

                    override fun onFlushCompleted() {
                        // Not used: HEART_RATE_CONTINUOUS delivers
                        // onDataReceived on its own cadence; we do not
                        // call tracker.flush().
                    }
                })
            }

            override fun onConnectionFailed(error: HealthTrackerException) {
                // The binder is live (we just got a connectService
                // response); mark the connection as established so
                // awaitClose releases it. Without this, the gate
                // would skip the disconnect on the failure path
                // and leak the binder.
                connectionEstablished = true
                // REQ-WATCH-HR-IBI-04: ConnectionFailed -> null + close.
                trySend(null)
                close()
            }

            override fun onConnectionEnded() {
                // REQ-WATCH-BG-01: the Samsung Health SDK closed the
                // connection (typically when the app goes to background).
                // Close the flow so SensorOrchestrator's retry loop can
                // reconnect. awaitClose releases the binder.
                Log.d(TAG, "HealthTrackingService connection ended; closing flow for reconnect")
                producer.close()
            }
        }

        service = serviceFactory(listener, context)
        service.connectService()

        // REQ-WATCH-HR-IBI-05 S01 + S02: awaitClose releases the binder
        // on collection cancel, but ONLY if the binder is live
        // (post-`onConnectionSuccess`). The pre-connection cancel
        // path must NOT call disconnectService per spec S02.
        awaitClose {
            idleMonitor?.cancel()
            if (connectionEstablished) {
                runCatching { service.disconnectService() }
            }
        }
    }

    private companion object {
        const val TAG = "SamsungHRProvider"

        /**
         * REQ-WATCH-BG-01: if no data arrives from the Samsung SDK
         * within this window, the flow closes so the orchestrator can
         * reconnect. 60 s tolerates off-wrist gaps while catching
         * ambient-mode / background disconnections.
         */
        const val IDLE_TIMEOUT_MS = 60_000L

        /**
         * How often the idle monitor checks [lastDataAt]. 10 s is
         * frequent enough to catch silence within [IDLE_TIMEOUT_MS]
         * without busy-looping.
         */
        const val IDLE_CHECK_INTERVAL_MS = 10_000L
    }
}
