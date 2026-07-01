package com.remotemonitor.watch.sensor

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Real Health Services heart-rate sensor (REQ-WATCH-01).
 *
 * Wires up the [androidx.health.services.client.HealthServices] platform
 * API via [PassiveMonitoringClient]. The callback is registered with an
 * injectable [Executor] (default: the main executor via
 * [ContextCompat.getMainExecutor]) so tests can use a deterministic
 * direct executor and avoid races.
 *
 * Flow shape:
 * - Cold [callbackFlow]: a new [PassiveListenerCallback] is registered
 *   on each collector start and unregistered on collection cancel
 *   ([awaitClose]). Re-collection re-registers — this is what
 *   [SensorOrchestrator] expects from a `val Flow`.
 * - Emits [HeartRateReading] on each [DataPointContainer] with
 *   `HEART_RATE_BPM` data, with `beatsPerMinute` = `roundToInt(value)`.
 * - Emits `null` on: an empty container, or `onPermissionLost()`. The
 *   orchestrator handles null as "BPM-unavailable right now; insert row
 *   with null heartRateBpm" (REQ-WATCH-67 S02.2).
 *
 * **No new dependencies**: `androidx.health:health-services-client:1.1.0-alpha02`
 * is already on the classpath via `gradle/libs.versions.toml` L33.
 *
 * **Note on permissions**: the watch's BODY_SENSORS permission is
 * declared but not runtime-granted. On Wear OS 5+ the system app
 * handles the consent dialog, and [PassiveMonitoringClient] fires
 * data regardless. The `onPermissionLost()` callback maps to a
 * `null` emission for safety.
 *
 * @param context Android [Context] for `HealthServices.getClient`.
 * @param clientFactory Factory for the [HealthServicesClient]; tests
 *   inject a hand-rolled fake. Default: `{ HealthServices.getClient(it) }`.
 * @param callbackExecutor [Executor] on which the [PassiveListenerCallback]
 *   fires. Production: `ContextCompat.getMainExecutor(context)`. Tests: a
 *   direct executor in the test file. The 3-arg overload is required.
 * @param clock Wall-clock supplier for `HeartRateReading.timestampMillis`.
 *   Tests inject a fixed lambda. Default: `System::currentTimeMillis`.
 */
class HealthServicesHeartRateSensor(
    private val context: Context,
    private val clientFactory: (Context) -> HealthServicesClient = { HealthServices.getClient(it) },
    private val callbackExecutor: Executor = ContextCompat.getMainExecutor(context),
    private val clock: () -> Long = System::currentTimeMillis,
) : HeartRateSensor {

    override val readings: Flow<HeartRateReading?> = callbackFlow {
        val passive: PassiveMonitoringClient =
            clientFactory(context).passiveMonitoringClient
        val config = PassiveListenerConfig.Builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        val callback = object : PassiveListenerCallback {
            override fun onNewDataPointsReceived(container: DataPointContainer) {
                val dataPoint = container.getData(DataType.HEART_RATE_BPM)
                    .filterIsInstance<SampleDataPoint<Double>>()
                    .firstOrNull()
                if (dataPoint == null) {
                    trySend(null)
                    return
                }
                trySend(
                    HeartRateReading(
                        beatsPerMinute = dataPoint.value.roundToInt(),
                        timestampMillis = clock(),
                    )
                )
            }

            override fun onPermissionLost() {
                trySend(null)
            }
        }
        passive.setPassiveListenerCallback(config, callbackExecutor, callback)
        // REQ-WATCH-81: clear the listener on collection cancel. The
        // method returns `ListenableFuture<Void>`, but the AAR's
        // Gradle module metadata only exposes `androidx.annotation`
        // and `kotlin-stdlib` on the consumer's compile classpath —
        // not `listenablefuture:1.0`. The production code cannot
        // reference `ListenableFuture` at compile time, so we call
        // the method via Java reflection. The return value
        // (a `ListenableFuture<Void>`) is discarded — the clear is
        // fire-and-forget; the platform's `clearPassiveListenerCallbackAsync`
        // runs asynchronously on the health-services binder thread.
        awaitClose {
            passive::class.java
                .getMethod("clearPassiveListenerCallbackAsync")
                .invoke(passive)
            Unit
        }
    }
}
