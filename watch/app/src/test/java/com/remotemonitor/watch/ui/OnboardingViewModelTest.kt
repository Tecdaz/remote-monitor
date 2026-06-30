package com.remotemonitor.watch.ui

import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.api.RegisterPatientRequest
import com.remotemonitor.watch.api.RegisterPatientResponse
import com.remotemonitor.watch.identity.IdentityRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [OnboardingViewModel] (T-WATCH-35, REQ-WATCH-18,
 * REQ-WATCH-11).
 *
 * These tests cover the ViewModel behavior that the Compose UI tests
 * cannot easily exercise — the failure paths through `runCatching`.
 *
 * Scenarios:
 *  - **happy path**: setPatientNumber + registerPatient + setPatientId all
 *    succeed → `NavigateToHome` event is emitted, state is not in error.
 *  - **setPatientId fails (silent-onboarding regression)**: the API call
 *    succeeds but `identity.setPatientId` throws (e.g. DataStore I/O
 *    error). The state must surface the error and NOT emit
 *    `NavigateToHome`. Without this guard, a failed DataStore write
 *    would leave the user on Home with `patient_id = null` and uploads
 *    would silently never happen (the HIGH-severity finding from the
 *    fresh review of PR 3).
 *  - **registerPatient fails**: the API throws → state shows error, no
 *    navigation event.
 *  - **invalid input**: regex does not match → state shows the
 *    validation error without invoking any side effect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private fun newViewModel(
        identity: IdentityRepository,
        api: MeasurementsApi,
        scope: TestScope,
    ): OnboardingViewModel {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return OnboardingViewModel(
            identity = identity,
            api = api,
            scope = TestScope(dispatcher),
        )
    }

    @Test
    fun happy_path_emits_navigate_to_home_and_clears_submitting() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "P-00042",
                createdAt = "2026-06-30T00:00:00Z",
            )

        val vm = newViewModel(identity, api, this)
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        // backgroundScope is provided by runTest; collecting on it keeps
        // the subscriber alive while the ViewModel emits the event, so
        // the SharedFlow's extraBufferCapacity does not drop it.
        backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        vm.onPatientNumberChange("P-00042")
        vm.onSubmit()
        advanceUntilIdle()

        // All three side effects must run, in order.
        coVerifyOrder {
            identity.setPatientNumber("P-00042")
            api.registerPatient("P-00042", RegisterPatientRequest(patientNumber = "P-00042"))
            identity.setPatientId("uuid-1")
        }
        assertEquals(false, vm.state.value.isSubmitting)
        assertNull(vm.state.value.error)
        assertEquals(OnboardingEvent.NavigateToHome, eventReceived.await())
    }

    @Test
    fun setPatientId_failure_surfaces_error_and_skips_navigation() = runTest {
        val identity = mockk<IdentityRepository>()
        val api = mockk<MeasurementsApi>()
        coEvery { identity.setPatientNumber(any()) } returns Unit
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-2",
                patientNumber = "P-00099",
                createdAt = "2026-06-30T00:00:00Z",
            )
        coEvery { identity.setPatientId(any()) } throws java.io.IOException("DataStore disk full")

        val vm = newViewModel(identity, api, this)
        // No subscriber: if NavigateToHome is emitted, it stays in the
        // SharedFlow's extra buffer (capacity=1). We check the buffer
        // after advanceUntilIdle to assert nothing was emitted.
        vm.onPatientNumberChange("P-00099")
        vm.onSubmit()
        advanceUntilIdle()

        coVerify { identity.setPatientId("uuid-2") }

        assertEquals(false, vm.state.value.isSubmitting)
        assertNotNull("state.error must surface the DataStore failure", vm.state.value.error)
        assertEquals(true, vm.state.value.error!!.contains("DataStore disk full"))

        // Subscribe AFTER advanceUntilIdle; with replay=0, the buffered
        // event (if any) is not delivered. This proves the production
        // code did not emit NavigateToHome.
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        val collector = backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        // If nothing was emitted, the collector will suspend forever;
        // cancel it to assert "no event was emitted".
        kotlinx.coroutines.delay(100)
        collector.cancel()
        assertEquals(false, eventReceived.isCompleted)
    }

    @Test
    fun registerPatient_failure_surfaces_error_and_skips_navigation() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        coEvery { api.registerPatient(any(), any()) } throws java.io.IOException("Backend unreachable")

        val vm = newViewModel(identity, api, this)
        vm.onPatientNumberChange("P-00042")
        vm.onSubmit()
        advanceUntilIdle()

        coVerify(exactly = 0) { identity.setPatientId(any()) }

        assertEquals(false, vm.state.value.isSubmitting)
        assertNotNull(vm.state.value.error)
        assertEquals(true, vm.state.value.error!!.contains("Backend unreachable"))

        val eventReceived = CompletableDeferred<OnboardingEvent>()
        val collector = backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        kotlinx.coroutines.delay(100)
        collector.cancel()
        assertEquals(false, eventReceived.isCompleted)
    }

    @Test
    fun invalid_input_does_not_invoke_any_side_effect() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()

        val vm = newViewModel(identity, api, this)
        vm.onPatientNumberChange("P_0042") // underscore, not in the regex
        vm.onSubmit()
        advanceUntilIdle()

        coVerify(exactly = 0) { identity.setPatientNumber(any()) }
        coVerify(exactly = 0) { api.registerPatient(any(), any()) }
        coVerify(exactly = 0) { identity.setPatientId(any()) }

        assertEquals(PatientNumberErrorMessage, vm.state.value.error)
        assertEquals(false, vm.state.value.isSubmitting)
    }
}
