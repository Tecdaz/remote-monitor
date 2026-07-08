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
 * Compose UI tests for [OccupiedBedDialog] (T-WATCH-35, REQ-WATCH-35).
 *
 * wear-bed-picker-onboarding S3 (dialog_opens_no_network): when the
 * dialog is visible, the title + body are rendered and the
 * accept/cancel buttons are exposed.
 *
 * S5 (cancelar_preserves_pagerstate): the cancel callback fires when
 * the dismiss button is tapped.
 *
 * S6 (aceptar_triggers_replace_session): the accept callback fires
 * with the correct semantics when the confirm button is tapped.
 *
 * The dialog is a stateless slot; visibility is owned by the VM and
 * passed in via [OccupiedBedDialog.visible].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class OccupiedBedDialogTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** S3 — dialog visible renders title and buttons. */
    @Test
    fun S3_dialog_opens_no_network() {
        composeTestRule.setContent {
            MyApplicationTheme {
                OccupiedBedDialog(
                    visible = true,
                    onAccept = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("occupied-bed-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog-title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog-body").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog-accept").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dialog-cancel").assertIsDisplayed()
    }

    /** Hidden dialog renders nothing. */
    @Test
    fun hidden_dialog_renders_nothing() {
        composeTestRule.setContent {
            MyApplicationTheme {
                OccupiedBedDialog(
                    visible = false,
                    onAccept = {},
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("dialog-title").assertDoesNotExist()
        composeTestRule.onNodeWithTag("dialog-body").assertDoesNotExist()
        composeTestRule.onNodeWithTag("dialog-accept").assertDoesNotExist()
    }

    /** S5 — cancel callback fires when dismiss button tapped. */
    @Test
    fun S5_cancelar_preserves_pagerstate() {
        var cancelCount = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                OccupiedBedDialog(
                    visible = true,
                    onAccept = {},
                    onCancel = { cancelCount++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("dialog-cancel").performClick()
        composeTestRule.runOnIdle { assertEquals(1, cancelCount) }
    }

    /** S6 — accept callback fires when confirm button tapped. */
    @Test
    fun S6_aceptar_triggers_replace_session() {
        var acceptCount = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                OccupiedBedDialog(
                    visible = true,
                    onAccept = { acceptCount++ },
                    onCancel = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("dialog-accept").performClick()
        composeTestRule.runOnIdle { assertEquals(1, acceptCount) }
    }
}
