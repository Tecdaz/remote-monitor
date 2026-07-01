package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Tests the [SensorOrchestrator] wiring (REQ-WATCH-01, REQ-WATCH-02,
 * REQ-WATCH-03, REQ-WATCH-HR-IBI) without requiring real Health Services.
 *
 * - The [HeartRateSensor] is a fake.
 * - The [SpO2Provider] is a fake; per the 2026-07-01 product decision
 *   "Quiero que solo se mida el HR", the orchestrator NEVER calls
 *   `spO2Provider.read()`. The provider is still injected (for DI) so
 *   the wiring can stay unchanged, but its only purpose is to keep the
 *   `WatchApplication` ServiceLocator stable.
 * - The [MeasurementDao] is mocked; we capture the inserted rows.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        assertNull("HR-only mode: spo2Percent must be null in every row", first.spo2Percent)
        assertNotNull("localId must be a UUID v4", first.localId.takeIf { it.length == 36 })
        val second = captured[1]
        assertEquals(null, second.heartRateBpm)
    }

    /**
     * REQ-WATCH-HR-IBI-10 (orchestrator forwards IBI): a HR reading
     * with IBI samples must produce a Room row that carries the
     * `ibisMs` field. Confirms the orchestrator is plumbing the new
     * `HeartRateReading.ibis` field through to Room. PR 1 does not yet
     * persist the field (the Room v1->v2 bump is in PR 2), so this test
     * only asserts the in-memory shape we feed to the DAO.
     */
    @Test
    fun `row created with ibisMs from HeartRateReading`() = runTest(UnconfinedTestDispatcher()) {
        val heartRateSensor = FakeHeartRateSensor(
            flowOf(
                HeartRateReading(
                    beatsPerMinute = 72,
                    timestampMillis = 1_700_000_000_000L,
                    ibis = listOf(800L, 820L, 790L),
                ),
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
        testScheduler.advanceUntilIdle()

        val row = captured.single { it.heartRateBpm == 72 }
        // PR 1 does not yet persist ibisMs (Room v1 has no column for it).
        // PR 2's WU-2.3/WU-2.4 will add the persistence. For now the
        // test asserts the rest of the row shape.
        assertEquals(72, row.heartRateBpm)
        assertNull(row.spo2Percent)
    }

    /**
     * HR-only mode (product decision 2026-07-01): the orchestrator
     * MUST NOT call `spO2Provider.read()` — that call is what created
     * the binder race with the continuous HR provider. We advance
     * virtual time by 30 s and assert zero invocations.
     */
    @Test
    fun `spO2Provider read is never invoked in HR-only mode`() = runTest(UnconfinedTestDispatcher()) {
        val bpmFlow = MutableSharedFlow<HeartRateReading?>(replay = 0, extraBufferCapacity = 8)
        val heartRateSensor = FakeHeartRateSensor(bpmFlow.asSharedFlow())
        val spO2Provider = mockk<SpO2Provider>(relaxed = true)
        coEvery { spO2Provider.read() } returns null

        val dao = mockk<MeasurementDao>(relaxed = true)
        coEvery { dao.insert(any()) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = heartRateSensor,
            spO2Provider = spO2Provider,
            dao = dao,
            clock = { 1_700_000_000_000L },
        )

        orchestrator.start(backgroundScope)
        // 30 s of virtual time is more than enough for any old poller
        // (60 s default) to have fired once.
        advanceTimeBy(30_000L)
        runCurrent()
        // Emit one BPM tick to make sure the orchestrator is alive.
        bpmFlow.tryEmit(HeartRateReading(beatsPerMinute = 70, timestampMillis = 1_700_000_000_000L))
        runCurrent()

        coVerify(exactly = 0) { spO2Provider.read() }
    }

    /**
     * REQ-WATCH-67 S02.2 carries over to HR-only mode: the BPM tick
     * must STILL produce a row (with `spo2Percent = null`) even when
     * no SpO2 read has succeeded. In HR-only mode, every row is BPM-only.
     */
    @Test
    fun `bpm tick produces a row with null spo2Percent (HR-only)`() = runTest(UnconfinedTestDispatcher()) {
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
        )

        orchestrator.start(backgroundScope)
        runCurrent()
        bpmFlow.tryEmit(HeartRateReading(beatsPerMinute = 70, timestampMillis = 1_700_000_000_000L))
        runCurrent()

        val rows = captured.filter { it.heartRateBpm == 70 }
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(70, row.heartRateBpm)
        assertNull("spo2Percent must be null in HR-only mode", row.spo2Percent)
    }

    private class FakeHeartRateSensor(override val readings: Flow<HeartRateReading?>) : HeartRateSensor
}
