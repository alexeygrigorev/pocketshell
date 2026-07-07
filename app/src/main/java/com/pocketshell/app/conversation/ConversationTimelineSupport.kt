package com.pocketshell.app.conversation

import com.pocketshell.core.agents.CodexParser
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationTextFormatting

/**
 * Issue #1225/#1267: the [ConversationEvent.SystemNote.tag] used for the
 * over-cap transcript-line truncation marker. Unlike ordinary system notes,
 * this marker is an ALWAYS-VISIBLE truncation affordance: it MUST survive the
 * `showSystemNotes = false` filter, because a truncated giant line is a
 * *dropped user message* the user needs to know about regardless of the
 * system-notes preference (the exact silently-dropped-again failure #1225 set
 * out to avoid). Kept in lockstep with the tag emitted by
 * `AgentConversationRepository.parseTranscriptTailLines` /
 * `parseOpenCodeRowsWithTruncation`.
 */
internal const val TRUNCATED_LINE_SYSTEM_NOTE_TAG: String = "truncated"

/**
 * Issue #176/#1267: the events the conversation timeline actually renders,
 * given the `showSystemNotes` preference. Internal-protocol-noise rows are
 * always dropped; when [showSystemNotes] is false, ordinary
 * [ConversationEvent.SystemNote] rows are hidden too — EXCEPT the truncation
 * marker ([TRUNCATED_LINE_SYSTEM_NOTE_TAG]), which stays visible so a
 * byte-clamped giant line never becomes a silently dropped message.
 *
 * Pure + side-effect free so it is unit-tested directly (the composable just
 * `remember`s the result).
 */
internal fun conversationTimelineVisibleEvents(
    events: List<ConversationEvent>,
    showSystemNotes: Boolean,
): List<ConversationEvent> {
    val timelineEvents = events.filterNot { it.isHiddenConversationTimelineRow() }
    if (showSystemNotes) return timelineEvents
    return timelineEvents.filterNot {
        it is ConversationEvent.SystemNote && it.tag != TRUNCATED_LINE_SYSTEM_NOTE_TAG
    }
}

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
        // Issue #838: the Codex AGENTS.md / <INSTRUCTIONS> injection arrives as
        // a synthetic user turn; label it CODEX so it reads as agent context,
        // not the maintainer's prose.
        CodexParser.AGENTS_INSTRUCTIONS_TAG -> "CODEX"
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
        // Issue #838: collapsed one-liner for the AGENTS.md injection. A fixed
        // label (not the raw first line, which is the noisy "AGENTS.md
        // instructions for <path>" preamble) keeps the collapsed row tidy.
        CodexParser.AGENTS_INSTRUCTIONS_TAG -> "AGENTS.md instructions"
        "scheduled_task_fire" -> firstLine.ifBlank { "Task notification" }
        "task-notification" -> firstLine.ifBlank { "Task notification" }
        "compact_boundary" -> firstLine.ifBlank { "Conversation compacted" }
        "tool-use-id" -> firstLine.ifBlank { "Tool call metadata" }
        "output-file" -> firstLine.ifBlank { "Output file metadata" }
        "system-reminder" -> firstLine.ifBlank { "System reminder" }
        else -> if (firstLine.isBlank()) tag else "$tag: $firstLine"
    }
}
