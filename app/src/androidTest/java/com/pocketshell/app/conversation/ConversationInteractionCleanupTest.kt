package com.pocketshell.app.conversation

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.ConversationPane
import com.pocketshell.app.session.SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX
import com.pocketshell.app.tmux.TmuxConversationPane
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationInteractionCleanupTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cleanupEffectHidesTextToolbarOnDispose() {
        val toolbar = RecordingTextToolbar()
        val showConversation = mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                if (showConversation.value) {
                    ConversationInteractionCleanupEffect()
                } else {
                    Text("Terminal")
                }
            }
        }

        compose.runOnIdle { showConversation.value = false }
        compose.waitForIdle()

        assertEquals(1, toolbar.hideCount)
    }

    @Test
    fun tmuxTranscriptInteractionHidesTextToolbarWhenSwitchingToTerminal() {
        val toolbar = RecordingTextToolbar()
        val showConversation = mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                PocketShellTheme {
                    if (showConversation.value) {
                        TmuxConversationPane(events = sampleToolEvents())
                    } else {
                        Text("Terminal")
                    }
                }
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .performClick()
        val beforeSwitchHideCount = compose.runOnIdle { toolbar.hideCount }
        compose.runOnIdle { showConversation.value = false }
        compose.waitForIdle()

        assertTrue(toolbar.hideCount > beforeSwitchHideCount)
    }

    @Test
    fun rawSshTranscriptInteractionHidesTextToolbarWhenSwitchingToTerminal() {
        val toolbar = RecordingTextToolbar()
        val showConversation = mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                PocketShellTheme {
                    if (showConversation.value) {
                        ConversationPane(
                            events = sampleToolEvents(),
                            onSendToAgent = { true },
                        )
                    } else {
                        Text("Terminal")
                    }
                }
            }
        }

        compose.onNodeWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .performClick()
        val beforeSwitchHideCount = compose.runOnIdle { toolbar.hideCount }
        compose.runOnIdle { showConversation.value = false }
        compose.waitForIdle()

        assertTrue(toolbar.hideCount > beforeSwitchHideCount)
    }

    private fun sampleToolEvents(): List<ConversationEvent> {
        val output = buildString {
            appendLine("selectable transcript output")
            repeat(20) { index -> appendLine("line $index") }
        }
        return listOf(
            ConversationEvent.ToolCall(
                id = "tool-1",
                agent = AgentKind.Codex,
                name = "exec_command",
                input = """{"cmd":"./gradlew test"}""",
            ),
            ConversationEvent.ToolResult(
                id = "result-1",
                agent = AgentKind.Codex,
                toolCallId = "tool-1",
                output = output,
            ),
        )
    }

    private class RecordingTextToolbar : TextToolbar {
        var hideCount = 0
            private set

        override val status: TextToolbarStatus
            get() = if (hideCount == 0) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

        override fun hide() {
            hideCount += 1
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) = Unit
    }
}
