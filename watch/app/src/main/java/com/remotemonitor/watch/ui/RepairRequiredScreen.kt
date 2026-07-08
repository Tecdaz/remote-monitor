package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text as WearText
import com.remotemonitor.watch.R

/**
 * Re-pair required screen (T3.8 / D12).
 *
 * wear-bed-picker-onboarding pass-1 finding A1: paired-but-no-bed watches
 * (a state reached by a legacy pre-PR-2 onboarding that wrote
 * `KEY_PATIENT_NUMBER` / `KEY_PATIENT_ID` but never wrote
 * `KEY_BED_NUMBER`) had no first-class recovery UI. The carousel only
 * renders after a successful `GET /api/v1/beds`, so a stale-paired watch
 * silently fell off the side of the flow.
 *
 * D12 + D12 routing precedence: when `getBedNumber() == null` AND
 * `getPatientId() != null`, MainActivity routes to this screen
 * (`"repair"` route). The screen offers a single primary action that
 * navigates back to `"onboarding"`, which calls
 * `POST /api/v1/patients` followed by an atomic `persistPaired(...)`
 * to write all three keys (D15 + D24).
 *
 * **Snapshot fetch lock (D33)**: this screen does NOT inject
 * `MeasurementsApi` and does NOT call `getBedSnapshot()`. The snapshot
 * fetch is hosted in `OnboardingScreen`'s `LaunchedEffect(Unit)` because
 * the snapshot endpoint is only safe to call once a healthy
 * `X-Patient-Number` header can be supplied (after pairing). The
 * re-pair screen is decoupled from the snapshot endpoint on purpose —
 * the operator can re-pair without a snapshot round-trip.
 */
@Composable
fun RepairRequiredScreen(
    onTapRePair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("repair-required-screen"),
    ) {
        WearText(
            // wear-bed-picker-onboarding-warnings WARN-005: dedicated
            // title copy. Previously reused `action_repair` ("Re-pair")
            // — the button label — as the title, which read confusingly
            // for operators on the first-screen re-pair prompt.
            text = stringResource(R.string.repair_required_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("repair-title"),
        )
        WearText(
            // wear-bed-picker-onboarding-warnings WARN-005: dedicated
            // body copy. Previously reused `dialog_occupied_body` — a
            // tap-confirmation flow description that misread on the
            // re-pair prompt as "you already selected something".
            text = stringResource(R.string.repair_required_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("repair-body"),
        )
        Spacer(Modifier.heightIn(min = 16.dp))
        androidx.wear.compose.material3.Button(
            onClick = onTapRePair,
            modifier = Modifier
                .testTag("repair-button")
                .heightIn(min = 56.dp)
                .semantics { contentDescription = "Re-pair with bed carousel" },
        ) {
            WearText(
                text = stringResource(R.string.action_repair),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * wear-bed-picker-onboarding routing precedence (D12 + §11 of design #419):
 *  - `bedNumber != null` → `"home"` (paired with bed; resume normal flow).
 *  - `bedNumber == null && patientId != null` → `"repair"` (legacy
 *    operator-typed pair; the half-paired DataStore needs a `persistPaired`
 *    write that populates `KEY_BED_NUMBER`).
 *  - else → `"onboarding"` (no identity at all; pick a bed).
 *
 * Extracted from [WatchApp]'s `LaunchedEffect` so the routing decision
 * is unit-testable without spinning up the full Compose runtime.
 */
internal fun resolveInitialRepairRoute(
    bedNumber: String?,
    patientId: String?,
): String = when {
    bedNumber != null -> "home"
    patientId != null -> "repair"
    else -> "onboarding"
}
