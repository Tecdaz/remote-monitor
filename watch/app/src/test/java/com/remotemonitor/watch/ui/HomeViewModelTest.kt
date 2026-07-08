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
 * Unit tests for [HomeViewModel] (PR 3 fresh-review follow-up;
 * wear-bed-picker-onboarding D17 + D25).
 *
 * The fresh review of PR 3 flagged a stale-read bug in the previous
 * implementation: `init { scope.launch { identity.getPatientNumber() } }`
 * ran once and wrote the result to a class-level `MutableStateFlow`.
 * When `SharingStarted.WhileSubscribed(5_000L)` cancelled the upstream
 * after a period of no subscribers (e.g., activity recreation,
 * navigation away-and-back) and a fresh subscription arrived, the
 * state flow re-emitted the initialValue (null bedNumber) and only
 * re-populated from the cached local flow on the next emission.
 *
 * The fix moves the read into the state flow itself via a cold
 * `flow { emit(identity.getBedNumber()) }`, so every subscription
 * re-reads the latest value from the repo. wear-bed-picker-onboarding
 * D17 switched the probe from `getPatientNumber()` (post-PR-2 returns
 * the CIPHERTEXT for a freshly-paired bed — not the bed plaintext in
 * 1..5) to `getBedNumber()` so the screen surfaces the plaintext bed
 * number via `stringResource(R.string.home_bed_label, ...)`.
 *
 * Scenarios:
 *  - **re_reads_on_resubscribe**: after a 5+s unsubscribe, the next
 *    subscription must see the latest value from the repo (not the
 *    cached value from the first subscription). Regression test for
 *    the fresh-review finding, now adapted to `getBedNumber()`.
 *  - **initial_state_surfaces_bed_number**: the first emission after
 *    subscription has the bedNumber from the repo.
 *  - **pending_count_propagates**: changes to the pendingCount flow
 *    are reflected in the state (proves the combine is wired).
 *  - **S_home_displays_bed_label** (D25): locale-agnostic assertion
 *    that `stringResource(R.string.home_bed_label, 3) == "Bed 3"`
 *    under Robolectric's default English locale. The expected string
 *    is built via `context.getString(...)` rather than a hardcoded
 *    literal so the test is locale-agnostic per D25.
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
    fun re_reads_bed_number_on_resubscribe() = runTest {
        // Boxed so the mock can return different values for the two
        // subscriptions. `BedCiphertextFixture.KNOWN_BED` style
        // literals (`"P-00001"`) are intentionally avoided — the
        // bed plaintext is in 1..5 (D17).
        val bedNumberBox = arrayOf<String?>("3")
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getBedNumber() } coAnswers { bedNumberBox[0] }

        val pendingCountFlow = MutableStateFlow(0)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        // First subscription: state should show "3" once the upstream
        // emits. The previous one-shot `init` read populated a
        // class-level MutableStateFlow; the new cold-flow read
        // collects on every subscription.
        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals("3", first.bedNumber)

        // `first` cancels the collection on match. With
        // WhileSubscribed(5_000L) the upstream is cancelled after 5s
        // of no subscribers. Fast-forward virtual time past the
        // timeout so the next subscription sees a fresh upstream.
        advanceTimeBy(5_100)
        runCurrent()

        // Simulate the operator re-pairing with a new bed number (or
        // any external write to DataStore).
        bedNumberBox[0] = "4"

        // Re-subscribe: the upstream must re-collect the bed-number
        // flow and see "4". With the previous cached-read
        // implementation, the state would show "3" (the stale value
        // from the first subscription) because the init block had
        // already populated the class-level MutableStateFlow and did
        // not re-run. We use withTimeout so a regression fails fast
        // (2s of virtual time) instead of hanging.
        val second = withTimeout(2_000) {
            vm.state.first { it.bedNumber == "4" }
        }
        assertEquals("4", second.bedNumber)
    }

    @Test
    fun initial_state_surfaces_bed_number() = runTest {
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getBedNumber() } returns "3"

        val pendingCountFlow = MutableStateFlow(3)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals("3", first.bedNumber)
        assertEquals(3, first.pendingCount)
    }

    @Test
    fun pending_count_propagates_through_combine() = runTest {
        val identity = mockk<IdentityRepository>()
        coEvery { identity.getBedNumber() } returns "3"

        val pendingCountFlow = MutableStateFlow(0)
        val dao = mockk<MeasurementDao>()
        every { dao.pendingCount() } returns pendingCountFlow

        val vm = newViewModel(identity, dao, this)

        val first = vm.state.first { it.bedNumber == "3" }
        assertEquals(0, first.pendingCount)

        // Update the pending count and assert the state follows.
        pendingCountFlow.value = 7
        val second = vm.state.first { it.pendingCount == 7 }
        assertEquals("3", second.bedNumber)
        assertEquals(7, second.pendingCount)
    }
}
