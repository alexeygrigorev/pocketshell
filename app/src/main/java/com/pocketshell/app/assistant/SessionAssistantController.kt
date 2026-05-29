package com.pocketshell.app.assistant

import com.pocketshell.core.assistant.AssistantLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reusable holder for one session screen's assistant run (issue #266).
 *
 * Extracted from the big [com.pocketshell.app.session.SessionViewModel] /
 * [com.pocketshell.app.tmux.TmuxSessionViewModel] so both routes share the
 * exact same confirm-or-correct state machine without duplicating it. The
 * owning view model supplies its [CoroutineScope] (its `viewModelScope`) and
 * a factory that builds a fresh [AssistantLlmClient] + [AssistantActions] per
 * run (so the latest provider key / connection params flow in).
 *
 * Replaces the deleted `VoiceCommandReview` planner state (D22 hard cut): the
 * Command-mode dictation transcript now lands in [start] instead of
 * `planVoiceCommand`.
 */
internal class SessionAssistantController(
    private val scope: CoroutineScope,
    /** Build (client, actions, traceSink, installId) for a run, or null when unconfigured. */
    private val sessionFactory: () -> AssistantRunDeps?,
) {

    /** Per-run dependencies resolved lazily so each run sees fresh config. */
    data class AssistantRunDeps(
        val client: AssistantLlmClient,
        val actions: AssistantActions,
        val traceSink: AssistantTraceSink,
        val installId: String,
        val sessionId: String?,
    )

    private val _state = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var pendingDecision: CompletableDeferred<AssistantAgentLoop.Decision>? = null

    /** Start a fresh assistant run for [transcript]. No-op while one is active. */
    fun start(transcript: String) {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return
        if (runJob?.isActive == true) return

        val deps = sessionFactory()
        if (deps == null) {
            _state.value = AssistantUiState.Error(
                "No assistant provider configured. Add an API key in Settings → Assistant.",
            )
            return
        }

        _state.value = AssistantUiState.Thinking(cleaned)
        runJob = scope.launch {
            val loop = AssistantAgentLoop(
                client = deps.client,
                actions = deps.actions,
                traceSink = deps.traceSink,
                installId = deps.installId,
                sessionId = deps.sessionId,
            )
            val outcome = loop.run(cleaned, confirmGate = ::awaitDecision)
            _state.value = when (outcome) {
                is AssistantAgentLoop.Outcome.Answer -> AssistantUiState.Done(outcome.text)
                is AssistantAgentLoop.Outcome.Cancelled -> AssistantUiState.Done(outcome.text)
                is AssistantAgentLoop.Outcome.Failed -> AssistantUiState.Error(outcome.message)
            }
        }
    }

    private suspend fun awaitDecision(
        candidate: AssistantAgentLoop.Candidate,
    ): AssistantAgentLoop.Decision {
        val deferred = CompletableDeferred<AssistantAgentLoop.Decision>()
        pendingDecision = deferred
        _state.value = AssistantUiState.Confirming(candidate)
        val decision = deferred.await()
        pendingDecision = null
        if (decision !is AssistantAgentLoop.Decision.Cancel) {
            _state.value = AssistantUiState.Thinking("")
        }
        return decision
    }

    fun confirm() {
        pendingDecision?.complete(AssistantAgentLoop.Decision.Confirm)
    }

    fun correct(correction: String) {
        val text = correction.trim()
        if (text.isEmpty()) return
        pendingDecision?.complete(AssistantAgentLoop.Decision.Correct(text))
    }

    fun cancel() {
        pendingDecision?.complete(AssistantAgentLoop.Decision.Cancel)
    }

    fun dismiss() {
        runJob?.cancel()
        runJob = null
        pendingDecision?.complete(AssistantAgentLoop.Decision.Cancel)
        pendingDecision = null
        _state.value = AssistantUiState.Idle
    }
}

/**
 * UI state for the assistant strip surfaced by the session screens.
 */
internal sealed interface AssistantUiState {
    /** No assistant activity. */
    data object Idle : AssistantUiState

    /** Loop is calling the model / running inspect tools. */
    data class Thinking(val transcript: String) : AssistantUiState

    /** A mutating candidate is awaiting Confirm / Correct / Cancel. */
    data class Confirming(val candidate: AssistantAgentLoop.Candidate) : AssistantUiState

    /** The run finished with a final assistant message. */
    data class Done(val message: String) : AssistantUiState

    /** The run failed. */
    data class Error(val message: String) : AssistantUiState
}
