package com.pocketshell.app.tmux

import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.tmux.TmuxClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Assistant wiring tests for [TmuxSessionViewModel] (issue #266) — the
 * Command-mode voice path now drives the in-app action assistant instead of
 * the deleted CommandPlanner (D22 hard cut). The agent loop / confirm-or-
 * correct logic is exercised directly in
 * [com.pocketshell.app.assistant.AssistantAgentLoopTest] and
 * [com.pocketshell.app.assistant.SessionAssistantControllerTest]; here we
 * verify the tmux ViewModel surfaces a clean error when the assistant is
 * unconfigured (no provider / no SSH deps wired).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelVoiceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newVm(): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
    ).also {
        // Issue #926: pin the seed-IO dispatcher (off-Main hop for the
        // attach/switch/reattach `capture-pane`/`list-panes` IO) to the rule's
        // test Main so any seed round-trip runs inline on the test clock instead
        // of a real `Dispatchers.IO` thread `runTest` cannot drain. Production
        // defaults to `Dispatchers.IO` (off the UI thread).
        it.setSeedIoDispatcherForTest(Dispatchers.Main)
    }

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun assistantStateStartsIdle() {
        val vm = newVm()
        assertTrue(vm.assistantState.value is AssistantUiState.Idle)
    }

    @Test
    fun dictateWithoutConfiguredAssistantSurfacesError() = runTest {
        val vm = newVm()
        vm.dictateToAssistant("show git status")
        advanceUntilIdle()
        // No AssistantLlmClientFactory / SSH deps wired ⇒ a visible error,
        // not a crash and not a silent no-op.
        assertTrue(vm.assistantState.value is AssistantUiState.Error)
    }

    @Test
    fun dismissResetsAssistantToIdle() = runTest {
        val vm = newVm()
        vm.dictateToAssistant("show git status")
        advanceUntilIdle()
        vm.dismissAssistant()
        assertTrue(vm.assistantState.value is AssistantUiState.Idle)
    }
}
