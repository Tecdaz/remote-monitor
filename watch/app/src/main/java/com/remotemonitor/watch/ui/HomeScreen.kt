package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.remotemonitor.watch.R

/**
 * Home screen (T-WATCH-37, REQ-WATCH-19, wear-bed-picker-onboarding D17
 * + D25).
 *
 * A stateless [Composable] that displays the locked-format status string
 * sourced from `R.string.home_status_label` as `"Bed %1\$d · %2\$d
 * pending uploads"` (English) / `"Cama %1\$d · %2\$d subidas pendientes"`
 * (Spanish). The text is rendered inside a [TransformingLazyColumn] per
 * the project pattern, so on scroll the header collapses to a compact
 * form.
 *
 * Format-arg discipline (D25): the bed number is converted via
 * `toIntOrNull() ?: 0` so a `null` bedNumber (legacy operator-typed
 * pair before `persistPaired(...)` repaired the DataStore) renders as
 * `"Cama 0 · …"` rather than crashing the home screen. The expected
 * string is built via `stringResource(R.string.home_status_label, …)`
 * — NEVER a hardcoded literal — so the test harness asserts the
 * expected output via `context.getString(...)`, locale-agnostic.
 *
 * The screen takes a [HomeUiState] (not primitives) because the
 * ViewModel aggregates two reactive sources — bed number and pending
 * count — and there are no event callbacks at this layer. The host
 * (`MainActivity`) reads the same `StateFlow` and may surface the
 * on-watch tap target as a separate action.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val bedNumberForDisplay = state.bedNumber?.toIntOrNull() ?: 0
    val statusText = stringResource(
        R.string.home_status_label,
        bedNumberForDisplay,
        state.pendingCount,
    )

    TransformingLazyColumn(
        state = listState,
        modifier = modifier,
    ) {
        item {
            ListHeader(
                modifier = Modifier.transformedHeight(this, transformationSpec),
                transformation = SurfaceTransformation(transformationSpec),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home-status"),
                )
            }
        }
    }
}
