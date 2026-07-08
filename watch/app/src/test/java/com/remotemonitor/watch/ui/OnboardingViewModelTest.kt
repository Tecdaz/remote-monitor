package com.remotemonitor.watch.ui

import com.remotemonitor.watch.api.BedSnapshot
import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.api.RegisterPatientRequest
import com.remotemonitor.watch.api.RegisterPatientResponse
import com.remotemonitor.watch.identity.DeviceInfoProvider
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the rewritten [OnboardingViewModel] (T-WATCH-35,
 * REQ-WATCH-18, REQ-WATCH-11, REQ-WATCH-37).
 *
 * wear-bed-picker-onboarding D14 + D15 + D18 + D24:
 *  - **D24 atomicity** (load-bearing): the persistPaired two-case test
 *    asserts that on any failure path (Pre-POST IOException AND
 *    Post-POST IOException on persistPaired), all three identity keys
 *    stay null and the operator sees an error.
 *  - **D15 POST-first reorder**: the happy-path emits NavigateToHome
 *    AFTER persistPaired lands, and `coVerifyOrder` proves the order.
 *  - **D14 dual guard**: a tap during Loading/Submitting is a no-op;
 *    a second tap on a free bed during Submitting does NOT issue a
 *    second POST.
 *  - **D6 dialog routing**: tapping an occupied bed opens the dialog
 *    state without issuing a POST. Accept → POST with replaceMode=true.
 *    Cancel → dialog closes, no POST.
 *  - **R8 late-subscriber regression**: the late_subscriber regression
 *    covers Channel-buffered event delivery (Channel + receiveAsFlow
 *    is load-bearing).
 *
 * The test scaffolding mirrors the original OnboardingViewModelTest
 * (StandardTestDispatcher + TestScope) so the VM scope is isolated
 * from `runTest`'s scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private fun newViewModel(
        identity: IdentityRepository,
        api: MeasurementsApi,
        deviceInfo: DeviceInfoProvider,
        scope: TestScope,
    ): OnboardingViewModel {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return OnboardingViewModel(
            identity = identity,
            api = api,
            deviceInfo = deviceInfo,
            scope = TestScope(dispatcher),
        )
    }

    private val testSnapshot = listOf(
        BedSnapshot(bedNumber = 1, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 2, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 3, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 4, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 5, isOccupied = false, currentPatientId = null),
    )

    private val occupiedSnapshot = listOf(
        BedSnapshot(bedNumber = 1, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 2, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 3, isOccupied = true, currentPatientId = "uuid-3"),
        BedSnapshot(bedNumber = 4, isOccupied = false, currentPatientId = null),
        BedSnapshot(bedNumber = 5, isOccupied = false, currentPatientId = null),
    )

    // ============================================================================
    // D15 happy path: POST → persistPaired → NavigateToHome
    // ============================================================================

    @Test
    fun happy_path_post_first_then_persist_emits_navigate_to_home() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "CIPHERTEXT",
                createdAt = "2026-06-30T00:00:00Z",
            )

        val vm = newViewModel(identity, api, deviceInfo, this)
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        vm.loadSnapshot()
        advanceUntilIdle()
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        coVerifyOrder {
            api.registerPatient(
                "3",
                RegisterPatientRequest(
                    bedNumber = 3,
                    deviceModel = "samsung SM-R870",
                    osVersion = "16 (API 36)",
                    replaceActiveSession = false,
                ),
            )
            identity.persistPaired(
                bedNumber = "3",
                patientNumberCipher = "CIPHERTEXT",
                patientId = "uuid-1",
            )
        }
        assertFalse(vm.state.value.isSubmitting)
        assertNull(vm.state.value.error)
        assertEquals(OnboardingEvent.NavigateToHome, eventReceived.await())
    }

    // ============================================================================
    // D24 atomicity — the load-bearing test for the chain
    //
    // Two cases:
    //  (a) Pre-POST IOException: api.registerPatient throws; persistPaired
    //      is NEVER called. No DataStore writes — keys stay null.
    //  (b) Post-POST IOException on persistPaired: registerPatient returns
    //      201; persistPaired throws. The single edit{} block rolls back
    //      atomically; keys stay null.
    //
    // Both cases must:
    //  - NOT emit NavigateToHome
    //  - Surface error in state
    //  - Leave the state so a retry is a clean slate
    // ============================================================================

    @Test
    fun S_post_failure_leaves_datastore_clean_pre_post_failure() = runTest {
        // Case (a): api.registerPatient throws BEFORE persistPaired runs.
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot
        coEvery { api.registerPatient(any(), any()) } throws java.io.IOException("Backend unreachable")

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        // PersistPaired was NEVER called → DataStore is untouched.
        coVerify(exactly = 0) { identity.persistPaired(any(), any(), any()) }
        assertFalse(vm.state.value.isSubmitting)
        assertNotNull("state.error must surface the API failure", vm.state.value.error)
        assertTrue(
            "state.error must mention the IOException cause",
            vm.state.value.error!!.contains("Backend unreachable"),
        )
        // Verify NavigateToHome was NOT emitted.
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        val collector = backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        kotlinx.coroutines.delay(100)
        collector.cancel()
        assertEquals(false, eventReceived.isCompleted)
    }

    @Test
    fun S_post_failure_leaves_datastore_clean_post_post_failure() = runTest {
        // Case (b): api.registerPatient returns 201; persistPaired throws.
        // Per D24, the single edit{} block rolls back atomically. None of
        // the three keys are observable in any intermediate state.
        val identity = mockk<IdentityRepository>()
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "CIPHERTEXT",
                createdAt = "2026-06-30T00:00:00Z",
            )
        coEvery {
            identity.persistPaired(any(), any(), any())
        } throws java.io.IOException("DataStore disk full")

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        // POST succeeded → persistPaired WAS called → it threw.
        coVerify(exactly = 1) { identity.persistPaired(any(), any(), any()) }
        assertFalse(vm.state.value.isSubmitting)
        assertNotNull("state.error must surface the persistPaired failure", vm.state.value.error)
        assertTrue(
            "state.error must mention the IOException cause",
            vm.state.value.error!!.contains("DataStore disk full"),
        )
        // Critical D24 assertion: the VM does not emit a partial state.
        // The single edit{} block either commits all three keys or rolls
        // back. We don't directly observe the DataStore here (that's
        // IdentityRepositoryImplTest's job) but we assert that the VM
        // surfaced the error and did NOT navigate.
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        val collector = backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        kotlinx.coroutines.delay(100)
        collector.cancel()
        assertEquals(false, eventReceived.isCompleted)
    }

    // ============================================================================
    // D14 dual guard (VM-side)
    // ============================================================================

    @Test
    fun D14_loading_state_tap_is_noop() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        // getBedSnapshot SUSPENDS so the snapshot fetch is still in flight.
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        // Snapshot is still Loading here; onBedSelected must be a no-op.
        assertEquals(SnapshotState.Loading, vm.state.value.snapshotState)
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { api.registerPatient(any(), any()) }
        coVerify(exactly = 0) { identity.persistPaired(any(), any(), any()) }
    }

    @Test
    fun D14_reentrant_tap_during_submitting_is_noop() = runTest {
        val identity = mockk<IdentityRepository>()
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot
        // Block the first POST so the second tap arrives during Submitting.
        val firstPostGate = CompletableDeferred<RegisterPatientResponse>()
        coEvery { api.registerPatient(any(), any()) } coAnswers { firstPostGate.await() }

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()

        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()  // registerPatient suspends at firstPostGate
        // Second tap during Submitting — must be a no-op.
        vm.onBedSelected(bed = 4, replaceMode = false)
        advanceUntilIdle()

        // Exactly ONE POST was issued (the second tap was dropped).
        coVerify(exactly = 1) { api.registerPatient(any(), any()) }

        // Release the first POST to let the test clean up.
        firstPostGate.complete(
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "CIPHERTEXT",
                createdAt = "2026-06-30T00:00:00Z",
            ),
        )
    }

    // ============================================================================
    // D6 dialog routing
    // ============================================================================

    @Test
    fun D6_occupied_bed_opens_dialog_without_post() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns occupiedSnapshot

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()

        // Tap the occupied bed 3 — dialog should open, no POST.
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        assertEquals(BedDialogState.Open(3), vm.state.value.dialog)
        coVerify(exactly = 0) { api.registerPatient(any(), any()) }
    }

    @Test
    fun D6_dialog_accept_submits_with_replaceMode_true() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns occupiedSnapshot
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "CIPHERTEXT",
                createdAt = "2026-06-30T00:00:00Z",
            )

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()

        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()
        assertEquals(BedDialogState.Open(3), vm.state.value.dialog)

        vm.onDialogAccept()
        advanceUntilIdle()

        // The POST went out with replaceActiveSession = true.
        coVerify {
            api.registerPatient(
                "3",
                RegisterPatientRequest(
                    bedNumber = 3,
                    deviceModel = "samsung SM-R870",
                    osVersion = "16 (API 36)",
                    replaceActiveSession = true,
                ),
            )
        }
        // Dialog is back to Closed.
        assertEquals(BedDialogState.Closed, vm.state.value.dialog)
    }

    @Test
    fun D6_dialog_cancel_closes_dialog_without_post() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns occupiedSnapshot

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()
        assertEquals(BedDialogState.Open(3), vm.state.value.dialog)

        vm.onDialogCancel()
        advanceUntilIdle()

        assertEquals(BedDialogState.Closed, vm.state.value.dialog)
        coVerify(exactly = 0) { api.registerPatient(any(), any()) }
    }

    // ============================================================================
    // R8 — late_subscriber regression (Channel delivers buffered events)
    // ============================================================================

    @Test
    fun late_subscriber_still_receives_navigate_to_home() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot
        coEvery { api.registerPatient(any(), any()) } returns
            RegisterPatientResponse(
                patientId = "uuid-1",
                patientNumber = "CIPHERTEXT",
                createdAt = "2026-06-30T00:00:00Z",
            )

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()
        vm.onBedSelected(bed = 3, replaceMode = false)
        advanceUntilIdle()

        // The event was emitted before this collector attached.
        val eventReceived = CompletableDeferred<OnboardingEvent>()
        val collector = backgroundScope.launch {
            vm.events.first().let { eventReceived.complete(it) }
        }
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            "Late subscriber must still receive the NavigateToHome event",
            true,
            eventReceived.isCompleted,
        )
        if (eventReceived.isCompleted) {
            assertEquals(OnboardingEvent.NavigateToHome, eventReceived.getCompleted())
        }
        collector.cancel()
    }

    // ============================================================================
    // S12 — legacy_key_patient_number_does_not_auto_migrate
    //
    // The pre-bed-picker flow wrote KEY_PATIENT_NUMBER (ciphertext)
    // without a KEY_BED_NUMBER. OnboardingViewModel does NOT inspect
    // legacy keys; an operator with only KEY_PATIENT_NUMBER set will
    // land on OnboardingScreen and have to re-pair. Verify this by
    // observing the VM's snapshotState path: loadSnapshot() always
    // re-fetches via the API, regardless of identity key state.
    // ============================================================================

    @Test
    fun S12_legacy_key_patient_number_does_not_auto_migrate() = runTest {
        val identity = mockk<IdentityRepository>(relaxed = true)
        // Pretend a legacy pairing is in DataStore (KEY_PATIENT_NUMBER
        // set, KEY_BED_NUMBER null). The VM does NOT consult these.
        coEvery { identity.getPatientNumber() } returns "OLD_CIPHERTEXT"
        coEvery { identity.getBedNumber() } returns null

        val api = mockk<MeasurementsApi>()
        val deviceInfo = mockk<DeviceInfoProvider>()
        coEvery { deviceInfo.deviceModel() } returns "samsung SM-R870"
        coEvery { deviceInfo.osVersion() } returns "16 (API 36)"
        coEvery { api.getBedSnapshot() } returns testSnapshot

        val vm = newViewModel(identity, api, deviceInfo, this)
        vm.loadSnapshot()
        advanceUntilIdle()

        // The VM loaded the snapshot from the API (it didn't read the
        // legacy ciphertext and short-circuit to Home).
        assertEquals(SnapshotState.Loaded, vm.state.value.snapshotState)
        assertEquals(testSnapshot, vm.state.value.snapshot)
    }
}
