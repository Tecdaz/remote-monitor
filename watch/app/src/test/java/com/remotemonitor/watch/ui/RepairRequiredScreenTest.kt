package com.remotemonitor.watch.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.remotemonitor.watch.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [RepairRequiredScreen] (T3.8 / D12).
 *
 * wear-bed-picker-onboarding pass-1 finding A1: paired-but-no-bed
 * watches (legacy operator-typed pair; KEY_PATIENT_ID set, but
 * KEY_BED_NUMBER missing) silently rendered an empty Home. The
 * pass-1 fix is a dedicated recovery screen.
 *
 * Scenarios:
 *  - **body_and_button_rendered**: the screen displays its title,
 *    body and the single primary action button — proven via the
 *    `repair-*` test tags.
 *  - **primary_action_navigates_to_onboarding**: tapping the button
 *    fires `onTapRePair()` exactly once (the host `MainActivity`
 *    wires this to `navController.navigate("onboarding")`).
 *
 * No `MeasurementsApi` is injected here — D33 snapshot-fetch
 * location lock; the snapshot endpoint requires a healthy
 * `X-Patient-Number` header (post-pairing), so it's hosted in
 * `OnboardingScreen` rather than the repair screen. This test
 * exercises the screen in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class RepairRequiredScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun body_and_button_rendered() {
        composeTestRule.setContent {
            MyApplicationTheme {
                RepairRequiredScreen(
                    onTapRePair = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("repair-required-screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("repair-title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("repair-body").assertIsDisplayed()
        composeTestRule.onNodeWithTag("repair-button").assertIsDisplayed()
    }

    @Test
    fun primary_action_navigates_to_onboarding() {
        var tapCount = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                RepairRequiredScreen(
                    onTapRePair = { tapCount++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("repair-button").performClick()
        composeTestRule.runOnIdle { assertEquals(1, tapCount) }
    }
}
