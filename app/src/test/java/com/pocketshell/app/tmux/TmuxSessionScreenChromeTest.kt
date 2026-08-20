package com.pocketshell.app.tmux

import com.pocketshell.app.session.SessionTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2191: show-keyboard on Conversation is a real chrome leak, not merely
 * an early observation. The screen derives tab selection from [SessionTab] and
 * used to derive chip chrome from [tmuxSessionConversationSurface]. Those
 * disagree when Conversation is selected but the content-area surface falls
 * through to Terminal (visible pane not yet the active runtime pane).
 */
class TmuxSessionScreenChromeTest {

    @Test
    fun showKeyboardChipIsUnreachableOnConversationTabWhenSurfaceFallsThroughToTerminal() {
        // The #2191 / #1979 class case: Conversation is selected, a surface pane
        // exists, and detection/events are present — but isActivePane is false,
        // so tmuxSessionConversationSurface returns Terminal instead of
        // Transcript. The screen used that surface for bottom chrome, so the
        // Terminal-only show-keyboard chip stayed reachable on Conversation.
        val selectedTab = SessionTab.Conversation
        val showsConversationTab = true
        assertTrue(
            "tab semantics treat this state as Conversation",
            showsConversationTab && selectedTab == SessionTab.Conversation,
        )
        assertEquals(
            TmuxConversationSurface.Terminal,
            tmuxSessionConversationSurface(
                showsConversationTab = showsConversationTab,
                isActivePane = false,
                hasSurfacePane = true,
                selectedTab = selectedTab,
                hasDetection = true,
                hasEvents = true,
            ),
        )
        assertFalse(
            "#1979/#2191: show-keyboard must be unreachable on Conversation even " +
                "when the content-area surface stays Terminal",
            screenShapedShowKeyboardChipReachable(
                selectedTab = selectedTab,
                isActivePane = false,
                hasSurfacePane = true,
                showsConversationTab = showsConversationTab,
                hasDetection = true,
                hasEvents = true,
                surfaceExists = true,
            ),
        )
    }

    @Test
    fun pre2191SurfaceDerivedChromeExposedShowKeyboardOnConversationFallthrough() {
        // Vacuity: the deleted transcript||placeholder predicate wore Terminal
        // chrome on the #2191 fallthrough, so the new tab-keyed helper is not
        // a no-op rename. A revert to that formula reds the sibling test.
        val surface = tmuxSessionConversationSurface(
            showsConversationTab = true,
            isActivePane = false,
            hasSurfacePane = true,
            selectedTab = SessionTab.Conversation,
            hasDetection = true,
            hasEvents = true,
        )
        val oldOnConversationTab = surface == TmuxConversationSurface.Transcript ||
            surface == TmuxConversationSurface.Placeholder
        assertEquals(TmuxConversationSurface.Terminal, surface)
        assertFalse(
            "pre-#2191 surface-derived chrome treated the fallthrough as Terminal",
            oldOnConversationTab,
        )
        assertEquals(
            TmuxTerminalHiddenImeSurface.Controls,
            tmuxTerminalHiddenImeSurface(
                showConversation = oldOnConversationTab,
                terminalHeld = false,
            ),
        )
    }

    @Test
    fun showKeyboardChipStaysReachableOnTerminalTab() {
        assertTrue(
            screenShapedShowKeyboardChipReachable(
                selectedTab = SessionTab.Terminal,
                isActivePane = true,
                hasSurfacePane = true,
                showsConversationTab = true,
                hasDetection = true,
                hasEvents = true,
                surfaceExists = true,
            ),
        )
    }

    @Test
    fun showKeyboardChipIsUnreachableOnEveryConversationTabCombination() {
        for (isActivePane in listOf(true, false)) {
            for (hasSurfacePane in listOf(true, false)) {
                for (hasDetection in listOf(true, false)) {
                    for (hasEvents in listOf(true, false)) {
                        for (surfaceExists in listOf(true, false)) {
                            val reachable = screenShapedShowKeyboardChipReachable(
                                selectedTab = SessionTab.Conversation,
                                isActivePane = isActivePane,
                                hasSurfacePane = hasSurfacePane,
                                showsConversationTab = true,
                                hasDetection = hasDetection,
                                hasEvents = hasEvents,
                                surfaceExists = surfaceExists,
                            )
                            assertFalse(
                                "#2191: Conversation tab must never expose show-keyboard " +
                                    "(isActivePane=$isActivePane hasSurfacePane=$hasSurfacePane " +
                                    "hasDetection=$hasDetection hasEvents=$hasEvents " +
                                    "surfaceExists=$surfaceExists)",
                                reachable,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Chip reachability as [TmuxSessionScreen] now derives it. Content-area
     * inputs stay in the signature so the exhaustive Conversation sweep still
     * names the combinations that used to leak; they must not affect the result.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun screenShapedShowKeyboardChipReachable(
        selectedTab: SessionTab?,
        isActivePane: Boolean,
        hasSurfacePane: Boolean,
        showsConversationTab: Boolean,
        hasDetection: Boolean,
        hasEvents: Boolean,
        surfaceExists: Boolean,
        terminalHeld: Boolean = false,
    ): Boolean = tmuxTerminalHiddenImeSurface(
        showConversation = tmuxSessionBottomControlsShowsConversation(selectedTab),
        terminalHeld = terminalHeld,
    ) == TmuxTerminalHiddenImeSurface.Controls
}
