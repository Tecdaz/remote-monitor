package com.remotemonitor.watch.sensor

import androidx.sqlite.db.SupportSQLiteDatabase
import com.remotemonitor.watch.data.AppDatabase
import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.data.MeasurementEntity
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T2 polar-h10-validation: per-row `source` discriminator + dual sensor.
 *
 * Run from `watch/`:
 *   ./gradlew :app:testDebugUnitTest --tests "com.remotemonitor.watch.sensor.SensorOrchestratorPolarTest"
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SensorOrchestratorPolarTest {

    private class FakeSensor(private val data: Flow<HeartRateReading?>) : HeartRateSensor {
        override val readings: Flow<HeartRateReading?> = data
    }

    @Test
    fun `samsung and polar rows carry their source`() = runTest(UnconfinedTestDispatcher()) {
        val samsung = FakeSensor(
            flowOf(HeartRateReading(beatsPerMinute = 72, timestampMillis = 1_700_000_000_000L)),
        )
        val polar = FakeSensor(
            flowOf(
                HeartRateReading(
                    beatsPerMinute = 65,
                    timestampMillis = 1_700_000_000_001L,
                    ibis = listOf(1000L, 500L),
                ),
            ),
        )
        val dao = mockk<MeasurementDao>(relaxed = true)
        val captured = mutableListOf<MeasurementEntity>()
        coEvery { dao.insert(capture(captured)) } returns Unit

        val orchestrator = SensorOrchestrator(
            heartRateSensor = samsung,
            spO2Provider = mockk(relaxed = true),
            dao = dao,
            clock = { 1_700_000_000_000L },
            polarSensor = polar,
        )

        orchestrator.start(backgroundScope)
        testScheduler.advanceUntilIdle()
        orchestrator.stop()

        val byBpm = captured.associateBy { it.heartRateBpm }
        assertEquals(MeasurementEntity.SOURCE_SAMSUNG, byBpm[72]?.source)
        assertEquals(MeasurementEntity.SOURCE_POLAR, byBpm[65]?.source)
        assertEquals(listOf(1000L, 500L), byBpm[65]?.ibisMs)
    }

    @Test
    fun `migration 6_7 adds source column with samsung default`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        AppDatabase.MIGRATION_6_7.migrate(db)
        verify(exactly = 1) {
            db.execSQL("ALTER TABLE measurements ADD COLUMN source TEXT NOT NULL DEFAULT 'samsung_watch'")
        }
    }
}
