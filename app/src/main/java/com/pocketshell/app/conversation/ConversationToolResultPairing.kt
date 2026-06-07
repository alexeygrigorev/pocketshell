package com.pocketshell.app.conversation

import com.pocketshell.core.agents.ConversationEvent

internal data class ToolResultPairing(
    val resultsByCallId: Map<String, ConversationEvent.ToolResult>,
    val pairedResultIds: Set<String>,
    val callIdsByResultId: Map<String, String>,
)

/**
 * Build the UI's tool-call/result pairing index.
 *
 * Explicit parser links win first. Some transcripts can still surface an
 * unlinked result immediately after its call, so the fallback pairs only the
 * visible adjacent shape `ToolCall` -> `ToolResult` when that result has no
 * visible explicit parent and the call does not already have a result. That is
 * deterministic and intentionally conservative: non-adjacent or many-to-one
 * ambiguities stay as standalone result rows.
 */
internal fun List<ConversationEvent>.toolResultPairing(): ToolResultPairing {
    val eventsById = associateBy { it.id }
    val resultsByCallId = linkedMapOf<String, ConversationEvent.ToolResult>()
    val pairedResultIds = linkedSetOf<String>()
    val callIdsByResultId = linkedMapOf<String, String>()

    for (result in filterIsInstance<ConversationEvent.ToolResult>()) {
        val toolCallId = result.toolCallId ?: continue
        if (eventsById[toolCallId] is ConversationEvent.ToolCall) {
            if (resultsByCallId.putIfAbsent(toolCallId, result) == null) {
                pairedResultIds += result.id
                callIdsByResultId[result.id] = toolCallId
            }
        }
    }

    for (index in 0 until lastIndex) {
        val call = this[index] as? ConversationEvent.ToolCall ?: continue
        val result = this[index + 1] as? ConversationEvent.ToolResult ?: continue
        if (call.id in resultsByCallId) continue
        if (result.id in pairedResultIds) continue
        if (result.hasExplicitVisibleParentToolCall(eventsById)) continue
        resultsByCallId[call.id] = result
        pairedResultIds += result.id
        callIdsByResultId[result.id] = call.id
    }

    return ToolResultPairing(
        resultsByCallId = resultsByCallId,
        pairedResultIds = pairedResultIds,
        callIdsByResultId = callIdsByResultId,
    )
}

/**
 * Identify every tool call that has no matching tool result yet so the
 * compact row can show a running status glyph without expanding details.
 */
internal fun runningToolCallIds(
    events: List<ConversationEvent>,
    pairing: ToolResultPairing = events.toolResultPairing(),
): Set<String> {
    val resolved = pairing.resultsByCallId.keys
    return events
        .filterIsInstance<ConversationEvent.ToolCall>()
        .map { it.id }
        .filter { it !in resolved }
        .toSet()
}

internal data class ConversationFilterResult(
    val events: List<ConversationEvent>,
    val searchExpandedToolCallIds: Set<String> = emptySet(),
)

internal fun filterConversationRows(
    events: List<ConversationEvent>,
    query: String,
    pairing: ToolResultPairing = events.toolResultPairing(),
): ConversationFilterResult {
    val q = query.trim()
    if (q.isBlank()) {
        return ConversationFilterResult(
            events = events.filterNot { it is ConversationEvent.ToolResult && it.id in pairing.pairedResultIds },
        )
    }

    val resultMatchedParentToolCallIds = events
        .filterIsInstance<ConversationEvent.ToolResult>()
        .mapNotNull { result ->
            pairing.callIdsByResultId[result.id]
                ?.takeIf { result.conversationTimelineSearchText().contains(q, ignoreCase = true) }
        }
        .toSet()
    val directlyMatchedToolCallIds = events
        .filterIsInstance<ConversationEvent.ToolCall>()
        .filter { it.conversationTimelineSearchText().contains(q, ignoreCase = true) }
        .mapTo(mutableSetOf()) { it.id }

    val searchExpandedToolCallIds = directlyMatchedToolCallIds + resultMatchedParentToolCallIds
    return ConversationFilterResult(
        events = events.mapNotNull { event ->
            when (event) {
                is ConversationEvent.ToolCall ->
                    event.takeIf {
                        event.id in resultMatchedParentToolCallIds ||
                            event.conversationTimelineSearchText().contains(q, ignoreCase = true)
                    }
                is ConversationEvent.ToolResult ->
                    event.takeIf {
                        event.id !in pairing.pairedResultIds &&
                            event.conversationTimelineSearchText().contains(q, ignoreCase = true)
                    }
                else -> event.takeIf { event.conversationTimelineSearchText().contains(q, ignoreCase = true) }
            }
        },
        searchExpandedToolCallIds = searchExpandedToolCallIds,
    )
}

private fun ConversationEvent.ToolResult.hasExplicitVisibleParentToolCall(
    eventsById: Map<String, ConversationEvent>,
): Boolean = toolCallId?.let { eventsById[it] is ConversationEvent.ToolCall } == true

private fun ConversationEvent.conversationTimelineSearchText(): String = when (this) {
    is ConversationEvent.Message -> text
    is ConversationEvent.ToolCall -> "$name $input"
    is ConversationEvent.ToolResult -> output
    is ConversationEvent.SystemNote -> "$tag $content"
}
