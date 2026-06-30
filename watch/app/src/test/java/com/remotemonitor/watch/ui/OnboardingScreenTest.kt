package com.remotemonitor.watch.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.remotemonitor.watch.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose UI tests for [OnboardingScreen] (T-WATCH-36, REQ-WATCH-17,
 * REQ-WATCH-18).
 *
 * Scenarios:
 *  - **S17.1**: the primary action button is ≥ 56dp tall (REQ-WATCH-18).
 *  - **S18.1**: "P-00042" passes the regex; the button is enabled and
 *    tapping it does not surface a validation error.
 *  - **S18.2**: "P-0042" (3 chars) and "  abc  " (whitespace) fail the
 *    regex; the button is disabled. The same scenario verifies the
 *    ViewModel-level error surface when a non-empty error is shown.
 *
 * Test runner: pure JVM via [runComposeUiTest] — no Robolectric, no
 * instrumentation. The Compose UI Test 1.5.x `runComposeUiTest` runs
 * the Compose runtime in-process.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {

    // --- S17.1: tap target ≥ 56dp ---------------------------------------

    @Test
    fun S17_1_primary_action_is_at_least_56dp_tall() = runComposeUiTest {
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P-00042",
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithContentDescription("Continue").assertHeightIsAtLeast(56.dp)
    }

    // --- S18.1: valid input → no error, button enabled ------------------

    @Test
    fun S18_1_valid_patient_number_enables_submit_and_shows_no_error() = runComposeUiTest {
        var submitted = false
        var lastValue: String? = null
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P-00042",
                    error = null,
                    isSubmitting = false,
                    onValueChange = { lastValue = it },
                    onSubmit = { submitted = true },
                )
            }
        }

        // The field shows the patient number the ViewModel handed us.
        onNodeWithText("P-00042").assertIsDisplayed()
        // No error is rendered.
        onNodeWithText(PatientNumberErrorMessage).assertDoesNotExist()
        // The primary action is enabled.
        onNodeWithContentDescription("Continue").assertIsEnabled().performClick()
        runOnUiThread { /* no-op: ensures UI events flushed */ }

        assertTrue("onSubmit must be invoked when the button is tapped", submitted)
        // onValueChange is not called by the screen itself; the host
        // (ViewModel) drives updates. We just sanity-check the wiring.
        assertEquals(null, lastValue)
    }

    // --- S18.2: invalid input → button disabled ------------------------

    @Test
    fun S18_2_short_patient_number_disables_button() = runComposeUiTest {
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P-0042", // 6 chars, but contains a dash
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithContentDescription("Continue").assertIsNotEnabled()
    }

    @Test
    fun S18_2b_three_char_patient_number_disables_button() = runComposeUiTest {
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "abc", // 3 chars, fails the {4,32} bound
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithContentDescription("Continue").assertIsNotEnabled()
    }

    @Test
    fun S18_2c_whitespace_padded_patient_number_disables_button() = runComposeUiTest {
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "  abc  ",
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithContentDescription("Continue").assertIsNotEnabled()
    }

    // --- error visibility (covers the `error != null` branch) -----------

    @Test
    fun renders_error_message_when_viewmodel_surfaces_one() = runComposeUiTest {
        setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P-00042",
                    error = "Network unavailable. Try again.",
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        onNodeWithText("Network unavailable. Try again.").assertIsDisplayed()
    }
}
