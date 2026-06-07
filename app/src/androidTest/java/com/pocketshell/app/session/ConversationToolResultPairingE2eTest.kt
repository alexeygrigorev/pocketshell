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
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
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
}
