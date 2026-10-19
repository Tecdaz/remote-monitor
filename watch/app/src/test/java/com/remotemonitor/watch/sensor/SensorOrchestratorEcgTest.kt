package com.remotemonitor.watch.sensor

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T2 polar-ecg-integration: ECG snippets persist as their own
 * `polar_h10` rows (no BPM, ECG columns set).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SensorOrchestratorEcgTest {

    private class FakeEcgProvider(
        private val flow: Flow<PolarEcgChunker.Snippet?>,
    ) : EcgSnippetProvider {
        override val snippets: Flow<PolarEcgChunker.Snippet?> = flow
    }

    private class FakeHrSensor(
        override val readings: Flow<HeartRateReading?>,
    ) : HeartRateSensor

    @Test
    fun `ecg snippet produces a polar row with ecg columns and null bpm`() =
        runTest(UnconfinedTestDispatcher()) {
            val snippet = PolarEcgChunker.Snippet(
                timestampMillis = 1_700_000_000_000L,
                voltagesUv = List(1300) { it },
            )
            val dao = mockk<MeasurementDao>(relaxed = true)
            val captured = mutableListOf<MeasurementEntity>()
            coEvery { dao.insert(capture(captured)) } returns Unit

            val orchestrator = SensorOrchestrator(
                heartRateSensor = FakeHrSensor(flowOf()),
                spO2Provider = mockk(relaxed = true),
                dao = dao,
                clock = { 1_700_000_000_000L },
                ecgProvider = FakeEcgProvider(flowOf(snippet)),
            )

            orchestrator.start(backgroundScope)
            testScheduler.advanceUntilIdle()

            assertEquals(1, captured.size)
            val row = captured[0]
            assertEquals(MeasurementEntity.SOURCE_POLAR, row.source)
            assertNull("ECG rows carry no BPM", row.heartRateBpm)
            assertEquals(1300, row.ecgUv?.size)
            assertEquals(130, row.ecgSampleRateHz)
            assertNull("ECG rows carry no IBI", row.ibisMs)
            orchestrator.stop()
        }
}
