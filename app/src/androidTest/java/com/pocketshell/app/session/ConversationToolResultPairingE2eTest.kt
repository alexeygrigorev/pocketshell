package com.pocketshell.app.session

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class ConversationToolResultPairingE2eTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun adjacentToolResultDetailsAreMergedIntoExpandedToolCall() {
        val events = listOf(
            ConversationEvent.ToolCall(
                id = "tool-1",
                agent = AgentKind.Codex,
                name = "exec_command",
                input = """{"cmd":"./gradlew test"}""",
            ),
            ConversationEvent.ToolResult(
                id = "result-1",
                agent = AgentKind.Codex,
                output = "raw-adjacent-output-only",
                isError = false,
            ),
        )

        compose.setContent {
            PocketShellTheme {
                ConversationPane(
                    events = events,
                    onSendToAgent = { true },
                )
            }
        }

        compose.onNodeWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .assertIsDisplayed()
        compose.onAllNodesWithText("raw-adjacent-output-only")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }

        compose.onNodeWithTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .performClick()
        compose.waitForIdle()
        compose.onAllNodes(
            hasText("raw-adjacent-output-only") and
                hasAnyAncestor(hasTestTag(SESSION_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
    }

    @Test
    fun codexLinkedToolResultDetailsAreMergedIntoExpandedTmuxToolCall() {
        val events = listOf(
            ConversationEvent.ToolCall(
                id = "call:call_1",
                agent = AgentKind.Codex,
                name = "exec_command",
                input = """{"cmd":"./gradlew test"}""",
            ),
            ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.Codex,
                role = ConversationRole.Assistant,
                text = "Checking the command result.",
            ),
            ConversationEvent.ToolResult(
                id = "result:call_1",
                agent = AgentKind.Codex,
                toolCallId = "call_1",
                output = "non-adjacent-codex-output",
                isError = false,
            ),
        )

        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(events = events)
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "call:call_1")
            .assertIsDisplayed()
        compose.onAllNodesWithText("non-adjacent-codex-output")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "call:call_1")
            .performClick()
        compose.waitForIdle()
        compose.onAllNodes(
            hasText("non-adjacent-codex-output") and
                hasAnyAncestor(hasTestTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "call:call_1")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
    }
}
