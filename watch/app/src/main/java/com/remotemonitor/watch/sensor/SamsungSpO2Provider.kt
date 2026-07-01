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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Real [SpO2Provider] backed by the Samsung Health Sensor SDK (AAR v1.4.1,
 * REQ-WATCH-12). Bridges the binder-thread `TrackerEventListener` callback
 * to a suspending `read(): SpO2Reading?` call site.
 *
 * **Lifecycle (REQ-WATCH-60..65)**: each [read] opens a fresh
 * [HealthTrackingService] (no persistent connection state), waits for
 * `onConnectionSuccess`, requests a `SPO2_ON_DEMAND` tracker, sets an
 * event listener, and resumes the coroutine on the first
 * `onDataReceived`. The SDK's `SPO2_ON_DEMAND` triggers the
 * measurement internally on `setEventListener`; we do NOT call
 * `tracker.flush()` (see "No `flush()` call" note below). The `disconnectService()` call runs on every
 * terminal path: the 5 normal-resume paths explicitly (REQ-WATCH-70..74),
 * plus the cancellation hook as a defensive backup. This ensures no
 * binder connection to `com.samsung.android.service.health` is leaked
 * per call. Total disconnects per `read()` invocation is at most 1.
 *
 * **Concurrency**: this class is NOT safe for concurrent [read] calls
 * on the same instance — the SDK's `HealthTrackingService` is single-shot
 * per connect. Callers that need concurrent reads should hold separate
 * instances or wrap the call in a `Mutex`. The
 * [com.remotemonitor.watch.sensor.SensorOrchestrator] only invokes
 * [read] from a single coroutine, so a mutex is unnecessary today.
 *
 * **No `flush()` call** for `SPO2_ON_DEMAND`. AAR v1.4.1 unbinds the
 * binder connection after the `Flush Not supported for SPO2` log, so
 * `TrackerEventListener.onDataReceived` would never fire (see engram
 * `discovery/samsung-spo2-flush-unbinds-connection`). The 30s
 * `withTimeoutOrNull` (REQ-WATCH-62) is the sole termination guarantee
 * for "SDK never fires a callback". The current contract spans
 * REQ-WATCH-60..78; see REQ-WATCH-63 / S-01 for the corrected
 * rationale.
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
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
) : SpO2Provider {

    companion object {
        /**
         * SpO2 read timeout (REQ-WATCH-62, per the [SpO2Provider.read]
         * KDoc: "Apply a timeout (e.g. 30 seconds) and return null on
         * timeout"). The 30 s budget is generous — Galaxy Watch 4 SPO2
         * reads typically complete in < 5 s when the user holds still
         * — and prevents the orchestrator's coroutine from hanging
         * indefinitely if the Samsung Health service is unresponsive.
         */
        const val READ_TIMEOUT_MS: Long = 30_000L
    }

    override suspend fun read(): SpO2Reading? {
        return withTimeoutOrNull(readTimeoutMs) {
            suspendCancellableCoroutine { cont ->
            // `service` is captured by the inner ConnectionListener so the
            // listener can call `getHealthTracker` / `disconnectService` on
            // it. We use `lateinit var` because `val` cannot be referenced
            // in its own initializer in Kotlin; the assignment below
            // happens before the listener can fire (the SDK only invokes
            // the listener after `connectService()` is called).
            lateinit var service: HealthTrackingService
            // C5: cancellation hook. If the caller's coroutine is
            // cancelled (timeout, parent scope teardown, the
            // orchestrator's stop()), release the SDK binder via
            // disconnectService() so we don't leak a process-wide
            // connection to com.samsung.android.service.health.
            // invokeOnCancellation runs on the cancelled coroutine's
            // context and is the standard pattern for binder cleanup.
            val cont = cont
            val cleanup: (Throwable?) -> Unit = { _ -> service.disconnectService() }
            cont.invokeOnCancellation(cleanup)
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
                            service.disconnectService()
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
                                service.disconnectService()
                            }

                            override fun onFlushCompleted() {
                                // Not used: we resume on onDataReceived.
                            }

                            override fun onError(error: HealthTracker.TrackerError) {
                                if (cont.isActive) cont.resume(null)
                                service.disconnectService()
                            }
                        })
                        // AAR v1.4.1: we MUST NOT call `tracker.flush()`
                        // for `SPO2_ON_DEMAND`. Real-device E2E on SM-R870
                        // (engram `discovery/samsung-spo2-flush-unbinds-connection`)
                        // proved the SDK unbinds the binder connection to
                        // `com.samsung.android.service.health` after logging
                        // `Flush Not supported for SPO2`; `TrackerEventListener
                        // .onDataReceived` is NEVER fired post-unbind.
                        // Removing the call entirely (option c from design
                        // #365) is the only viable path: the SDK triggers
                        // the SPO2_ON_DEMAND measurement via its own
                        // internal mechanism after `setEventListener`.
                        // The 30s `withTimeoutOrNull` (REQ-WATCH-62) is
                        // the sole termination guarantee. Unit tests
                        // guard this with `verify(exactly = 0) {
                        // tracker.flush() }` (SamsungSpO2ProviderTest
                        // `read waits for onDataReceived (no flush call
                        // for SPO2_ON_DEMAND)`).
                    }

                    override fun onConnectionFailed(error: HealthTrackerException) {
                        // C4: graceful degradation. Resume null so the
                        // orchestrator's row inserts with spo2Percent = null
                        // (REQ-WATCH-67 S02.2) instead of hanging on a stale
                        // binder or throwing to the call site.
                        if (cont.isActive) cont.resume(null)
                        service.disconnectService()
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
}
