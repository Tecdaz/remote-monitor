package com.remotemonitor.watch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.activity.ComponentActivity
import com.remotemonitor.watch.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [HomeScreen] (T-WATCH-39, REQ-WATCH-19).
 *
 * Scenario:
 *  - **S19.1**: with `patientNumber = "P-00042"` and `pendingCount = 7`,
 *    the screen displays exactly
 *    `"Monitoring patient P-00042 · 7 pending uploads"`.
 *
 * Test runner: [RobolectricTestRunner] + [createAndroidComposeRule]. The
 * test-class documentation in `OnboardingScreenTest` explains the
 * rationale (the same test infra applies here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun S19_1_renders_exact_status_string_for_patient_and_pending_count() {
        val expected = "Monitoring patient P-00042 · 7 pending uploads"
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeUiState(
                        patientNumber = "P-00042",
                        pendingCount = 7,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }
}
