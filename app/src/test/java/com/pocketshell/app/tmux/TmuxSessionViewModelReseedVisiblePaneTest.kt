package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelReseedVisiblePaneTest : TmuxSessionViewModelTestBase() {
    @Test
    fun reseedVisiblePaneIfBlankReCapturesAndHealsABlackPane() = runTest(scheduler) {
        // The maintainer's symptom: a window renders a BLACK pane (the seed
        // never painted content) on a LIVE connection, and switching to it does
        // not recover it. Drive that exact state: the pane's attach-time seed
        // returns EMPTY (no content), so its emulator stays blank. Then a window
        // switch calls reseedVisiblePaneIfBlank — which must issue a FRESH
        // capture-pane and paint the content tmux's grid now holds.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        // Attach-time seed comes back EMPTY -> the pane stays black.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertTrue(
            "precondition: the empty attach-time seed must leave the pane BLACK",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountAfterAttach =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        // The user switches to this window: a fresh capture now HAS content.
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 3L,
                output = listOf("ISSUE662-RECOVERED-CONTENT"),
                isError = false,
            ),
        )
        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfterReseed =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertTrue(
            "expected a FRESH capture-pane re-seed for the blank pane, " +
                "got commands ${client.sentCommands}",
            captureCountAfterReseed > captureCountAfterAttach,
        )
        assertFalse(
            "the pane must no longer be BLACK after the re-seed painted content",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the re-seeded content must be on the pane's grid, got " +
                renderedTranscriptFrom(pane.terminalState),
            renderedTranscriptFrom(pane.terminalState)
                .contains("ISSUE662-RECOVERED-CONTENT"),
        )
    }

    @Test
    fun reseedVisiblePaneIfBlankIsNoOpWhenPaneAlreadyShowsContent() = runTest(scheduler) {
        // A pane that already painted content must NOT be re-captured on a
        // window switch — the blank-only guard keeps the switch cheap and never
        // clobbers a good frame.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("ALREADY-VISIBLE-CONTENT"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertFalse(
            "precondition: the seeded pane must show content (not blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountBefore =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfter =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertEquals(
            "a non-blank pane must NOT trigger a re-capture on switch",
            captureCountBefore,
            captureCountAfter,
        )
    }

    private fun renderedTranscriptFrom(state: TerminalSurfaceState): String {
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }
}
