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
}
