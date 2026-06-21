package com.pocketshell.app.crash

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #863: the crash-reports "Delete all reports?" confirmation dialog's
 * Cancel action was migrated from the private `AppBarTextButton` wrapper
 * (raw Material clickable Box) to the shared `PocketShellButton(variant = Text,
 * compact = true)`, and the wrapper was deleted. This test guards that the
 * migrated Cancel button preserves behaviour exactly: it dispatches onDismiss
 * and does NOT confirm the delete.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmDeleteAllDialogButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cancelButton_dispatchesDismiss_notConfirm() {
        var dismissed = false
        var confirmed = false
        compose.setContent {
            PocketShellTheme {
                ConfirmDeleteAllDialog(
                    count = 3,
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CANCEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CANCEL_TAG).performClick()

        assertTrue("Cancel must dispatch onDismiss", dismissed)
        assertFalse("Cancel must NOT confirm the delete", confirmed)
    }

    @Test
    fun confirmButton_stillDispatchesConfirm() {
        var confirmed = false
        compose.setContent {
            PocketShellTheme {
                ConfirmDeleteAllDialog(
                    count = 1,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CRASH_REPORTS_DELETE_ALL_CONFIRM_TAG).performClick()

        assertTrue("Delete all confirm must still dispatch onConfirm", confirmed)
    }
}
