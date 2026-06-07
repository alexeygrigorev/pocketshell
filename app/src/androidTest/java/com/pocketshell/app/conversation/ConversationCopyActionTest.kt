package com.pocketshell.app.conversation

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.tmux.TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX
import com.pocketshell.app.tmux.TmuxConversationPane
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationCopyActionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun messageCopyPutsMessageTextOnClipboard() {
        compose.setContent {
            PocketShellTheme {
                ConversationMessageTurn(
                    event = ConversationEvent.Message(
                        id = "m1",
                        agent = AgentKind.ClaudeCode,
                        role = ConversationRole.Assistant,
                        text = "copy this conversation message",
                    ),
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_COPY_TAG_PREFIX + "m1")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("copy this conversation message", clipboardText())
    }

    @Test
    fun userMessageCopyPutsMessageTextOnClipboard() {
        compose.setContent {
            PocketShellTheme {
                ConversationMessageTurn(
                    event = ConversationEvent.Message(
                        id = "user-message",
                        agent = AgentKind.ClaudeCode,
                        role = ConversationRole.User,
                        text = "move this prompt into another window",
                    ),
                )
            }
        }

        compose.onNodeWithTag(CONVERSATION_COPY_TAG_PREFIX + "user-message")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("move this prompt into another window", clipboardText())
    }

    @Test
    fun expandedToolCallInputCopyPutsToolInputOnClipboard() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.ToolCall(
                            id = "tool1",
                            agent = AgentKind.ClaudeCode,
                            name = "Bash",
                            input = "kubectl logs deploy/api --tail=80",
                        ),
                        ConversationEvent.ToolResult(
                            id = "result1",
                            agent = AgentKind.ClaudeCode,
                            toolCallId = "tool1",
                            output = "migration timeout after 120s",
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool1")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CONVERSATION_TOOL_COPY_TAG_PREFIX + "tool1:input")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()

        assertEquals("kubectl logs deploy/api --tail=80", clipboardText())
    }

    private fun clipboardText(): String? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clipText: String? = null
        instrumentation.runOnMainSync {
            val clipboard = instrumentation.targetContext
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return clipText
    }
}
