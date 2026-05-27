package com.pocketshell.app.tmux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #179: connected coverage for the "agent hint chip should not
 * re-appear on every JSONL event" regression. The on-device test is
 * the production composition of [TmuxAgentHintBanner] (which owns the
 * auto-dismiss timer) bound to a real [TmuxSessionViewModel] (which
 * owns the dismissed-set state machine).
 *
 * The workflow under test:
 *
 *  1. Synthesize a detected Claude agent on pane `%0` via the
 *     production test seam — equivalent to the first JSONL detection
 *     cycle that fires `startAgentConversationForPane` in the live
 *     code path.
 *  2. Assert the muted bottom banner is displayed.
 *  3. Tap the chip's `X` dismiss action.
 *  4. Append a synthetic `ConversationEvent.Message` for the same pane
 *     — mimicking the `agentRepository.tailEventsFromLine` callback
 *     that the production code wires onto every parsed JSONL row.
 *  5. Re-seed the conversation with the same detection (mirrors a
 *     reconcile re-running `startAgentConversationForPane` for the
 *     same pane, which is what kept resurrecting the hint pre-#179).
 *  6. Assert the chip is gone and stays gone.
 *
 * Compose UI-only test: no Hilt, no live SSH/tmux. The chip's display
 * predicate, view-model wiring, and dismissed-set are the actual
 * production code, swapped via test seams only for the data fed in.
 */
@RunWith(AndroidJUnit4::class)
class TmuxAgentHintDismissUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val factoryScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    private fun newViewModel(): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
    )

    private fun detection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun pane(paneId: String = "%0"): TmuxPaneState = TmuxPaneState(
        paneId = paneId,
        windowId = "@0",
        sessionId = "\$0",
        title = "shell",
        terminalState = TerminalSurfaceState(),
    )

    @Test
    fun hintBannerAppearsOnDetectionThenStaysGoneAfterDismissAndJsonlAppend() {
        val vm = newViewModel()
        val pane0 = pane()

        // Step 1: synthesize first detection.
        vm.startAgentConversationForTest(pane0.paneId, detection())

        compose.setContent {
            val conversations by vm.agentConversations.collectAsState()
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Box(modifier = Modifier.fillMaxSize()) {
                    TmuxAgentHintBanner(
                        pane = pane0,
                        conversation = conversations[pane0.paneId],
                        onOpen = { paneId ->
                            vm.selectSessionTab(paneId, SessionTab.Conversation)
                        },
                        onDismiss = vm::dismissAgentHint,
                    )
                }
            }
        }

        // Step 2: chip is displayed.
        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Claude Code session detected").assertIsDisplayed()

        // Step 3: tap X to dismiss.
        compose.onNodeWithText("X").performClick()
        compose.waitForIdle()

        // Chip is gone after dismiss.
        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertDoesNotExist()
        assertFalse(vm.agentConversations.value[pane0.paneId]!!.hintVisible)

        // Step 4: simulate a JSONL append (assistant message) — this
        // is the path that historically resurrected the chip.
        vm.appendAgentEventsForTest(
            pane0.paneId,
            listOf(
                ConversationEvent.Message(
                    id = "assistant-1",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 1L,
                    role = ConversationRole.Assistant,
                    text = "still here",
                ),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertDoesNotExist()
        assertFalse(
            "JSONL append after dismiss must not resurrect the hint",
            vm.agentConversations.value[pane0.paneId]!!.hintVisible,
        )

        // Step 5: simulate a re-detection cycle — production calls
        // [startAgentConversationForPane] again after reconciles. The
        // dismissed-set must keep the chip hidden.
        vm.startAgentConversationForTest(pane0.paneId, detection())
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertDoesNotExist()
        assertFalse(
            "re-detection after dismiss must not resurrect the hint",
            vm.agentConversations.value[pane0.paneId]!!.hintVisible,
        )

        // Sanity: the dismissed-set has the expected key recorded.
        assertTrue(
            "explicit dismiss must populate the dismissed-set",
            vm.dismissedHintKeysForTest().any { it.contains("abc") },
        )
    }

    @Test
    fun hintBannerHiddenAfterVisitingConversationTab() {
        val vm = newViewModel()
        val pane0 = pane()

        vm.startAgentConversationForTest(pane0.paneId, detection())

        compose.setContent {
            val conversations by vm.agentConversations.collectAsState()
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                Box(modifier = Modifier.fillMaxSize()) {
                    TmuxAgentHintBanner(
                        pane = pane0,
                        conversation = conversations[pane0.paneId],
                        onOpen = { paneId ->
                            vm.selectSessionTab(paneId, SessionTab.Conversation)
                        },
                        onDismiss = vm::dismissAgentHint,
                    )
                }
            }
        }

        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertIsDisplayed()

        // Tap the chip label (onOpen) -> selectSessionTab(Conversation)
        compose.onNodeWithText("Claude Code session detected").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertDoesNotExist()
        assertFalse(vm.agentConversations.value[pane0.paneId]!!.hintVisible)

        // Re-seed the same detection to mimic a follow-up reconcile.
        // Bounce selectedTab back to Terminal first so the banner's
        // gating predicate (`selectedTab == Terminal`) does not by
        // itself suppress the chip — we want to assert the
        // dismissed-set is the active suppressor.
        vm.selectSessionTab(pane0.paneId, SessionTab.Terminal)
        vm.startAgentConversationForTest(pane0.paneId, detection())
        compose.waitForIdle()

        compose.onNodeWithTag(TMUX_AGENT_HINT_TAG).assertDoesNotExist()
        assertFalse(
            "visiting Conversation tab must persist as a dismissal across re-detection",
            vm.agentConversations.value[pane0.paneId]!!.hintVisible,
        )
    }
}
