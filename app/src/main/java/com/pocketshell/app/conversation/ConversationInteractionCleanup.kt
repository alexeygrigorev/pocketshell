package com.pocketshell.app.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar

/**
 * Conversation rows expose selectable transcript text. If a user leaves the
 * pane while Compose selection or the search field owns focus, clear that
 * interaction state before the Terminal surface is mounted again.
 */
@Composable
internal fun ConversationInteractionCleanupEffect() {
    val textToolbar = LocalTextToolbar.current
    val focusManager = LocalFocusManager.current
    DisposableEffect(textToolbar, focusManager) {
        onDispose {
            textToolbar.hide()
            focusManager.clearFocus(force = true)
        }
    }
}

/**
 * Issue #605: defer the heavyweight Terminal `AndroidView` re-attach by exactly
 * one frame when switching Conversation → Terminal, so the leaving conversation
 * pane's [ConversationInteractionCleanupEffect] teardown (synchronous
 * `textToolbar.hide()` + `focusManager.clearFocus(force = true)`, which can
 * drive a synchronous IME/insets pass) does NOT contend with the embedded
 * terminal view's input-connection attach in the SAME swap frame. That
 * same-frame race — the `SelectionContainer` floating `ActionMode`/toolbar
 * teardown vs the `TerminalView` AndroidView attach, both touching the host
 * window/IME — is the residual hang path after the earlier #605 fixes
 * (`04a4dcb7` bounded expand-render, `b8ff2b0a` orphaned-focus cleanup).
 *
 * Usage at the swap site: when this returns `true`, paint the lightweight
 * switching placeholder instead of the terminal surface; the conversation pane
 * disposes (running its cleanup) on this frame, and the terminal attaches on
 * the next one — so the two never share a frame.
 *
 * The latch arms only on the Conversation → Terminal edge. Steady-state
 * Terminal (no preceding conversation) and Terminal → Conversation are
 * untouched, so this adds no extra frame to normal navigation.
 *
 * @param showConversation the live "render conversation content" gate. The
 *   latch observes the transition of this value from `true` to `false`.
 * @return whether the terminal attach should be held back for one frame.
 */
@Composable
internal fun rememberConversationToTerminalSwapLatch(showConversation: Boolean): State<Boolean> {
    // Previous observed value of the gate, mutated only inside the clear effect
    // so the true -> false edge is detected DURING composition (not one frame
    // late). Using a plain holder (not snapshot state) keeps the edge compare
    // off the recomposition-trigger path.
    val previous = remember { BooleanHolder(showConversation) }
    val deferTerminalAttach = remember { mutableStateOf(false) }

    // Arm synchronously, during composition, on the Conversation -> Terminal
    // edge. This must run in the same composition pass that flips the swap so
    // the terminal AndroidView is NOT composed on the swap frame; otherwise a
    // LaunchedEffect would fire after the terminal already attached.
    if (previous.value && !showConversation) {
        deferTerminalAttach.value = true
    }

    LaunchedEffect(showConversation) {
        if (previous.value && !showConversation) {
            // Conversation -> Terminal edge. The latch is already armed (above);
            // hold for exactly one frame so the conversation pane's onDispose
            // teardown (textToolbar.hide() + clearFocus(force=true)) runs alone,
            // then release so the terminal AndroidView attaches on the NEXT
            // frame with the toolbar/focus teardown already complete.
            withFrameNanos { }
            deferTerminalAttach.value = false
        } else {
            // Any other transition (entering conversation, steady state) must
            // never leave the latch stuck on.
            deferTerminalAttach.value = false
        }
        previous.value = showConversation
    }

    return deferTerminalAttach
}

/** Plain (non-snapshot) mutable boolean holder for the swap-edge compare. */
private class BooleanHolder(var value: Boolean)
