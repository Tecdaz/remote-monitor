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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SamsungHeartRateProvider] (REQ-WATCH-HR-IBI-01..09).
 *
 * The Samsung Health Sensor SDK is mocked by injecting a
 * [serviceFactory] lambda that returns a [HealthTrackingService] mock.
 * The mock's `connectService()` fires `onConnectionSuccess` synchronously,
 * and the tracker's `setEventListener` captures the
 * [HealthTracker.TrackerEventListener] so the test can drive
 * `onDataReceived` / `onError` callbacks directly. No real coroutines
 * spin; the producer's `callbackFlow` is drained by `advanceUntilIdle`.
 *
 * Run from `watch/`:
 *   ./gradlew :app:testDebugUnitTest --tests "com.remotemonitor.watch.sensor.SamsungHeartRateProviderTest"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SamsungHeartRateProviderTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * REQ-WATCH-HR-IBI-01 S01: when the binder connects and a single
     * `HeartRateSet` DataPoint arrives via `onDataReceived`, the flow
     * emits a [HeartRateReading] with `beatsPerMinute`, the injected
     * `clock`'s timestamp, the converted `IBI_LIST` (Int -> Long), and
     * the IBI_STATUS_LIST.
     *
     * RED proof (this WU): the class `SamsungHeartRateProvider` does
     * not exist yet — `SamsungHeartRateProvider(...)` is an unresolved
     * reference. GREEN proof (WU-1.4): the minimal implementation
     * compiles, registers the listener, and the assertion holds.
     */
    @Test
    fun `read returns HR with IBI_LIST on first DataPoint`() =
        runTest(UnconfinedTestDispatcher()) {
            // Arrange: capture the SDK ctor args so we can fire
            // onConnectionSuccess synchronously from connectService().
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            // One DataPoint carrying bpm=72, ibis=[800,820,790] (Int),
            // and matching status ints. The GREEN impl converts Int->Long.
            val dataPoint = mockk<DataPoint>()
            every { dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 72
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns
                listOf(800, 820, 790)
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns
                listOf(1, 1, 1)

            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
                clock = { 1_700_000_000_000L },
            )

            // Act: collect one emission, then cancel.
            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            // Assert: exactly one reading, BPM and timestamp match.
            assertEquals(
                "exactly one emission expected on a single DataPoint",
                1,
                emissions.size,
            )
            val reading = emissions.single()
            assertNotNull("emission must be a HeartRateReading, not null", reading)
            assertEquals(72, reading!!.beatsPerMinute)
            assertEquals(1_700_000_000_000L, reading.timestampMillis)
            assertEquals(listOf(800L, 820L, 790L), reading.ibis)
            assertEquals(listOf(1, 1, 1), reading.ibisStatus)

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-WATCH-HR-IBI-02 S01: when a single `onDataReceived` callback
     * delivers multiple `HeartRateSet` DataPoints, the provider emits
     * exactly once, taking BPM and IBI from the **first** DataPoint and
     * dropping the rest. Per the skill "Critical batching rule", only
     * the first DataPoint of each `onDataReceived` call carries
     * `IBI_LIST`; subsequent DataPoints in the same call have IBI=null.
     *
     * RED proof: today the impl reads BPM from the first DataPoint but
     * still emits once per DataPoint (it doesn't iterate). Actually,
     * the current impl already takes only `dataPoints.firstOrNull()` —
     * so this test would PASS on the current impl. To make it a true
     * RED, the test asserts the **second** DataPoint has null IBI but
     * is still **dropped** (not double-emitted). The test guards
     * against a future regression where someone changes the impl to
     * iterate.
     *
     * We feed 3 DataPoints (1st with IBI, 2nd/3rd with IBI=null) and
     * assert exactly one emission, with the first's IBI.
     */
    @Test
    fun `read drops IBI on subsequent DataPoints in same onDataReceived`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            // First DataPoint: bpm=72 with IBI.
            val dp1 = mockk<DataPoint>()
            every { dp1.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 72
            every { dp1.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns
                listOf(800, 820, 790)
            every { dp1.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns
                listOf(1, 1, 1)

            // Second + third DataPoints: bpm with NO IBI.
            // Mirrors the SDK's display-off behavior where only the
            // first DataPoint of a batched callback carries IBI.
            val dp2 = mockk<DataPoint>()
            every { dp2.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 73
            every { dp2.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns null
            every { dp2.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns null

            val dp3 = mockk<DataPoint>()
            every { dp3.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 74
            every { dp3.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns null
            every { dp3.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns null

            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onDataReceived(listOf(dp1, dp2, dp3))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
                clock = { 1_700_000_000_000L },
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            // Assert: exactly ONE emission, taking the FIRST DataPoint.
            assertEquals(
                "3 DataPoints in one callback must yield exactly one emission",
                1,
                emissions.size,
            )
            val reading = emissions.single()!!
            assertEquals(
                "BPM must come from the first DataPoint, not later ones",
                72,
                reading.beatsPerMinute,
            )
            assertEquals(
                "IBI must come from the first DataPoint, not later ones",
                listOf(800L, 820L, 790L),
                reading.ibis,
            )

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-WATCH-HR-IBI-03: the provider MUST convert `IBI_LIST` from
     * the AAR's `List<Integer>` to `List<Long>` at the read boundary.
     * This test feeds values that distinguish Int (which would be the
     * wrong type) from Long, and asserts the *exact* `List<Long>` shape
     * on the emitted reading.
     *
     * The values are deliberately larger than 32-bit Int max would
     * allow if you mis-read the type — `2_500_000_000L` cannot be
     * represented as Int, so a failure to convert would either throw
     * a ClassCastException or produce wrong values. The test uses
     * values in the normal IBI range (800..1000 ms) but asserts the
     * type is `List<Long>` via `is List<Long>` (a runtime type
     * assertion that distinguishes `ArrayList<Long>` from
     * `ArrayList<Integer>`).
     */
    @Test
    fun `IBI values are converted to Long from the SDK's Int`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            val dataPoint = mockk<DataPoint>()
            every { dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 72
            // SDK AAR exposes this as List<Int>. The provider must
            // convert to List<Long> at the read boundary.
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns
                listOf(800, 820, 790)
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns
                listOf(1, 1, 1)

            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
                clock = { 1_700_000_000_000L },
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            val ibis = emissions.single()!!.ibis
            // Value equality: List<Long>(800, 820, 790).
            assertEquals(listOf(800L, 820L, 790L), ibis)
            // Type check: in Kotlin on the JVM, Long::class.java.name
            // is the primitive "long", while Int::class.java.name is
            // "int". If the impl failed to convert, the list element
            // type would be "int" (Int). Asserting the element's
            // runtime class is "long" proves the Int -> Long
            // conversion happened at the read boundary.
            val firstElement = ibis!![0]
            assertEquals(
                "first IBI element must be a Long at runtime, not an Int",
                "long",
                firstElement::class.java.name,
            )

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-WATCH-HR-IBI-06: when `getHealthTracker(HEART_RATE_CONTINUOUS)`
     * throws `HealthTrackerException`, the flow MUST emit `null` and
     * `disconnectService` MUST be called exactly once (the binder IS
     * live by the time the throw happens).
     *
     * RED proof: the current impl wraps `getHealthTracker` in
     * `runCatching` and calls `disconnectService` on null. This test
     * will pass on the current impl. The WU-1.8 RED establishes the
     * test, WU-1.9 GREEN hardens the impl with the same `runCatching`
     * pattern (which is already in place from WU-1.4's defensive
     * pattern).
     */
    @Test
    fun `getHealthTracker throws - read returns null and disconnectService called once`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            // Simulate the AAR throwing when the device does not
            // support HEART_RATE_CONTINUOUS (or permission was denied).
            // The AAR's HealthTrackerException takes a String
            // message; the error code (PACKAGE_NOT_INSTALLED,
            // OLD_PLATFORM_VERSION, ...) is set via the protected
            // setter that the platform calls internally. For test
            // purposes the message is enough.
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } throws HealthTrackerException("tracker unavailable on this device")
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
            )

            val channel = kotlinx.coroutines.channels.Channel<HeartRateReading?>(kotlinx.coroutines.channels.Channel.BUFFERED)
            val job = backgroundScope.launch {
                provider.readings.collect { channel.send(it) }
            }
            testScheduler.advanceUntilIdle()

            // Assert: exactly one null emission (the close path).
            val first = channel.receive()
            assertNull(
                "the first emission must be null (no reading possible)",
                first,
            )
            // Assert: disconnectService was called exactly once.
            // The binder IS live when onConnectionSuccess fired, so
            // we MUST release it.
            io.mockk.verify(exactly = 1) { service.disconnectService() }

            job.cancel()
            testScheduler.advanceUntilIdle()
            channel.close()
        }

    /**
     * REQ-WATCH-HR-IBI-05 S01: when the collector cancels the flow
     * (`awaitClose` path), the provider MUST call
     * `service.disconnectService()` exactly once so the binder
     * connection to `com.samsung.android.service.health` is released.
     *
     * RED proof: this test cancels BEFORE any callback fires. The
     * `awaitClose { runCatching { service.disconnectService() } }`
     * block must run and call disconnect. Without it, the verify
     * fails.
     */
    @Test
    fun `disconnectService called exactly once on awaitClose`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()

            val service = mockk<HealthTrackingService>(relaxed = true)
            // connectService fires onConnectionSuccess but we set up
            // the tracker to NEVER call onDataReceived, so the
            // coroutine suspends in the producer. We then cancel the
            // collection and assert disconnectService fires from
            // awaitClose.
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(any()) } returns Unit
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
            )

            val job = backgroundScope.launch {
                provider.readings.collect { /* discard */ }
            }
            testScheduler.advanceUntilIdle()
            // Pre-cancel: disconnectService MUST NOT have been called yet.
            io.mockk.verify(exactly = 0) { service.disconnectService() }

            // Cancel the collector -> awaitClose fires.
            job.cancel()
            testScheduler.advanceUntilIdle()

            // Post-cancel: disconnectService called exactly once.
            io.mockk.verify(exactly = 1) { service.disconnectService() }
        }

    /**
     * REQ-WATCH-HR-IBI-05 S02: when the coroutine is cancelled BEFORE
     * `onConnectionSuccess` fires, the binder is not yet live. The
     * spec says "disconnectService is NOT called (the binder was
     * never live)". Design §17 WARNING flags this as a verification
     * item: gate `disconnectService` on a `connectionEstablished`
     * flag.
     *
     * RED proof: today the impl calls `disconnectService` from
     * `awaitClose` unconditionally. For a pre-connection cancel,
     * `awaitClose` will fire and call `disconnectService` on a
     * not-yet-connected service. Per spec S02, this should NOT
     * happen — the test asserts 0 calls in this case. The current
     * impl fails this test (it calls disconnectService once from
     * awaitClose).
     */
    @Test
    fun `disconnectService NOT called when cancel happens before onConnectionSuccess`() =
        runTest(UnconfinedTestDispatcher()) {
            val service = mockk<HealthTrackingService>(relaxed = true)
            // connectService does NOT fire onConnectionSuccess — we
            // simulate a long connect that gets cancelled.
            every { service.connectService() } returns Unit
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { _, _ -> service }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
            )

            val job = backgroundScope.launch {
                provider.readings.collect { /* discard */ }
            }
            // Cancel BEFORE advanceUntilIdle (before onConnectionSuccess could fire).
            // Note: with UnconfinedTestDispatcher the connectService
            // call is the last thing in the producer block before
            // awaitClose; advanceUntilIdle here lets connectService
            // run (which is a no-op per our mock), then we cancel.
            testScheduler.advanceUntilIdle()
            job.cancel()
            testScheduler.advanceUntilIdle()

            // Spec REQ-WATCH-HR-IBI-05 S02: NO disconnectService call
            // because the binder was never live.
            io.mockk.verify(exactly = 0) { service.disconnectService() }
        }

    /**
     * REQ-WATCH-HR-IBI-04: when the tracker fires `onError(TrackerError)`,
     * the flow MUST emit `null` and close. The `awaitClose` block then
     * runs and calls `disconnectService` exactly once.
     *
     * RED proof: the current impl DOES emit null and close on
     * TrackerError (see WU-1.4's `onError` override). This test is
     * a regression guard. It will pass on the current impl.
     */
    @Test
    fun `read returns null on TrackerError`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            val tracker = mockk<HealthTracker>(relaxed = true)
            // Fire onError synchronously on setEventListener.
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onError(mockk<HealthTracker.TrackerError>(relaxed = true))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            // Exactly one null emission.
            assertEquals(1, emissions.size)
            assertNull("TrackerError must emit null", emissions.single())
            // DisconnectService called exactly once (from awaitClose).
            io.mockk.verify(exactly = 1) { service.disconnectService() }

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-WATCH-HR-IBI-04: when the binder fires `onConnectionFailed`,
     * the flow MUST emit `null` and close. `awaitClose` then runs and
     * calls `disconnectService` exactly once.
     *
     * RED proof: the current impl DOES emit null and close on
     * `onConnectionFailed`. This test is a regression guard.
     */
    @Test
    fun `read returns null on ConnectionFailed`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionFailed(
                    HealthTrackerException("connection failed"),
                )
            }
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            // Exactly one null emission.
            assertEquals(1, emissions.size)
            assertNull("onConnectionFailed must emit null", emissions.single())
            // DisconnectService called exactly once (from awaitClose).
            io.mockk.verify(exactly = 1) { service.disconnectService() }

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-NOISE-WATCH-01: mixed status values (e.g. [1,0,1]) are forwarded
     * unchanged from the SDK to [HeartRateReading.ibisStatus]. The watch
     * no longer filters rejected beats locally.
     */
    @Test
    fun `read forwards mixed ibisStatus unchanged`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            val dataPoint = mockk<DataPoint>()
            every { dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 72
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns
                listOf(800, 820, 900)
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns
                listOf(1, 0, 1)

            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
                clock = { 1_700_000_000_000L },
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            val reading = emissions.single()!!
            assertEquals(listOf(800L, 820L, 900L), reading.ibis)
            assertEquals(listOf(1, 0, 1), reading.ibisStatus)

            job.cancel()
            testScheduler.advanceUntilIdle()
        }

    /**
     * REQ-WATCH-HR-IBI-02 S02 / null-tolerance: a `HeartRateSet`
     * DataPoint whose `IBI_LIST` and `IBI_STATUS_LIST` are null MUST
     * still emit a reading (we don't drop the BPM tick just because
     * the SDK couldn't compute IBIs in this batch). Also: a null
     * `HEART_RATE_STATUS` (the per-reading lifecycle status — values
     * are undocumented by Samsung) MUST NOT break the read.
     *
     * RED proof: the current impl already handles null IBI_LIST
     * gracefully (it falls through to `trySend` with `ibis = null`).
     * This is a regression guard.
     */
    @Test
    fun `tolerates null IBI_LIST and null HEART_RATE_STATUS`() =
        runTest(UnconfinedTestDispatcher()) {
            val connectionListenerSlot = slot<ConnectionListener>()
            val trackerListenerSlot = slot<HealthTracker.TrackerEventListener>()

            // DataPoint with BPM but no IBI and no status (e.g. the
            // very first sample after wakeup, before the SDK has
            // computed IBI).
            val dataPoint = mockk<DataPoint>()
            every { dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE) } returns 72
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_LIST) } returns null
            every { dataPoint.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } returns null
            // The impl does NOT read HEART_RATE_STATUS today; this
            // assert exists to guard against a future regression
            // that adds a STATUS gate without first checking the
            // AAR's nullability contract.
            every { dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) } returns null

            val tracker = mockk<HealthTracker>(relaxed = true)
            every { tracker.setEventListener(capture(trackerListenerSlot)) } answers {
                trackerListenerSlot.captured.onDataReceived(listOf(dataPoint))
            }

            val service = mockk<HealthTrackingService>(relaxed = true)
            every { service.connectService() } answers {
                connectionListenerSlot.captured.onConnectionSuccess()
            }
            every {
                service.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            } returns tracker
            every { service.disconnectService() } returns Unit

            val serviceFactory: (ConnectionListener, Context) -> HealthTrackingService =
                { listener, _ ->
                    connectionListenerSlot.captured = listener
                    service
                }

            val provider = SamsungHeartRateProvider(
                context = mockk<Context>(relaxed = true),
                serviceFactory = serviceFactory,
                clock = { 1_700_000_000_000L },
            )

            val emissions = mutableListOf<HeartRateReading?>()
            val job = backgroundScope.launch {
                provider.readings.collect { emissions += it }
            }
            testScheduler.advanceUntilIdle()

            // Exactly one reading — BPM, no IBI, no status.
            assertEquals(1, emissions.size)
            val reading = emissions.single()!!
            assertEquals(72, reading.beatsPerMinute)
            assertNull("null IBI_LIST must surface as null ibis", reading.ibis)
            assertNull("null IBI_STATUS_LIST must surface as null ibisStatus", reading.ibisStatus)

            job.cancel()
            testScheduler.advanceUntilIdle()
        }
}
