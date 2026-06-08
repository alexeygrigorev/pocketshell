package com.pocketshell.app.conversation

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationDiagnosticsTest {
    @Test
    fun messageRowToggleFieldsRecordShapeWithoutTranscriptText() {
        val text = "deploy failed with sk-secret\nsecond line"
        val event = ConversationEvent.Message(
            id = "message-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = text,
        )

        val fields = ConversationDiagnostics.rowToggleFields(
            mode = "tmux",
            paneId = "%0",
            event = event,
            expanded = true,
        ).toMap()

        assertEquals("tmux", fields["mode"])
        assertEquals("%0", fields["paneId"])
        assertEquals("message", fields["rowType"])
        assertEquals(true, fields["expanded"])
        assertEquals("Assistant", fields["role"])
        assertEquals(text.toByteArray(Charsets.UTF_8).size, fields["textBytes"])
        assertEquals(2, fields["lineCount"])
        assertFalse(fields.containsValue(text))
    }

    @Test
    fun toolCallRowToggleFieldsRecordSizesWithoutInputOrOutput() {
        val input = """{"command":"cat ~/.ssh/id_rsa"}"""
        val output = "private output\nline 2"
        val toolCall = ConversationEvent.ToolCall(
            id = "tool-1",
            agent = AgentKind.ClaudeCode,
            name = "Bash",
            input = input,
        )
        val result = ConversationEvent.ToolResult(
            id = "result-1",
            agent = AgentKind.ClaudeCode,
            toolCallId = "tool-1",
            output = output,
        )

        val fields = ConversationDiagnostics.rowToggleFields(
            mode = "raw_ssh",
            event = toolCall,
            expanded = false,
            pairedToolResult = result,
        ).toMap()

        assertEquals("tool_call", fields["rowType"])
        assertEquals("Bash", fields["toolName"])
        assertEquals(input.toByteArray(Charsets.UTF_8).size, fields["inputBytes"])
        assertEquals(true, fields["hasPairedResult"])
        assertEquals(output.toByteArray(Charsets.UTF_8).size, fields["resultBytes"])
        assertEquals(2, fields["resultLineCount"])
        assertFalse(fields.containsValue(input))
        assertFalse(fields.containsValue(output))
    }

    @Test
    fun hugeToolResultRowToggleFieldsDoNotRequireExactPayloadScan() {
        val output = buildString {
            repeat(101_000) { index ->
                append(if (index % 20 == 0) '\n' else 'x')
            }
            append("private output tail")
        }
        val toolCall = ConversationEvent.ToolCall(
            id = "tool-1",
            agent = AgentKind.Codex,
            name = "exec_command",
            input = """{"cmd":"./gradlew connectedDebugAndroidTest"}""",
        )
        val result = ConversationEvent.ToolResult(
            id = "result-1",
            agent = AgentKind.Codex,
            toolCallId = "tool-1",
            output = output,
        )

        val fields = ConversationDiagnostics.rowToggleFields(
            mode = "tmux",
            event = toolCall,
            expanded = true,
            pairedToolResult = result,
        ).toMap()

        assertEquals(null, fields["resultBytes"])
        assertEquals(null, fields["resultLineCount"])
        assertEquals(output.length, fields["resultChars"])
        assertEquals(false, fields["resultMetricsExact"])
        assertEquals(100_000, fields["resultMetricsExactCharLimit"])
        assertEquals(5_001, fields["resultLineCountAtLeast"])
        assertFalse(fields.containsValue(output))
    }
}
