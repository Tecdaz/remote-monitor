package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Band-only [SensorOrchestrator] wiring.
 *
 * The fake [HeartRateSensor] stands in for the Polar H10 GATT stream;
 * the [MeasurementDao] is mocked and the inserted rows are captured.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SensorOrchestratorTest {

    @Test
    fun `start writes a Room row for each band reading`() = runTest(UnconfinedTestDispatcher()) {
        val band = FakeHeartRateSensor(
            flowOf(
                HeartRateReading(beatsPerMinute = 72, timestampMillis = 1_700_000_000_000L),
                null, // link retry / no sample
            ),
        )
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        orchestrator.stop()

        assertEquals(2, captured.size)
        val first = captured[0]
        assertEquals(72, first.heartRateBpm)
        assertNotNull("localId must be a UUID", first.localId.takeIf { it.length == 36 })
        assertNull(first.ecgUv)
        assertNull(first.ecgSampleRateHz)
        assertNull(captured[1].heartRateBpm)
    }

    @Test
    fun `row carries the RR array from the band`() = runTest(UnconfinedTestDispatcher()) {
        val band = FakeHeartRateSensor(
            flowOf(
                HeartRateReading(
                    beatsPerMinute = 72,
                    timestampMillis = 1_700_000_000_000L,
                    ibis = listOf(800L, 820L, 790L),
                ),
            ),
        )
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        orchestrator.stop()

        val row = captured.single()
        assertEquals(72, row.heartRateBpm)
        assertEquals(listOf(800L, 820L, 790L), row.ibisMs)
        assertNull(row.ecgUv)
    }

    @Test
    fun `a BPM of zero is skipped`() = runTest(UnconfinedTestDispatcher()) {
        val band = FakeHeartRateSensor(
            flowOf(HeartRateReading(beatsPerMinute = 0, timestampMillis = 1_700_000_000_000L)),
        )
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        orchestrator.stop()

        assertEquals(emptyList<MeasurementEntity>(), captured)
    }

    @Test
    fun `healthState flips to Failed when the band flow throws`() = runTest(UnconfinedTestDispatcher()) {
        val throwing = object : HeartRateSensor {
            override val readings: Flow<HeartRateReading?> =
                flow { throw RuntimeException("BLE link died") }
        }
        val dao = mockk<MeasurementDao>(relaxed = true)

        val orchestrator = SensorOrchestrator(
            polarSensor = throwing,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        assertEquals(SensorHealth.Connecting, orchestrator.healthState.value)
        orchestrator.start(backgroundScope)
        runCurrent()
        orchestrator.stop()

        assertEquals(SensorHealth.Failed, orchestrator.healthState.value)
    }

    @Test
    fun `healthState flips to OffWrist after sustained null readings`() = runTest(UnconfinedTestDispatcher()) {
        val readings = MutableSharedFlow<HeartRateReading?>(extraBufferCapacity = 8)
        val band = FakeHeartRateSensor(readings.asSharedFlow())
        val dao = mockk<MeasurementDao>(relaxed = true)
        coEvery { dao.insert(any()) } returns Unit
        var now = 1_700_000_000_000L

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { now },
            offWristTimeoutMs = 30_000L,
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        now += 31_000L
        readings.tryEmit(null)
        runCurrent()
        assertEquals(SensorHealth.OffWrist, orchestrator.healthState.value)
        orchestrator.stop()
    }

    @Test
    fun `healthState stays Healthy while BPM flows`() = runTest(UnconfinedTestDispatcher()) {
        val band = FakeHeartRateSensor(
            flowOf(HeartRateReading(beatsPerMinute = 72, timestampMillis = 1_700_000_000_000L)),
        )
        val dao = mockk<MeasurementDao>(relaxed = true)

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        orchestrator.stop()

        assertEquals(SensorHealth.Healthy, orchestrator.healthState.value)
    }

    @Test
    fun `start is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val readings = MutableSharedFlow<HeartRateReading?>(extraBufferCapacity = 8)
        val band = FakeHeartRateSensor(readings.asSharedFlow())
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            polarSensor = band,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        orchestrator.start(backgroundScope)
        runCurrent()
        readings.tryEmit(HeartRateReading(beatsPerMinute = 70, timestampMillis = 1_700_000_000_000L))
        runCurrent()
        orchestrator.stop()

        assertEquals(1, captured.size)
    }

    @Test
    fun `no band configured leaves Room untouched`() = runTest(UnconfinedTestDispatcher()) {
        val dao = mockk<MeasurementDao>(relaxed = true)
        val orchestrator = SensorOrchestrator(
            polarSensor = null,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        advanceTimeBy(1_000L)
        runCurrent()
        orchestrator.stop()

        io.mockk.coVerify(exactly = 0) { dao.insert(any()) }
    }

    private class FakeHeartRateSensor(
        override val readings: Flow<HeartRateReading?>,
    ) : HeartRateSensor
}
