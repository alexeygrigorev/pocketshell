package com.pocketshell.app.tmux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #197: connected coverage for the conversation composer's
 * "where does my message land?" surfaces. Drives [TmuxConversationPane]
 * directly because the surrounding [TmuxSessionScreen] needs Hilt + a
 * live tmux client to spin up — the unit under test is the per-pane
 * composer band, not the screen integration.
 *
 * Three behaviours are covered:
 *
 * 1. The target indicator strip always renders the agent's window /
 *    pane labels above the composer (acceptance criterion #1).
 * 2. The one-time first-send banner appears until the user taps
 *    "Got it" and is hidden afterwards (acceptance criterion #2).
 * 3. The cross-window mismatch banner shows only when the user is
 *    viewing a sibling window while the composer is locked to the
 *    agent pane (acceptance criterion #3).
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationSendTargetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun targetIndicatorAlwaysShowsAgentWindowAndPane() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = true,
                    firstSendConfirmed = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TARGET_INDICATOR_TAG)
            .assertIsDisplayed()
        compose.onNodeWithText("Sending to: Window 1 · Pane 1")
            .assertIsDisplayed()
    }

    @Test
    fun firstSendBannerShowsUntilUserConfirms() {
        var confirmCount = 0
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = true,
                    firstSendConfirmed = false,
                    onConfirmFirstSend = { confirmCount += 1 },
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_FIRST_SEND_BANNER_TAG)
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Sending to Window 1's Claude Code pane — got it?",
        ).assertIsDisplayed()

        compose.onNodeWithTag(TMUX_CONVERSATION_FIRST_SEND_CONFIRM_TAG)
            .performClick()

        assertEquals(
            "tapping Got it must fire the onConfirmFirstSend callback exactly once",
            1,
            confirmCount,
        )
    }

    @Test
    fun firstSendBannerHiddenWhenAlreadyConfirmed() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = true,
                    firstSendConfirmed = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_FIRST_SEND_BANNER_TAG)
            .assertDoesNotExist()
    }

    @Test
    fun mismatchBannerShowsWhenCurrentWindowDiffersFromAgentWindow() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = false,
                    firstSendConfirmed = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Agent is on Window 1, not the current window.",
        ).assertIsDisplayed()
    }

    @Test
    fun mismatchBannerHiddenWhenWindowsAgree() {
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = true,
                    firstSendConfirmed = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertDoesNotExist()
    }

    @Test
    fun allThreeSurfacesCoexistWhenAppropriate() {
        // Worst-case clarity: user has navigated to a sibling window
        // AND hasn't yet acknowledged the first-send banner. Both
        // banners + the target indicator must all be visible.
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    agentPaneLabel = "Pane 1",
                    agentDisplayName = "Claude Code",
                    currentWindowMatchesAgent = false,
                    firstSendConfirmed = false,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_FIRST_SEND_BANNER_TAG)
            .assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_TARGET_INDICATOR_TAG)
            .assertIsDisplayed()
    }

    @Test
    fun emptyAgentLabelsSuppressAllNewSurfaces() {
        // Defensive: callers that don't have window / pane labels
        // (older call sites, unit tests) pass empty strings — none of
        // the new surfaces should render in that case so the
        // pre-#197 composition is preserved bit-for-bit.
        compose.setContent {
            PocketShellTheme {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "",
                    agentPaneLabel = "",
                    agentDisplayName = "",
                    currentWindowMatchesAgent = true,
                    firstSendConfirmed = false,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TARGET_INDICATOR_TAG)
            .assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONVERSATION_FIRST_SEND_BANNER_TAG)
            .assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertDoesNotExist()
    }
}
