package com.remotemonitor.watch.ui

import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.api.RegisterPatientRequest
import com.remotemonitor.watch.identity.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the onboarding screen (T-WATCH-35, REQ-WATCH-18).
 *
 * @property patientNumber the current value of the input field
 * @property error non-null while showing a validation / submission error
 * @property isSubmitting true while the API call is in flight
 */
data class OnboardingUiState(
    val patientNumber: String = "",
    val error: String? = null,
    val isSubmitting: Boolean = false,
)

/** One-shot events emitted by [OnboardingViewModel] for navigation. */
sealed interface OnboardingEvent {
    /** POST /api/v1/patients succeeded; the operator should see Home. */
    data object NavigateToHome : OnboardingEvent
}

/**
 * ViewModel for the onboarding screen (T-WATCH-35, REQ-WATCH-18,
 * REQ-WATCH-11).
 *
 * Lifecycle:
 *  - Constructor-injected with [IdentityRepository], [MeasurementsApi],
 *    and a [CoroutineScope] (typically the ViewModel scope). The scope
 *    parameter keeps the ViewModel testable in pure JVM without
 *    `viewModelScope` (which would require the `androidx.lifecycle`
 *    Compose runtime).
 *  - `onSubmit()` runs the validation; on success it calls
 *    [IdentityRepository.setPatientNumber], then
 *    [MeasurementsApi.registerPatient], then
 *    [IdentityRepository.setPatientId] (with the patientId returned by
 *    the backend). On any failure the state emits `error`.
 *  - A successful submission emits [OnboardingEvent.NavigateToHome] so
 *    the host (MainActivity) can swap destinations.
 */
class OnboardingViewModel(
    private val identity: IdentityRepository,
    private val api: MeasurementsApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    /** Update the input field and clear any previous error. */
    fun onPatientNumberChange(value: String) {
        _state.update { it.copy(patientNumber = value, error = null) }
    }

    /**
     * Validate the input and (if valid) call the backend. Re-entrant calls
     * while a submission is in flight are ignored.
     */
    fun onSubmit() {
        val current = _state.value
        if (current.isSubmitting) return
        if (!PatientNumberRegex.matches(current.patientNumber)) {
            _state.update { it.copy(error = PatientNumberErrorMessage) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            runCatching {
                identity.setPatientNumber(current.patientNumber)
                api.registerPatient(
                    patientNumber = current.patientNumber,
                    body = RegisterPatientRequest(patientNumber = current.patientNumber),
                )
            }.onSuccess { response ->
                runCatching { identity.setPatientId(response.patientId) }
                _state.update { it.copy(isSubmitting = false) }
                _events.tryEmit(OnboardingEvent.NavigateToHome)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = e.message ?: "Registration failed",
                    )
                }
            }
        }
    }
}
