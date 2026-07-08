package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.remotemonitor.watch.R
import com.remotemonitor.watch.sensor.SensorHealth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home screen (wear-ui-guidelines D6, spec cap 1; wear-bed-picker-
 * onboarding D17 + D25).
 *
 * Glanceable clinical surface: the live HR BPM is the dominant numeral
 * (`numeralLarge`) with the last-update time beneath it, followed by the
 * locked-format bed + pending status line. Laid out as a centered
 * [Column] (replacing the previous single-item `TransformingLazyColumn`)
 * because the content is a fixed, non-scrolling vitals stack sized for
 * ~1m glanceability, not a list.
 *
 * **Graceful degradation (D6, spec cap 1 scenario 2)**: when the HR
 * pipeline has failed (`health == SensorHealth.Failed`) or no BPM is
 * available (`hrBpm == null`), the `home_hr_placeholder` ("—") renders
 * instead of the numeral — no crash, no stale BPM.
 *
 * **D25 invariant**: the bed + pending line still resolves
 * `R.string.home_status_label` (`"Bed %1$d · %2$d pending uploads"` /
 * `"Cama %1$d · %2$d subidas pendientes"`) with its shape UNCHANGED. The
 * bed number is converted via `toIntOrNull() ?: 0` so a null bedNumber
 * renders as `"Bed 0 · …"` rather than crashing. All copy is resolved
 * via `stringResource(...)` — never a hardcoded literal — so tests
 * assert locale-agnostically via `context.getString(...)`.
 */
@Composable
fun HomeScreen(
    state: HomeVitals,
    modifier: Modifier = Modifier,
) {
    val bedNumberForDisplay = state.bedNumber?.toIntOrNull() ?: 0
    val statusText = stringResource(
        R.string.home_status_label,
        bedNumberForDisplay,
        state.pendingCount,
    )
    // D6: suppress the numeral when the pipeline failed or no BPM is
    // available; render `home_hr_placeholder` ("—") instead.
    val hrText = state.hrBpm
        ?.takeUnless { state.health == SensorHealth.Failed }
        ?.let { stringResource(R.string.home_hr_value_format, it) }
        ?: stringResource(R.string.home_hr_placeholder)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.home_hr_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("home-hr-label"),
        )
        Text(
            text = hrText,
            style = MaterialTheme.typography.numeralLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("home-hr"),
        )
        state.lastUpdate?.let { instant ->
            val timeText = LAST_UPDATE_FORMATTER.format(
                instant.atZone(ZoneId.systemDefault()),
            )
            Text(
                text = stringResource(R.string.home_last_update_label, timeText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("home-last-update"),
            )
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("home-status"),
        )
    }
}

/**
 * Fixed `HH:mm` clock for the last-update label. Uses [Locale.ROOT] so
 * the 24-hour numeric pattern is stable across locales (the localized
 * prefix comes from `home_last_update_label`, not the time itself).
 */
private val LAST_UPDATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
