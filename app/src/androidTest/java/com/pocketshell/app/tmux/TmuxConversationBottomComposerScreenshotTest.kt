package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.voice.ADD_PROMPT_CHIP_LABEL
import com.pocketshell.app.voice.BottomChipControls
import com.pocketshell.app.voice.SESSION_MIC_FAB_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #459 screenshot evidence — proves the Conversation tab's bottom is
 * IDENTICAL to the Terminal tab's bottom: the unified composer band (the
 * mic FAB that opens the shared `PromptComposerSheet`), with the
 * Terminal-only "show keyboard" chip / key bar absent in Conversation.
 *
 * The full `TmuxSessionScreen` needs Hilt + a live tmux connect, so this
 * test renders the exact bottom band the screen wires per tab
 * ([com.pocketshell.app.voice.BottomChipControls]) and captures a
 * full-device screenshot for the maintainer / reviewer to eyeball:
 *
 *  - `issue459-bottoms-side-by-side.png` — both bottoms stacked, labelled,
 *    so the "Conversation == Terminal composer; key bar is Terminal-only"
 *    convergence is visible in one frame.
 *
 * Asserts the contract too: the mic FAB is present in both; the
 * show-keyboard chip is present only on Terminal; the key bar (Esc/Tab raw
 * keys) never appears.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationBottomComposerScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun conversationAndTerminalShareUnifiedComposerBottom() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("Conversation tab — unified composer (no key bar)")
                    // How TmuxSessionScreen wires the bottom band for an agent
                    // pane on its Conversation tab (#459): mic FAB only, the
                    // Terminal-only show-keyboard chip is null.
                    BottomChipControls(
                        chips = AgentExitChips,
                        onChipTap = {},
                        onDictateTap = {},
                        onShowKeyboardTap = null,
                        onAddSnippetTap = {},
                        addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                        addSnippetIcon = null,
                        onProjectNavigationTap = null,
                        modifier = Modifier.testTag(CONVERSATION_BAND_TAG),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    SectionLabel("Terminal tab — same composer + show-keyboard")
                    // The Terminal tab keeps the identical mic FAB plus the
                    // Terminal-only show-keyboard chip (raises the key bar).
                    BottomChipControls(
                        chips = AgentExitChips,
                        onChipTap = {},
                        onDictateTap = {},
                        onShowKeyboardTap = {},
                        onAddSnippetTap = {},
                        addSnippetLabel = ADD_PROMPT_CHIP_LABEL,
                        addSnippetIcon = null,
                        onProjectNavigationTap = null,
                        modifier = Modifier.testTag(TERMINAL_BAND_TAG),
                    )
                }
            }
        }

        compose.onNodeWithTag(ROOT_TAG).assertExists()
        compose.waitForIdle()
        // Best-effort device capture. Some emulators (SwiftShader / headless)
        // return null from UiAutomation.takeScreenshot(); when that happens
        // the maintainer's review evidence is captured externally with
        // `adb exec-out screencap` against this same rendered frame. The
        // assertions below are the authoritative contract check.
        captureFullDevice(File(artifactDir(), "issue459-bottoms-side-by-side.png"))

        // Contract: the unified composer mic FAB is present in BOTH bands.
        compose.onAllNodesWithTag(SESSION_MIC_FAB_TAG).let { nodes ->
            // Two bands → two mic FABs.
            assert(nodes.fetchSemanticsNodes().size == 2) {
                "expected the unified mic FAB in BOTH bottoms (Conversation + Terminal)"
            }
        }
        // The Terminal-only show-keyboard chip appears exactly once (Terminal).
        compose.onNodeWithTag(SHOW_KEYBOARD_CHIP_TAG).assertIsDisplayed()
        // No key bar raw-key labels anywhere in this bottom-band render.
        compose.onNodeWithText("Esc").assertDoesNotExist()
        compose.onNodeWithText("Tab").assertDoesNotExist()
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text = text,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-459-conversation-bottom")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-459 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-459 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE459_CONVERSATION_BOTTOM_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val ROOT_TAG = "issue459:bottoms-root"
        const val CONVERSATION_BAND_TAG = "issue459:conversation-band"
        const val TERMINAL_BAND_TAG = "issue459:terminal-band"
    }
}
