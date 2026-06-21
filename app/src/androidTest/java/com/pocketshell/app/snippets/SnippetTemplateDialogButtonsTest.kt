package com.pocketshell.app.snippets

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #863: the snippet template-parameter dialog's `Send` (confirm) and
 * `Cancel` (dismiss) actions were migrated from raw Material `TextButton`s to
 * the shared `PocketShellButton(variant = Text)`. This test guards that the
 * migrated buttons preserve behaviour exactly: onClick fires, and the `enabled`
 * gating on `Send` (only firing once every template parameter is filled) is
 * preserved.
 *
 * No Docker needed — it composes the `SnippetTemplateDialog` directly and
 * inspects only its local callback wiring.
 */
@RunWith(AndroidJUnit4::class)
class SnippetTemplateDialogButtonsTest {

    @get:Rule
    val compose = createComposeRule()

    private val templateSnippet = SnippetEntity(
        id = 42,
        hostId = 1,
        label = "greet",
        body = "echo hello {{name}}",
        kind = "command",
    )

    @Test
    fun cancelButton_dispatchesDismiss() {
        var dismissed = false
        compose.setContent {
            PocketShellTheme {
                SnippetTemplateDialog(
                    snippet = templateSnippet,
                    onDismiss = { dismissed = true },
                    onSend = {},
                )
            }
        }

        compose.onNodeWithTag(SNIPPET_TEMPLATE_CANCEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SNIPPET_TEMPLATE_CANCEL_TAG).performClick()

        assertTrue("Cancel must dispatch onDismiss", dismissed)
    }

    @Test
    fun sendButton_disabledUntilParameterFilled_thenDispatchesExpandedBody() {
        var sent: String? = null
        compose.setContent {
            PocketShellTheme {
                SnippetTemplateDialog(
                    snippet = templateSnippet,
                    onDismiss = {},
                    onSend = { sent = it },
                )
            }
        }

        // Send must be present but disabled (enabled = false) while the
        // {{name}} parameter is blank: tapping it must NOT fire onSend.
        compose.onNodeWithTag(SNIPPET_TEMPLATE_SEND_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SNIPPET_TEMPLATE_SEND_TAG).performClick()
        assertEquals("Send must not fire while a parameter is blank", null, sent)

        // Fill the parameter, then Send must fire with the expanded body.
        compose.onNodeWithTag(snippetTemplateParameterTag("name")).performTextInput("world")
        compose.onNodeWithTag(SNIPPET_TEMPLATE_SEND_TAG).performClick()

        assertEquals("echo hello world", sent)
    }
}
