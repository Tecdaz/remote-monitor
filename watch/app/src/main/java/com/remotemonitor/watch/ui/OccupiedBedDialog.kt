package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text as WearText
import com.remotemonitor.watch.R

/**
 * Full-screen occupied-bed confirmation dialog (T-WATCH-35, REQ-WATCH-35).
 *
 * wear-bed-picker-onboarding D6: a Wear M3 [AlertDialog] that hard-stops
 * the carousel flow when the operator tries to pair a bed the backend
 * already has an active session for. The two callbacks (`onAccept`,
 * `onCancel`) are wired by the host (the screen does not own dialog
 * state).
 *
 * The dialog is shown AFTER the operator taps the bed in the carousel.
 * It does NOT fire on first paint; the `OnboardingViewModel.openDialog`
 * is invoked from the host `OnboardingScreen` in response to the
 * carousel callback.
 */
@Composable
fun OccupiedBedDialog(
    visible: Boolean,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onCancel,
        modifier = modifier.testTag("occupied-bed-dialog"),
        title = {
            WearText(
                text = stringResource(R.string.dialog_occupied_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialog-title"),
            )
        },
        text = {
            WearText(
                text = stringResource(R.string.dialog_occupied_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dialog-body"),
            )
        },
        confirmButton = {
            androidx.wear.compose.material3.Button(
                onClick = onAccept,
                modifier = Modifier
                    .testTag("dialog-accept")
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = "Accept occupied bed replacement" },
            ) {
                WearText(
                    text = stringResource(R.string.dialog_accept),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            androidx.wear.compose.material3.TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .testTag("dialog-cancel")
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = "Cancel occupied bed dialog" },
            ) {
                WearText(
                    text = stringResource(R.string.dialog_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/**
 * Status affordance for the snapshot-loading / snapshot-error states.
 * Hosts the retry button when the snapshot fetch failed.
 */
@Composable
fun SnapshotStatusMessage(
    isError: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        WearText(
            text = stringResource(
                if (isError) R.string.error_snapshot_failed else R.string.onboarding_section_label,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isError) {
            Spacer(Modifier.height(8.dp))
            androidx.wear.compose.material3.Button(
                onClick = onRetry,
                modifier = Modifier
                    .testTag("snapshot-retry")
                    .semantics { contentDescription = "Retry snapshot" },
            ) {
                WearText(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Unused ref kept here for parity with the Snackbar affordance in
 * [OnboardingScreen]. Reserved for future PR-3d "occupancy timeout"
 * follow-up.
 */
@Suppress("unused")
@Composable
internal fun DialogButtonRow(
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.wear.compose.material3.Button(onClick = onAccept) {
            WearText(text = stringResource(R.string.dialog_accept))
        }
        androidx.wear.compose.material3.TextButton(onClick = onCancel) {
            WearText(text = stringResource(R.string.dialog_cancel))
        }
    }
}
