package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.uikit.model.SessionAgentKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordedAgentRouteEvidence(
    val durableSessionKey: String,
    val paneId: String,
    val agentKind: AgentKind,
)

internal fun retainRecordedAgentRouteEvidence(
    existing: RecordedAgentRouteEvidence?,
    durableSessionKey: String?,
    paneId: String?,
): RecordedAgentRouteEvidence? = existing?.takeIf {
    durableSessionKey != null && paneId != null &&
        it.durableSessionKey == durableSessionKey && it.paneId == paneId
}

internal fun recordedAgentRefreshStillCurrent(
    requestToken: Int,
    currentToken: Int,
    requestedSessionKey: String?,
    requestedPaneId: String?,
    currentSessionKey: String?,
    currentPaneId: String?,
): Boolean = requestToken == currentToken &&
    requestedSessionKey != null && requestedPaneId != null &&
    requestedSessionKey == currentSessionKey && requestedPaneId == currentPaneId

internal fun SessionAgentKind?.toRecordedAgentKindOrNull(): AgentKind? = when (this) {
    SessionAgentKind.Claude -> AgentKind.ClaudeCode
    SessionAgentKind.Codex -> AgentKind.Codex
    SessionAgentKind.OpenCode -> AgentKind.OpenCode
    SessionAgentKind.Grok -> AgentKind.GrokBuild
    SessionAgentKind.Shell,
    SessionAgentKind.Probing,
    SessionAgentKind.Exited,
    SessionAgentKind.Unknown,
    null -> null
}

internal data class RecordedAgentRouteRefresh(
    val token: Int,
    val durableSessionKey: String?,
    val paneId: String?,
)

/** Correlates asynchronous option reads with one exact tmux generation/pane. */
internal class RecordedAgentRouteTracker {
    private val token = AtomicInteger(0)
    private val mutableEvidence = MutableStateFlow<RecordedAgentRouteEvidence?>(null)
    val evidence: StateFlow<RecordedAgentRouteEvidence?> = mutableEvidence.asStateFlow()

    fun clear() {
        token.incrementAndGet()
        mutableEvidence.value = null
    }

    fun begin(durableSessionKey: String?, paneId: String?): RecordedAgentRouteRefresh {
        mutableEvidence.value = retainRecordedAgentRouteEvidence(
            mutableEvidence.value,
            durableSessionKey,
            paneId,
        )
        return RecordedAgentRouteRefresh(token.incrementAndGet(), durableSessionKey, paneId)
    }

    fun isCurrent(
        request: RecordedAgentRouteRefresh,
        durableSessionKey: String?,
        paneId: String?,
    ): Boolean = recordedAgentRefreshStillCurrent(
        request.token,
        token.get(),
        request.durableSessionKey,
        request.paneId,
        durableSessionKey,
        paneId,
    )

    fun isLatest(request: RecordedAgentRouteRefresh): Boolean = request.token == token.get()

    fun accept(request: RecordedAgentRouteRefresh, kind: SessionAgentKind?) {
        mutableEvidence.value = kind.toRecordedAgentKindOrNull()?.let { agentKind ->
            RecordedAgentRouteEvidence(
                durableSessionKey = requireNotNull(request.durableSessionKey),
                paneId = requireNotNull(request.paneId),
                agentKind = agentKind,
            )
        }
    }
}

internal fun recordedRouteKey(
    target: TmuxSessionViewModel.ConnectionTarget?,
    pane: TmuxPaneState?,
): String? = target?.let {
    durableTmuxSessionKey(
        it.hostId,
        pane?.sessionId ?: it.tmuxSessionId,
        pane?.sessionCreated ?: it.sessionCreated,
    )
}
