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
 * UI state for the home screen (T-WATCH-38, REQ-WATCH-19).
 *
 * @property patientNumber operator-typed number (may be null before
 *           onboarding completes; the home screen renders an empty
 *           string in that case rather than crashing)
 * @property pendingCount number of measurements still in Room awaiting
 *           upload (presence-in-table = pending, per design D3)
 */
data class HomeUiState(
    val patientNumber: String? = null,
    val pendingCount: Int = 0,
)

/**
 * ViewModel for the home screen (T-WATCH-38, REQ-WATCH-19).
 *
 * Combines two reactive sources into a single [HomeUiState] flow:
 *  - [IdentityRepository.getPatientNumber] is wrapped in a cold
 *    `flow { emit(identity.getPatientNumber()) }` and re-collected on
 *    every subscription. This avoids the stale-read bug where the
 *    previous implementation cached the value in a one-shot `init`
 *    block: after `WhileSubscribed(5_000L)` cancelled the upstream and
 *    a fresh subscription arrived (e.g., activity recreation, screen
 *    nav), the state flow re-emitted the initialValue (null
 *    patientNumber) until the upstream restarted, causing a brief null
 *    flicker. Wrapping the getter in a cold flow keeps the read on the
 *    re-subscription path so the state never reports a stale value.
 *  - [MeasurementDao.pendingCount] is observed as a `Flow<Int>` (Room
 *    supports `@Query("SELECT COUNT(*)")` returning a `Flow` that
 *    re-emits on every insert / delete). The screen reflects this in
 *    real time without polling.
 *
 * The `catch` on the patient number flow keeps a DataStore I/O error
 * from killing the combine (the operator-typed number is a
 * "nice-to-have" label; if DataStore is down, we just keep the
 * initialValue with patientNumber = null rather than crash the home
 * screen).
 */
class HomeViewModel(
    private val identity: IdentityRepository,
    private val dao: MeasurementDao,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<HomeUiState> = combine(
        flow { emit(identity.getPatientNumber()) }.catch { /* keep state at initialValue */ },
        dao.pendingCount(),
    ) { patientNumber, pendingCount ->
        HomeUiState(patientNumber = patientNumber, pendingCount = pendingCount)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
