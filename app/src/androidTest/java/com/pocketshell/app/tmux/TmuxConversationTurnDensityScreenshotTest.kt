package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.app.conversation.ConversationMessageTurn
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #260 visual evidence for the conversation turn layout. The
 * direct [TmuxConversationPane] render keeps the artifact focused on
 * message density and role differentiation, independent of the live tmux
 * client and #256 composer-routing work.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationTurnDensityScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureDenseConversationTurnDensity() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = sampleConversationEvents(),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(TMUX_CONVERSATION_PANE_TAG),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG)
            .assertIsDisplayed()
        compose.onAllNodesWithText("US" + "ER", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("ASS" + "ISTANT", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText(
            "Check why the staging deploy failed",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onAllNodesWithText(
            "I will inspect the deploy logs",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)

        captureFullDevice(File(artifactDir(), "after-dense-terminal-turns.png"))
    }

    /**
     * Issue #493: "after" density evidence — the production
     * [ConversationMessageTurn] with the tightened spacing (compact
     * `bodyDense` line height, trimmed per-turn padding). Paired with
     * [captureConversationTurnSpacingBefore] so the reviewer can compare how
     * much more conversation fits per screen. The shared
     * [densityComparisonEvents] fixture keeps both captures identical except
     * for the spacing under test.
     */
    @Test
    fun captureConversationTurnSpacingAfter() {
        val events = densityComparisonEvents()
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag("conversation-density-after"),
                ) {
                    events.forEach { event ->
                        ConversationMessageTurn(event = event)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-density-after").assertIsDisplayed()
        compose.onAllNodesWithText(
            "summarise the fix",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        captureFullDevice(File(artifactDir(), "issue-493-after-tight-spacing.png"))
    }

    /**
     * Issue #493: "before" baseline — an inline replica of the pre-#493 turn
     * (looser 12sp body, 3 dp turn padding, 3 dp intra-turn gap, default
     * platform leading). Same fixture as
     * [captureConversationTurnSpacingAfter] so the only difference visible
     * between the two screenshots is the spacing change.
     */
    @Test
    fun captureConversationTurnSpacingBefore() {
        val events = densityComparisonEvents()
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag("conversation-density-before"),
                ) {
                    events.forEach { event ->
                        LegacyConversationMessageTurn(event = event)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-density-before").assertIsDisplayed()
        captureFullDevice(File(artifactDir(), "issue-493-before-loose-spacing.png"))
    }

    @Test
    fun longMessageRowsShowFullContentWithoutExpand() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.Message(
                            id = "dense-expand",
                            agent = AgentKind.ClaudeCode,
                            role = ConversationRole.Assistant,
                            text = "first line visible 561\nhidden detail line 561",
                        ),
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                )
            }
        }
        compose.waitForIdle()

        // Issue #561: chat-style message blocks always show full content.
        // Both lines of the multi-line message are visible without needing
        // to expand/collapse (the old dense timeline collapsed to one line).
        compose.onNodeWithText("first line visible 561", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()

        compose.onAllNodesWithText(
            "hidden detail line 561",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
    }

    /**
     * Issue #704 evidence. Seeds the production [TmuxConversationPane] with the
     * exact transcript shapes the maintainer reported:
     *  - a SystemNote whose content is a bare `<task-id>…</task-id>` wrapper
     *    (must NOT render as a raw, unstyled XML row),
     *  - a `Read` tool call whose result is a base64 image content-block array
     *    (must collapse to `[image …]`, never the multi-KB blob),
     *  - dense Bash/Agent/Read rows for the compact-spacing check.
     * Captures a collapsed view then taps the `Read` row open and captures the
     * parsed input + `[image]` output.
     */
    @Test
    fun issue704ConsistentStyledParsedCompactRows() {
        val base64 = "iVBORw0KGgoAAAANSUhEUgAAA" + "B".repeat(38_000)
        val events: List<ConversationEvent> = listOf(
            ConversationEvent.Message(
                id = "m-claude-task",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                // #704 req #1: a message whose entire body is a task-id wrapper
                // must be dropped, not rendered raw.
                text = "<task-id>a1887b43e9b725929</task-id>",
            ),
            ConversationEvent.SystemNote(
                id = "n-claude-task",
                agent = AgentKind.ClaudeCode,
                tag = "task-notification",
                content = "<task-id>a415fb6992433733a</task-id>",
            ),
            ConversationEvent.ToolCall(
                id = "t-bash",
                agent = AgentKind.ClaudeCode,
                name = "Bash",
                input = """{"command":"cd /home/alexey/git/pocketshell && echo ok"}""",
            ),
            ConversationEvent.ToolResult(
                id = "r-bash",
                agent = AgentKind.ClaudeCode,
                toolCallId = "t-bash",
                output = "ok",
            ),
            ConversationEvent.ToolCall(
                id = "t-agent",
                agent = AgentKind.ClaudeCode,
                name = "Agent",
                input = """{"description":"implementer: Restore #690 wiring layout","subagent_type":"implementer"}""",
            ),
            ConversationEvent.ToolCall(
                id = "t-read",
                agent = AgentKind.ClaudeCode,
                name = "Read",
                input = """{"file_path":"/home/alexey/.pocketshell/attachments/host-1-git-pocketshell-c/20260611-174904-01-Screenshot_20260611-174856.png"}""",
            ),
            ConversationEvent.ToolResult(
                id = "r-read",
                agent = AgentKind.ClaudeCode,
                toolCallId = "t-read",
                output = """[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$base64"}}]""",
            ),
        )

        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = events,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(TMUX_CONVERSATION_PANE_TAG),
                )
            }
        }
        compose.waitForIdle()

        // #704 req #1: no raw <task-id> XML anywhere in the transcript.
        compose.onAllNodesWithText("task-id", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("a1887b43e9b725929", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("a415fb6992433733a", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)

        // The styled tool rows are present (consistent styling, compact).
        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "t-read")
            .assertIsDisplayed()
        captureFullDevice(File(artifactDir(), "issue-704-collapsed-rows.png"))

        // #704 req #2: expand the Read row -> parsed file_path + [image] result,
        // never the base64 blob.
        compose.onNodeWithTag(TMUX_CONVERSATION_TOOL_ROW_TAG_PREFIX + "t-read")
            .performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("[image", substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("file_path", substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        // The base64 blob is never rendered.
        compose.onAllNodesWithText(
            "B".repeat(120),
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(0)
        captureFullDevice(File(artifactDir(), "issue-704-expanded-read-image.png"))
    }

    /**
     * Issue #781: a GFM pipe table inside an assistant message renders as a
     * laid-out table (header + body cells, honoured alignment), NOT raw
     * `|`-delimited lines. Asserts the parsed cell text is visible and that the
     * literal delimiter row (`|---|`) never appears in the rendered transcript,
     * then captures a full-device screenshot of the real table in the
     * conversation pane.
     */
    @Test
    fun gfmTableRendersAsLaidOutTableNotRawPipes() {
        val tableMessage = ConversationEvent.Message(
            id = "a-table",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = """
                Here are the open issues:

                | # | Issue | Type |
                |--:|:------|:----:|
                | 1 | Render Markdown tables in the conversation pane | ui |
                | 2 | Voice composer | feature |
                | 10 | Reconnect grace window | bug |

                Let me know which to start.
            """.trimIndent(),
        )

        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = listOf(tableMessage),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(TMUX_CONVERSATION_PANE_TAG),
                )
            }
        }
        compose.waitForIdle()

        // Header + cell text rendered as table cells.
        compose.onAllNodesWithText("Issue", substring = false, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText(
            "Reconnect grace window",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        compose.onAllNodesWithText("feature", substring = false, useUnmergedTree = true)
            .assertCountEquals(1)

        // The literal GFM delimiter row must NOT survive as raw text.
        compose.onAllNodesWithText("|--", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("--:", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)

        captureFullDevice(File(artifactDir(), "issue-781-conversation-table.png"))
    }

    private fun densityComparisonEvents(): List<ConversationEvent.Message> = listOf(
        ConversationEvent.Message(
            id = "d1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Check why the staging deploy failed and keep it brief.",
        ),
        ConversationEvent.Message(
            id = "d2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "I will inspect the deploy logs and compare the failing " +
                "revision with the last green run, then summarise the fix.",
        ),
        ConversationEvent.Message(
            id = "d3",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Good. What should I run next?",
        ),
        ConversationEvent.Message(
            id = "d4",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "Run the migration dry-run first so we can see the pending " +
                "steps without applying them to the database.",
        ),
        ConversationEvent.Message(
            id = "d5",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Then redeploy?",
        ),
        ConversationEvent.Message(
            id = "d6",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "Yes, once the dry-run is clean, trigger the deploy and " +
                "watch the rollout for the first two pods.",
        ),
    )

    private fun sampleConversationEvents(): List<ConversationEvent> = listOf(
        ConversationEvent.Message(
            id = "u1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Check why the staging deploy failed and keep it brief.",
        ),
        ConversationEvent.Message(
            id = "a1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "I will inspect the deploy logs and compare the failing revision with the last green run.",
        ),
        ConversationEvent.ToolCall(
            id = "t1",
            agent = AgentKind.ClaudeCode,
            name = "Bash",
            input = "kubectl logs deploy/api -n staging --tail=80",
        ),
        ConversationEvent.ToolResult(
            id = "r1",
            agent = AgentKind.ClaudeCode,
            toolCallId = "t1",
            output = "migration timeout after 120s\nrollback started",
            isError = false,
        ),
        ConversationEvent.Message(
            id = "u2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "What should I run next?",
        ),
        ConversationEvent.Message(
            id = "a2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "Run the migration dry-run first:\n\n```bash\nbundle exec rails db:migrate:status\n```",
        ),
    )

    /**
     * Issue #493: inline replica of the pre-#493 message turn used only as the
     * "before" baseline in the density screenshots. Mirrors the old layout —
     * 3 dp top/bottom turn padding, 3 dp intra-turn gap, 12sp body, and the
     * default platform leading (no compact `bodyDense` line height). Kept in
     * the test so the comparison does not depend on any production fallback.
     */
    @Composable
    private fun LegacyConversationMessageTurn(event: ConversationEvent.Message) {
        val isUser = event.role == ConversationRole.User
        val roleColor = if (isUser) PocketShellColors.Accent else PocketShellColors.Purple
        val glyph = if (isUser) "›" else "A"
        val startIndent = if (isUser) 0.dp else 10.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startIndent, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = glyph,
                color = roleColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.width(18.dp),
            )
            Column(
                modifier = Modifier
                    .padding(start = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                MarkdownText(
                    text = event.text,
                    color = PocketShellColors.Text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-260-conversation-turns")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-260 screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-260 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_260_CONVERSATION_TURNS_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
