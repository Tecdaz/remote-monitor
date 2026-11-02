package com.remotemonitor.watch.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotemonitor.watch.R
import com.remotemonitor.watch.api.BedSnapshot
import com.remotemonitor.watch.ui.BedDialogState
import com.remotemonitor.watch.ui.SnapshotState

/**
 * Bed-picker onboarding for phones and tablets.
 *
 * Same state machine and callbacks as the Wear screen; the layout is a
 * responsive card grid (2 columns on a phone, 3 on a tablet portrait, 4
 * on a tablet landscape). Occupied beds open the confirmation dialog;
 * free beds submit directly.
 */
@Composable
fun MobileOnboardingScreen(
    snapshot: List<BedSnapshot>,
    snapshotState: SnapshotState,
    error: String?,
    isSubmitting: Boolean,
    dialog: BedDialogState,
    onBedSelected: (bed: Int, replaceMode: Boolean) -> Unit,
    onSnapshotRetry: () -> Unit,
    onDialogAccept: () -> Unit,
    onDialogCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val columns = when {
            maxWidth < 600.dp -> 2
            maxWidth < 840.dp -> 3
            else -> 4
        }
        Column(
            modifier = Modifier
                .widthIn(max = 1080.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.band_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_section_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            when (snapshotState) {
                SnapshotState.Loading -> {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Text(
                                text = stringResource(R.string.onboarding_section_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SnapshotState.Error -> {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.error_snapshot_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onSnapshotRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                SnapshotState.Loaded -> {
                    val enabledBeds = when {
                        isSubmitting || dialog is BedDialogState.Open -> emptySet()
                        else -> (1..5).toSet()
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("bed-picker"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(snapshot, key = { it.bedNumber }) { bed ->
                            BedCard(
                                bed = bed,
                                enabled = enabledBeds.contains(bed.bedNumber),
                                onClick = { onBedSelected(bed.bedNumber, false) },
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("onboarding-error"),
                )
            }
        }

        OccupiedBedDialogMobile(
            visible = dialog is BedDialogState.Open,
            onAccept = onDialogAccept,
            onCancel = onDialogCancel,
        )
    }
}

@Composable
private fun BedCard(
    bed: BedSnapshot,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val occupied = bed.isOccupied
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("bed-${bed.bedNumber}"),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant
                occupied -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_bed_label, bed.bedNumber),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (occupied) R.string.bed_occupied else R.string.bed_free,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OccupiedBedDialogMobile(
    visible: Boolean,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("occupied-bed-dialog"),
        title = {
            Text(
                text = stringResource(R.string.dialog_occupied_title),
                modifier = Modifier.testTag("dialog-title"),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_occupied_body),
                modifier = Modifier.testTag("dialog-body"),
            )
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("dialog-accept"),
            ) {
                Text(stringResource(R.string.dialog_accept))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("dialog-cancel"),
            ) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
