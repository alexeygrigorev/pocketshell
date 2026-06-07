package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #154: connected coverage for the conversation pane navigation
 * polish.
 *
 *  - **Jump-to-latest FAB visibility** — drives a tall list of message
 *    events into [TmuxConversationPane], scrolls the LazyColumn up via
 *    `performScrollToIndex(0)` against [TMUX_CONVERSATION_LIST_TAG], and
 *    asserts the "↓ Latest" pill appears while scrolled away from the
 *    tail and hides once tapped.
 *  - **Search query persistence** — wires the pane to a hoisted
 *    `(query, onQueryChange)` pair (the same shape the screen uses to
 *    bind the ViewModel state), types into the search field, and
 *    confirms the filtered feed renders only the matching event row.
 *    This proves the pane no longer holds a local `remember` that
 *    overrides the hoisted state — i.e. the query will survive a
 *    Terminal ↔ Conversation tab flip.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationPaneNavigationUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun jumpToLatestPillAppearsWhenScrolledAwayAndHidesAfterTap() {
        val events = sampleMessageEvents(count = 80)
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Wait for the LazyColumn to be measured. Auto-scroll to the
        // tail fires inside a LaunchedEffect keyed on the (initial)
        // event count, so the FAB stays hidden after the first frame.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_LIST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
            .assertDoesNotExist()

        // Drive the LazyColumn up to the head. With 80 message rows the
        // list is taller than the viewport, so this leaves the user
        // scrolled away from the bottom and the FAB should appear.
        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToIndex(0)
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
            .assertIsDisplayed()

        // Tap the FAB — it should scroll back to the tail and disappear.
        compose.onNodeWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
            .performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    @Test
    fun largeCodexTranscriptScrollsUpWithoutFreezing() {
        val events = sampleCodexTranscript(turns = 900)
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("codex-visible-message-899")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithText("codex-visible-message-0").assertIsDisplayed()

        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToIndex(901)
        compose.waitForIdle()
        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "codex-tool-450")
            .assertIsDisplayed()

        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToIndex(1798)
        compose.waitForIdle()
        compose.onNodeWithText("codex-visible-message-899").assertIsDisplayed()
    }

    @Test
    fun conversationOpensAtLatestMessage() {
        val events = sampleMessageEvents(count = 80)
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("event-79 body text")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("event-79 body text").assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_JUMP_TO_LATEST_TAG)
            .assertDoesNotExist()
    }

    @Test
    fun toolCallDetailsAreCollapsedUntilExpanded() {
        val events = listOf(
            ConversationEvent.ToolCall(
                id = "tool-1",
                agent = AgentKind.ClaudeCode,
                name = "Bash",
                input = "printf 'expanded-input-only'",
            ),
            ConversationEvent.ToolResult(
                id = "result-1",
                agent = AgentKind.ClaudeCode,
                toolCallId = "tool-1",
                output = "expanded-output-only",
                isError = false,
            ),
        )
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .assertIsDisplayed()
        compose.onNodeWithText("expanded-output-only").assertDoesNotExist()

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("expanded-output-only").assertIsDisplayed()
    }

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
                output = "adjacent-output-only",
                isError = false,
            ),
        )
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .assertIsDisplayed()
        compose.onAllNodesWithText("adjacent-output-only")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .performClick()
        compose.waitForIdle()
        compose.onAllNodes(
            hasText("adjacent-output-only") and
                hasAnyAncestor(hasTestTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
    }

    @Test
    fun searchForParentedToolResultOutputPromotesAndExpandsToolCall() {
        val events = listOf(
            ConversationEvent.ToolCall(
                id = "tool-1",
                agent = AgentKind.ClaudeCode,
                name = "Bash",
                input = "printf 'hidden-input'",
            ),
            ConversationEvent.ToolResult(
                id = "result-1",
                agent = AgentKind.ClaudeCode,
                toolCallId = "tool-1",
                output = "needle-output-only",
                isError = false,
            ),
            ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                text = "ordinary assistant text",
            ),
        )
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithText("needle-output-only").assertDoesNotExist()

        compose.onNodeWithTag(TMUX_CONVERSATION_SEARCH_TAG)
            .performTextInput("needle-output-only")
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")
            .assertIsDisplayed()
        compose.onAllNodes(
            hasText("needle-output-only") and
                hasAnyAncestor(hasTestTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "tool-1")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
            .let { assertEquals(1, it.size) }
        compose.onAllNodesWithText("ordinary assistant text")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
        compose.onAllNodesWithText("No matching events.")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
    }

    @Test
    fun searchQueryFlowsThroughHoistedStateAndFiltersFeed() {
        // The pane accepts (query, onQueryChange). Wire them to a
        // mutableStateOf and prove the query flows from the field into
        // the hoisted state by checking that the feed filters down to
        // the matching event. This contract — that the field reads from
        // and writes to the hoisted state — is what makes the query
        // survive a Terminal ↔ Conversation tab flip in the screen.
        compose.setContent {
            PocketShellTheme {
                var query by remember { mutableStateOf("") }
                TmuxConversationPane(
                    events = sampleMessageEvents(count = 3),
                    query = query,
                    onQueryChange = { query = it },
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_SEARCH_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_CONVERSATION_SEARCH_TAG)
            .performTextInput("event-1 body")
        compose.waitForIdle()

        // event-1 stays (matches), event-0 / event-2 are filtered out.
        compose.onNodeWithText("event-1 body text").assertExists()
        compose.onAllNodesWithText("event-0 body text")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
        compose.onAllNodesWithText("event-2 body text")
            .fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }

        // Clear the field — all three rows return.
        compose.onNodeWithTag(TMUX_CONVERSATION_SEARCH_TAG)
            .performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithText("event-0 body text").assertExists()
        compose.onNodeWithText("event-1 body text").assertExists()
        compose.onNodeWithText("event-2 body text").assertExists()
    }

    /**
     * Build a deterministic list of [count] user message rows whose
     * visible text is `event-<n> body text`. The text is unique per
     * row so the search test can assert exactly which rows survive
     * filtering.
     */
    private fun sampleMessageEvents(count: Int): List<ConversationEvent> {
        val ts = 1_700_000_000_000L
        return (0 until count).map { i ->
            ConversationEvent.Message(
                id = "event-$i",
                agent = AgentKind.ClaudeCode,
                atMillis = ts + i,
                role = if (i % 2 == 0) ConversationRole.User else ConversationRole.Assistant,
                text = "event-$i body text",
            )
        }
    }

    private fun sampleCodexTranscript(turns: Int): List<ConversationEvent> {
        val ts = 1_700_000_000_000L
        return buildList {
            repeat(turns) { i ->
                add(
                    ConversationEvent.Message(
                        id = "codex-message-$i",
                        agent = AgentKind.Codex,
                        atMillis = ts + i,
                        role = if (i % 2 == 0) ConversationRole.User else ConversationRole.Assistant,
                        text = "codex-visible-message-$i",
                    ),
                )
                add(
                    ConversationEvent.ToolCall(
                        id = "codex-tool-$i",
                        agent = AgentKind.Codex,
                        atMillis = ts + i,
                        name = "exec_command",
                        input = """{"cmd":"./gradlew test --tests CodexScroll$i"}""",
                    ),
                )
                add(
                    ConversationEvent.ToolResult(
                        id = "codex-result-$i",
                        agent = AgentKind.Codex,
                        atMillis = ts + i,
                        toolCallId = "codex-tool-$i",
                        output = buildString {
                            repeat(80) { line ->
                                append("tool-output-$i-$line ")
                                append("0123456789abcdef0123456789abcdef0123456789abcdef")
                                append('\n')
                            }
                        },
                    ),
                )
            }
        }
    }
}
