package com.remotemonitor.watch.ui

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.IdentityRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [HomeViewModel] (PR 3 fresh-review follow-up).
 *
 * The fresh review of PR 3 flagged a stale-read bug in the previous
 * implementation: `init { scope.launch { identity.getPatientNumber() } }`
 * ran once and wrote the result to a class-level `MutableStateFlow`.
 * When `SharingStarted.WhileSubscribed(5_000L)` cancelled the upstream
 * after a period of no subscribers (e.g., activity recreation,
 * navigation away-and-back) and a fresh subscription arrived, the
 * state flow re-emitted the initialValue (null patientNumber) and
 * only re-populated from the cached local flow on the next emission.
 *
 * The fix moves the read into the state flow itself via a cold
 * `flow { emit(identity.getPatientNumber()) }`, so every subscription
 * re-reads the latest value from the repo.
 *
 * Scenarios:
 *  - **re_reads_on_resubscribe**: after a 5+s unsubscribe, the next
 *    subscription must see the latest value from the repo (not the
 *    cached value from the first subscription). This is the regression
 *    test for the fresh-review finding.
 *  - **initial_state_surfaces_patient_number**: the first emission
 *    after subscription has the patientNumber from the repo.
 *  - **pending_count_propagates**: changes to the pendingCount flow
 *    are reflected in the state (proves the combine is wired).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    /**
     * Create a [HomeViewModel] on its own [TestScope] sharing the
     * test scheduler with the calling [scope]. The separate scope
     * keeps the StateFlow's internal coroutine from being a child of
     * the test scope (which would make `runTest` wait for the
     * `WhileSubscribed(5_000L)` timeout to fire before completing).
     * Mirrors the pattern in [OnboardingViewModelTest].
     */
    private fun newViewModel(
        identity: IdentityRepository,
        dao: MeasurementDao,
        scope: TestScope,
    ): HomeViewModel {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return HomeViewModel(identity, dao, TestScope(dispatcher))
    }

    @Test
    fun re_reads_patient_number_on_resubscribe() = runTest {
        // Boxed so the mock can return different values for the two
        // subscriptions.
        val patientNumberBox = arrayOf<String?>("P-00001")
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getPatientNumber() } coAnswers { patientNumberBox[0] }

        val pendingCountFlow = MutableStateFlow(0)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        // First subscription: state should show "P-00001" once the
        // upstream emits. The previous one-shot `init` read populates
        // a class-level MutableStateFlow; the new cold-flow read
        // collects on every subscription.
        val first = vm.state.first { it.patientNumber == "P-00001" }
        assertEquals("P-00001", first.patientNumber)

        // `first` cancels the collection on match. With
        // WhileSubscribed(5_000L) the upstream is cancelled after 5s
        // of no subscribers. Fast-forward virtual time past the
        // timeout so the next subscription sees a fresh upstream.
        advanceTimeBy(5_100)
        runCurrent()

        // Simulate the operator re-pairing with a new patient number
        // (or any external write to DataStore).
        patientNumberBox[0] = "P-00002"

        // Re-subscribe: the upstream must re-collect the patient
        // number flow and see "P-00002". With the previous cached-read
        // implementation, the state would show "P-00001" (the stale
        // value from the first subscription) because the init block
        // had already populated the class-level MutableStateFlow and
        // did not re-run. We use withTimeout so a regression fails
        // fast (2s of virtual time) instead of hanging.
        val second = withTimeout(2_000) {
            vm.state.first { it.patientNumber == "P-00002" }
        }
        assertEquals("P-00002", second.patientNumber)
    }

    @Test
    fun initial_state_surfaces_patient_number() = runTest {
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getPatientNumber() } returns "P-00042"

        val pendingCountFlow = MutableStateFlow(3)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        val first = vm.state.first { it.patientNumber == "P-00042" }
        assertEquals("P-00042", first.patientNumber)
        assertEquals(3, first.pendingCount)
    }

    @Test
    fun pending_count_propagates_through_combine() = runTest {
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getPatientNumber() } returns "P-00042"

        val pendingCountFlow = MutableStateFlow(0)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        val first = vm.state.first { it.patientNumber == "P-00042" }
        assertEquals(0, first.pendingCount)

        // Update the pending count and assert the state follows.
        pendingCountFlow.value = 7
        val second = vm.state.first { it.pendingCount == 7 }
        assertEquals("P-00042", second.patientNumber)
        assertEquals(7, second.pendingCount)
    }
}
