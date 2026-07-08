package com.remotemonitor.watch.ui

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.IdentityRepository
import com.remotemonitor.watch.sensor.SensorHealth
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [HomeViewModel] (wear-ui-guidelines D6 + D10;
 * wear-bed-picker-onboarding D17 + D25).
 *
 * The vitals Flow combines FIVE reactive sources — the additive
 * `observeBedNumber()` DataStore Flow (D10), the reactive
 * pending-count / last-HR / last-timestamp Room Flows, and the
 * [SensorHealth] signal (D6). HR is suppressed when the pipeline has
 * failed (spec cap 1 scenario 2).
 *
 * Scenarios:
 *  - **initial_state_surfaces_bed_number**: the first emission carries
 *    the bed number from `observeBedNumber()`.
 *  - **bed_number_propagates_from_observe_flow**: a change to the
 *    DataStore bed-number Flow is reflected in the state (D10 reactive
 *    contract).
 *  - **pending_count_propagates**: changes to `pendingCount()` propagate
 *    (proves the combine is wired).
 *  - **S_vitalsFlow_emits_when_sensors_healthy**: with a healthy sensor
 *    and a stored BPM, the state surfaces the live HR (spec cap 1
 *    scenario 1).
 *  - **S_vitalsFlow_suppresses_HR_when_health_failed**: when the sensor
 *    health is Failed, `hrBpm` is suppressed to null (D6, spec cap 1
 *    scenario 2) — no stale BPM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    /**
     * Create a [HomeViewModel] on its own [TestScope] sharing the test
     * scheduler with the calling [scope]. The separate scope keeps the
     * StateFlow's internal coroutine from being a child of the test
     * scope (which would make `runTest` wait for the
     * `WhileSubscribed(5_000L)` timeout before completing). Mirrors the
     * pattern in [OnboardingViewModelTest].
     */
    private fun newViewModel(
        identity: IdentityRepository,
        dao: MeasurementDao,
        sensorHealth: MutableStateFlow<SensorHealth>,
        scope: TestScope,
    ): HomeViewModel {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return HomeViewModel(identity, dao, sensorHealth, TestScope(dispatcher))
    }

    private fun mockDao(
        pendingCount: MutableStateFlow<Int> = MutableStateFlow(0),
        lastHeartRate: MutableStateFlow<Int?> = MutableStateFlow(null),
        lastTimestamp: MutableStateFlow<Long?> = MutableStateFlow(null),
    ): MeasurementDao {
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCount
        every { dao.lastHeartRate() } returns lastHeartRate
        every { dao.lastTimestamp() } returns lastTimestamp
        return dao
    }

    private fun mockIdentity(bedFlow: MutableStateFlow<String?>): IdentityRepository {
        val identity = mockk<IdentityRepository>()
        every { identity.observeBedNumber() } returns bedFlow
        return identity
    }

    @Test
    fun initial_state_surfaces_bed_number() = runTest {
        val identity = mockIdentity(MutableStateFlow("3"))
        val dao = mockDao(pendingCount = MutableStateFlow(3))
        val health = MutableStateFlow<SensorHealth>(SensorHealth.Healthy)

        val vm = newViewModel(identity, dao, health, this)

        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals("3", first.bedNumber)
        assertEquals(3, first.pendingCount)
    }

    @Test
    fun bed_number_propagates_from_observe_flow() = runTest {
        val bedFlow = MutableStateFlow<String?>("3")
        val identity = mockIdentity(bedFlow)
        val dao = mockDao()
        val health = MutableStateFlow<SensorHealth>(SensorHealth.Healthy)

        val vm = newViewModel(identity, dao, health, this)

        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals("3", first.bedNumber)

        // The additive observeBedNumber() Flow re-emits on change (D10).
        bedFlow.value = "4"
        val second = withTimeout(2_000) { vm.state.first { it.bedNumber == "4" } }
        assertEquals("4", second.bedNumber)
    }

    @Test
    fun pending_count_propagates_through_combine() = runTest {
        val identity = mockIdentity(MutableStateFlow("3"))
        val pendingCountFlow = MutableStateFlow(0)
        val dao = mockDao(pendingCount = pendingCountFlow)
        val health = MutableStateFlow<SensorHealth>(SensorHealth.Healthy)

        val vm = newViewModel(identity, dao, health, this)

        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals(0, first.pendingCount)

        pendingCountFlow.value = 7
        val second = vm.state.first { it.pendingCount == 7 }
        assertEquals("3", second.bedNumber)
        assertEquals(7, second.pendingCount)
    }

    @Test
    fun S_vitalsFlow_emits_when_sensors_healthy() = runTest {
        val identity = mockIdentity(MutableStateFlow("3"))
        val dao = mockDao(
            lastHeartRate = MutableStateFlow(72),
            lastTimestamp = MutableStateFlow(1_700_000_000_000L),
        )
        val health = MutableStateFlow<SensorHealth>(SensorHealth.Healthy)

        val vm = newViewModel(identity, dao, health, this)

        val emitted = vm.state.first { it.hrBpm == 72 }
        assertEquals(72, emitted.hrBpm)
        assertEquals(SensorHealth.Healthy, emitted.health)
        assertEquals(1_700_000_000_000L, emitted.lastUpdate?.toEpochMilli())
    }

    @Test
    fun S_vitalsFlow_suppresses_HR_when_health_failed() = runTest {
        val identity = mockIdentity(MutableStateFlow("3"))
        val dao = mockDao(lastHeartRate = MutableStateFlow(72))
        val health = MutableStateFlow<SensorHealth>(SensorHealth.Failed)

        val vm = newViewModel(identity, dao, health, this)

        // Even though Room holds a BPM of 72, a Failed pipeline must
        // suppress the readout (D6, spec cap 1 scenario 2).
        val emitted = vm.state.first { it.health == SensorHealth.Failed }
        assertNull("HR must be suppressed when health == Failed", emitted.hrBpm)
    }
}
