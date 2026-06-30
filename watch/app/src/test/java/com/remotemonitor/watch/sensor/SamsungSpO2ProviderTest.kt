package com.remotemonitor.watch.sensor

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
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
 * The Samsung Health Sensor SDK types are mocked via `mockkConstructor` so
 * the unit-test JVM can run the bridge without the proprietary Samsung
 * service installed. The mocks fire callbacks synchronously to keep the
 * test deterministic.
 */
class SamsungSpO2ProviderTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `read returns SpO2Reading when flush succeeds`() = runTest {
        // Arrange: capture the ConnectionListener the provider hands to the
        // SDK ctor, and arrange for connectService() to immediately fire
        // onConnectionSuccess. The tracker mock's flush() then fires
        // onDataReceived with a DataPoint whose SPO2 value is 95.
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

        mockkConstructor(HealthTrackingService::class)
        every { anyConstructed<HealthTrackingService>().connectService() } answers {
            connectionListenerSlot.captured.onConnectionSuccess()
        }
        every {
            anyConstructed<HealthTrackingService>().getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
        } returns tracker
        every { anyConstructed<HealthTrackingService>().disconnectService() } returns Unit

        val before = System.currentTimeMillis()
        val provider = SamsungSpO2Provider(mockk<Context>(relaxed = true))

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
    }
}
