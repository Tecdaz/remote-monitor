package com.remotemonitor.watch.ui

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * UI state for the home screen (T-WATCH-38, REQ-WATCH-19,
 * wear-bed-picker-onboarding D17).
 *
 * @property bedNumber operator-paired bed in `1..5` (may be null before
 *           onboarding completes; the home screen renders `0` in that
 *           case rather than crashing — see D17)
 * @property pendingCount number of measurements still in Room awaiting
 *           upload (presence-in-table = pending, per design D3)
 */
data class HomeUiState(
    val bedNumber: String? = null,
    val pendingCount: Int = 0,
)

/**
 * ViewModel for the home screen (T-WATCH-38, REQ-WATCH-19,
 * wear-bed-picker-onboarding D17 + D25).
 *
 * Combines two reactive sources into a single [HomeUiState] flow:
 *  - [IdentityRepository.getBedNumber] is wrapped in a cold
 *    `flow { emit(identity.getBedNumber()) }` and re-collected on every
 *    subscription. This avoids the stale-read bug where the previous
 *    implementation cached the value in a one-shot `init` block: after
 *    `WhileSubscribed(5_000L)` cancelled the upstream and a fresh
 *    subscription arrived (e.g., activity recreation, screen nav), the
 *    state flow re-emitted the initialValue (null bedNumber) until the
 *    upstream restarted, causing a brief null flicker. Wrapping the
 *    getter in a cold flow keeps the read on the re-subscription path
 *    so the state never reports a stale value.
 *  - [MeasurementDao.pendingCount] is observed as a `Flow<Int>` (Room
 *    supports `@Query("SELECT COUNT(*)")` returning a `Flow` that
 *    re-emits on every insert / delete). The screen reflects this in
 *    real time without polling.
 *
 * wear-bed-picker-onboarding D17: the previous implementation read
 * `identity.getPatientNumber()` which (post-PR-2) returns the CIPHERTEXT
 * for a freshly-paired bed — not the bed plaintext in `1..5`. The
 * display would have leaked the ciphertext to the watch screen. The
 * D17 fix reads `getBedNumber()` and renders via
 * `R.string.home_bed_label` / `R.string.home_status_label` (D25 format-
 * arg discipline).
 *
 * The `catch` on the bed number flow keeps a DataStore I/O error from
 * killing the combine (the bed number is a "nice-to-have" label; if
 * DataStore is down, we just keep the initialValue with bedNumber =
 * null rather than crash the home screen).
 */
class HomeViewModel(
    private val identity: IdentityRepository,
    private val dao: MeasurementDao,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<HomeUiState> = combine(
        flow { emit(identity.getBedNumber()) }.catch { /* keep state at initialValue */ },
        dao.pendingCount(),
    ) { bedNumber, pendingCount ->
        HomeUiState(bedNumber = bedNumber, pendingCount = pendingCount)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
