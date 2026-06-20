package com.pocketshell.uikit.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM (Robolectric) behaviour tests for the shared [ConfirmDialog] (#756): the
 * title / message / confirm / cancel content, the confirm and dismiss callbacks,
 * the custom labels, and that the [ConfirmDialog.destructive] flag still wires
 * the confirm callback. Runs under the real [PocketShellTheme] on the host JVM
 * (`:shared:ui-kit:testDebugUnitTest`) — no emulator needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ConfirmDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleMessageAndButtons() {
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Delete this key?",
                    message = "The key and any hosts that reference it are removed.",
                    confirmLabel = "Delete",
                    onConfirm = {},
                    onDismiss = {},
                    destructive = true,
                )
            }
        }
        composeRule.onNodeWithText("Delete this key?").assertIsDisplayed()
        composeRule.onNodeWithText("The key and any hosts that reference it are removed.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun confirmButtonRoutesOnConfirm() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Reset cost history?",
                    message = "This clears all recorded costs.",
                    confirmLabel = "Reset",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Reset").performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun cancelButtonRoutesOnDismiss() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Discard changes?",
                    message = "Your edits will be lost.",
                    confirmLabel = "Discard",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun honoursCustomDismissLabel() {
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Overwrite host?",
                    message = "An existing host has the same address.",
                    confirmLabel = "Overwrite",
                    onConfirm = {},
                    onDismiss = {},
                    dismissLabel = "Keep both",
                )
            }
        }
        composeRule.onNodeWithText("Keep both").assertIsDisplayed()
        composeRule.onNodeWithText("Overwrite").assertIsDisplayed()
    }

    @Test
    fun confirmAndDismissTestTags_routeTheirCallbacks() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Stop this session?",
                    message = "This ends the tmux session on the host.",
                    confirmLabel = "Stop",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    destructive = true,
                    confirmTestTag = "confirm-tag",
                    dismissTestTag = "dismiss-tag",
                )
            }
        }
        composeRule.onNodeWithTag("confirm-tag").assertIsDisplayed()
        composeRule.onNodeWithTag("dismiss-tag").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
        composeRule.onNodeWithTag("confirm-tag").performClick()
        composeRule.runOnIdle {
            assertEquals(1, confirmed)
        }
    }

    @Test
    fun affirmativeVariantStillRoutesConfirm() {
        var confirmed = 0
        composeRule.setContent {
            PocketShellTheme {
                ConfirmDialog(
                    title = "Share host?",
                    message = "Private keys are never included.",
                    confirmLabel = "Share",
                    onConfirm = { confirmed++ },
                    onDismiss = {},
                    destructive = false,
                )
            }
        }
        composeRule.onNodeWithText("Share").performClick()
        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }
}
