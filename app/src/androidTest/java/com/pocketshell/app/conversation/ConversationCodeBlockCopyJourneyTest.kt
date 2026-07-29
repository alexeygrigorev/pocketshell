package com.pocketshell.app.conversation

import android.content.RecordingClipboardManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.test.ClipboardOverrideContext
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1888 D32/D33 journey through the production Conversation message and
 * Markdown renderer. The fixture deliberately has two fenced blocks and a line
 * wider than a phone, so one passing click cannot stand in for block identity
 * or for the fixed-overlay-vs-horizontal-scroll requirement.
 */
@RunWith(AndroidJUnit4::class)
class ConversationCodeBlockCopyJourneyTest {

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
    fun eachFencedBlockHasReachableCopyThatCopiesOnlyItsRawPayload() {
        compose.setContent {
            WithRecordingClipboard {
                ConversationMessageTurn(
                    event = ConversationEvent.Message(
                        id = MESSAGE_ID,
                        agent = AgentKind.ClaudeCode,
                        role = ConversationRole.Assistant,
                        text = MESSAGE,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background)
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        compose.waitForIdle()

        val firstCopy = compose.onNodeWithTag(copyTag(0))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Copy code block")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextEquals("Copy code")
        val secondCopy = compose.onNodeWithTag(copyTag(1))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Copy code block")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertTextEquals("Copy code")

        // The action is a viewport-anchored sibling of the sideways scroller:
        // scrolling the long payload must not carry the action off-screen.
        val viewport = compose.onNodeWithTag(codeViewportTag(0))
            .assert(hasScrollAction())
        val beforeSwipe = viewport.fetchSemanticsNode().config[
            SemanticsProperties.HorizontalScrollAxisRange
        ].value()
        viewport
            .performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val afterSwipe = viewport.fetchSemanticsNode().config[
            SemanticsProperties.HorizontalScrollAxisRange
        ].value()
        check(afterSwipe > beforeSwipe) {
            "Long code viewport did not scroll: before=$beforeSwipe after=$afterSwipe"
        }
        compose.assertNodeFullyWithinRoot(copyTag(0))
        compose.assertNodeFullyWithinRoot(copyTag(1))

        firstCopy.performClick()
        compose.waitForIdle()
        val afterCopy = viewport.fetchSemanticsNode().config[
            SemanticsProperties.HorizontalScrollAxisRange
        ].value()
        check(afterCopy == afterSwipe) {
            "Copy changed horizontal offset: before=$afterSwipe after=$afterCopy"
        }
        assertEquals(FIRST_PAYLOAD, recording.lastText)

        secondCopy.performClick()
        compose.waitForIdle()
        assertEquals(SECOND_PAYLOAD, recording.lastText)

        // Capture while the second block's production confirmation is visible,
        // before exercising the separate whole-message action below.
        captureFullDevice(File(artifactDir(), "issue-1888-code-block-copy.png"))

        // Whole-message Copy remains independently present and keeps its
        // original full-message granularity.
        compose.onNodeWithTag(CONVERSATION_COPY_TAG_PREFIX + MESSAGE_ID)
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        assertEquals(MESSAGE, recording.lastText)
    }

    private fun copyTag(index: Int): String =
        "conversation-code-block-$MESSAGE_ID-$index-copy"

    private fun codeViewportTag(index: Int): String =
        "conversation-code-block-$MESSAGE_ID-$index-viewport"

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-1888-code-block-copy")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create issue-1888 screenshot dir: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Could not capture issue-1888 full-device screenshot"
        }
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write issue-1888 screenshot: ${file.absolutePath}"
                }
            }
            println("ISSUE_1888_CODE_BLOCK_COPY_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MESSAGE_ID = "two-fenced-blocks"
        const val FIRST_PAYLOAD =
            "printf '%s\\n' " +
                "this-is-a-deliberately-long-command-that-must-scroll-sideways-without-moving-copy"
        const val SECOND_PAYLOAD = "val answer = 42\nprintln(answer)"
        const val MESSAGE =
            "Run the first command:\n\n" +
                "```bash\n" +
                FIRST_PAYLOAD +
                "\n```\n\n" +
                "Then evaluate Kotlin:\n\n" +
                "```kotlin\n" +
                SECOND_PAYLOAD +
                "\n```\n\n" +
                "Keep this surrounding prose out of both block copies."
    }
}
