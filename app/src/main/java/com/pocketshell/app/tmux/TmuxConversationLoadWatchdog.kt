package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.session.ConversationLoadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #793 / #1603: the Conversation-tab first-paint load watchdog, extracted
 * out of [TmuxSessionViewModel] (D28 god-object hygiene). It bounds the
 * "Loading conversation…" spinner and — Issue #1603 — AUTO-RETRIES a load that
 * raced a still-settling post-reattach transport before surfacing the Failed
 * dead-end ("Couldn't load this conversation."), so a slow first load after a
 * reattach recovers on its own with NO manual reconnect. The auto-retry is
 * bounded by [CONVERSATION_LOAD_MAX_AUTO_RETRIES] so a genuinely-absent /
 * unreachable conversation still resolves to the clear Failed terminal state
 * instead of spinning forever.
 *
 * The VM wires the per-pane state accessors + the load-restart / flip-to-Failed
 * effects as lambdas; this class owns the per-pane timer jobs, the auto-retry
 * budget, and the timeout→retry-or-fail decision.
 */
internal class TmuxConversationLoadWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: () -> Long,
    private val loadStateOf: (String) -> ConversationLoadState?,
    private val isSessionLive: () -> Boolean,
    private val paneFor: (String) -> TmuxPaneState?,
    // Re-run the full detect → window-read → tail load for the pane (clears the
    // detection dedup key so the re-detection is not short-circuited).
    private val onRetryLoad: (TmuxPaneState) -> Unit,
    // Flip the row to the Failed terminal state IFF it is still Loading.
    private val onExhaustedFailed: (String) -> Unit,
) {
    private val jobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val attempts: MutableMap<String, Int> = ConcurrentHashMap()

    /** Auto-retries already spent on the CURRENT open (diagnostics only). */
    fun attemptsSpent(paneId: String): Int = attempts[paneId] ?: 0

    /**
     * Arm for a FRESH open (or an explicit manual Retry): resets the auto-retry
     * budget so the row gets the full [CONVERSATION_LOAD_MAX_AUTO_RETRIES]
     * recovery attempts. The auto-retry path re-arms via [rearm], which PRESERVES
     * the budget so the recursion is bounded.
     */
    fun arm(paneId: String) {
        attempts.remove(paneId)
        rearm(paneId)
    }

    private fun rearm(paneId: String) {
        jobs.remove(paneId)?.cancel()
        jobs[paneId] = scope.launch {
            delay(timeoutMs())
            if (loadStateOf(paneId) != ConversationLoadState.Loading) return@launch
            val spent = attempts[paneId] ?: 0
            val pane = paneFor(paneId)
            if (!isSessionLive() || pane == null || spent >= CONVERSATION_LOAD_MAX_AUTO_RETRIES) {
                // No live session, or the bounded budget is spent (the
                // genuinely-absent / unreachable-log case) → clear Failed.
                onExhaustedFailed(paneId)
                return@launch
            }
            val attempt = spent + 1
            attempts[paneId] = attempt
            DiagnosticEvents.record(
                "recoverable",
                "tmux_agent_conversation_load_auto_retry",
                "pane" to paneId,
                "attempt" to attempt,
                "maxAttempts" to CONVERSATION_LOAD_MAX_AUTO_RETRIES,
                "timeoutMs" to timeoutMs(),
            )
            // Give the still-settling transport progressively more room, then
            // re-run the load and re-arm (the row stays Loading throughout, so the
            // user never sees the dead-end mid-recovery).
            delay(CONVERSATION_LOAD_RETRY_BACKOFF_MS * attempt)
            if (loadStateOf(paneId) != ConversationLoadState.Loading) return@launch
            if (!isSessionLive()) {
                onExhaustedFailed(paneId)
                return@launch
            }
            onRetryLoad(pane)
            rearm(paneId)
        }
    }

    fun cancel(paneId: String) {
        jobs.remove(paneId)?.cancel()
        attempts.remove(paneId)
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        attempts.clear()
    }
}
