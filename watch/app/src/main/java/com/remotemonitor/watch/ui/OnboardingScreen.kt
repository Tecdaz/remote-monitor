package com.remotemonitor.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text as WearText

/**
 * Onboarding screen (T-WATCH-34, REQ-WATCH-17, REQ-WATCH-18).
 *
 * A stateless [Composable] that surfaces a single `OutlinedTextField` for
 * the operator-typed `patientNumber` and a primary `Button` for submit.
 * Validation:
 *  - The submit button is **disabled** while [isSubmitting] is true or
 *    when [patientNumber] does not match [PatientNumberRegex].
 *  - The button's tap target is **≥56dp tall** (REQ-WATCH-18). The Wear
 *    M3 [Button] default is 52dp; the explicit
 *    `Modifier.heightIn(min = 56.dp)` enforces the requirement.
 *  - When [error] is non-null, the error message is rendered below the
 *    field in [MaterialTheme.colorScheme.error].
 *
 * State is owned by [OnboardingViewModel] (T-WATCH-35). The screen takes
 * primitives, not a state object, so it can be previewed in isolation
 * and so tests can drive the screen without standing up a ViewModel.
 */
@Composable
fun OnboardingScreen(
    patientNumber: String,
    error: String?,
    isSubmitting: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = !isSubmitting && PatientNumberRegex.matches(patientNumber)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WearText(
            text = "Pair your watch",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = patientNumber,
            onValueChange = onValueChange,
            label = { Text("Patient number") },
            singleLine = true,
            isError = error != null,
            enabled = !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Patient number input" },
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = "Continue" },
        ) {
            WearText(
                text = if (isSubmitting) "Pairing…" else "Continue",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// PaddingValues is referenced to keep the param list future-proof when
// we add a Scaffold-based layout. Suppress the unused warning explicitly
// to make the intent visible to readers.
@Suppress("unused")
private val ReservedScaffoldPadding: PaddingValues = PaddingValues(0.dp)
