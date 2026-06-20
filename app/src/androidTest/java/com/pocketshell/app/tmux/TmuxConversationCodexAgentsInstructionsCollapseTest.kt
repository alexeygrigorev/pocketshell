package com.pocketshell.app.tmux

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
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #838 acceptance proof: the Codex AGENTS.md / `<INSTRUCTIONS>` first
 * **user** turn — parsed by the production [CodexParser] from a real JSONL
 * line — must render in the production [TmuxConversationPane] as a muted,
 * **collapsed-by-default** one-line row (the wall of instructions hidden),
 * expand to the full text on tap, and **collapse again** on the next tap. It
 * is collapsed, NOT removed — the content stays in the transcript.
 *
 * End-to-end on purpose: parser output feeds the real pane, so the test
 * exercises the whole path (#838) rather than a convenient stand-in.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationCodexAgentsInstructionsCollapseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instructionsWallMarker = "Production Data Access"
    private val previewLabel = "AGENTS.md instructions"

    private fun codexAgentsMdLine(): String {
        val wall =
            "AGENTS.md instructions for /home/alexey/git/ai-shipping-labs\n" +
                "<INSTRUCTIONS>\n" +
                "# Agent Notes\n" +
                "## Development Process\n" +
                "- Before continuing development work, read _docs/PROCESS.md.\n" +
                "## $instructionsWallMarker\n" +
                "- Production URL: https://aishippinglabs.com.\n" +
                "- Do not assume local files, SQLite, or a remote database tunnel.\n" +
                "</INSTRUCTIONS>"
        return """{"type":"event_msg","payload":{"type":"user_message","message":${JSONObject.quote(wall)}}}"""
    }

    @Test
    fun codexAgentsInstructionsRowCollapsedByDefaultThenExpandsThenCollapses() {
        val events = CodexParser().parseLine(codexAgentsMdLine())
        // Sanity: the parser produced a single collapsible SystemNote, not a
        // full-weight user Message (the #838 fix).
        val note = events.single() as ConversationEvent.SystemNote
        assertEquals(CodexParser.AGENTS_INSTRUCTIONS_TAG, note.tag)

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

        val rowTag = TMUX_CONVERSATION_SYSTEM_NOTE_ROW_TAG_PREFIX + note.id
        compose.onNodeWithTag(rowTag).assertIsDisplayed()

        // Collapsed by default: the one-line preview label is shown and the
        // instruction wall is NOT rendered.
        compose.onAllNodesWithText(previewLabel, substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText(instructionsWallMarker, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        captureFullDevice(File(artifactDir(), "issue-838-collapsed-by-default.png"))

        // Tap -> expands to the full content (the wall is now visible).
        compose.onNodeWithTag(rowTag).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(instructionsWallMarker, substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
        captureFullDevice(File(artifactDir(), "issue-838-expanded-after-tap.png"))

        // Tap again -> collapses back (NOT removed: the row + preview persist).
        compose.onNodeWithTag(rowTag).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(rowTag).assertIsDisplayed()
        compose.onAllNodesWithText(instructionsWallMarker, substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithText(previewLabel, substring = true, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-838-codex-agents-collapse")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-838 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-838 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_838_CODEX_AGENTS_COLLAPSE_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
