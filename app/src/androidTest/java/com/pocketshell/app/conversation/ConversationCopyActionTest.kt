package com.pocketshell.app.conversation

import android.content.RecordingClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.test.ClipboardOverrideContext
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

    // Production copy resolves `LocalContext.current.getSystemService(
    // CLIPBOARD_SERVICE)`; routing that lookup through this recording subclass
    // (via ClipboardOverrideContext provided over LocalContext) lets the test
    // observe `setPrimaryClip` deterministically. Reading the real system
    // clipboard back returns `null` on the un-focused AOSP API 35 AVD window
    // (the API 29+ foreground-focus policy), which is the test-only artifact
    // this avoids — production `copyConversationTextToClipboard` is unchanged.
    private val recording = RecordingClipboardManager()

    @Composable
    private fun WithRecordingClipboard(content: @Composable () -> Unit) {
        val base = LocalContext.current
        CompositionLocalProvider(
            LocalContext provides ClipboardOverrideContext(base, recording),
        ) {
            PocketShellTheme { content() }
        }
    }

    @Test
    fun messageCopyPutsMessageTextOnClipboard() {
        compose.setContent {
            WithRecordingClipboard {
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

        assertEquals("copy this conversation message", recording.lastText)
    }

    @Test
    fun userMessageCopyPutsMessageTextOnClipboard() {
        compose.setContent {
            WithRecordingClipboard {
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

        assertEquals("move this prompt into another window", recording.lastText)
    }

    @Test
    fun expandedToolCallInputCopyPutsToolInputOnClipboard() {
        compose.setContent {
            WithRecordingClipboard {
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

        assertEquals("kubectl logs deploy/api --tail=80", recording.lastText)
    }
}
