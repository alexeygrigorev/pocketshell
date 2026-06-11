package com.pocketshell.app.conversation

import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationTextFormatting

internal fun ConversationEvent.isHiddenConversationTimelineRow(): Boolean = when (this) {
    is ConversationEvent.SystemNote ->
        // turn_duration was always hidden; #704 req #1 also hides rows whose
        // only content is internal-protocol noise (e.g. a bare <task-id>…),
        // which would otherwise render as a raw, unstyled XML block.
        tag == "turn_duration" ||
            ConversationTextFormatting.isOnlyInternalProtocolNoise(content)
    is ConversationEvent.Message ->
        // A message whose entire text is an internal-protocol wrapper has no
        // user-facing content left once stripped — drop it instead of showing
        // an empty styled block.
        ConversationTextFormatting.isOnlyInternalProtocolNoise(text)
    else -> false
}

internal fun ConversationEvent.timelineTimestamp(): String? =
    ConversationTimeFormat.format(atMillis)

internal fun ConversationEvent.SystemNote.timelineActorLabel(): String =
    when (tag) {
        "scheduled_task_fire",
        "task-notification",
        "compact_boundary",
        "away_summary",
        "stop_hook_summary",
        "informational",
        "api_error",
        "local_command",
        "system-reminder",
        "command-name",
        "command-args",
        "command-message",
        "command-stdout",
        "local-command-stdout",
        "tool-use-id",
        "output-file",
        -> "CLAUDE"
        else -> "SYSTEM"
    }

internal fun ConversationEvent.SystemNote.timelinePreview(): String {
    // #704 req #1: strip internal-protocol noise (e.g. <task-id>…) so the
    // preview never shows a raw XML wrapper inconsistent with styled rows.
    val cleaned = ConversationTextFormatting.stripInternalProtocolNoise(content)
    val firstLine = cleaned.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    return when (tag) {
        "scheduled_task_fire" -> firstLine.ifBlank { "Task notification" }
        "task-notification" -> firstLine.ifBlank { "Task notification" }
        "compact_boundary" -> firstLine.ifBlank { "Conversation compacted" }
        "tool-use-id" -> firstLine.ifBlank { "Tool call metadata" }
        "output-file" -> firstLine.ifBlank { "Output file metadata" }
        "system-reminder" -> firstLine.ifBlank { "System reminder" }
        else -> if (firstLine.isBlank()) tag else "$tag: $firstLine"
    }
}
