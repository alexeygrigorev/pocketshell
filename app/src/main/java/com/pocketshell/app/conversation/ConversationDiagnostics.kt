package com.pocketshell.app.conversation

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.core.agents.ConversationEvent

internal object ConversationDiagnostics {
    fun recordTabSwitch(
        mode: String,
        fromTab: String,
        toTab: String,
        hasConversation: Boolean,
        paneId: String? = null,
        eventCount: Int? = null,
        syncStatus: String? = null,
    ) {
        DiagnosticEvents.record(
            "action",
            "conversation_terminal_tab_switch",
            "mode" to mode,
            "paneId" to paneId,
            "fromTab" to fromTab,
            "toTab" to toTab,
            "direction" to "${fromTab.lowercase()}_to_${toTab.lowercase()}",
            "hasConversation" to hasConversation,
            "eventCount" to eventCount,
            "syncStatus" to syncStatus,
        )
    }

    fun recordRowToggle(
        mode: String,
        event: ConversationEvent,
        expanded: Boolean,
        paneId: String? = null,
        pairedToolResult: ConversationEvent.ToolResult? = null,
    ) {
        DiagnosticEvents.record(
            "action",
            "conversation_transcript_row_toggle",
            *rowToggleFields(
                mode = mode,
                event = event,
                expanded = expanded,
                paneId = paneId,
                pairedToolResult = pairedToolResult,
            ),
        )
    }

    fun rowToggleFields(
        mode: String,
        event: ConversationEvent,
        expanded: Boolean,
        paneId: String? = null,
        pairedToolResult: ConversationEvent.ToolResult? = null,
    ): Array<Pair<String, Any?>> {
        val common = mutableListOf<Pair<String, Any?>>(
            "mode" to mode,
            "paneId" to paneId,
            "rowId" to event.id,
            "rowType" to event.diagnosticRowType(),
            "expanded" to expanded,
        )
        when (event) {
            is ConversationEvent.Message -> common += listOf(
                "role" to event.role.name,
                "agent" to event.agent.name,
                "textBytes" to event.text.utf8Bytes(),
                "lineCount" to event.text.lineCount(),
                "streaming" to event.streaming,
                "sendState" to event.sendState.name,
            )
            is ConversationEvent.ToolCall -> common += listOf(
                "agent" to event.agent.name,
                "toolName" to event.name,
                "inputBytes" to event.input.utf8Bytes(),
                "hasPairedResult" to (pairedToolResult != null),
                "resultBytes" to pairedToolResult?.output?.utf8Bytes(),
                "resultLineCount" to pairedToolResult?.output?.lineCount(),
            )
            is ConversationEvent.ToolResult -> common += listOf(
                "agent" to event.agent.name,
                "toolCallId" to event.toolCallId,
                "outputBytes" to event.output.utf8Bytes(),
                "lineCount" to event.output.lineCount(),
            )
            is ConversationEvent.SystemNote -> common += listOf(
                "agent" to event.agent.name,
                "tag" to event.tag,
                "contentBytes" to event.content.utf8Bytes(),
                "lineCount" to event.content.lineCount(),
            )
        }
        return common.toTypedArray()
    }

    private fun ConversationEvent.diagnosticRowType(): String = when (this) {
        is ConversationEvent.Message -> "message"
        is ConversationEvent.ToolCall -> "tool_call"
        is ConversationEvent.ToolResult -> "tool_result"
        is ConversationEvent.SystemNote -> "system_note"
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    private fun String.lineCount(): Int =
        if (isEmpty()) 0 else count { it == '\n' } + 1
}
