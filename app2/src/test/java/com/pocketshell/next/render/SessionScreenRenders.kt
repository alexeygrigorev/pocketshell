package com.pocketshell.next.render

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.pocketshell.next.composer.ComposerUiState
import com.pocketshell.next.terminal.SessionScreen
import com.pocketshell.next.terminal.SessionUiState
import com.pocketshell.next.terminal.createRemoteTerminalSession
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Fast design renders for the session screen's chrome (tasks U-5, U-7 and P-1).
 *
 * Same harness as [HostScreenRenders]: Pixel-7-class viewport, the app's own
 * always-dark theme, PNGs under `app2/build/renders/`.
 *
 * ```
 * ./gradlew :app2:testDebugUnitTest --tests '*SessionScreenRenders*' --rerun-tasks
 * ```
 *
 * Any state that HOSTS the terminal — `Live` and `Reconnecting` both — renders
 * with a blank terminal grid on the JVM: the vendored emulator paints through
 * `libtermux.so`, a device artifact, and its `AndroidView` takes the whole
 * capture with it. So the reconnect banner's real appearance is a device
 * screenshot from `J05ReconnectAfterDropJourney`, its text is pinned by
 * `SessionScreenTest`, and what these two states' renders show is the CHROME
 * around the terminal, not the terminal itself. This is the fast first look,
 * never the acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
class SessionScreenRenders {

    /** Attaching, with the compact launcher already docked so the layout cannot jump. */
    @Test
    fun sessionAttaching() = render("u5-session-attaching") {
        SessionScreen(
            state = SessionUiState.Connecting,
            composerState = ComposerUiState(),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onRetry = {},
            onHotkeySend = {},
            onDraftChange = {},
            onSend = { true },
            onInsert = {},
            onAttach = {},
            onMicTap = {},
            onCancelRecording = {},
            onToggleHistory = {},
            onTogglePreview = {},
            onRemoveAttachment = {},
            onDismissNotice = {},
            onDiscardDraft = {},
            onUseHistoryEntry = {},
        )
    }

    /** Attached: compact Prompt Composer + ⌨ launcher under a full-bleed terminal. */
    @Test
    fun sessionLiveWithKeyBar() = render("u5-session-key-bar") {
        SessionScreen(
            state = SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onRetry = {},
            onHotkeySend = {},
            onDraftChange = {},
            onSend = { true },
            onInsert = {},
            onAttach = {},
            onMicTap = {},
            onCancelRecording = {},
            onToggleHistory = {},
            onTogglePreview = {},
            onRemoveAttachment = {},
            onDismissNotice = {},
            onDiscardDraft = {},
            onUseHistoryEntry = {},
        )
    }

    /** The ladder gave up: what is left is a message and a way to try again. */
    @Test
    fun sessionGaveUp() = render("u7-session-gave-up") {
        SessionScreen(
            state = SessionUiState.Failed(
                "Could not reconnect to the session. Tap Retry to try again.",
            ),
            composerState = ComposerUiState(),
            sessionName = "git-pocketshell",
            onBack = {},
            onResized = { _, _ -> },
            onRetry = {},
            onHotkeySend = {},
            onDraftChange = {},
            onSend = { true },
            onInsert = {},
            onAttach = {},
            onMicTap = {},
            onCancelRecording = {},
            onToggleHistory = {},
            onTogglePreview = {},
            onRemoveAttachment = {},
            onDismissNotice = {},
            onDiscardDraft = {},
            onUseHistoryEntry = {},
        )
    }

    /**
     * #2630: the maintainer's terminal screenshot wrapped the workspace name to
     * two lines, pushing the terminal down. The stock fixtures use a 15-char
     * name that never wrapped even at the shipped 28sp, so this case carries a
     * longer, realistic one.
     *
     * Honest scope note: the reconciled 20sp screen rung roughly doubles the
     * one-line budget at 412dp (about 16 characters at 28sp to about 23 at
     * 20sp), which is what this render shows. It does not make wrapping
     * impossible — a name longer than that still takes two lines, and fixing
     * THAT would be a header-layout change (marquee/ellipsis/truncation), not
     * the density pass this issue is.
     */
    @Test
    fun sessionLongWorkspaceNameHeader() = render("i2630-session-long-name-header") {
        SessionScreen(
            state = SessionUiState.Live(createRemoteTerminalSession()),
            composerState = ComposerUiState(),
            sessionName = "pocketshell-quiet-ui",
            onBack = {},
            onResized = { _, _ -> },
            onRetry = {},
            onHotkeySend = {},
            onDraftChange = {},
            onSend = { true },
            onInsert = {},
            onAttach = {},
            onMicTap = {},
            onCancelRecording = {},
            onToggleHistory = {},
            onTogglePreview = {},
            onRemoveAttachment = {},
            onDismissNotice = {},
            onDiscardDraft = {},
            onUseHistoryEntry = {},
        )
    }

    private fun render(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/renders/$name.png") {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PocketShellColors.Background,
                ) {
                    content()
                }
            }
        }
    }
}
