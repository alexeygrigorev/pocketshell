package com.pocketshell.app.conversation

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.settings.AppSettings
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
 * Issue #496 visual evidence for the adjustable conversation font size.
 *
 * Renders the production [ConversationMessageTurn] feed at the compact
 * default ([AppSettings.DEFAULT_CONVERSATION_FONT_SP], 13sp) and at the
 * bumped maximum ([AppSettings.MAX_CONVERSATION_FONT_SP], 22sp) by supplying
 * [LocalConversationFontSizeSp] — the same composition local
 * `MainActivity` provides from the persisted setting. The two captures
 * let the reviewer confirm the body text actually scales while the dense
 * turn layout still holds together at the larger size.
 */
@RunWith(AndroidJUnit4::class)
class ConversationFontSizeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureConversationAtDefaultFontSize() {
        renderFeed(AppSettings.DEFAULT_CONVERSATION_FONT_SP, "conversation-font-default")
        compose.onNodeWithTag("conversation-font-default").assertIsDisplayed()
        compose.onAllNodesWithText(
            "inspect the deploy logs",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        captureFullDevice(File(artifactDir(), "issue-496-conversation-13sp-default.png"))
    }

    @Test
    fun captureConversationAtLargeFontSize() {
        renderFeed(AppSettings.MAX_CONVERSATION_FONT_SP, "conversation-font-large")
        compose.onNodeWithTag("conversation-font-large").assertIsDisplayed()
        compose.onAllNodesWithText(
            "inspect the deploy logs",
            substring = true,
            useUnmergedTree = true,
        ).assertCountEquals(1)
        captureFullDevice(File(artifactDir(), "issue-496-conversation-22sp-large.png"))
    }

    private fun renderFeed(fontSizeSp: Float, tag: String) {
        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(
                    LocalConversationFontSizeSp provides fontSizeSp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(PocketShellColors.Background)
                            .padding(8.dp)
                            .testTag(tag),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        sampleEvents().forEach { event ->
                            ConversationMessageTurn(event = event)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sampleEvents(): List<ConversationEvent.Message> = listOf(
        ConversationEvent.Message(
            id = "c1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Check why the staging deploy failed and keep it brief.",
        ),
        ConversationEvent.Message(
            id = "c2",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "I will inspect the deploy logs and compare the failing " +
                "revision with the last green run, then summarise the fix.",
        ),
        ConversationEvent.Message(
            id = "c3",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.User,
            text = "Good. What should I run next?",
        ),
        ConversationEvent.Message(
            id = "c4",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "Run the migration dry-run first so we can see the pending " +
                "steps without applying them to the database.",
        ),
    )

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/issue-496-conversation-font")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-496 screenshot dir: ${dir.absolutePath}"
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
                    "Could not write issue-496 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_496_CONVERSATION_FONT_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}
