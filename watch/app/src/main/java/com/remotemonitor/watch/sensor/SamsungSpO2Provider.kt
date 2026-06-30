package com.remotemonitor.watch.sensor

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Real [SpO2Provider] backed by the Samsung Health Sensor SDK (AAR v1.4.1,
 * REQ-WATCH-12). Bridges the binder-thread `TrackerEventListener` callback
 * to a suspending `read(): SpO2Reading?` call site.
 *
 * **Lifecycle (REQ-WATCH-60..65)**: each [read] opens a fresh
 * [HealthTrackingService] (no persistent connection state), waits for
 * `onConnectionSuccess`, requests a `SPO2_ON_DEMAND` tracker, sets an
 * event listener, calls `flush()`, and resumes the coroutine on the
 * first `onDataReceived`. The `disconnectService()` call is the
 * cancellation hook's responsibility (added in C5).
 *
 * **Concurrency**: this class is NOT safe for concurrent [read] calls
 * on the same instance — the SDK's `HealthTrackingService` is single-shot
 * per connect. Callers that need concurrent reads should hold separate
 * instances or wrap the call in a `Mutex`. The
 * [com.remotemonitor.watch.sensor.SensorOrchestrator] only invokes
 * [read] from a single coroutine, so a mutex is unnecessary today.
 *
 * **No 30-second timeout yet** — added in the C2 commit. **No `flush() == false`
 * short-circuit yet** — added in C3. **No `onConnectionFailed` handling
 * yet** — added in C4. **No `disconnectService()` on cancellation yet**
 * — added in C5. The C1 commit ships the happy path only.
 *
 * **Testability deviation from design #333**: the design's code shape
 * constructs `HealthTrackingService(listener, context)` directly. We
 * inject a [serviceFactory] lambda instead so unit tests can replace
 * the SDK ctor with a mock. The production code passes
 * `::HealthTrackingService` from [WatchApplication]. The factory is the
 * only deviation; the production behavior is identical.
 *
 * @param context Android [Context] used by the SDK to perform
 *   `bindService` on `com.samsung.android.service.health`. The field is
 *   not used directly by this class; it is forwarded to the SDK ctor.
 * @param serviceFactory factory function that constructs the SDK's
 *   [HealthTrackingService]. Defaults to `::HealthTrackingService` in
 *   production; tests inject a mock-returning lambda.
 */
class SamsungSpO2Provider(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
        ::HealthTrackingService,
) : SpO2Provider {

    override suspend fun read(): SpO2Reading? {
        return suspendCancellableCoroutine { cont ->
            // `service` is captured by the inner ConnectionListener so the
            // listener can call `getHealthTracker` / `disconnectService` on
            // it. We use `lateinit var` because `val` cannot be referenced
            // in its own initializer in Kotlin; the assignment below
            // happens before the listener can fire (the SDK only invokes
            // the listener after `connectService()` is called).
            lateinit var service: HealthTrackingService
            service = serviceFactory(
                object : ConnectionListener {
                    override fun onConnectionSuccess() {
                        val tracker = runCatching {
                            service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                        }.getOrNull()
                        if (tracker == null) {
                            // SDK threw on getHealthTracker (e.g. unknown tracker
                            // type on a non-Samsung device). Treat as no-data.
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                            override fun onDataReceived(dataPoints: List<DataPoint>) {
                                if (!cont.isActive) return
                                val intVal = dataPoints.firstOrNull()
                                    ?.getValue(ValueKey.SpO2Set.SPO2) ?: return
                                cont.resume(
                                    SpO2Reading(
                                        percent = intVal.toDouble(),
                                        timestampMillis = System.currentTimeMillis(),
                                    )
                                )
                            }

                            override fun onFlushCompleted() {
                                // Not used: we resume on onDataReceived.
                            }

                            override fun onError(error: HealthTracker.TrackerError) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                        // C1 ignores the flush() return value. C3 will add the
                        // `if (!tracker.flush() && cont.isActive) cont.resume(null)`
                        // short-circuit so a `false` return (tracker busy) does
                        // not block the coroutine forever.
                        tracker.flush()
                    }

                    override fun onConnectionFailed(error: HealthTrackerException) {
                        // C1 stub: the failure path is added in C4.
                    }

                    override fun onConnectionEnded() {
                        // Not used: the connection is a one-shot per read.
                    }
                },
                context,
            )
            service.connectService()
        }
    }
}
