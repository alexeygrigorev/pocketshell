package com.pocketshell.app.conversation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TmuxConversationPane
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #474 visual evidence: the Conversation pane renders a compact
 * per-message timestamp on each turn, and the previously-unparsed Claude
 * Code `system` structural block (`scheduled_task_fire` — the maintainer's
 * "Task Notification"-like component) renders as a muted SystemNote row
 * instead of being dropped.
 *
 * The render is direct (no live tmux / Docker) and uses real-ish epoch
 * millis: two turns earlier *today* (so the timestamp shows `HH:mm`) and
 * one turn from an earlier day (so it shows the date prefix). The
 * formatter resolves "today" from the device clock, so the today turns are
 * seeded relative to `System.currentTimeMillis()`.
 */
@RunWith(AndroidJUnit4::class)
class ConversationTimestampScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureConversationTimestampsAndStructuralBlock() {
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

        compose.onNodeWithTag(TMUX_CONVERSATION_PANE_TAG).assertIsDisplayed()

        // The structural block renders as a normalized Claude timeline row,
        // not as a raw subtype label.
        compose.onAllNodesWithText("scheduled_task_fire", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText("CLAUDE", substring = true, useUnmergedTree = true)
            .assertCountAtLeast(1)
        compose.onAllNodesWithText("Claude resuming /loop wakeup", substring = true, useUnmergedTree = true)
            .assertCountAtLeast(1)
        compose.onAllNodesWithText("Turn took", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        // Prose turns are present.
        compose.onAllNodesWithText(
            "Did the migration finish?",
            substring = true,
            useUnmergedTree = true,
        ).assertCountAtLeast(1)

        captureFullDevice(File(artifactDir(), "issue-474-conversation-timestamps.png"))
    }

    private fun sampleConversationEvents(): List<ConversationEvent> {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        // Two turns earlier today at fixed wall-clock minutes so the
        // timestamp chip is legible (HH:mm); fall back to `now`-deltas if
        // resolving today's 09:05 lands in the future.
        val today = java.time.LocalDate.now(zone)
        fun todayAt(time: LocalTime): Long {
            val candidate = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
            return if (candidate <= now) candidate else now - 90 * 60_000L
        }
        val userTodayMillis = todayAt(LocalTime.of(9, 5))
        val assistantTodayMillis = todayAt(LocalTime.of(9, 7))
        // One turn from an earlier day so the dated form (MMM d, HH:mm)
        // is exercised in the same capture.
        val yesterdayMillis = now - 26 * 60 * 60_000L

        return listOf(
            ConversationEvent.Message(
                id = "u-yesterday",
                agent = AgentKind.ClaudeCode,
                atMillis = yesterdayMillis,
                role = ConversationRole.User,
                text = "Kick off the staging deploy and let me know when it lands.",
            ),
            ConversationEvent.SystemNote(
                id = "sys-task-fire",
                agent = AgentKind.ClaudeCode,
                atMillis = yesterdayMillis + 60_000L,
                tag = "scheduled_task_fire",
                content = "Claude resuming /loop wakeup (deploy watch)",
            ),
            ConversationEvent.SystemNote(
                id = "sys-duration",
                agent = AgentKind.ClaudeCode,
                atMillis = yesterdayMillis + 70_000L,
                tag = "turn_duration",
                content = "Turn took 9s across 1303 messages",
            ),
            ConversationEvent.Message(
                id = "u-today",
                agent = AgentKind.ClaudeCode,
                atMillis = userTodayMillis,
                role = ConversationRole.User,
                text = "Did the migration finish?",
            ),
            ConversationEvent.Message(
                id = "a-today",
                agent = AgentKind.ClaudeCode,
                atMillis = assistantTodayMillis,
                role = ConversationRole.Assistant,
                text = "Yes — the migration finished and the deploy is green. Done.",
            ),
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountAtLeast(
        min: Int,
    ) {
        val count = fetchSemanticsNodes().size
        check(count >= min) { "expected at least $min matching nodes; found $count" }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-474-conversation-timestamps")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-474 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-474 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_474_CONVERSATION_TIMESTAMPS_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
