package com.pocketshell.app.conversation

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #605 regression: switching Conversation → Terminal while the
 * SelectionContainer floating toolbar (ActionMode) is up must not let the
 * conversation pane's selection/focus teardown contend with the heavyweight
 * terminal `AndroidView` re-attach in the same frame.
 *
 * The existing [ConversationInteractionCleanupTest] only asserts that `hide()`
 * was *called* on a [TextToolbar] double — it never exercises the real
 * toolbar-raised + AndroidView-attach contention. This test reproduces the real
 * swap structure (a real [SelectionContainer] long-pressed to raise the real
 * floating toolbar, then a real terminal-stand-in `AndroidView` gated by the
 * production [rememberConversationToTerminalSwapLatch]) and pins the contract
 * the fix provides:
 *   1. the conversation pane is disposed (cleanup runs) on the swap frame while
 *      the terminal `AndroidView` is NOT yet attached (one-frame hold), and
 *   2. the switch still completes — the terminal attaches and is visible on the
 *      next frame — so the switch is not blocked / wedged.
 *
 * Driven with a manual frame clock so the "one frame later" hold is observed
 * deterministically rather than by racing the auto-advance clock.
 */
@RunWith(AndroidJUnit4::class)
class ConversationToTerminalSwapLatchTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Red → green guard. A "base-shaped" swap with NO latch attaches the
     * heavyweight terminal `AndroidView` on the SAME frame the conversation
     * pane is disposed (its cleanup runs) — reproducing the same-frame race.
     * The production latched swap (next test) separates them by one frame.
     */
    @Test
    fun baseShapedSwapAttachesTerminalOnTheSameFrameTheConversationIsDisposed() {
        val disposed = AtomicBoolean(false)
        val terminalAttached = AtomicBoolean(false)
        val showConversation = mutableStateOf(true)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            PocketShellTheme {
                if (showConversation.value) {
                    DisposableEffect(Unit) {
                        onDispose { disposed.set(true) }
                    }
                    SelectionContainer {
                        Text("selectable transcript row", modifier = Modifier.testTag("row"))
                    }
                } else {
                    TerminalStandIn(onAttach = { terminalAttached.set(true) })
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithTag("row").performTouchInput { longClick() }
        compose.mainClock.advanceTimeByFrame()

        // Trigger the swap, then advance exactly ONE frame and observe state.
        showConversation.value = false
        compose.mainClock.advanceTimeByFrame()

        // Without a latch, the very same frame that disposes the conversation
        // attaches the heavyweight terminal AndroidView — the #605 contention.
        assertTrue("conversation disposed on swap frame", disposed.get())
        assertTrue(
            "base (no-latch) swap attaches the terminal on the SAME frame the conversation is disposed",
            terminalAttached.get(),
        )
    }

    /**
     * The production fix: with [rememberConversationToTerminalSwapLatch] in
     * front of the terminal branch, the conversation pane's cleanup teardown
     * (its onDispose) runs on the swap frame while the terminal `AndroidView` is
     * held back — it is NOT attached on that frame. One frame later the latch
     * releases and the terminal attaches and becomes visible. The teardown and
     * the AndroidView attach never share a frame, and the switch completes.
     */
    @Test
    fun latchedSwapHoldsTerminalAttachOneFramePastConversationDispose() {
        val recordingToolbar = RecordingTextToolbar()
        val disposed = AtomicBoolean(false)
        val terminalAttached = AtomicBoolean(false)
        val showConversation = mutableStateOf(true)

        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides recordingToolbar) {
                PocketShellTheme {
                    val deferTerminalAttach by rememberConversationToTerminalSwapLatch(
                        showConversation = showConversation.value,
                    )
                    if (showConversation.value) {
                        // Production cleanup effect: hides toolbar + clears
                        // focus on dispose. Record the dispose alongside.
                        ConversationInteractionCleanupEffect()
                        DisposableEffect(Unit) {
                            onDispose { disposed.set(true) }
                        }
                        SelectionContainer {
                            Text(
                                "selectable transcript row",
                                modifier = Modifier.testTag("row"),
                            )
                        }
                    } else if (deferTerminalAttach) {
                        Text("switching", modifier = Modifier.testTag("placeholder"))
                    } else {
                        TerminalStandIn(onAttach = { terminalAttached.set(true) })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        // Raise the REAL floating selection toolbar via a real long-press.
        compose.onNodeWithTag("row").performTouchInput { longClick() }
        compose.mainClock.advanceTimeByFrame()

        // Switch to Terminal with the toolbar still up, advance ONE frame.
        showConversation.value = false
        compose.mainClock.advanceTimeByFrame()

        // The fix's core contract: the conversation disposed (its cleanup ran)
        // on the swap frame, but the terminal AndroidView is held back — NOT
        // attached on that same frame. The teardown gets the frame to itself.
        assertTrue("conversation disposed on swap frame", disposed.get())
        assertFalse(
            "latch must hold the terminal AndroidView attach off the swap frame " +
                "(it attached in the same frame the conversation was disposed)",
            terminalAttached.get(),
        )
        // The production cleanup effect hid the toolbar before the terminal
        // surface attached.
        assertTrue("toolbar hidden on switch", recordingToolbar.hideCount >= 1)

        // Now let the latch release and the terminal attach: the switch
        // COMPLETES (not blocked) and the terminal becomes visible.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithTag("terminal").assertIsDisplayed()
        assertTrue("terminal attached after latch released", terminalAttached.get())
    }

    /** A heavyweight-stand-in for the terminal AndroidView re-attach. */
    @Composable
    private fun TerminalStandIn(onAttach: () -> Unit) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("terminal"),
            factory = { context ->
                onAttach()
                FrameLayout(context).apply {
                    addView(View(context))
                }
            },
        )
    }

    private class RecordingTextToolbar : TextToolbar {
        private val hides = AtomicInteger(0)
        val hideCount: Int get() = hides.get()

        override val status: TextToolbarStatus
            get() = if (hideCount == 0) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

        override fun hide() {
            hides.incrementAndGet()
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) = Unit
    }
}
