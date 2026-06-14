package com.pocketshell.app.tmux

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #616 / #755: the keyboard-chrome FSM contract for tmux terminal panes.
 *
 * History: #616 wired the terminal hotkey [com.pocketshell.uikit.components.KeyBar]
 * to appear above the keyboard via the host-window IME inset
 * ([com.pocketshell.app.layout.rememberHostImeBottomPx]). That bar lived on the
 * separate `TmuxTerminalBottomControls` surface.
 *
 * Issue #755 (PR2, composer redesign — D22 hard-cut): the bar was OCCLUDED there
 * because that surface is not anchored to the IME inset (the v0.4.0 "key bar
 * completely hidden by the keyboard" regression). It moved INTO the
 * [com.pocketshell.app.composer.PromptComposerSheet]'s inset-anchored column,
 * where it rides the same IME inset and can never be occluded. The real
 * keyboard-up reachability of the relocated bar is now covered by
 * `PromptComposerKeyBarImeReachabilityTest` (the twin of the #615 Send
 * reachability test).
 *
 * What survives here is the pure FSM contract: a terminal pane with the IME up
 * still maps to [TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys] (now a
 * render-nothing state on the terminal surface — the bar is in the composer),
 * and a conversation pane maps to the no-accessory state. This belt-and-
 * suspenders guards a future refactor from silently re-routing IME-up terminal
 * panes.
 */
@RunWith(AndroidJUnit4::class)
class TmuxKeyBarImeReachabilityTest {

    @Test
    fun chromeModeFsmMapsImeStatesCorrectly() {
        assertEquals(
            TmuxTerminalKeyboardChromeMode.HiddenImeControls,
            tmuxTerminalKeyboardChromeMode(isImeVisible = false, showConversation = false),
        )
        assertEquals(
            TmuxTerminalKeyboardChromeMode.OpenImeTerminalHotkeys,
            tmuxTerminalKeyboardChromeMode(isImeVisible = true, showConversation = false),
        )
        assertEquals(
            TmuxTerminalKeyboardChromeMode.OpenImeConversationNoAccessory,
            tmuxTerminalKeyboardChromeMode(isImeVisible = true, showConversation = true),
        )
    }
}
