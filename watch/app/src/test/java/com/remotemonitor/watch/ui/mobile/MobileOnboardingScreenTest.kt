package com.remotemonitor.watch.ui.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.remotemonitor.watch.R
import com.remotemonitor.watch.api.BedSnapshot
import com.remotemonitor.watch.ui.BedDialogState
import com.remotemonitor.watch.ui.SnapshotState
import com.remotemonitor.watch.ui.theme.MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI coverage for the phone/tablet onboarding surface (S4).
 *
 * The same APK uses this Material 3 screen when the host is not a Wear
 * OS watch. The test proves the adaptive grid renders all five beds and
 * that a free-bed tap reaches the shared [com.remotemonitor.watch.ui.OnboardingViewModel]
 * callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w840dp-h1200dp", application = com.remotemonitor.watch.WatchApplication::class)
class MobileOnboardingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val beds = (1..5).map { bed ->
        BedSnapshot(
            bedNumber = bed,
            isOccupied = bed == 3,
            currentPatientId = if (bed == 3) "p-3" else null,
        )
    }

    @Test
    fun renders_all_five_beds_and_occupied_label() {
        composeTestRule.setContent {
            MobileTheme {
                MobileOnboardingScreen(
                    snapshot = beds,
                    snapshotState = SnapshotState.Loaded,
                    error = null,
                    isSubmitting = false,
                    dialog = BedDialogState.Closed,
                    onBedSelected = { _, _ -> },
                    onSnapshotRetry = {},
                    onDialogAccept = {},
                    onDialogCancel = {},
                )
            }
        }

        (1..5).forEach { bed ->
            composeTestRule.onNodeWithTag("bed-$bed").assertExists()
        }
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.bed_occupied))
            .assertExists()
    }

    @Test
    fun free_bed_tap_invokes_shared_callback() {
        var selectedBed = -1
        composeTestRule.setContent {
            MobileTheme {
                MobileOnboardingScreen(
                    snapshot = beds,
                    snapshotState = SnapshotState.Loaded,
                    error = null,
                    isSubmitting = false,
                    dialog = BedDialogState.Closed,
                    onBedSelected = { bed, _ -> selectedBed = bed },
                    onSnapshotRetry = {},
                    onDialogAccept = {},
                    onDialogCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("bed-2").performClick()
        assertEquals(2, selectedBed)
    }
}
