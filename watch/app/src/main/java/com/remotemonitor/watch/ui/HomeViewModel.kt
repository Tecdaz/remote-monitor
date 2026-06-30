package com.remotemonitor.watch.ui

import com.remotemonitor.watch.data.MeasurementDao
import com.remotemonitor.watch.identity.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 *  - [IdentityRepository.getPatientNumber] is called once at start
 *    (the operator-typed number does not change while the home screen
 *    is on top — the onboarding flow owns the write path).
 *  - [MeasurementDao.pendingCount] is observed as a `Flow<Int>` (Room
 *    supports `@Query("SELECT COUNT(*)")` returning a `Flow` that
 *    re-emits on every insert / delete). The screen reflects this in
 *    real time without polling.
 */
class HomeViewModel(
    private val identity: IdentityRepository,
    private val dao: MeasurementDao,
    private val scope: CoroutineScope,
) {
    private val patientNumberFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeUiState> = combine(
        patientNumberFlow,
        dao.pendingCount(),
    ) { patientNumber, pendingCount ->
        HomeUiState(patientNumber = patientNumber, pendingCount = pendingCount)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    init {
        // Patient number is a one-shot read; refresh on first launch.
        scope.launch {
            runCatching { identity.getPatientNumber() }
                .onSuccess { patientNumberFlow.value = it }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
