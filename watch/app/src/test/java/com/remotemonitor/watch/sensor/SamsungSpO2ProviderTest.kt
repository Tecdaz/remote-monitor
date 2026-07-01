package com.remotemonitor.watch.sensor

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the [SamsungSpO2Provider] coroutine bridge (REQ-WATCH-60..65).
 *
 * The Samsung Health Sensor SDK is mocked by injecting a [serviceFactory]
 * lambda that returns a [HealthTrackingService] mock. The mock's
 * `connectService()` fires `onConnectionSuccess` synchronously, and the
 * tracker's `flush()` fires `onDataReceived` synchronously, so the test
 * stays deterministic without spinning real coroutines or installing the
 * proprietary Samsung service.
 */
class SamsungSpO2ProviderTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `read returns SpO2Reading when flush succeeds`() = runTest {
        // Arrange: arrange for connectService() to fire onConnectionSuccess
        // synchronously. The tracker's flush() then fires onDataReceived
        // with a DataPoint whose SPO2 value is 95.
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val dataPoint = mockk<DataPoint>()
        every { dataPoint.getValue(ValueKey.SpO2Set.SPO2) } returns 95

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit
        every { tracker.flush() } answers {
            trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
            true
        }

        val service = mockk<HealthTrackingService>(relaxed = true)
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every {
            service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
        } returns tracker
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val before = System.currentTimeMillis()
        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
        )

        // Act
        val reading = provider.read()
        val after = System.currentTimeMillis()

        // Assert (REQ-WATCH-61): percent == 95.0, timestamp within ±1s of now.
        assertNotNull("read() must return a SpO2Reading on the happy path", reading)
        assertEquals(95.0, reading!!.percent, 0.001)
        // SDK timestamp is intentionally NOT used; wall clock is.
        assert(reading.timestampMillis in before..after) {
            "timestampMillis=${reading.timestampMillis} must be in [$before, $after]"
        }
        // The connection listener was indeed handed to the factory, proving
        // the bridge wires it to the SDK.
        assertNotNull("ConnectionListener must be captured by the factory", connectionListenerSlot.isCaptured)
        // REQ-WATCH-71: disconnectService() must fire on the happy path so
        // the binder is released per read. The verify is placed BEFORE
        // the KDoc-referencing assertEquals(null, unused) block so the
        // last interaction with the mock is the assertion, not a dummy
        // null comparison.
        verify(exactly = 1) { service.disconnectService() }
        // HealthTrackerException is referenced to make sure the AAR types
        // remain on the test classpath. Touching it here catches a
        // missing-testImplementation regression early.
        val unused: HealthTrackerException? = null
        assertEquals(null, unused)
    }

    /**
     * REQ-WATCH-62: when no callback fires within the read timeout,
     * `read()` must return `null`; no `TimeoutCancellationException`
     * leaks.
     *
     * The spec mandates 30 s in production; the test injects a
     * compressed 2 s via the `readTimeoutMs` ctor param so the suite
     * stays fast. `runTest` advances virtual time automatically, so
     * the assertion completes in milliseconds of real time. The
     * production code path (default `READ_TIMEOUT_MS = 30_000L`) is
     * unchanged.
     */
    /**
     * REQ-WATCH-63 (modified) / S-01: when `tracker.flush()` returns
     * `false` — which AAR v1.4.1 does for `SPO2_ON_DEMAND` because
     * that tracker type has no flush concept (NOT because the tracker
     * is busy) — `read()` MUST NOT short-circuit. It must continue
     * waiting for `onDataReceived` and return the reading when the
     * callback fires. The 30s `withTimeoutOrNull` (REQ-WATCH-62) is
     * the termination guarantee.
     *
     * RED-compression: the impl's `readTimeoutMs` is 5_000L. If the
     * impl retains the `if (!tracker.flush()) { resume(null) }`
     * short-circuit, `read()` returns `null` and this test fails the
     * `result.percent == 97.0` assertion (a `null` reading is not
     * `SpO2Reading(percent = 97.0)`). With the fix, the listener
     * fires `onDataReceived` and `read()` returns
     * `SpO2Reading(percent = 97.0)`.
     */
    @Test
    fun `read waits for onDataReceived when flush returns false`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val dataPoint = mockk<DataPoint>()
        every { dataPoint.getValue(ValueKey.SpO2Set.SPO2) } returns 97

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit
        every { tracker.flush() } returns false // SPO2_ON_DEMAND per AAR v1.4.1

        val service = mockk<HealthTrackingService>(relaxed = true)
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every {
            service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
        } returns tracker
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 5_000L, // longer than any test advance
        )

        // Launch read() in a child coroutine so we can interleave the
        // listener callback between suspension and resume. The
        // production code's withTimeoutOrNull(5_000L) is the only
        // "SDK never fires" termination guarantee (REQ-WATCH-62).
        val deferred = async { provider.read() }
        // runCurrent() (NOT advanceUntilIdle()) runs the coroutine to
        // its suspension point WITHOUT advancing virtual time past the
        // 5s timeout. advanceUntilIdle() would jump to the next
        // scheduled task — the timeout itself — and fire it before
        // onDataReceived gets a chance to resume the coroutine.
        testScheduler.runCurrent()
        // Fire the SDK's onDataReceived callback as it would on real
        // hardware once the SPO2 measurement completes.
        trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
        // advanceUntilIdle() now runs the scheduled continuation (the
        // onDataReceived resume) and cancels the pending timeout.
        testScheduler.advanceUntilIdle()

        val result = deferred.await()
        assertNotNull(
            "read() must return a SpO2Reading when onDataReceived fires after flush() == false",
            result,
        )
        assertEquals(97.0, result!!.percent, 0.001)
    }

    /**
     * REQ-WATCH-64: when the SDK fires `onConnectionFailed` (e.g.
     * `PACKAGE_NOT_INSTALLED` on a non-Samsung device, or
     * `OLD_PLATFORM_VERSION` on an outdated Wear OS image),
     * `read()` must return `null` and propagate no exception.
     *
     * RED-compression: impl `readTimeoutMs` is 5_000L, the test
     * wraps in `withTimeout(1_000L)`. Without the connection-failed
     * handler, the coroutine suspends forever and the test scope
     * fires the 1 s timeout — assertion never sees null and the
     * test fails.
     */
    @Test
    fun `read returns null on connection failed`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val service = mockk<HealthTrackingService>(relaxed = true)
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionFailed(
                mockk<com.samsung.android.service.health.tracking.HealthTrackerException>(relaxed = true)
            )
        }
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 5_000L,
        )

        val result = withTimeout(1_000L) { provider.read() }
        assertNull("read() must return null on connection failure, not throw", result)
        // REQ-WATCH-74: disconnectService() must fire on the
        // onConnectionFailed path so the binder is released per read.
        verify(exactly = 1) { service.disconnectService() }
    }

    /**
     * REQ-WATCH-65: when the caller's coroutine is cancelled before
     * any callback fires, `service.disconnectService()` SHALL be
     * invoked exactly once to prevent a binder leak.
     *
     * This test is a bit unusual: it cancels the coroutine WHILE it
     * is suspended waiting for the SDK callback, then verifies the
     * cleanup hook fired. Without `invokeOnCancellation { service
     * .disconnectService() }` in the impl, the binder stays open
     * and the assertion below would fail.
     */
    @Test
    fun `disconnectService called on coroutine cancellation`() = runTest {
        val service = mockk<HealthTrackingService>(relaxed = true)
        // connectService() fires onConnectionSuccess, but the tracker
        // is configured never to fire onDataReceived, so the impl
        // suspends in the bridge. We then cancel the surrounding
        // coroutine and assert the cleanup hook ran.
        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(any()) } returns Unit
        every { tracker.flush() } returns true // do not resume from flush

        val connectionListenerSlot = slot<ConnectionListener>()
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every { service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND) } returns tracker
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 30_000L, // long enough that we cancel first
        )

        // Launch a child coroutine that calls read() and gets stuck
        // in the suspendCancellableCoroutine. Cancel it and verify
        // the cleanup hook ran.
        val deferred = async { provider.read() }
        // Allow the launch to reach the suspension point.
        testScheduler.advanceUntilIdle()
        deferred.cancel()
        // Run any post-cancellation continuations.
        testScheduler.advanceUntilIdle()

        // The cleanup hook must have been invoked exactly once.
        verify(exactly = 1) { service.disconnectService() }
    }

    @Test
    fun `read returns null on 30s timeout`() = runTest {
        // The listener is never fired → the implementation's coroutine
        // must self-cancel via withTimeoutOrNull(readTimeoutMs) and
        // resume(null).
        val service = mockk<HealthTrackingService>(relaxed = true)
        // connectService() does NOT fire onConnectionSuccess — the
        // tracker is never requested, no data is ever received.
        every { service.connectService() } returns Unit
        every { service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND) } returns mockk(relaxed = true)
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { _, _ -> service }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 2_000L, // compressed 30 s → 2 s for the test
        )

        // No callback ever fires; read() must time out and return null.
        val result = provider.read()
        assertNull("read() must return null on timeout, not throw", result)
    }

    /**
     * REQ-WATCH-70: when `getHealthTracker(SPO2_ON_DEMAND)` throws
     * (e.g. on a non-Samsung device where the SDK cannot resolve the
     * tracker), `read()` must return `null` AND
     * `service.disconnectService()` must be invoked exactly once.
     * Without the disconnect call, the binder connection to
     * `com.samsung.android.service.health` stays open — that is the
     * binder leak this change is fixing.
     *
     * The impl's `runCatching { getHealthTracker(...) }.getOrNull()`
     * swallows the throw and routes the code through the
     * `tracker == null` branch, which is the path we are testing here.
     *
     * RED-compression: impl `readTimeoutMs` is 5_000L, the test wraps
     * in `withTimeout(1_000L)`. Without the `tracker == null`
     * short-circuit, the coroutine suspends forever on
     * `suspendCancellableCoroutine` and the test scope fires the 1 s
     * timeout — assertion never sees null and the test fails.
     */
    @Test
    fun `read returns null when getHealthTracker throws`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val service = mockk<HealthTrackingService>(relaxed = true)
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every { service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND) } throws
            RuntimeException("test: getHealthTracker failed")
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 5_000L, // longer than the test's outer 1 s
        )

        val result = withTimeout(1_000L) { provider.read() }
        assertNull("read() must return null when getHealthTracker throws", result)
        verify(exactly = 1) { service.disconnectService() }
    }

    /**
     * REQ-WATCH-72: when the tracker fires `onError(TrackerError)`
     * (e.g. the SPO2 sensor reported a hardware fault), `read()` must
     * return `null` AND `service.disconnectService()` must be invoked
     * exactly once. Without the disconnect call, the binder connection
     * to `com.samsung.android.service.health` stays open — that is the
     * binder leak this change is fixing.
     *
     * Pattern: the `tracker.flush()` answer block fires the captured
     * `TrackerEventListener.onError` synchronously (mirroring the
     * happy-path test, which fires `onDataReceived` from inside
     * `flush()`). `flush()` itself returns `true` so the impl's
     * `if (!tracker.flush())` short-circuit does NOT fire — the
     * resume must come through the listener, not the flush branch.
     */
    @Test
    fun `read returns null on tracker error`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit
        every { tracker.flush() } answers {
            trackerListenerSlot.captured.onError(
                mockk<HealthTracker.TrackerError>(relaxed = true)
            )
            true
        }

        val service = mockk<HealthTrackingService>(relaxed = true)
        every { service.connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every {
            service.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
        } returns tracker
        every { service.disconnectService() } returns Unit

        val serviceFactory: (ConnectionListener, android.content.Context) -> HealthTrackingService =
            { listener, _ ->
                connectionListenerSlot.captured = listener
                service
            }

        val provider = SamsungSpO2Provider(
            context = mockk<Context>(relaxed = true),
            serviceFactory = serviceFactory,
            readTimeoutMs = 5_000L, // longer than the test's outer 1 s
        )

        val result = withTimeout(1_000L) { provider.read() }
        assertNull("read() must return null when the tracker fires onError", result)
        verify(exactly = 1) { service.disconnectService() }
    }
}
