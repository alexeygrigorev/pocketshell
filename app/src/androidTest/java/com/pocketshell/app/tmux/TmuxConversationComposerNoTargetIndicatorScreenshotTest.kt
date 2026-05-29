package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Issue #255 screenshot evidence: the Conversation pane composer must
 * show NO terminal send-target indicator ("Sending to: Window N · Pane
 * N"). Renders [TmuxConversationPane] in the worst case for the removed
 * strip — agent window label present, same window as the agent — which
 * is exactly the state where the always-visible "Sending to:" strip
 * used to render before #255.
 *
 * Writes `conversation-composer-no-target-indicator.png` to
 * `<media>/additional_test_output/tmux-conversation-composer/` so the
 * reviewer / maintainer can eyeball that the agent conversation composer
 * no longer leaks the meaningless terminal send target.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationComposerNoTargetIndicatorScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureConversationComposerWithoutTerminalSendTargetIndicator() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = listOf(
                        ConversationEvent.Message(
                            id = "u1",
                            agent = AgentKind.ClaudeCode,
                            role = ConversationRole.User,
                            text = "deploy the staging build",
                        ),
                        ConversationEvent.Message(
                            id = "a1",
                            agent = AgentKind.ClaudeCode,
                            role = ConversationRole.Assistant,
                            text = "Deploying the staging build now.",
                        ),
                    ),
                    onSendToAgent = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                    // Worst case for the removed strip: agent window label,
                    // same window — used to always show the
                    // "Sending to: Window 1 · Pane 1" indicator.
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = true,
                )
            }
        }
        compose.waitForIdle()

        // The composer is present and usable...
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertIsDisplayed()
        // ...but the terminal send-target indicator is gone (#255).
        compose.onNodeWithText("Sending to: Window 1 · Pane 1")
            .assertDoesNotExist()

        captureFullDevice(
            File(artifactDir(), "conversation-composer-no-target-indicator.png"),
        )
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/tmux-conversation-composer")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create conversation-composer screenshot dir: ${dir.absolutePath}"
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
                    "Could not write conversation-composer screenshot: ${file.absolutePath}"
                }
            }
            println("TMUX_CONVERSATION_COMPOSER_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
