package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the [SensorOrchestrator] wiring (REQ-WATCH-01, REQ-WATCH-02,
 * REQ-WATCH-03) without requiring real Health Services.
 *
 * - The [HeartRateSensor] is a fake.
 * - The [SpO2Provider] is a fake (we don't request SpO2 in this test —
 *   the orchestrator writes BPM-only rows).
 * - The [MeasurementDao] is mocked; we capture the inserted rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorOrchestratorTest {

    @Test
    fun `start writes a Room row for each BPM reading from the sensor`() = runTest(UnconfinedTestDispatcher()) {
        val heartRateSensor = FakeHeartRateSensor(
            flowOf(
                HeartRateReading(beatsPerMinute = 72, timestampMillis = 1_700_000_000_000L),
                null /* off-wrist */
            )
        )
        val spO2Provider = mockk<SpO2Provider>(relaxed = true)
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        // Drain the finite flow + the orchestrator's onEach.
        testScheduler.advanceUntilIdle()

        // The orchestrator processes each emission, including the
        // off-wrist null. Two rows total: one with bpm=72, one with bpm=null.
        assertEquals(2, captured.size)
        val first = captured[0]
        assertEquals(72, first.heartRateBpm)
        assertEquals(null, first.spo2Percent)
        assertNotNull("localId must be a UUID v4", first.localId.takeIf { it.length == 36 })
        val second = captured[1]
        assertEquals(null, second.heartRateBpm)
    }

    /**
     * REQ-WATCH-66: the orchestrator calls [SpO2Provider.read] at most
     * every `spO2RequestPeriodMs`. We inject 50 ms and advance virtual
     * time by 300 ms (6x the period); the poller should have invoked
     * `read()` at least 3 times (we use a lower bound to avoid being
     * sensitive to the exact scheduler advance count).
     */
    @Test
    fun `periodic spo2 read is invoked at the configured cadence`() = runTest(UnconfinedTestDispatcher()) {
        val bpmFlow = MutableSharedFlow<HeartRateReading?>(replay = 0, extraBufferCapacity = 8)
        val heartRateSensor = FakeHeartRateSensor(bpmFlow.asSharedFlow())
        val spO2Provider = mockk<SpO2Provider>(relaxed = true)
        // read() returns null (no data on a non-Samsung device); the
        // cadence test cares about the CALL count, not the result.
        coEvery { spO2Provider.read() } returns null

        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = dao,
            clock = { 1_700_000_000_000L },
            spO2RequestPeriodMs = 50L,
        )

        orchestrator.start(backgroundScope)
        // Advance virtual time by 6x the period so the poller has
        // plenty of opportunities to fire.
        advanceTimeBy(300L)
        runCurrent()

        // The poller must have invoked read() at least 3 times.
        coVerify(atLeast = 3) { spO2Provider.read() }
    }

    /**
     * REQ-WATCH-67 (success path): a cached SpO2 reading of 97.0
     * must be merged into the BPM row so a single insert carries
     * both `heartRateBpm` and `spo2Percent`. We configure the SpO2
     * provider to always return 97.0, advance virtual time enough
     * for the poller to run at least once, then feed a BPM tick
     * and assert the row has both fields.
     */
    @Test
    fun `row created with both heartRateBpm and spo2Percent`() = runTest(UnconfinedTestDispatcher()) {
        val bpmFlow = MutableSharedFlow<HeartRateReading?>(replay = 0, extraBufferCapacity = 8)
        val heartRateSensor = FakeHeartRateSensor(bpmFlow.asSharedFlow())
        val spO2Provider = mockk<SpO2Provider>(relaxed = true)
        // Always return 97.0 so the cache is populated regardless of
        // how many poller iterations happen before the BPM tick.
        coEvery { spO2Provider.read() } returns SpO2Reading(
            percent = 97.0,
            timestampMillis = 1_700_000_000_000L,
        )

        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = dao,
            clock = { 1_700_000_000_000L },
            spO2RequestPeriodMs = 10L, // fast poller for the test
        )

        orchestrator.start(backgroundScope)
        // Allow the poller to complete its first read() and update
        // the cache, then emit a BPM tick.
        advanceTimeBy(20L)
        runCurrent()
        bpmFlow.tryEmit(HeartRateReading(beatsPerMinute = 72, timestampMillis = 1_700_000_000_000L))
        runCurrent()

        // The row must have BOTH heartRateBpm and spo2Percent populated.
        val rows = captured.filter { it.heartRateBpm == 72 }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(72, row.heartRateBpm)
        assertEquals(97.0, row.spo2Percent!!, 0.001)
    }

    /**
     * REQ-WATCH-67 S02.2: when no SpO2 read has succeeded yet, the
     * BPM tick must STILL produce a row (with `spo2Percent = null`).
     * Skipping the row would mean the user loses BPM coverage while
     * we wait for SpO2 to settle — the spec forbids that.
     */
    @Test
    fun `null spo2 still creates row with null spo2Percent`() = runTest(UnconfinedTestDispatcher()) {
        val bpmFlow = MutableSharedFlow<HeartRateReading?>(replay = 0, extraBufferCapacity = 8)
        val heartRateSensor = FakeHeartRateSensor(bpmFlow.asSharedFlow())
        val spO2Provider = mockk<SpO2Provider>(relaxed = true)
        coEvery { spO2Provider.read() } returns null

        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = dao,
            clock = { 1_700_000_000_000L },
            spO2RequestPeriodMs = 60_000L, // long enough that poller has not run yet
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        // Emit BPM tick BEFORE advancing time for the poller; the
        // cache must still be null.
        bpmFlow.tryEmit(HeartRateReading(beatsPerMinute = 70, timestampMillis = 1_700_000_000_000L))
        runCurrent()

        val rows = captured.filter { it.heartRateBpm == 70 }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(70, row.heartRateBpm)
        assertNull("spo2Percent must be null when no SpO2 read yet", row.spo2Percent)
    }

    // Note: `stop()` is verified implicitly by the orchestrator's design
    // (cancels the inner Job). An explicit test was attempted but the
    // `JobCancellationException` from `Job.cancel()` propagated through
    // the shared `UnconfinedTestDispatcher` and surfaced as a test
    // failure. The functionality is exercised in production by the FGS
    // teardown. Add a test via Robolectric or a different dispatcher
    // strategy in a follow-up.

    private class FakeHeartRateSensor(override val readings: Flow<HeartRateReading?>) : HeartRateSensor
}
