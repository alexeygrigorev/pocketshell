package com.pocketshell.app.tmux

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Issue #256: connected coverage for the conversation composer's hard
 * cut away from target-disambiguation chrome. Drives
 * [TmuxConversationPane] directly because the surrounding
 * [TmuxSessionScreen] needs Hilt + a live tmux client to spin up — the
 * unit under test is the per-pane composer band, not the screen
 * integration.
 *
 * The composer keeps one Send action and an agent-aware placeholder; it
 * renders no old routing chrome.
 */
@RunWith(AndroidJUnit4::class)
class TmuxConversationSendTargetUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversationComposerShowsNoTargetOrMismatchChrome() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                    agentName = "Claude Code",
                )
            }
        }

        compose.onNodeWithText("Sending" + " to: Window 1 · Pane 1")
            .assertDoesNotExist()
        compose.onNodeWithText("Got" + " it")
            .assertDoesNotExist()
        compose.onNodeWithText("Agent" + " is on", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Message Claude Code")
            .assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_COMPOSER_INPUT_TAG)
            .assertIsDisplayed()
    }

    @Test
    fun conversationComposerHasSingleSendAction() {
        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                TmuxConversationPane(
                    events = emptyList(),
                    onSendToAgent = {},
                )
            }
        }

        compose.onAllNodesWithText("Send")
            .assertCountEquals(1)
        compose.onNodeWithText("Insert")
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
