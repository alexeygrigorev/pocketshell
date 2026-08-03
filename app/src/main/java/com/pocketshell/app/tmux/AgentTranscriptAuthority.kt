package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.tmux.CommandResponse

/**
 * Owns the transcript-authority half of durable agent-submit acknowledgement.
 *
 * A terminal redraw is not proof that Enter created a new agent turn. This
 * component snapshots confirmed transcript ids before Enter and accepts only a
 * new confirmed user event from the exact live detection source afterward.
 * Keeping this state here also prevents test-only authority overrides from
 * becoming another responsibility of [TmuxSessionViewModel].
 */
internal class AgentTranscriptAuthority(
    private val conversationForPane: (String) -> AgentConversationUiState?,
    private val tailJobActive: (String) -> Boolean,
    private val tailGenerationPresent: (String) -> Boolean,
) {
    internal data class Baseline(
        val detection: AgentDetection,
        val confirmedMatchingIds: Set<String>,
    )

    private val activeOverrides = mutableSetOf<String>()
    private val startingOverrides = mutableSetOf<String>()

    fun setActiveForTest(paneId: String, active: Boolean) {
        if (active) {
            activeOverrides += paneId
            startingOverrides -= paneId
        } else {
            activeOverrides -= paneId
        }
    }

    fun setStartingForTest(paneId: String, starting: Boolean) {
        if (starting) startingOverrides += paneId else startingOverrides -= paneId
    }

    fun baseline(paneId: String, payload: String): Baseline? {
        val conversation = conversationForPane(paneId) ?: return null
        val detection = conversation.detection?.takeIf { it.sourcePath.isNotBlank() } ?: return null
        val active = isActive(paneId, detection)
        val starting = paneId in startingOverrides || tailJobActive(paneId)
        if (!active && !starting) return null
        return Baseline(
            detection = detection,
            confirmedMatchingIds = conversation.events.confirmedUserIds(payload),
        ).also {
            DiagnosticEvents.record(
                "action",
                "agent_submit_transcript_baseline",
                "pane" to paneId,
                "sourceHash" to detection.sourcePath.hashCode().toUInt().toString(16),
                "authorityActive" to active,
                "authorityStarting" to starting,
                "confirmedBaselineCount" to it.confirmedMatchingIds.size,
                "turnoverTimeoutMs" to AGENT_TRANSCRIPT_TURNOVER_TIMEOUT_MS,
            )
        }
    }

    fun timeoutMs(baseline: Baseline?): Long {
        if (baseline != null) return AGENT_TRANSCRIPT_TURNOVER_TIMEOUT_MS
        DiagnosticEvents.record(
            "action",
            "agent_submit_transcript_baseline",
            "authorityActive" to false,
            "authorityStarting" to false,
            "confirmedBaselineCount" to 0,
            "turnoverTimeoutMs" to AGENT_SUBMIT_TURNOVER_TIMEOUT_MS,
        )
        return AGENT_SUBMIT_TURNOVER_TIMEOUT_MS
    }

    fun isActive(paneId: String, detection: AgentDetection): Boolean {
        val conversation = conversationForPane(paneId) ?: return false
        if (conversation.detection != detection || conversation.syncStatus != AgentConversationSyncStatus.Live) {
            return false
        }
        return paneId in activeOverrides || (tailJobActive(paneId) && tailGenerationPresent(paneId))
    }

    fun acknowledged(paneId: String, payload: String, baseline: Baseline): Boolean {
        val conversation = conversationForPane(paneId) ?: return false
        if (conversation.detection != baseline.detection || !isActive(paneId, baseline.detection)) return false
        val confirmed = conversation.events.filterIsInstance<ConversationEvent.Message>().firstOrNull {
            it.isConfirmedTranscriptUser(payload) && it.id !in baseline.confirmedMatchingIds
        } ?: return false
        DiagnosticEvents.record(
            "action",
            "agent_submit_transcript_ack",
            "pane" to paneId,
            "eventId" to confirmed.id,
            "sourceHash" to baseline.detection.sourcePath.hashCode().toUInt().toString(16),
            "agentSessionId" to baseline.detection.sessionId,
            "agent" to baseline.detection.agent.name,
        )
        return true
    }

    suspend fun awaitTurnover(
        identity: AgentSendRuntimeIdentity,
        paneId: String,
        payload: String,
        preEnterFrame: CommandResponse,
        baseline: Baseline?,
        capture: suspend (timeoutMs: Long, scrollbackLines: Int) -> AgentPaneCaptureResult,
        currentClientHash: () -> Int?,
        currentGeneration: () -> Long,
    ) {
        awaitAgentSubmitTurnover(
            identity = identity,
            paneId = paneId,
            payload = payload,
            preEnterFrame = preEnterFrame,
            timeoutMs = timeoutMs(baseline),
            transcriptAuthorityPresent = { baseline?.let { isActive(paneId, it.detection) } == true },
            transcriptAcknowledged = { baseline?.let { acknowledged(paneId, payload, it) } == true },
            capture = capture,
            currentClientHash = currentClientHash,
            currentGeneration = currentGeneration,
        )
    }
}

private fun List<ConversationEvent>.confirmedUserIds(payload: String): Set<String> =
    filterIsInstance<ConversationEvent.Message>()
        .filter { it.isConfirmedTranscriptUser(payload) }
        .mapTo(linkedSetOf()) { it.id }

private fun ConversationEvent.Message.isConfirmedTranscriptUser(payload: String): Boolean =
    role == ConversationRole.User &&
        text == payload &&
        sendState == com.pocketshell.core.agents.MessageSendState.Confirmed &&
        !id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX)
