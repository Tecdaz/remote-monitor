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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(ExperimentalCoroutinesApi::class)
class SamsungSpO2ProviderTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `read returns SpO2Reading on onDataReceived`() = runTest {
        // Arrange: arrange for connectService() to fire onConnectionSuccess
        // synchronously. The tracker's setEventListener() registration
        // then fires onDataReceived with a DataPoint whose SPO2 value
        // is 95 (this mirrors what the real Samsung SDK does — it fires
        // onDataReceived after the listener is registered, not via
        // flush()). Per WU-5 the impl no longer calls tracker.flush()
        // for SPO2_ON_DEMAND (option c from design #365).
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val dataPoint = mockk<DataPoint>()
        every { dataPoint.getValue(ValueKey.SpO2Set.SPO2) } returns 95
        // REQ-WATCH-79: onDataReceived gates on STATUS==2 before reading
        // SPO2. The current SDK mock returns STATUS=2 ("measurement
        // complete") for the happy-path so the SPO2 read is the
        // terminal value. The new STATUS-gated impl requires this mock
        // to be present (the DataPoint mock is strict, so an unmocked
        // getValue(STATUS) would throw inside the impl).
        every { dataPoint.getValue(ValueKey.SpO2Set.STATUS) } returns 2

        val tracker = mockk<HealthTracker>(relaxed = true)
        // setEventListener is the real-world trigger for the SDK to
        // fire onDataReceived. Capturing the slot lets the answer block
        // invoke the listener on the SDK's behalf.
        every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
            trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
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
        // Asymmetry check: the impl MUST NOT call tracker.flush() for
        // SPO2_ON_DEMAND (see WU-5 / engram
        // `discovery/samsung-spo2-flush-unbinds-connection`).
        verify(exactly = 0) { tracker.flush() }
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
     * REQ-WATCH-63 (modified) / S-01: for `SPO2_ON_DEMAND` the
     * implementation MUST NOT call `tracker.flush()` at all. AAR v1.4.1
     * (confirmed on real SM-R870, engram `discovery/samsung-spo2-flush-unbinds-connection`)
     * unbinds the binder connection to `com.samsung.android.service.health`
     * after logging `Flush Not supported for SPO2`; `TrackerEventListener.onDataReceived`
     * is then NEVER fired. Removing the call is the only viable path
     * (option c from design #365, after option e was proven insufficient
     * by the WU-4 E2E run). `read()` must still wait for `onDataReceived`
     * and return the reading when the listener fires. The 30s
     * `withTimeoutOrNull` (REQ-WATCH-62) is the termination guarantee.
     *
     * RED-asymmetry check: the test asserts `verify(exactly = 0) {
     * tracker.flush() }`. This will FAIL on the current implementation
     * (which still calls `tracker.flush()` as a bare statement per
     * WU-2's edit) and PASS only after the call is removed entirely
     * (WU-5). The `every { tracker.flush() } returns false` mock is
     * kept so the test compiles if a future regression accidentally
     * re-introduces the call; the asymmetry check is what catches it.
     */
    @Test
    fun `read waits for onDataReceived (no flush call for SPO2_ON_DEMAND)`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val dataPoint = mockk<DataPoint>()
        every { dataPoint.getValue(ValueKey.SpO2Set.SPO2) } returns 97
        // REQ-WATCH-79: status-gated impl reads STATUS before SPO2.
        // The happy-path test simulates the SDK's terminal "complete"
        // callback (STATUS=2). Required because the DataPoint mock is
        // strict: an unmocked getValue(STATUS) would throw.
        every { dataPoint.getValue(ValueKey.SpO2Set.STATUS) } returns 2

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
            "read() must return a SpO2Reading when onDataReceived fires (no flush call)",
            result,
        )
        assertEquals(97.0, result!!.percent, 0.001)
        // Asymmetry check (per WU-5 corrective fix): the impl MUST NOT
        // call tracker.flush() for SPO2_ON_DEMAND. AAR v1.4.1 unbinds
        // the binder on the flush failure, killing the onDataReceived
        // callback. See engram `discovery/samsung-spo2-flush-unbinds-connection`.
        // This verify guards against regression to option (e) or to
        // the pre-WU-2 short-circuit behavior.
        verify(exactly = 0) { tracker.flush() }
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
     * Pattern: the `tracker.setEventListener()` answer block fires
     * the captured `TrackerEventListener.onError` synchronously
     * (mirroring the happy-path test, which fires `onDataReceived`
     * from inside `setEventListener()`). This is the real-world
     * mechanism — the SDK fires the callback after listener
     * registration, not via `tracker.flush()`. Per WU-5 the impl no
     * longer calls `tracker.flush()` for SPO2_ON_DEMAND (option c
     * from design #365), so the previous flush-based trigger is dead.
     */
    @Test
    fun `read returns null on tracker error`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
            trackerListenerSlot.captured.onError(
                mockk<HealthTracker.TrackerError>(relaxed = true)
            )
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
        // Asymmetry check: the impl MUST NOT call tracker.flush() for
        // SPO2_ON_DEMAND (see WU-5 / engram
        // `discovery/samsung-spo2-flush-unbinds-connection`).
        verify(exactly = 0) { tracker.flush() }
    }

    /**
     * REQ-WATCH-79 / S-01: the SDK fires multiple DataPoints per
     * on-demand cycle. The first has `STATUS=0` (calculating, SPO2=0.0)
     * and a later one has `STATUS=2` (complete, SPO2=actual). The impl
     * MUST ignore STATUS!=2 callbacks and resume only on STATUS==2.
     *
     * RED: the current impl reads SPO2 only and resumes on the FIRST
     * DataPoint — so it would resume with percent=0.0 from the
     * STATUS=0 callback, and the STATUS=2 callback would no-op (the
     * continuation is already used). The assertion `percent == 97.0`
     * therefore fails on the current code. After WU-2's STATUS gate,
     * the first callback is ignored and the second resumes with
     * 97.0.
     */
    @Test
    fun `read returns SpO2Reading when STATUS transitions from 0 to 2`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        // First DataPoint: STATUS=0 (calculating), SPO2=0.0.
        val calculatingDp = mockk<DataPoint>()
        every { calculatingDp.getValue(ValueKey.SpO2Set.STATUS) } returns 0
        every { calculatingDp.getValue(ValueKey.SpO2Set.SPO2) } returns 0
        // Second DataPoint: STATUS=2 (complete), SPO2=97.
        val completeDp = mockk<DataPoint>()
        every { completeDp.getValue(ValueKey.SpO2Set.STATUS) } returns 2
        every { completeDp.getValue(ValueKey.SpO2Set.SPO2) } returns 97

        val tracker = mockk<HealthTracker>(relaxed = true)
        // setEventListener is non-firing; we fire both DataPoints
        // manually after the coroutine has reached its suspension
        // point. This mirrors how the real SDK delivers
        // multi-DataPoint cycles (calculating → complete).
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit

        val service = mockk<HealthTrackingService>(relaxed = true)
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
            readTimeoutMs = 5_000L,
        )

        val deferred = async { provider.read() }
        // Reach the suspension point without firing the timeout.
        testScheduler.runCurrent()
        // First SDK callback: STATUS=0, SPO2=0.0. The gate MUST ignore
        // this DataPoint and leave the coroutine suspended.
        trackerListenerSlot.captured.onDataReceived(listOf(calculatingDp))
        testScheduler.runCurrent()
        // Second SDK callback: STATUS=2, SPO2=97. The gate MUST resume
        // the coroutine with the SPO2 reading.
        trackerListenerSlot.captured.onDataReceived(listOf(completeDp))
        testScheduler.advanceUntilIdle()

        val result = deferred.await()
        assertNotNull(
            "read() must resume with a SpO2Reading when STATUS transitions 0→2",
            result,
        )
        assertEquals(97.0, result!!.percent, 0.001)
        verify(exactly = 1) { service.disconnectService() }
    }

    /**
     * REQ-WATCH-79 / S-02: when the SDK only fires STATUS=0 ("still
     * calculating") callbacks within the 30 s budget, the impl MUST
     * return `null` (the 30 s `withTimeoutOrNull` is the termination
     * guarantee). No `TimeoutCancellationException` may leak.
     *
     * RED: the current impl reads SPO2 only and resumes on the first
     * DataPoint with the mocked SPO2 value (0.0). The assertion
     * `assertNull` therefore fails on the current code. After WU-2's
     * STATUS gate, all STATUS=0 callbacks are ignored and the 30 s
     * (compressed to 2 s) timeout returns null.
     */
    @Test
    fun `read returns null on 30s timeout when only STATUS=0 callbacks fire`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val calculatingDp = mockk<DataPoint>()
        every { calculatingDp.getValue(ValueKey.SpO2Set.STATUS) } returns 0
        every { calculatingDp.getValue(ValueKey.SpO2Set.SPO2) } returns 0

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit

        val service = mockk<HealthTrackingService>(relaxed = true)
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
            // Compressed 30 s → 2 s for the test (mirrors the
            // `read returns null on 30s timeout` test at L313).
            readTimeoutMs = 2_000L,
        )

        val deferred = async { provider.read() }
        testScheduler.runCurrent()
        // Fire only STATUS=0 callbacks. The gate MUST ignore each one.
        // Multiple callbacks are fired to exercise the "SDK keeps
        // delivering in the same cycle" path.
        repeat(3) {
            trackerListenerSlot.captured.onDataReceived(listOf(calculatingDp))
            testScheduler.runCurrent()
        }
        // Advance past the 2 s timeout. withTimeoutOrNull must cancel
        // the coroutine and resolve to null.
        testScheduler.advanceTimeBy(2_500L)
        testScheduler.runCurrent()

        val result = deferred.await()
        assertNull(
            "read() must return null when SDK only fires STATUS=0 callbacks within timeout",
            result,
        )
    }

    /**
     * REQ-WATCH-79 / S-03: when the SDK fires a STATUS=-4 ("device
     * moved") callback, the impl MUST NOT resume with a SPO2 value.
     * The connection stays open so the SDK can deliver a subsequent
     * callback; if no STATUS=2 callback arrives within the 30 s
     * budget, `read()` returns `null` via the timeout.
     *
     * RED: the current impl reads SPO2 only and ignores STATUS, so it
     * resumes with the mocked SPO2 value. The `assertNull` therefore
     * fails on the current code. After WU-2's STATUS gate, the
     * STATUS=-4 callback is ignored and the timeout returns null.
     */
    @Test
    fun `read returns null on 30s timeout when only STATUS=-4 callbacks fire`() = runTest {
        val connectionListenerSlot = slot<ConnectionListener>()
        val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

        val deviceMovedDp = mockk<DataPoint>()
        every { deviceMovedDp.getValue(ValueKey.SpO2Set.STATUS) } returns -4
        every { deviceMovedDp.getValue(ValueKey.SpO2Set.SPO2) } returns 0

        val tracker = mockk<HealthTracker>(relaxed = true)
        every { tracker.setEventListener(capture(trackerListenerSlot)) } returns Unit

        val service = mockk<HealthTrackingService>(relaxed = true)
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
            readTimeoutMs = 2_000L,
        )

        val deferred = async { provider.read() }
        testScheduler.runCurrent()
        // Fire only STATUS=-4 callbacks. The gate MUST ignore them
        // and leave the coroutine suspended.
        repeat(3) {
            trackerListenerSlot.captured.onDataReceived(listOf(deviceMovedDp))
            testScheduler.runCurrent()
        }
        // Advance past the 2 s timeout.
        testScheduler.advanceTimeBy(2_500L)
        testScheduler.runCurrent()

        val result = deferred.await()
        assertNull(
            "read() must return null when SDK only fires STATUS=-4 callbacks within timeout",
            result,
        )
    }
}
