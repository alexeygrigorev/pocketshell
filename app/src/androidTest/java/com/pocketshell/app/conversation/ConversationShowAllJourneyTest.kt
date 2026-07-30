package com.pocketshell.app.conversation

import android.content.RecordingClipboardManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.test.ClipboardOverrideContext
import com.pocketshell.app.tmux.TMUX_CONVERSATION_LIST_TAG
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.ConsolidatedTopChrome
import com.pocketshell.app.tmux.TmuxConversationPane
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Issue #1889 device regression: a capped transcript body must have an in-app
 * route to its exact tail. The test renders the production transcript row,
 * opens the production full-text surface, and observes the real clipboard
 * service call through the same deterministic override used by copy tests.
 */
@RunWith(AndroidJUnit4::class)
class ConversationShowAllJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
    fun cappedAssistantMessageOpensExactLazyFullTextCopiesClosesAndKeepsPosition() {
        val sentinel = "ISSUE_1889_SENTINEL_AFTER_INLINE_CAP"
        val body = "assistant-start\n" + "x".repeat(12_000) + "\n$sentinel"

        compose.setContent {
            WithRecordingClipboard {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.Message(
                            id = "assistant-char-limit",
                            agent = AgentKind.ClaudeCode,
                            role = ConversationRole.Assistant,
                            text = body,
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val showAllTag = "conversation-show-all-assistant-char-limit"
        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToNode(hasTestTag(showAllTag))
        val showAll = compose.onNodeWithTag(showAllTag)
            .assertIsDisplayed()
            .assertHasClickAction()
        val before = showAll.fetchSemanticsNode().boundsInRoot
        assertEquals(Role.Button, showAll.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTouchSized(before)
        compose.onNodeWithTag("conversation-full-text-dialog").assertDoesNotExist()
        captureFullDevice("issue-1889-inline-show-all.png")

        showAll.performClick()
        compose.onNodeWithTag("conversation-full-text-dialog").assertExists()
        compose.waitForIdle()
        captureFullDevice("issue-1889-full-text-open.png")
        waitForFullTextChunks()
        compose.onNodeWithTag("conversation-full-text-list")
            .performScrollToIndex(lastFullTextChunkIndex(body))
        compose.onNode(hasText(sentinel, substring = true), useUnmergedTree = true).assertIsDisplayed()
        captureFullDevice("issue-1889-full-text-tail.png")

        compose.onNodeWithTag("conversation-full-text-copy")
            .assertHasClickAction()
            .performClick()
        compose.waitForIdle()
        assertEquals(body, recording.lastText)

        compose.onNodeWithTag("conversation-full-text-close")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("conversation-full-text-dialog").assertDoesNotExist()
        val after = compose.onNodeWithTag("conversation-show-all-assistant-char-limit")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "closing the overlay must preserve the transcript anchor: before=$before after=$after",
            abs(before.top - after.top) <= 1f,
        )
    }

    @Test
    fun lineCappedUserMessageExposesTheReadableRoute() {
        val lineLimited = buildString {
            repeat(201) { index -> appendLine("user-line-$index") }
            append("USER_LINE_SENTINEL_1889")
        }
        compose.setContent {
            WithRecordingClipboard {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.Message(
                            id = "user-line-limit",
                            agent = AgentKind.Codex,
                            role = ConversationRole.User,
                            text = lineLimited,
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val showAllTag = "conversation-show-all-user-line-limit"
        compose.onNodeWithTag(TMUX_CONVERSATION_LIST_TAG)
            .performScrollToNode(hasTestTag(showAllTag))
        compose.onNodeWithTag(showAllTag)
            .assertIsDisplayed()
            .performClick()
        waitForFullTextChunks()
        compose.onNodeWithTag("conversation-full-text-list")
            .performScrollToIndex(lastFullTextChunkIndex(lineLimited))
        compose.onNode(
            hasText("USER_LINE_SENTINEL_1889", substring = true),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag("conversation-full-text-close").performClick()
    }

    @Test
    fun cappedToolOutputExposesTheSameReadableRoute() {
        val toolBody = "tool-start\n" + "t".repeat(5_001) + "\nTOOL_SENTINEL_1889"
        compose.setContent {
            WithRecordingClipboard {
                ConversationTextSection(
                    label = "output",
                    body = toolBody,
                    copyTestTag = "tool-output-copy",
                )
            }
        }
        compose.onNodeWithTag("tool-output-copy:show-all")
            .assertIsDisplayed()
            .performClick()
        waitForFullTextChunks()
        compose.onNodeWithTag("conversation-full-text-list")
            .performScrollToIndex(lastFullTextChunkIndex(toolBody))
        compose.onNode(
            hasText("TOOL_SENTINEL_1889", substring = true),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithTag("conversation-full-text-copy").performClick()
        compose.waitForIdle()
        assertEquals(toolBody, recording.lastText)
    }

    @Test
    fun realisticallyLargeFullTextStaysLazyAndMainThreadResponsive() {
        val body = buildString {
            appendLine("large-start")
            repeat(10_000) { index ->
                append(index.toString().padStart(5, '0'))
                append(':')
                append("0123456789".repeat(10))
                appendLine()
            }
            append("LARGE_END_1889")
        }
        compose.setContent {
            WithRecordingClipboard {
                var selectedTabIndex by remember { mutableIntStateOf(1) }
                Column(modifier = Modifier.fillMaxSize()) {
                    ConsolidatedTopChrome(
                        sessionName = "issue-1889",
                        agentName = "Codex",
                        onBack = {},
                        onMore = {},
                        tabLabels = listOf("Terminal", "Conversation"),
                        selectedTabIndex = selectedTabIndex,
                        onTabSelected = { selectedTabIndex = it },
                    )
                    if (selectedTabIndex == 1) {
                        TmuxConversationPane(
                            events = listOf(
                                ConversationEvent.Message(
                                    id = "million-char-message",
                                    agent = AgentKind.Codex,
                                    role = ConversationRole.Assistant,
                                    text = body,
                                ),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("issue-1889-terminal-tab"),
                        ) {
                            Text("Terminal")
                        }
                    }
                }
            }
        }

        val handler = Handler(Looper.getMainLooper())
        val maxMainThreadStallMs = AtomicLong(0)
        val pingCount = AtomicLong(0)
        fun schedulePing() {
            val scheduledAt = SystemClock.uptimeMillis() + 16
            handler.postDelayed({
                val latency = SystemClock.uptimeMillis() - scheduledAt
                if (latency > maxMainThreadStallMs.get()) {
                    maxMainThreadStallMs.set(latency)
                }
                pingCount.incrementAndGet()
            }, 16)
        }

        val showAllTag = "conversation-show-all-million-char-message"
        // Settle the already-capped transcript preview before measuring the
        // new full-reader path itself.
        compose.waitForIdle()
        val openedAt = SystemClock.elapsedRealtime()
        schedulePing()
        // The assistant journey above proves the action's visible touch path.
        // Invoke its real semantics action here so this stress case measures
        // reader work rather than test-framework scrolling through 200 preview
        // lines to bring the already-composed action on screen.
        compose.onNodeWithTag(showAllTag)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 4_000) {
            compose.onAllNodesWithTag(
                "conversation-full-text-chunk",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val openElapsed = SystemClock.elapsedRealtime() - openedAt
        val composedChunks =
            compose.onAllNodesWithTag(
                "conversation-full-text-chunk",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
        assertTrue(
            "LazyColumn must compose only a bounded viewport, not every chunk; composed=$composedChunks",
            composedChunks in 1..12,
        )
        schedulePing()
        compose.onNodeWithTag("conversation-full-text-list")
            .performScrollToIndex(lastFullTextChunkIndex(body))
        compose.onNode(
            hasText("LARGE_END_1889", substring = true),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        schedulePing()
        compose.onNodeWithTag("conversation-full-text-close").performClick()

        schedulePing()
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG).performClick()
        compose.onNodeWithTag("issue-1889-terminal-tab").assertIsDisplayed()
        schedulePing()
        compose.onNodeWithTag(TMUX_CONSOLIDATED_TAB_PILL_TAG_PREFIX + 1).performClick()
        compose.onNodeWithTag(showAllTag).assertExists()

        compose.waitUntil(timeoutMillis = 2_000) { pingCount.get() >= 5 }
        assertTrue(
            "opening, scrolling, closing, and switching tabs with a million-character " +
                "message must not block the main " +
                "looper for the 1s #605/#796 backstop; max=${maxMainThreadStallMs.get()}ms",
            maxMainThreadStallMs.get() <= 1_000,
        )
        println(
            "ISSUE1889_STRESS openMs=$openElapsed composedChunks=$composedChunks " +
                "maxMainThreadStallMs=${maxMainThreadStallMs.get()} pings=${pingCount.get()}",
        )
    }

    private fun assertTouchSized(bounds: Rect) {
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Show all width must be at least 48dp: $bounds", bounds.width >= 48f * density)
        assertTrue("Show all height must be at least 48dp: $bounds", bounds.height >= 48f * density)
    }

    private fun waitForFullTextChunks() {
        compose.waitUntil(timeoutMillis = 4_000) {
            compose.onAllNodesWithTag(
                "conversation-full-text-chunk",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Test-local copy of the public rendering contract (2,048 UTF-16 units OR
     * 80 newlines per lazy item). Keeping it local means this same source still
     * compiles on exact base for the missing-Show-all RED.
     */
    private fun lastFullTextChunkIndex(body: String): Int {
        var chunks = 0
        var start = 0
        while (start < body.length) {
            var end = start
            var newlineCount = 0
            while (end < body.length && end - start < 2_048 && newlineCount < 80) {
                if (body[end] == '\n') newlineCount += 1
                end += 1
            }
            if (
                end < body.length &&
                end > start &&
                body[end - 1].isHighSurrogate() &&
                body[end].isLowSurrogate()
            ) {
                end += 1
            }
            chunks += 1
            start = end
        }
        return chunks - 1
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-1889-show-all")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-1889 screenshot dir: ${dir.absolutePath}"
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-1889 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_1889_SHOW_ALL_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
