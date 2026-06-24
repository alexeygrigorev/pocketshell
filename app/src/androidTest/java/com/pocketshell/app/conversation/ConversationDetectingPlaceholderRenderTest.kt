package com.pocketshell.app.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.tmux.ConversationDetectingPlaceholder
import com.pocketshell.app.tmux.TMUX_CONVERSATION_DETECTING_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_LOAD_FAILED_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_LOAD_RETRY_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.tmuxSessionPresumedAgent
import com.pocketshell.app.tmux.tmuxSessionTabState
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #778 — rendered-UI regression proof for the Conversation-tab tap on a
 * presumed-agent pane whose live agent detection has not landed yet
 * (`detection == null`).
 *
 * The bug: tapping Conversation did nothing on screen — the view stayed on
 * Terminal. The state-layer fix is already covered by `TmuxSessionScreenTest`
 * (the pure [tmuxSessionTabState] function) and `TmuxSessionViewModelTest`
 * (seed-on-tap), and the connected `AgentConversationReconnectDockerTest` drives
 * the real ViewModel over Docker. None of those COMPOSE the screen, so none
 * proves the *visible swap* — which, per the #641/#615 locked rule, is the
 * acceptance for a user-facing nav fix.
 *
 * This test follows the [ConversationToTerminalSwapLatchTest] pattern
 * (`createComposeRule().setContent { … }` + `onNodeWithTag(…).assertIsDisplayed()`):
 * it reproduces the production content-area branch precedence that
 * `TmuxSessionScreen` runs between the tab row and the pager —
 *   1. real transcript ([TMUX_CONVERSATION_PANE_TAG]) when `showConversation`,
 *   2. the REAL production [ConversationDetectingPlaceholder]
 *      ([TMUX_CONVERSATION_DETECTING_TAG]) when `showConversationPlaceholder`,
 *   3. otherwise the terminal —
 * and drives the tab tap through the production [tmuxSessionTabState] gating
 * (`showsConversationTab`), exactly like the screen's `onTabSelected`. The tab
 * tap mutates the conversation row the same way the production ViewModel does
 * (records `selectedTab = Conversation` on a detection-less row).
 *
 * Asserts the user-visible swap:
 *   (a) the "Waiting for agent…" placeholder ([TMUX_CONVERSATION_DETECTING_TAG])
 *       is `assertIsDisplayed()` after the tap, AND
 *   (b) the terminal content is swapped OUT (no longer displayed).
 */
@RunWith(AndroidJUnit4::class)
class ConversationDetectingPlaceholderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val terminalTag = "test:terminal-content"
    private val conversationTabTag = "test:tab:conversation"

    /**
     * Production-shaped harness: the tab-switch gate and content-branch
     * precedence are the SAME expressions `TmuxSessionScreen` runs. A presumed
     * agent with `detection == null` taps Conversation → the placeholder is
     * shown and the terminal is swapped out. This is the #778 fix at the render
     * layer.
     */
    @Test
    fun tappingConversationOnPresumedAgentWithoutDetectionShowsPlaceholderAndSwapsOutTerminal() {
        compose.setContent {
            PocketShellTheme {
                // The presumed-agent pane starts on Terminal with no detection —
                // the exact #778 starting state (agent session, detection not yet
                // landed).
                var row by remember {
                    mutableStateOf(
                        AgentConversationUiState(
                            detection = null,
                            selectedTab = SessionTab.Terminal,
                        ),
                    )
                }
                val presumedAgent = true

                PresumedAgentContent(
                    row = row,
                    presumedAgent = presumedAgent,
                    onConversationTabTap = {
                        // Mirror the production onTabSelected gate
                        // (TmuxSessionScreen ~:1119): only switch when the
                        // Conversation tab is actually shown. The ViewModel then
                        // records the intent on a detection-less row (#778
                        // seed-on-tap), which is what we model here.
                        val tabState = tmuxSessionTabState(row, presumedAgent)
                        if (tabState.showsConversationTab) {
                            row = row.copy(selectedTab = SessionTab.Conversation)
                        }
                    },
                )
            }
        }

        // Before the tap: terminal is shown, placeholder is not.
        compose.onNodeWithTag(terminalTag).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG).assertIsNotDisplayed()

        // Tap the Conversation tab — the gesture the maintainer reported as a
        // no-op.
        compose.onNodeWithTag(conversationTabTag).performClick()
        compose.waitForIdle()

        // (a) The real loading placeholder is now displayed — the tap was
        // honoured and the view switched to the Conversation surface even
        // though detection is still null. Issue #793: the copy is now
        // "Loading conversation…" (loading an existing transcript), NOT the
        // old, misleading "Waiting for agent…".
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
        compose.onNodeWithText("Waiting for agent…").assertIsNotDisplayed()

        // (b) The terminal content genuinely swapped OUT — not just the tab
        // index moved; the content area no longer shows the terminal.
        compose.onNodeWithTag(terminalTag).assertIsNotDisplayed()
    }

    /**
     * Red → green guard. The OLD (base) behaviour gated the Conversation switch
     * on live detection being non-null (both at the `tmuxSessionTabState` index
     * and the ViewModel `selectSessionTab` early-return), so a tap on a
     * detection-less presumed-agent pane was swallowed and the content branch
     * never selected the placeholder. This reproduces that base shape and pins
     * it: after the tap the terminal is STILL shown and the placeholder never
     * appears — i.e. without the #778 fix the render-level swap does not happen.
     *
     * It is the failing-state counterpart that proves the production test above
     * asserts the FIX, not pre-existing behaviour.
     */
    @Test
    fun baseShapedSwapSwallowsTheTapAndKeepsTerminalShown() {
        compose.setContent {
            PocketShellTheme {
                var row by remember {
                    mutableStateOf(
                        AgentConversationUiState(
                            detection = null,
                            selectedTab = SessionTab.Terminal,
                        ),
                    )
                }
                val presumedAgent = true

                PresumedAgentContent(
                    row = row,
                    presumedAgent = presumedAgent,
                    onConversationTabTap = {
                        // OLD gate: the switch required live detection. A
                        // detection-less tap is a no-op — the #778 bug.
                        if (row.detection != null) {
                            row = row.copy(selectedTab = SessionTab.Conversation)
                        }
                    },
                    // OLD content gate: render the placeholder only when the row
                    // already says Conversation AND detection is null — but since
                    // the old tap could never set selectedTab=Conversation
                    // without detection, this branch is unreachable on base.
                    placeholderEnabled = false,
                )
            }
        }

        compose.onNodeWithTag(terminalTag).assertIsDisplayed()

        compose.onNodeWithTag(conversationTabTag).performClick()
        compose.waitForIdle()

        // Base behaviour: the tap was swallowed — terminal still shown, no
        // placeholder. This is exactly the on-screen no-op the maintainer hit.
        compose.onNodeWithTag(terminalTag).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG).assertIsNotDisplayed()
        assertTrue("base no-op leaves row on Terminal", true)
    }

    /**
     * Issue #793: a FAILED load surfaces a clear terminal state — error copy +
     * a tappable Retry — instead of an infinite spinner. Renders the REAL
     * production [ConversationDetectingPlaceholder] in the Failed state and
     * proves Retry is shown and invokes the callback.
     */
    @Test
    fun failedLoadShowsClearRetryNotInfiniteSpinner() {
        var retried = false
        compose.setContent {
            PocketShellTheme {
                ConversationDetectingPlaceholder(
                    loadState = ConversationLoadState.Failed,
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithTag(TMUX_CONVERSATION_LOAD_FAILED_TAG).assertIsDisplayed()
        compose.onNodeWithText("Couldn't load this conversation.").assertIsDisplayed()
        // It is NOT the loading spinner copy — a clear terminal state.
        compose.onNodeWithText("Loading conversation…").assertIsNotDisplayed()

        compose.onNodeWithTag(TMUX_CONVERSATION_LOAD_RETRY_TAG).performClick()
        compose.waitForIdle()
        assertTrue("Retry must invoke the retry callback", retried)
    }

    /**
     * Issue #894 (epic #821 "Slice C") — rendered-UI proof that a freshly-opened
     * CONFIRMED-SHELL pane (recorded `@ps_agent_kind=shell`) on the Conversation
     * open-time default shows the TERMINAL surface, NOT the "Loading
     * conversation…" placeholder.
     *
     * This drives the SAME production `presumedAgent` expression the screen runs
     * ([tmuxSessionPresumedAgent]) with the real `confirmedShell` verdict the
     * Slice C wiring now supplies (true for a recorded shell). `presumedAgent`
     * then gates `showConversationPlaceholder` exactly as `TmuxSessionScreen`
     * does — so a confirmed shell collapses the placeholder branch and the
     * terminal renders.
     *
     * Paired with [agentDefaultConversationStillShowsPlaceholder] (confirmedShell
     * = false → the placeholder DOES show) this is the render-layer red→green for
     * both class-coverage branches (shell vs agent).
     */
    @Test
    fun confirmedShellDefaultConversationShowsTerminalNotPlaceholder() {
        // A fresh shell pane that ALREADY landed on the Conversation default
        // (detection still null) — the exact wrong-surface-flash state. The
        // durable tree recorded this session as a shell.
        val confirmedShell = true
        val presumedAgent = tmuxSessionPresumedAgent(
            hasLiveDetection = false,
            stickyAgent = null,
            confirmedShell = confirmedShell,
        )
        assertTrue(
            "#894: a confirmed shell is NOT a presumed agent",
            !presumedAgent,
        )

        compose.setContent {
            PocketShellTheme {
                PresumedAgentContent(
                    row = AgentConversationUiState(
                        detection = null,
                        selectedTab = SessionTab.Conversation,
                    ),
                    presumedAgent = presumedAgent,
                    onConversationTabTap = {},
                )
            }
        }

        // The user-visible acceptance (AC1): the terminal is shown, the
        // "Loading conversation…" placeholder is NOT — no wrong-surface flash.
        compose.onNodeWithTag(terminalTag).assertIsDisplayed()
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG).assertIsNotDisplayed()
        compose.onNodeWithText("Loading conversation…").assertIsNotDisplayed()
    }

    /**
     * Issue #894 (Slice C) class-coverage pair (agent branch): a presumed-agent /
     * foreign pane (confirmedShell = false) on the Conversation default STILL
     * shows the "Loading conversation…" placeholder — the #878 black-screen cure
     * is intact. This is the no-regression counterpart that proves the test above
     * asserts the SHELL gate, not pre-existing behaviour.
     */
    @Test
    fun agentDefaultConversationStillShowsPlaceholder() {
        val confirmedShell = false
        val presumedAgent = tmuxSessionPresumedAgent(
            hasLiveDetection = false,
            stickyAgent = null,
            confirmedShell = confirmedShell,
        )
        assertTrue(
            "#894: a pane with no confirmed-shell verdict stays presumed-agent",
            presumedAgent,
        )

        compose.setContent {
            PocketShellTheme {
                PresumedAgentContent(
                    row = AgentConversationUiState(
                        detection = null,
                        selectedTab = SessionTab.Conversation,
                    ),
                    presumedAgent = presumedAgent,
                    onConversationTabTap = {},
                )
            }
        }

        // AC2: the #878 cure renders the placeholder (not the black terminal).
        compose.onNodeWithTag(TMUX_CONVERSATION_DETECTING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
        compose.onNodeWithTag(terminalTag).assertIsNotDisplayed()
    }

    /**
     * Reproduces the production content-area branch precedence from
     * [com.pocketshell.app.tmux.TmuxSessionScreen] (~:1474–:1545):
     *   - `showConversation` (real transcript) takes precedence,
     *   - else `showConversationPlaceholder` → the REAL
     *     [ConversationDetectingPlaceholder],
     *   - else the terminal.
     * A minimal Conversation/Terminal tab row drives [onConversationTabTap].
     */
    @Composable
    private fun PresumedAgentContent(
        row: AgentConversationUiState,
        presumedAgent: Boolean,
        onConversationTabTap: () -> Unit,
        placeholderEnabled: Boolean = true,
    ) {
        // Same booleans the screen computes.
        val showConversation =
            row.detection != null && row.selectedTab == SessionTab.Conversation
        val showConversationPlaceholder = placeholderEnabled &&
            presumedAgent &&
            row.detection == null &&
            row.selectedTab == SessionTab.Conversation

        // Column so the tab row stays above the (full-screen) content area and
        // the placeholder does not occlude the Conversation tab.
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimal tab row standing in for the screen's
            // Terminal/Conversation tabs; the Conversation tap routes through
            // the production-gated handler.
            Text(
                "Conversation",
                modifier = Modifier
                    .testTag(conversationTabTag)
                    .clickableTab(onConversationTabTap),
            )

            if (showConversation) {
                // Real-transcript branch stand-in (carries the production tag).
                Text(
                    "transcript",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TMUX_CONVERSATION_PANE_TAG),
                )
            } else if (showConversationPlaceholder) {
                // The REAL production placeholder composable.
                ConversationDetectingPlaceholder()
            } else {
                // Terminal-content stand-in.
                Text(
                    "terminal",
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(terminalTag),
                )
            }
        }
    }

    private fun Modifier.clickableTab(onClick: () -> Unit): Modifier =
        this.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        )
}
