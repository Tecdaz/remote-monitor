package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text as WearText
import com.remotemonitor.watch.R
import com.remotemonitor.watch.api.BedSnapshot

/**
 * Bed-picker onboarding screen (T-WATCH-34, REQ-WATCH-17, REQ-WATCH-18,
 * REQ-WATCH-34, REQ-WATCH-35, REQ-WATCH-37).
 *
 * wear-bed-picker-onboarding D6 + D10 + D14 + D33: a stateless
 * Composable that:
 *  - Composes the [BedPickerScreen] carousel + the [OccupiedBedDialog]
 *    confirmation.
 *  - Renders the carousel when [snapshotState] is [SnapshotState.Loaded];
 *    a loading message while [SnapshotState.Loading]; the
 *    [SnapshotStatusMessage] retry affordance while [SnapshotState.Error].
 *  - The snapshot fetch itself is triggered from the host
 *    `LaunchedEffect(Unit) { vm.loadSnapshot() }` (D33) — this screen
 *    intentionally does NOT fire the fetch, so the host retains the
 *    test-friendly trigger pattern.
 *  - On a successful pairing, the VM emits
 *    `OnboardingEvent.NavigateToHome` through its events channel (the
 *    host `MainActivity` NavHost listens for it). This screen does NOT
 *    show a snackbar (D10 snackbar variant lives in PR-3c alongside
 *    the Home screen).
 *
 * The dual guard (D14) is enforced here AND in the VM:
 *  - VM: re-entrant calls into [OnboardingViewModel.onBedSelected] are
 *    dropped if `snapshotState != Loaded || isSubmitting || dialog is Open`.
 *  - UI: the [BedPickerScreen] renders each bed's button with
 *    `enabled = enabledBeds.contains(bed)` derived from
 *    `snapshotState == Loaded && !isSubmitting && dialog is Closed`.
 *
 * @param dialog dialog visibility is owned by the VM (D6). The screen
 *        simply routes the visible state to [OccupiedBedDialog] and the
 *        callbacks back to the VM.
 */
@Composable
fun OnboardingScreen(
    snapshot: List<BedSnapshot>,
    snapshotState: SnapshotState,
    error: String?,
    isSubmitting: Boolean,
    dialog: BedDialogState,
    onBedSelected: (bed: Int, replaceMode: Boolean) -> Unit,
    onSnapshotRetry: () -> Unit,
    onDialogAceptar: () -> Unit,
    onDialogCancelar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (snapshotState) {
                SnapshotState.Loaded -> {
                    val enabledBeds = computeEnabledBeds(snapshotState, isSubmitting, dialog)
                    BedPickerScreen(
                        snapshot = snapshot,
                        onBedSelected = { bed -> onBedSelected(bed, false) },
                        enabledBeds = enabledBeds,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("bed-picker"),
                    )
                }
                SnapshotState.Loading -> {
                    WearText(
                        text = stringResource(R.string.onboarding_section_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("snapshot-loading"),
                    )
                }
                SnapshotState.Error -> {
                    SnapshotStatusMessage(
                        isError = true,
                        onRetry = onSnapshotRetry,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("snapshot-error"),
                    )
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding-error"),
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
            }
        }
        OccupiedBedDialog(
            visible = dialog is BedDialogState.Open,
            onAccept = onDialogAceptar,
            onCancel = onDialogCancelar,
            modifier = Modifier
                .fillMaxSize()
                .testTag("occupied-bed-dialog"),
        )
    }
}

/**
 * D14 dual guard (screen-side): derive the enabled-beds predicate from
 * the VM state. When `snapshotState != Loaded`, OR `isSubmitting`, OR
 * `dialog is Open` — every bed is disabled.
 */
private fun computeEnabledBeds(
    snapshotState: SnapshotState,
    isSubmitting: Boolean,
    dialog: BedDialogState,
): Set<Int> {
    if (snapshotState != SnapshotState.Loaded) return emptySet()
    if (isSubmitting) return emptySet()
    if (dialog is BedDialogState.Open) return emptySet()
    return setOf(1, 2, 3, 4, 5)
}
