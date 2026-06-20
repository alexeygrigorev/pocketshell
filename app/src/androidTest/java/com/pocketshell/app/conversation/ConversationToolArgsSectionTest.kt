package com.pocketshell.app.conversation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.agents.ToolArgsView
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #841 visual + behaviour proof for the structured tool-call args card.
 *
 * Composes the PRODUCTION [ConversationToolArgsSection] with the maintainer's
 * exact reported payload — a Codex `exec_command` whose args were dumped as a
 * raw `{"cmd":...,"timeout_ms":...}` JSON blob — and asserts the card now shows:
 * - the command as a readable `$ <cmd>` monospace command line, and
 * - the other fields (`timeout_ms`, `cwd`) as labeled key/value rows,
 *
 * NOT the raw brace-soup one-liner. A full-device screenshot is captured for
 * the reviewer.
 */
@RunWith(AndroidJUnit4::class)
class ConversationToolArgsSectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tag = "issue-841-tool-args"

    @Test
    fun execCommandRendersStructuredNotRawBlob() {
        val input =
            """{"cmd":"gh run list --limit 25 --json status,name,conclusion","timeout_ms":10000,"cwd":"/home/alexey/git/pocketshell"}"""
        val view = ToolArgsView.forInput("exec_command", input)

        // The model itself must be structured (the parse-level half of the fix).
        assertTrue("expected a structured view, got $view", view is ToolArgsView.Structured)

        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(12.dp)
                        .testTag(tag),
                ) {
                    ConversationToolArgsSection(
                        view = view,
                        copyTestTag = "$tag-copy",
                        rawCopyText = input,
                    )
                }
            }
        }
        compose.waitForIdle()

        // The command line is shown as a `$ <cmd>` monospace block.
        compose.onNodeWithTag("$tag-copy:command").assertIsDisplayed()
        compose.onNodeWithText(
            "gh run list --limit 25 --json status,name,conclusion",
            substring = true,
        ).assertIsDisplayed()

        // The other args are labeled rows, not folded into a JSON blob.
        compose.onNodeWithTag("$tag-copy:field:timeout_ms").assertIsDisplayed()
        compose.onNodeWithTag("$tag-copy:field:cwd").assertIsDisplayed()
        compose.onNodeWithText("timeout_ms").assertIsDisplayed()
        compose.onNodeWithText("/home/alexey/git/pocketshell").assertIsDisplayed()

        // The Raw/degrade-gracefully fallback (malformed JSON -> formatted block,
        // never the raw one-liner) is proven exhaustively at the JVM level in
        // core-agents' ToolArgsViewTest; it is not re-asserted here because the
        // single shared ComponentActivity rule cannot cleanly relaunch
        // setContent for a second @Test in this harness.

        captureFullDevice(File(artifactDir(), "issue-841-structured-exec-command.png"))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-841-tool-args")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-841 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-841 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_841_TOOL_ARGS_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
