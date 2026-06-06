package com.pocketshell.app.composer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #196 visual-parity evidence — captures the two composer surfaces
 * side by side so the reviewer/maintainer can eyeball that the terminal
 * shell composer ([SheetContent], the body of [PromptComposerSheet]) and
 * the agent-pane composer ([AgentComposerSurface]) now share the same
 * draft-box treatment (surface-elev fill, accent cursor, muted
 * placeholder) and the same accent primary Send button.
 *
 * Two artifacts get written to
 * `<media>/additional_test_output/unified-composer-parity/`:
 *
 *  - `unified-composer-terminal.png` — the terminal-shell composer body.
 *  - `unified-composer-agent.png` — the agent-pane composer surface.
 *  - `unified-composer-side-by-side.png` — both stacked in one frame so
 *    the shared visual language is obvious at a glance.
 *
 * These are deliberately Compose-only renders (no live SSH/tmux) — the
 * subject under test is the shared composer UI, not the byte pipe (the
 * pipe is covered by the connected E2E tests). The artifact-summary
 * call-out in the issue comment lists these as the parity screenshots.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedComposerParityScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureTerminalComposer() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Surface)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    SectionLabel("Terminal shell composer")
                    SheetContent(
                        state = PromptComposerViewModel.UiState(
                            draft = "check the deploy log and tell me what failed",
                            recording = PromptComposerViewModel.RecordingState.Idle,
                            amplitude = 0f,
                            error = null,
                        ),
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = {},
                        onSend = {},
                    )
                }
            }
        }
        captureAfterIdle("unified-composer-terminal.png")
    }

    @Test
    fun captureAgentComposer() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp, start = 12.dp, end = 12.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                ) {
                    SectionLabel("Agent-pane composer")
                    AgentComposerSurfaceHarness("check the deploy log and tell me what failed")
                }
            }
        }
        captureAfterIdle("unified-composer-agent.png")
    }

    @Test
    fun captureSideBySide() {
        compose.setContent {
            PocketShellTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .padding(top = 24.dp)
                        .testTag(SCREENSHOT_ROOT_TAG),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.background(PocketShellColors.Surface)) {
                        SectionLabel("Terminal shell composer")
                        SheetContent(
                            state = PromptComposerViewModel.UiState(
                                draft = "restart nginx and tail the error log",
                                recording = PromptComposerViewModel.RecordingState.Idle,
                                amplitude = 0f,
                                error = null,
                            ),
                            onClose = {},
                            onDraftChange = {},
                            onMicTap = {},
                            onSend = {},
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                        SectionLabel("Agent-pane composer")
                        AgentComposerSurfaceHarness("restart nginx and tail the error log")
                    }
                }
            }
        }
        captureAfterIdle("unified-composer-side-by-side.png")
    }

    @Composable
    private fun AgentComposerSurfaceHarness(initial: String) {
        var text by remember { mutableStateOf(initial) }
        AgentComposerSurface(
            value = text,
            onValueChange = { text = it },
            onSend = {},
            inputFieldTag = "parity:agent-input",
            sendButtonTag = "parity:agent-send",
            sendEnabled = true,
        )
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(
            text = text,
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    private fun captureAfterIdle(name: String) {
        compose.onNodeWithTag(SCREENSHOT_ROOT_TAG).assertExists()
        compose.waitForIdle()
        SystemClock.sleep(200)
        captureFullDevice(File(artifactDir(), name))
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/unified-composer-parity")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create unified-composer-parity screenshot dir: ${dir.absolutePath}"
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
                    "Could not write unified-composer-parity screenshot: ${file.absolutePath}"
                }
            }
            println("UNIFIED_COMPOSER_PARITY_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val SCREENSHOT_ROOT_TAG = "unified-composer:parity-screenshot"
    }
}
