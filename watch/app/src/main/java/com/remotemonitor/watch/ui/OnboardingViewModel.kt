package com.remotemonitor.watch.ui

import com.remotemonitor.watch.api.BedSnapshot
import com.remotemonitor.watch.api.MeasurementsApi
import com.remotemonitor.watch.api.RegisterPatientRequest
import com.remotemonitor.watch.identity.DeviceInfoProvider
import com.remotemonitor.watch.identity.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the bed-picker onboarding screen (T-WATCH-34, T-WATCH-35,
 * REQ-WATCH-17, REQ-WATCH-34, REQ-WATCH-35, REQ-WATCH-37).
 *
 * wear-bed-picker-onboarding D6 + D14 + D18 + D33:
 *  - `snapshotState`: a three-state machine — [SnapshotState.Loading],
 *    [SnapshotState.Loaded], [SnapshotState.Error]. The initial value is
 *    [SnapshotState.Loading] because the snapshot fetch fires from
 *    `LaunchedEffect(Unit) { vm.loadSnapshot() }` (D33) on the host
 *    `OnboardingScreen.kt`, not from `init {}` here.
 *  - `snapshot`: the list of beds returned by `getBedSnapshot()`. Empty
 *    until [SnapshotState.Loaded].
 *  - `dialog`: a `Closed | Open(bed: Int)` sealed class for the
 *    occupied-bed confirmation (D6).
 *  - `isSubmitting`: true while a POST is in flight or while
 *    `persistPaired` is mid-write; the dual guard (D14) uses this +
 *    `dialog` to suppress rapid double-tap.
 *  - `error`: a non-null human-readable message; null on success.
 */
data class OnboardingUiState(
    val snapshotState: SnapshotState = SnapshotState.Loading,
    val snapshot: List<BedSnapshot> = emptyList(),
    val dialog: BedDialogState = BedDialogState.Closed,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

/** Snapshot fetch state — drives the carousel + retry affordance. */
sealed interface SnapshotState {
    data object Loading : SnapshotState
    data object Loaded : SnapshotState
    data object Error : SnapshotState
}

/**
 * Dialog state — drives the [OccupiedBedDialog] composable. `Closed`
 * means the dialog is not rendered; `Open(bed)` carries the bed the
 * operator tapped so the host can re-render the dialog if the
 * configuration changes mid-flight.
 */
sealed interface BedDialogState {
    data object Closed : BedDialogState
    data class Open(val bed: Int) : BedDialogState
}

/** One-shot events emitted by [OnboardingViewModel] for navigation. */
sealed interface OnboardingEvent {
    /** POST /api/v1/patients succeeded; the operator should see Home. */
    data object NavigateToHome : OnboardingEvent
}

/**
 * ViewModel for the bed-picker onboarding screen (T-WATCH-35,
 * REQ-WATCH-18, REQ-WATCH-11, REQ-WATCH-37).
 *
 * wear-bed-picker-onboarding D14 + D15 + D18 + D24 + D33:
 *  - [loadSnapshot] is the snapshot-fetch entry point. The host calls
 *    it from `LaunchedEffect(Unit)` per D33 (NOT from `init {}`).
 *  - [onBedSelected] is the main interaction:
 *      1. Guard against re-entry (D14): if `snapshotState != Loaded`
 *         OR `isSubmitting` OR `dialog is Open`, drop the call.
 *      2. For a free bed (non-occupied) the operator went straight to
 *         submit; `replaceMode = false`.
 *      3. For an occupied bed the dialog opens first; on accept,
 *         `replaceMode = true`; on cancel, the dialog closes and the
 *         carousel remains.
 *      4. POST FIRST (D15) — no DataStore writes before the 201. On a
 *         201, call `identity.persistPaired(bedNumber, ciphertext,
 *         patientId)` ONCE (D24) — a single atomic `edit { }` block
 *         that either commits all three keys or rolls back together.
 *      5. On any failure: surface an error, do NOT navigate.
 */
class OnboardingViewModel(
    private val identity: IdentityRepository,
    private val api: MeasurementsApi,
    private val deviceInfo: DeviceInfoProvider,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * Channel + receiveAsFlow (per design D18 / R8): events emitted
     * before any subscriber attached are still delivered to the first
     * collector. `Channel.BUFFERED` (default capacity) is sufficient for
     * the single-shot NavigateToHome emission.
     */
    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    /**
     * wear-bed-picker-onboarding D33: snapshot fetch entry point. The
     * host `OnboardingScreen.kt` calls this from
     * `LaunchedEffect(Unit)` so the fetch fires once per screen visit.
     */
    fun loadSnapshot() {
        val current = _state.value
        // If a fetch is already in flight (Loading + empty snapshot),
        // or the snapshot is already loaded, do nothing.
        if (current.snapshotState == SnapshotState.Loaded) return
        _state.update { it.copy(snapshotState = SnapshotState.Loading, error = null) }
        scope.launch {
            val outcome = runCatching { api.getBedSnapshot() }
            outcome.onSuccess { beds ->
                _state.update {
                    it.copy(
                        snapshotState = SnapshotState.Loaded,
                        snapshot = beds,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        snapshotState = SnapshotState.Error,
                        error = e.message ?: "Failed to load bed status",
                    )
                }
            }
        }
    }

    /**
     * wear-bed-picker-onboarding D14 + D18: dual guard. Each early-out
     * is a no-op so a rapid double-tap during a POST does not
     * re-register the bed.
     */
    fun onBedSelected(bed: Int, replaceMode: Boolean) {
        val current = _state.value
        if (current.snapshotState != SnapshotState.Loaded) return
        if (current.isSubmitting) return
        if (current.dialog is BedDialogState.Open) return

        // D6: occupied beds open the confirm dialog; the POST happens
        // on accept. Free beds go straight to submit.
        val bedOccupied = current.snapshot
            .firstOrNull { it.bedNumber == bed }
            ?.isOccupied == true
        if (bedOccupied && !replaceMode) {
            _state.update { it.copy(dialog = BedDialogState.Open(bed)) }
            return
        }
        submitBed(bed = bed, replaceMode = replaceMode || bedOccupied)
    }

    /** D6: dialog dismiss — closes the dialog without submitting. */
    fun onDialogCancel() {
        _state.update { it.copy(dialog = BedDialogState.Closed) }
    }

    /**
     * D6: dialog accept — submit the bed with `replaceMode = true`,
     * closing the dialog as a side effect. If the bed state changed
     * (e.g. another watch freed the bed), the dual guard cancels the
     * submit silently.
     */
    fun onDialogAccept() {
        val dialog = _state.value.dialog
        if (dialog !is BedDialogState.Open) return
        _state.update { it.copy(dialog = BedDialogState.Closed) }
        submitBed(bed = dialog.bed, replaceMode = true)
    }

    /**
     * Internal: run the POST → persistPaired pipeline. Both steps must
     * complete for the operator to land on Home. Per D15: POST FIRST,
     * then `persistPaired`. Per D24: a single atomic write — partial
     * DataStore writes are NEVER persisted (the JVM either commits all
     * three keys or rolls back together).
     *
     * D24 atomicity test gate: `S_post_failure_leaves_datastore_clean_*`
     * asserts that all three identity keys stay null on any failure
     * path (Pre-POST IOException AND Post-POST IOException on
     * persistPaired).
     */
    private fun submitBed(bed: Int, replaceMode: Boolean) {
        _state.update { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            val outcome = runCatching {
                val response = api.registerPatient(
                    bedNumber = bed.toString(),
                    body = RegisterPatientRequest(
                        bedNumber = bed,
                        deviceModel = deviceInfo.deviceModel(),
                        osVersion = deviceInfo.osVersion(),
                        replaceActiveSession = replaceMode,
                    ),
                )
                identity.persistPaired(
                    bedNumber = bed.toString(),
                    patientNumberCipher = response.patientNumber,
                    patientId = response.patientId,
                )
                response
            }
            outcome.onSuccess {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                    )
                }
                _events.trySend(OnboardingEvent.NavigateToHome)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = e.message ?: "Pairing failed",
                    )
                }
                // D24: nothing was written; clean state on retry.
            }
        }
    }
}
