package com.pocketshell.app.tmux

import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole

/**
 * Issue #1555 (D28 / file-size hygiene ratchet): pure, VM-state-free
 * conversation/agent-detection helpers split out of the [TmuxSessionViewModel]
 * god-object into cohesive same-package top-level functions. Extraction only —
 * byte-identical bodies, zero behaviour change; the VM calls them unqualified
 * (same package) exactly as it called the former private members.
 */

/**
 * The text a conversation event contributes to port-offer scanning, or `null`
 * when the event carries nothing scannable (assistant messages, tool results,
 * and system notes carry offers; tool calls and blank text do not).
 */
internal fun ConversationEvent.portOfferText(): String? = when (this) {
    is ConversationEvent.Message -> text.takeIf {
        role == ConversationRole.Assistant && it.isNotBlank()
    }
    is ConversationEvent.ToolResult -> output.takeIf { it.isNotBlank() }
    is ConversationEvent.SystemNote -> content.takeIf { it.isNotBlank() }
    is ConversationEvent.ToolCall -> null
}

/**
 * Issue #495: two detections describe the same live agent session when
 * the agent kind and the log source path match. Confidence and
 * sessionId can drift between a seeded reconnect verdict and the live
 * re-detection without meaning "a different agent" — treating that as a
 * new agent would discard the user's tab choice.
 */
internal fun sameAgentSource(left: AgentDetection?, right: AgentDetection?): Boolean =
    left != null &&
        right != null &&
        left.agent == right.agent &&
        left.sourcePath == right.sourcePath
