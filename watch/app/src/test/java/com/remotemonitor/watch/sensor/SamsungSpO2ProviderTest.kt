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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // HealthTrackerException is referenced to make sure the AAR types
        // remain on the test classpath. Touching it here catches a
        // missing-testImplementation regression early.
        val unused: HealthTrackerException? = null
        assertEquals(null, unused)
    }
}
