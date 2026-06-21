package com.pocketshell.uikit.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
 * JVM (Robolectric) behaviour tests for the shared [FormDialog] (#861): the
 * title, the CONTENT SLOT (the caller's `OutlinedTextField`(s) render and are
 * editable), the Cancel/Primary action row callbacks, the `confirmEnabled`
 * validation gate, the custom labels + test tags, and the optional
 * [FormDialog.extraAction] (the recurring-job "Remove" leading action). Runs
 * under the real [PocketShellTheme] on the host JVM
 * (`:shared:ui-kit:testDebugUnitTest`) — no emulator needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class FormDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleContentSlotAndActionRow() {
        composeRule.setContent {
            PocketShellTheme {
                FormDialog(
                    title = "Add snippet",
                    confirmLabel = "Save",
                    onConfirm = {},
                    onDismiss = {},
                ) {
                    // The caller's content — an arbitrary composable placed in
                    // the slot, here a tagged label + helper line standing in
                    // for the migrated dialogs' OutlinedTextField + caption.
                    // (A focused OutlinedTextField runs an infinite cursor
                    // animation that wedges the Robolectric idle wait; the real
                    // text-field behaviour is covered on the emulator by
                    // CommandTemplateEditorDialogTest / RecurringJobsScreenTest /
                    // FolderListStopSessionTest post-migration.)
                    Text("Snippet text", modifier = Modifier.testTag("body-field"))
                    Text("Use the row menu to rename it.")
                }
            }
        }
        // Title.
        composeRule.onNodeWithText("Add snippet").assertIsDisplayed()
        // Content slot renders the caller's content.
        composeRule.onNodeWithTag("body-field").assertIsDisplayed()
        composeRule.onNodeWithText("Snippet text").assertIsDisplayed()
        composeRule.onNodeWithText("Use the row menu to rename it.").assertIsDisplayed()
        // Canonical action row.
        composeRule.onNodeWithText("Save").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun confirmButtonRoutesOnConfirm() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                FormDialog(
                    title = "Rename session",
                    confirmLabel = "Rename",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                ) {
                    Text("Rename foo on this host.")
                }
            }
        }
        composeRule.onNodeWithText("Rename").performClick()
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
                FormDialog(
                    title = "Edit snippet",
                    confirmLabel = "Save",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                ) {
                    Text("body")
                }
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun confirmDisabledWhenFormInvalid() {
        composeRule.setContent {
            PocketShellTheme {
                FormDialog(
                    title = "Add macro",
                    confirmLabel = "Save",
                    onConfirm = {},
                    onDismiss = {},
                    confirmEnabled = false,
                ) {
                    Text("Fill the fields first.")
                }
            }
        }
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    /**
     * The content slot drives validation: interacting with a caller-supplied
     * control in the slot flips `confirmEnabled` true, enabling the confirm
     * button, and confirming then carries the slot-derived value — proving the
     * slot is live and wired to the action row (the migration's core behaviour:
     * the caller's field state gates + feeds submit). A slot Button stands in
     * for the OutlinedTextField (whose cursor animation wedges the JVM idle
     * wait); the real text-entry path is covered on the emulator.
     */
    @Test
    fun slotInteractionEnablesConfirmAndFeedsSubmittedValue() {
        var saved: String? = null
        composeRule.setContent {
            PocketShellTheme {
                var value by remember { mutableStateOf("") }
                FormDialog(
                    title = "Rename snippet",
                    confirmLabel = "Save",
                    onConfirm = { saved = value },
                    onDismiss = {},
                    confirmEnabled = value.isNotBlank(),
                ) {
                    // Caller's slot control: tapping it sets the form value,
                    // exactly as typing into an OutlinedTextField would.
                    Text(
                        text = if (value.isBlank()) "Tap to fill" else value,
                        modifier = Modifier
                            .testTag("slot-control")
                            .clickable { value = "deploy" },
                    )
                }
            }
        }
        // Empty -> confirm disabled.
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        // Interact with the slot control -> sets the form value.
        composeRule.onNodeWithTag("slot-control").performClick()
        // Now confirm is enabled; submitting carries the slot-derived value.
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("deploy", saved) }
    }

    @Test
    fun honoursCustomLabelsAndTestTags() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                FormDialog(
                    title = "SSH key passphrase",
                    confirmLabel = "Open",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    dismissLabel = "Not now",
                    confirmTestTag = "open-tag",
                    dismissTestTag = "cancel-tag",
                ) {
                    Text("Enter the passphrase.")
                }
            }
        }
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel-tag").performClick()
        composeRule.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
        composeRule.onNodeWithTag("open-tag").performClick()
        composeRule.runOnIdle { assertEquals(1, confirmed) }
    }

    /**
     * The recurring-job editor pairs a destructive "Remove" leading action with
     * the standard Cancel/Save row: the [FormDialog.extraAction] slot renders to
     * the left of Cancel and routes its own callback, independent of confirm /
     * dismiss.
     */
    @Test
    fun extraActionRendersAndRoutesIndependently() {
        var removed = 0
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            PocketShellTheme {
                FormDialog(
                    title = "Edit job",
                    confirmLabel = "Save",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    extraAction = {
                        PocketShellButton(
                            text = "Remove",
                            onClick = { removed++ },
                            variant = ButtonVariant.Destructive,
                        )
                    },
                ) {
                    Text("job fields")
                }
            }
        }
        composeRule.onNodeWithText("Remove").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, confirmed)
            assertEquals(0, dismissed)
        }
    }
}
