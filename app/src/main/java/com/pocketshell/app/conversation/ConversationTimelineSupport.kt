package com.pocketshell.app.conversation

import com.pocketshell.core.agents.ConversationEvent

internal fun ConversationEvent.isHiddenConversationTimelineRow(): Boolean =
    this is ConversationEvent.SystemNote && tag == "turn_duration"

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
    val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
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
