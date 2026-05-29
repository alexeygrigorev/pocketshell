package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #260 visual evidence for the conversation turn layout. The
 * direct [TmuxConversationPane] render keeps the artifact focused on
 * message density and role differentiation, independent of the live tmux
 * client and #256 composer/send-target work.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationTurnDensityScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureLegacyConversationTurnReference() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                LegacyFlatConversationReference(
                    events = sampleConversationEvents().filterIsInstance<ConversationEvent.Message>(),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                )
            }
        }
        compose.waitForIdle()
        captureFullDevice(File(artifactDir(), "before-current-flat-rectangles.png"))
    }

    @Test
    fun captureDenseConversationTurnDensity() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = sampleConversationEvents(),
                    onSendToAgent = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .testTag(TMUX_CONVERSATION_PANE_TAG),
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = true,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG)
            .assertIsDisplayed()
        compose.onAllNodesWithText("USER", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("ASSISTANT", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)

        captureFullDevice(File(artifactDir(), "after-dense-terminal-turns.png"))
    }

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

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
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

@Composable
private fun LegacyFlatConversationReference(
    events: List<ConversationEvent.Message>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(events, key = { it.id }) { event ->
            val isUser = event.role == ConversationRole.User
            val title = when (event.role) {
                ConversationRole.User -> "USER"
                ConversationRole.Assistant -> if (event.streaming) "ASSISTANT - streaming" else "ASSISTANT"
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = if (isUser) PocketShellColors.SurfaceElev else PocketShellColors.Surface)
                    .border(width = 1.dp, color = PocketShellColors.BorderSoft)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    color = if (isUser) PocketShellColors.Accent else PocketShellColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                MarkdownText(text = event.text)
            }
        }
    }
}
