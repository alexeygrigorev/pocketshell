package com.pocketshell.app.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.tmux.ConversationDetectingPlaceholder
import com.pocketshell.app.tmux.ConversationTuiCommandNotice
import com.pocketshell.app.tmux.TMUX_CONVERSATION_OPEN_TERMINAL_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_TUI_NOTICE_TAG
import com.pocketshell.app.tmux.tmuxConversationPlaceholderLoadState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1207 — rendered-UI proof for the Conversation view's TUI-only
 * slash-command UX and the stranded-placeholder spinner-race fix.
 *
 * The bug: a fresh Claude session opens on the Conversation view with zero
 * transcript. Sending `/model` (an alt-screen TUI picker that writes NOTHING to
 * the JSONL) showed the user a silent nothing (and a misleading optimistic
 * "/model" bubble). Adjacently, a torn-down conversation row left the placeholder
 * spinning "Loading conversation…" forever with no watchdog behind it.
 *
 * These render the REAL production composables ([ConversationTuiCommandNotice],
 * [ConversationDetectingPlaceholder]) under the real [PocketShellTheme] and
 * assert the user-visible affordance — the Open-in-Terminal action and a
 * terminal, legible Empty state instead of an eternal spinner. PURE Compose (NO
 * Docker fixture, NO SSH/tmux, NO port) so it needs no tests.yml service change,
 * and it does NOT self-skip on CI (no assumeTrue / assumeFalse(isRunningOnCi())).
 */
@RunWith(AndroidJUnit4::class)
class ConversationTuiCommandNoticeRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tuiCommandNoticeShowsCommandAndOpenInTerminalAction() {
        // Criterion (a): after a `/model` send, the inline notice names the
        // command and offers a one-tap Open-in-Terminal jump — instead of the
        // silent nothing the user saw before.
        var openedTerminal = false
        var dismissed = false
        compose.setContent {
            PocketShellTheme {
                ConversationTuiCommandNotice(
                    command = "/model",
                    onOpenTerminal = { openedTerminal = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_TAG).assertIsDisplayed()
        // The command the user sent is named in the notice.
        compose.onNodeWithText("/model", substring = true).assertIsDisplayed()
        // The one-tap action is present and routes the user to the Terminal.
        compose.onNodeWithTag(TMUX_CONVERSATION_TUI_NOTICE_OPEN_TAG)
            .assertIsDisplayed()
            .performClick()
        assertTrue("Open-in-Terminal must invoke the terminal jump", openedTerminal)
        assertTrue("dismiss stayed inert on the Open action", !dismissed)
    }

    @Test
    fun emptyPlaceholderShowsTerminalStateWithOpenInTerminalNotSpinner() {
        // Criterion (b): the terminal Empty state (what a torn-down / stranded
        // placeholder now resolves to) is legible and actionable — NOT an eternal
        // "Loading conversation…" spinner.
        var openedTerminal = false
        compose.setContent {
            PocketShellTheme {
                ConversationDetectingPlaceholder(
                    loadState = ConversationLoadState.Empty,
                    onOpenTerminal = { openedTerminal = true },
                )
            }
        }

        // The eternal spinner is GONE — no "Loading conversation…" label.
        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        // The legible terminal state points the user at the Terminal tab.
        compose.onNodeWithText("Terminal tab", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_OPEN_TERMINAL_TAG)
            .assertIsDisplayed()
            .performClick()
        assertTrue("Empty-state Open-in-Terminal must jump to Terminal", openedTerminal)
    }

    @Test
    fun strandedPlaceholderFallbackRendersTerminalStateNotSpinner() {
        // Criterion (b), end-to-end through the PRODUCTION fallback: a torn-down
        // row (null load state) resolves via [tmuxConversationPlaceholderLoadState]
        // — the exact expression the showConversationPlaceholder branch runs — to
        // the terminal Empty affordance, never Loading. On base the `?: Loading`
        // fallback would render the spinner here forever.
        val rowLoadState = mutableStateOf<ConversationLoadState?>(null)
        compose.setContent {
            PocketShellTheme {
                ConversationDetectingPlaceholder(
                    loadState = tmuxConversationPlaceholderLoadState(rowLoadState.value),
                )
            }
        }

        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        compose.onNodeWithTag(TMUX_CONVERSATION_OPEN_TERMINAL_TAG).assertIsDisplayed()
    }
}
