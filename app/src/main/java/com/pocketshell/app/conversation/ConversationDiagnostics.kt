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
                "streaming" to event.streaming,
                "sendState" to event.sendState.name,
            ) + event.text.diagnosticTextFields(
                bytesKey = "textBytes",
                lineCountKey = "lineCount",
                charsKey = "textChars",
                exactKey = "textMetricsExact",
            )
            is ConversationEvent.ToolCall -> common += listOf(
                "agent" to event.agent.name,
                "toolName" to event.name,
                "hasPairedResult" to (pairedToolResult != null),
            ) + event.input.diagnosticTextFields(
                bytesKey = "inputBytes",
                lineCountKey = "inputLineCount",
                charsKey = "inputChars",
                exactKey = "inputMetricsExact",
            ) + pairedToolResult?.output.diagnosticTextFields(
                bytesKey = "resultBytes",
                lineCountKey = "resultLineCount",
                charsKey = "resultChars",
                exactKey = "resultMetricsExact",
            )
            is ConversationEvent.ToolResult -> common += listOf(
                "agent" to event.agent.name,
                "toolCallId" to event.toolCallId,
            ) + event.output.diagnosticTextFields(
                bytesKey = "outputBytes",
                lineCountKey = "lineCount",
                charsKey = "outputChars",
                exactKey = "outputMetricsExact",
            )
            is ConversationEvent.SystemNote -> common += listOf(
                "agent" to event.agent.name,
                "tag" to event.tag,
            ) + event.content.diagnosticTextFields(
                bytesKey = "contentBytes",
                lineCountKey = "lineCount",
                charsKey = "contentChars",
                exactKey = "contentMetricsExact",
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

    private fun String?.diagnosticTextFields(
        bytesKey: String,
        lineCountKey: String,
        charsKey: String,
        exactKey: String,
    ): List<Pair<String, Any?>> {
        if (this == null) {
            return listOf(
                bytesKey to null,
                lineCountKey to null,
                charsKey to null,
                exactKey to null,
            )
        }
        val exact = length <= MaxExactDiagnosticChars
        val fields = mutableListOf<Pair<String, Any?>>(
            bytesKey to if (exact) toByteArray(Charsets.UTF_8).size else null,
            lineCountKey to if (exact) exactLineCount() else null,
            charsKey to length,
            exactKey to exact,
        )
        if (!exact) {
            fields += "${lineCountKey}AtLeast" to sampledLineCount(MaxExactDiagnosticChars)
            fields += "${exactKey}CharLimit" to MaxExactDiagnosticChars
        }
        return fields
    }

    private fun String.exactLineCount(): Int =
        if (isEmpty()) 0 else count { it == '\n' } + 1

    private fun String.sampledLineCount(maxChars: Int): Int {
        if (isEmpty()) return 0
        var lines = 1
        val limit = minOf(length, maxChars)
        for (index in 0 until limit) {
            if (this[index] == '\n') lines += 1
        }
        return lines
    }

    private const val MaxExactDiagnosticChars = 100_000
}
