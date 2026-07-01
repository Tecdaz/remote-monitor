package com.remotemonitor.watch.sensor

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
        lateinit var service: HealthTrackingService
        // REQ-WATCH-HR-IBI-05 S02: track whether the binder ever
        // reached `onConnectionSuccess`. Pre-connection cancel must
        // NOT call `disconnectService` because the binder was never
        // live (calling it on a not-yet-connected service is a
        // no-op per the AAR, but spec S02 mandates "NOT called").
        // Post-connection cancel MUST call it (binder is live).
        var connectionEstablished: Boolean = false

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
                tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        // REQ-WATCH-HR-IBI-02: only the first DataPoint
                        // carries IBI_LIST (and IBI_STATUS_LIST).
                        // Subsequent DataPoints in the same callback are
                        // dropped (their IBI is null).
                        val first = dataPoints.firstOrNull() ?: return
                        val bpm = first.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: return
                        // REQ-WATCH-HR-IBI-03: Int -> Long at the read
                        // boundary (cleaner downstream HRV math).
                        val ibis = first.getValue(ValueKey.HeartRateSet.IBI_LIST)
                            ?.map { it.toLong() }
                        val ibisStatus = first.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                        trySend(
                            HeartRateReading(
                                beatsPerMinute = bpm,
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
                // Not used: the connection is single-shot per collect.
            }
        }

        service = serviceFactory(listener, context)
        service.connectService()

        // REQ-WATCH-HR-IBI-05 S01 + S02: awaitClose releases the binder
        // on collection cancel, but ONLY if the binder is live
        // (post-`onConnectionSuccess`). The pre-connection cancel
        // path must NOT call disconnectService per spec S02.
        awaitClose {
            if (connectionEstablished) {
                runCatching { service.disconnectService() }
            }
        }
    }
}
