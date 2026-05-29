package com.pocketshell.app.tmux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #197 / #255: connected coverage for the conversation composer's
 * "where is the agent?" disambiguation surfaces. Drives
 * [TmuxConversationPane] directly because the surrounding
 * [TmuxSessionScreen] needs Hilt + a live tmux client to spin up — the
 * unit under test is the per-pane composer band, not the screen
 * integration.
 *
 * Issue #255 removed the always-visible "Sending to: Window N · Pane N"
 * target-indicator strip — naming the underlying terminal pane as the
 * send target was meaningless in the agent conversation. This test now
 * asserts that strip never renders, while the remaining multi-window
 * disambiguation banner is preserved:
 *
 * 1. The conversation composer shows NO terminal send-target indicator.
 * 2. The first-send confirmation removed by #282 never renders.
 * 3. The cross-window mismatch banner shows only when the user is
 *    viewing a sibling window while the composer is locked to the
 *    agent pane (acceptance criterion #3).
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationSendTargetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversationComposerShowsNoTerminalSendTargetIndicator() {
        // Issue #255: in the Conversation pane the user is talking to the
        // agent, not the shell. The "Sending to: Window N · Pane N"
        // terminal send-target indicator must NOT render — not even in the
        // fully-labelled, confirmed, same-window happy path where it used
        // to always show.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = true,
                )
            }
        }

        compose.onNodeWithText("Sending to: Window 1 · Pane 1")
            .assertDoesNotExist()
        // The composer itself is still present and usable.
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertIsDisplayed()
    }

    @Test
    fun firstSendConfirmationDoesNotRender() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = true,
                )
            }
        }

        val removedConfirmationPrefix = "Sending to " + "Window 1's Claude Code pane"
        compose.onNodeWithText(removedConfirmationPrefix, substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Got" + " it")
            .assertDoesNotExist()
    }

    @Test
    fun mismatchBannerShowsWhenCurrentWindowDiffersFromAgentWindow() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = false,
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
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertDoesNotExist()
    }

    @Test
    fun mismatchBannerCanShowWithoutFirstSendConfirmation() {
        // Worst-case remaining clarity: user has navigated to a sibling
        // window. The mismatch banner stays visible, but the removed
        // terminal send-target indicator (#255) and first-send
        // confirmation (#282) must NOT be.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "Window 1",
                    currentWindowMatchesAgent = false,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertIsDisplayed()
        // Issue #255: no "Sending to: ..." terminal send-target strip,
        // even with full agent labels present.
        compose.onNodeWithText("Sending to: Window 1 · Pane 1")
            .assertDoesNotExist()
        compose.onNodeWithText("Got" + " it")
            .assertDoesNotExist()
    }

    @Test
    fun emptyAgentLabelsSuppressMismatchBanner() {
        // Defensive: callers that don't have window labels (older call
        // sites, unit tests) pass empty strings — the mismatch banner
        // does not render in that case.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentWindowLabel = "",
                    currentWindowMatchesAgent = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_WINDOW_MISMATCH_BANNER_TAG)
            .assertDoesNotExist()
    }

    // ─── Issue #249: input gated while disconnected, draft preserved ────

    @Test
    fun sendIsDisabledAndDraftPreservedWhenSessionNotLive() {
        // The exact data-loss repro from #249: user types a message while
        // the session is disconnected/reconnecting. With sendEnabled=false
        // the Send button must be disabled, a Send attempt must NOT fire
        // onSendToAgent, and the typed draft must remain in the composer.
        var sentCount = 0
        var lastSent: String? = null
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = { text ->
                        sentCount += 1
                        lastSent = text
                    },
                    sendEnabled = false,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .performTextInput("deploy the thing")

        // Send is disabled while not live.
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_SEND_TAG)
            .assertIsNotEnabled()

        // A tap on the disabled button must be a no-op (no delivery).
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_SEND_TAG)
            .performClick()
        assertEquals(
            "a disconnected Send must NOT deliver the message (#249 silent-loss bug)",
            0,
            sentCount,
        )
        assertEquals(null, lastSent)

        // The draft must still be in the composer so the user can re-send
        // once the session is live — nothing was silently dropped/cleared.
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertTextContains("deploy the thing")
    }

    @Test
    fun sendDeliversAndClearsWhenSessionIsLive() {
        // Mirror of the gated test for the live case: with sendEnabled=true
        // (and non-blank text) Send is enabled, fires onSendToAgent once,
        // and clears the composer — the normal happy path is intact.
        var sentCount = 0
        var lastSent: String? = null
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = { text ->
                        sentCount += 1
                        lastSent = text
                    },
                    sendEnabled = true,
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .performTextInput("ls -la")

        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_SEND_TAG)
            .assertIsEnabled()
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_SEND_TAG)
            .performClick()

        assertEquals("live Send must deliver exactly once", 1, sentCount)
        assertEquals("ls -la", lastSent)
        // Composer cleared after a successful live send.
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertTextContains("")
    }

    @Test
    fun restoredDraftSeedsTheComposer() {
        // Issue #177: a fast-resumed session comes back with the user's
        // half-typed message restored into the composer.
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    initialDraft = "half typed message",
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertTextContains("half typed message")
    }
}
