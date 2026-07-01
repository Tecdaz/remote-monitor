package com.remotemonitor.watch.sensor

import android.content.Context
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Unit tests for [HealthServicesHeartRateSensor] (REQ-WATCH-01, REQ-WATCH-80..82).
 *
 * The sensor is wired against a hand-rolled fake of [PassiveMonitoringClient]
 * and [HealthServicesClient]. The fake captures the registered config and
 * callback in plain properties (no mockk `verify` blocks) so the assertions
 * read top-down. The 3-arg `setPassiveListenerCallback(config, executor,
 * callback)` overload is the only registration path the sensor uses.
 *
 * **Why `mockkStatic(HealthServices::class)`**: the sensor's production
 * ctor uses the default `clientFactory = { HealthServices.getClient(it) }`.
 * We cannot inject the fake through the ctor at T-BPM-01 time (the stub
 * ctor takes only `Context`). Intercepting the static `getClient` call is
 * what lets the test compile against the 1-arg stub AND drive the
 * registration path. At T-BPM-02 the production ctor gains the
 * `clientFactory` param; the static mock is no longer needed for the
 * existing tests but stays in place for safety.
 *
 * - `DirectExecutor` runs the callback inline on the calling thread, so
 *   the test does not have to spin coroutines to deliver callback events.
 * - `makeSensor` returns the sensor plus the fake client so tests can
 *   drive `simulateNewData` / `simulateEmptyContainer` / etc.
 *
 * Run from `watch/`:
 *   ./gradlew :app:testDebugUnitTest --tests "*HealthServicesHeartRateSensorTest*"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthServicesHeartRateSensorTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * REQ-WATCH-01 S01 + REQ-WATCH-80: on the first collection of
     * `readings`, the sensor MUST register a [PassiveListenerCallback]
     * with `dataTypes == setOf(DataType.HEART_RATE_BPM)`. The
     * registration goes through the 3-arg
     * `setPassiveListenerCallback(config, executor, callback)` overload
     * so the test [Executor] (not the platform main executor) is the
     * one that fires the callback.
     *
     * RED proof (T-BPM-01): the stub emits `flowOf()` and never
     * registers a callback, so `passive.capturedCallback` stays null
     * and `assertNotNull` fails with "capturedListener was null".
     * GREEN proof (T-BPM-02): the real impl wires the registration in
     * its `callbackFlow` producer and the assertion holds.
     */
    @Test
    fun `registers PassiveListenerCallback with HEART_RATE_BPM on first collection`() =
        runTest(UnconfinedTestDispatcher()) {
            val passive = FakePassiveMonitoringClient()
            val fakeClient = FakeHealthServicesClient(passive)
            // Intercept the production default factory
            // `{ HealthServices.getClient(it) }` so the test controls
            // which HealthServicesClient the sensor sees.
            mockkStatic(HealthServices::class)
            every { HealthServices.getClient(any<Context>()) } returns fakeClient

            val ctx = mockk<Context>(relaxed = true)
            val sensor = HealthServicesHeartRateSensor(ctx)

            // Start collecting — this is the cold-flow's producer trigger.
            backgroundScope.launch {
                sensor.readings.collect { /* discard */ }
            }
            // Drain the callbackFlow setup: the `setPassiveListenerCallback`
            // call happens inside the producer block; advanceUntilIdle
            // ensures it ran.
            testScheduler.advanceUntilIdle()

            // Assert the registration captured the right config and a
            // non-null callback. The pre-fix stub emits `flowOf()`, so
            // neither property is ever set — this test FAILS on the
            // stub (RED) and PASSES once the real impl is wired
            // (GREEN in T-BPM-02).
            assertNotNull(
                "capturedListener was null — setPassiveListenerCallback was never called",
                passive.capturedCallback,
            )
            assertNotNull(
                "capturedConfig must be set by the registration",
                passive.capturedConfig,
            )
            assertEquals(
                "the registered config must subscribe to HEART_RATE_BPM only",
                setOf(DataType.HEART_RATE_BPM),
                passive.capturedConfig!!.dataTypes,
            )
        }
}

/** Test helper: run commands inline on the calling thread. */
private class DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/**
 * Hand-rolled fake for [PassiveMonitoringClient]. Captures the
 * registration arguments in plain properties and counts clear-callback
 * calls. Unused methods (the [PassiveListenerService] path, `flushAsync`,
 * `getCapabilitiesAsync`) throw because the sensor under test never
 * calls them.
 */
private class FakePassiveMonitoringClient : PassiveMonitoringClient {
    var capturedConfig: PassiveListenerConfig? = null
    var capturedExecutor: Executor? = null
    var capturedCallback: PassiveListenerCallback? = null
    var clearCallCount: Int = 0

    // Simulate helpers are added in T-BPM-03..05 as the test suite
    // expands. For T-BPM-01 the registration-only assertion does not
    // need any drive helpers.

    override fun setPassiveListenerServiceAsync(
        serviceClass: Class<out androidx.health.services.client.PassiveListenerService>,
        config: PassiveListenerConfig,
    ): ListenableFuture<Void> =
        error("PassiveListenerService is not used by HealthServicesHeartRateSensor")

    override fun setPassiveListenerCallback(
        config: PassiveListenerConfig,
        callback: PassiveListenerCallback,
    ): Unit =
        error("the 2-arg overload is forbidden by REQ-WATCH-80 — use the 3-arg overload")

    override fun setPassiveListenerCallback(
        config: PassiveListenerConfig,
        executor: Executor,
        callback: PassiveListenerCallback,
    ) {
        capturedConfig = config
        capturedExecutor = executor
        capturedCallback = callback
    }

    override fun clearPassiveListenerServiceAsync(): ListenableFuture<Void> =
        error("PassiveListenerService is not used by HealthServicesHeartRateSensor")

    override fun clearPassiveListenerCallbackAsync(): ListenableFuture<Void> {
        clearCallCount++
        // Return a completed future so the awaitClose block in the
        // production code does not block.
        @Suppress("UNCHECKED_CAST")
        return mockk<ListenableFuture<Void>>(relaxed = true)
    }

    override fun flushAsync(): ListenableFuture<Void> =
        error("flushAsync is not used by HealthServicesHeartRateSensor")

    override fun getCapabilitiesAsync(): ListenableFuture<androidx.health.services.client.data.PassiveMonitoringCapabilities> =
        error("getCapabilitiesAsync is not used by HealthServicesHeartRateSensor")
}

/**
 * Hand-rolled fake for [HealthServicesClient]. Only
 * `passiveMonitoringClient` is non-stub; the other getters throw.
 */
private class FakeHealthServicesClient(
    val passive: FakePassiveMonitoringClient,
) : HealthServicesClient {
    override val exerciseClient: androidx.health.services.client.ExerciseClient
        get() = error("ExerciseClient is not used by HealthServicesHeartRateSensor")

    override val passiveMonitoringClient: PassiveMonitoringClient
        get() = passive

    override val measureClient: androidx.health.services.client.MeasureClient
        get() = error("MeasureClient is not used by HealthServicesHeartRateSensor")
}
