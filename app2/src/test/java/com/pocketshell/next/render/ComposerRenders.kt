package com.pocketshell.next.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.composer.ComposerBar
import com.pocketshell.next.composer.ComposerNotice
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.composer.MessageHistorySheet
import com.pocketshell.next.composer.RecordingState
import com.pocketshell.next.composer.SentMessage
import com.pocketshell.next.composer.StagedAttachment
import com.pocketshell.next.composer.StagingProgress
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design-render harness for the composer (task P-1), alongside
 * [HostScreenRenders] and for the same reason (issue #555).
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*ComposerRenders*' --rerun-tasks
 * # then open the PNGs under app2/build/renders/
 * ```
 *
 * These are renders, not assertions — they exist to be looked at while
 * iterating, and every state below is behaviourally covered by
 * `ComposerBarTest`. The emulator journey (`J07ComposerSendJourney`) remains
 * the acceptance gate: only it shows the composer against the real keyboard,
 * which is the geometry that actually goes wrong.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class ComposerRenders {

    @Test
    fun composerEmpty() = render("p1-composer-empty") {
        ComposerBar(state = ComposerUiState(micAvailable = true))
    }

    @Test
    fun composerWithDraft() = render("p1-composer-draft") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Run the full test suite and summarise what failed.",
                micAvailable = true,
            ),
        )
    }

    /** The whole delivery story, as the user sees it. */
    @Test
    fun composerUndelivered() = render("p1-composer-undelivered") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Run the full test suite and summarise what failed.",
                notice = ComposerNotice.Undelivered,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerWithAttachments() = render("p1-composer-attachments") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Have a look at these two.",
                attachments = listOf(
                    StagedAttachment("~/.pocketshell/attachments/7-devbox/a.png", "screenshot.png"),
                    StagedAttachment("~/.pocketshell/attachments/7-devbox/b.log", "build-output.log"),
                ),
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerUploading() = render("p1-composer-uploading") {
        ComposerBar(
            state = ComposerUiState(
                draft = "Have a look at these.",
                staging = StagingProgress(2, 3, "20260903-114210-02-screenshot.png"),
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerRecording() = render("p1-composer-recording") {
        ComposerBar(
            state = ComposerUiState(
                draft = "run the tests and",
                recording = RecordingState.Recording,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun composerPreview() = render("p1-composer-preview") {
        ComposerBar(
            state = ComposerUiState(
                draft = "## Plan\n\n- fix the parser\n- add `--json`\n\nThen ship it.",
                previewing = true,
                micAvailable = true,
            ),
        )
    }

    @Test
    fun messageHistory() = render("p1-composer-history") {
        MessageHistorySheet(
            messages = listOf(
                SentMessage(3, "Run the full test suite and summarise what failed.", SENT_AT, true),
                SentMessage(2, "this one never left the phone", SENT_AT - 600_000, false),
                SentMessage(1, "git status", SENT_AT - 3_600_000, true),
            ),
            onPick = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun ComposerBar(state: ComposerUiState) = ComposerBar(
        state = state,
        onDraftChange = {},
        onSend = {},
        onAttach = {},
        onMicTap = {},
        onCancelRecording = {},
        onToggleHistory = {},
        onTogglePreview = {},
        onRemoveAttachment = {},
        onDismissNotice = {},
        onDiscard = {},
    )

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = PocketShellColors.Background) {
                    Column(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private companion object {
        const val SENT_AT = 1_756_900_000_000L
    }
}
