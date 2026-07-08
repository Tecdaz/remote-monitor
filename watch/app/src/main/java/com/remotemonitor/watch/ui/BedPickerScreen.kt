package com.remotemonitor.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text as WearText
import com.remotemonitor.watch.R
import com.remotemonitor.watch.api.BedSnapshot
import com.remotemonitor.watch.ui.theme.BED_FREE_COLOR
import com.remotemonitor.watch.ui.theme.BED_OCCUPIED_COLOR

/**
 * Bed-picker carousel screen (T-WATCH-34, REQ-WATCH-17, REQ-WATCH-34,
 * REQ-WATCH-35).
 *
 * wear-bed-picker-onboarding D5: five pages in a [HorizontalPager], one
 * per bed 1..5. Each page renders a circular [Box] (Badge-shaped) with a
 * 56dp+ tap target, plus a state label ("Libre" / "Ocupada") that is
 * also the `contentDescription` for accessibility.
 *
 * Stateless contract (per design #419 §6):
 *  - The screen takes only the snapshot list, the on-bed-selected
 *    callback, and the per-bed-is-enabled predicate.
 *  - The VM owns `dialog`, `error`, and `isSubmitting`. The enabled
 *    predicate is derived from those by the host (so the UI lock
 *    matches D14's dual guard exactly).
 *
 * Tap-during-loading defence (D14): if [enabledBeds] omits a bed, both
 * the badge and the Confirm button are disabled, so a swipe-and-tap
 * mid-load is a hard no-op.
 */
@Composable
fun BedPickerScreen(
    snapshot: List<BedSnapshot>,
    onBedSelected: (bed: Int) -> Unit,
    enabledBeds: Set<Int>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WearText(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val pagerState = rememberPagerState(initialPage = 0) { 5 }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { pageIndex ->
            val bedNumber = pageIndex + 1
            val entry = snapshot.firstOrNull { it.bedNumber == bedNumber }
            BedPage(
                bedNumber = bedNumber,
                isOccupied = entry?.isOccupied == true,
                isEnabled = enabledBeds.contains(bedNumber),
                onSelected = { onBedSelected(bedNumber) },
            )
        }
        WearText(
            text = stringResource(R.string.onboarding_section_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One bed page: a circular badge (64dp diameter — well above the
 * REQ-WATCH-18 56dp tap target), the bed number centered inside, and a
 * state label below. The badge's tint depends on occupancy (red =
 * occupied, green = free).
 *
 * Test tag pattern: `bed-page-{N}`, `bed-badge-{N}`, `bed-button-{N}`
 * so the carousel test can address nodes without text matching.
 */
@Composable
private fun BedPage(
    bedNumber: Int,
    isOccupied: Boolean,
    isEnabled: Boolean,
    onSelected: () -> Unit,
) {
    val stateLabel = if (isOccupied) {
        stringResource(R.string.bed_occupied)
    } else {
        stringResource(R.string.bed_free)
    }
    val badgeColor = if (isOccupied) BED_OCCUPIED_COLOR else BED_FREE_COLOR
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("bed-page-$bedNumber")
            .semantics { contentDescription = "Bed $bedNumber $stateLabel" },
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(if (isEnabled) badgeColor else badgeColor.copy(alpha = 0.4f))
                .testTag("bed-badge-$bedNumber"),
            contentAlignment = Alignment.Center,
        ) {
            WearText(
                text = bedNumber.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        WearText(
            text = stateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isEnabled) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSelected,
            enabled = isEnabled,
            modifier = Modifier
                .testTag("bed-button-$bedNumber")
                .heightIn(min = 56.dp)
                .semantics { contentDescription = "Confirm bed $bedNumber" },
        ) {
            Text(
                text = stringResource(R.string.dialog_accept),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// BED_OCCUPIED_COLOR + BED_FREE_COLOR moved to ui/theme/BedStatusColors.kt
// (wear-ui-guidelines PR-1 task 2.1.2).
