package com.remotemonitor.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * Home screen (T-WATCH-37, REQ-WATCH-19).
 *
 * A stateless [Composable] that displays the exact status string
 * `Monitoring patient {patient_number} · {pending} pending uploads`.
 * The text is rendered inside a [TransformingLazyColumn] per the
 * existing `MainActivity.kt` pattern, so on scroll the header
 * collapses to a compact form.
 *
 * The screen takes a [HomeUiState] (not primitives) because the
 * ViewModel aggregates two reactive sources — patient number and
 * pending count — and there are no event callbacks at this layer.
 * The host (`MainActivity`) reads the same `StateFlow` and may
 * surface the on-watch tap target as a separate action.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val patientNumber = state.patientNumber.orEmpty()
    val pending = state.pendingCount
    val statusText = "Monitoring patient $patientNumber · $pending pending uploads"

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
                )
            }
        }
    }
}
