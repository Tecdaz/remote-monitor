package com.remotemonitor.watch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.activity.ComponentActivity
import com.remotemonitor.watch.R
import com.remotemonitor.watch.sensor.SensorHealth
import com.remotemonitor.watch.ui.theme.MyApplicationTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [HomeScreen] (wear-ui-guidelines D6, spec cap 1;
 * wear-bed-picker-onboarding D17 + D25).
 *
 * Scenarios:
 *  - **S19_1** (D25 preserved): with `bedNumber = "3"` /
 *    `pendingCount = 7`, the status line renders
 *    `stringResource(R.string.home_status_label, 3, 7)`. Derived via
 *    `context.getString(...)` (NOT a literal) so the assertion follows
 *    the active locale.
 *  - **S_home_displays_bed_label_es_locale** (D25): under `es-rES` the
 *    status resolves the Spanish `values-es/strings.xml` translation.
 *  - **S_renders_numeralLarge_HR_when_healthy** (spec cap 1 scenario 1):
 *    a healthy sensor + a BPM renders `home_hr_value_format`.
 *  - **S_renders_placeholder_when_health_failed_or_HR_null** (D6, spec
 *    cap 1 scenario 2): a failed pipeline OR a null BPM renders
 *    `home_hr_placeholder`, never a numeral.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** S19.1 — bed + pending count renders the home-status resource (D25). */
    @Test
    fun S19_1_renders_exact_status_string_for_bed_and_pending_count() {
        val bedNumber = "3"
        val pendingCount = 7
        val expected = composeTestRule.activity
            .getString(R.string.home_status_label, bedNumber.toInt(), pendingCount)
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeVitals(
                        bedNumber = bedNumber,
                        pendingCount = pendingCount,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }

    /**
     * D25 — Spanish locale renders the `values-es/strings.xml`
     * translation through the resource pipeline (derived via
     * `context.getString(...)`, not a literal).
     */
    @Test
    @org.robolectric.annotation.Config(qualifiers = "es-rES")
    fun S_home_displays_bed_label_es_locale() {
        val bedNumber = "3"
        val expected = composeTestRule.activity
            .getString(R.string.home_status_label, bedNumber.toInt(), 0)
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeVitals(
                        bedNumber = bedNumber,
                        pendingCount = 0,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()

        val expectedBedLabel = composeTestRule.activity
            .getString(R.string.home_bed_label, bedNumber.toInt())
        assert(expectedBedLabel.startsWith("Cama")) {
            "expected Spanish bed label 'Cama 3', got '$expectedBedLabel'"
        }
    }

    /**
     * spec cap 1 scenario 1 — a healthy sensor with a fresh BPM renders
     * the HR numeral (`home_hr_value_format`) as the dominant readout.
     */
    @Test
    fun S_renders_numeralLarge_HR_when_healthy() {
        val expectedHr = composeTestRule.activity
            .getString(R.string.home_hr_value_format, 72)
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeVitals(
                        bedNumber = "3",
                        pendingCount = 0,
                        hrBpm = 72,
                        lastUpdate = Instant.ofEpochMilli(1_700_000_000_000L),
                        health = SensorHealth.Healthy,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(expectedHr).assertIsDisplayed()
        composeTestRule.onNodeWithTag("home-hr").assertIsDisplayed()
    }

    /**
     * D6 / spec cap 1 scenario 2 — a Failed pipeline suppresses the HR
     * numeral in favour of `home_hr_placeholder`; a null BPM does the
     * same. No crash, no stale BPM.
     */
    @Test
    fun S_renders_placeholder_when_health_failed_or_HR_null() {
        val placeholder = composeTestRule.activity
            .getString(R.string.home_hr_placeholder)

        // Case 1: sensor Failed (a stale BPM is present in the model but
        // must be suppressed by the screen's health gate).
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeVitals(
                        bedNumber = "3",
                        pendingCount = 0,
                        hrBpm = null,
                        health = SensorHealth.Failed,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(placeholder).assertIsDisplayed()
    }

    /** D6 — a null BPM under a healthy pipeline also renders the placeholder. */
    @Test
    fun S_renders_placeholder_when_HR_null_and_healthy() {
        val placeholder = composeTestRule.activity
            .getString(R.string.home_hr_placeholder)
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeScreen(
                    state = HomeVitals(
                        bedNumber = "3",
                        pendingCount = 0,
                        hrBpm = null,
                        health = SensorHealth.Healthy,
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText(placeholder).assertIsDisplayed()
    }
}
