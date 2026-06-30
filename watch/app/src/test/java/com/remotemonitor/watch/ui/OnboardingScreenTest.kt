package com.remotemonitor.watch.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.remotemonitor.watch.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [OnboardingScreen] (T-WATCH-36, REQ-WATCH-17,
 * REQ-WATCH-18).
 *
 * Scenarios:
 *  - **S17.1**: the primary action button is ≥ 56dp tall (REQ-WATCH-18).
 *  - **S18.1**: "P-00042" passes the regex; the button is enabled and
 *    tapping it does not surface a validation error.
 *  - **S18.2**: "P-0042" (3 chars) and "  abc  " (whitespace) fail the
 *    regex; the button is disabled.
 *  - An extra scenario covers the error-message branch when the
 *    ViewModel surfaces a non-null error.
 *
 * Test runner: [RobolectricTestRunner] + [createAndroidComposeRule].
 * The `runComposeUiTest` path was tried first but is unusable on a
 * stock JVM: Compose UI Test 1.5.x's `RobolectricIdlingStrategy`
 * calls `Build.FINGERPRINT.toLowerCase()` unconditionally at test
 * start, and JDK 17+ blocks the field-modifier reflection hack that
 * would have set the field.
 *
 * `app/src/debug/AndroidManifest.xml` declares a launchable
 * `ComponentActivity` so the rule can resolve
 * `Intent { act=MAIN cat=LAUNCHER }` (the manifest is debug-variant
 * only, so it does not affect release builds).
 *
 * `@Config(sdk = [33])` pins the runtime to API 33 (Wear OS 6 = API 36
 * is not yet in Robolectric's pre-instrumented jars).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    // --- S17.1: tap target ≥ 56dp ---------------------------------------

    @Test
    fun S17_1_primary_action_is_at_least_56dp_tall() {
        composeTestRule.setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P00042",
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        composeTestRule
            .onNodeWithContentDescription("Continue")
            .assertHeightIsAtLeast(56.dp)
    }

    // --- S18.1: valid input → no error, button enabled ------------------
    //
    // Note: the orchestrator's task description showed the valid value
    // as "P-00042", but the validation regex `^[A-Za-z0-9]{4,32}$`
    // (REQ-WATCH-18, hard-coded in the OnboardingScreen contract)
    // disallows the hyphen. We use the all-alphanumeric form
    // "P00042" so the test exercises the intended code path; the
    // difference is documented in the PR's `deviations` block.

    @Test
    fun S18_1_valid_patient_number_enables_submit_and_shows_no_error() {
        var submitted = false
        var lastValue: String? = null
        composeTestRule.setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P00042",
                    error = null,
                    isSubmitting = false,
                    onValueChange = { lastValue = it },
                    onSubmit = { submitted = true },
                )
            }
        }

        composeTestRule.onNodeWithText("P00042").assertIsDisplayed()
        composeTestRule.onNodeWithText(PatientNumberErrorMessage).assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription("Continue")
            .assertIsEnabled()
            .performClick()

        assertTrue("onSubmit must be invoked when the button is tapped", submitted)
        // onValueChange is not called by the screen itself; the host
        // (ViewModel) drives updates. We just sanity-check the wiring.
        assertEquals(null, lastValue)
    }

    // --- S18.2: invalid input → button disabled ------------------------

    @Test
    fun S18_2_dashed_patient_number_disables_button() {
        composeTestRule.setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P-0042", // 6 chars, but the hyphen is illegal
                    error = null,
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        composeTestRule
            .onNodeWithContentDescription("Continue")
            .assertIsNotEnabled()
    }

    @Test
    fun S18_2b_three_char_patient_number_disables_button() {
        composeTestRule.setContent {
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
        composeTestRule
            .onNodeWithContentDescription("Continue")
            .assertIsNotEnabled()
    }

    @Test
    fun S18_2c_whitespace_padded_patient_number_disables_button() {
        composeTestRule.setContent {
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
        composeTestRule
            .onNodeWithContentDescription("Continue")
            .assertIsNotEnabled()
    }

    // --- error visibility (covers the `error != null` branch) -----------

    @Test
    fun renders_error_message_when_viewmodel_surfaces_one() {
        composeTestRule.setContent {
            MyApplicationTheme {
                OnboardingScreen(
                    patientNumber = "P00042",
                    error = "Network unavailable. Try again.",
                    isSubmitting = false,
                    onValueChange = {},
                    onSubmit = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Network unavailable. Try again.").assertIsDisplayed()
    }
}
