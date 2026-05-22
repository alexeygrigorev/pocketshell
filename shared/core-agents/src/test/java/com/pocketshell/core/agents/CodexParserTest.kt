package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexParserTest {
    private val parser = CodexParser()

    @Test
    fun parsesResponseMessageItem() {
        val events = parser.parseLine(
            """{"type":"response_item","item":{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"Done"}]}}""",
        )

        assertEquals(
            ConversationEvent.Message(
                id = "m1",
                agent = AgentKind.Codex,
                role = ConversationRole.Assistant,
                text = "Done",
            ),
            events.single(),
        )
    }

    @Test
    fun parsesFunctionCallAndOutput() {
        val call = parser.parseLine(
            """{"type":"response_item","item":{"type":"function_call","call_id":"call_1","name":"shell","arguments":"{\"cmd\":\"ls\"}"}}""",
        ).single() as ConversationEvent.ToolCall
        val output = parser.parseLine(
            """{"type":"response_item","item":{"type":"function_call_output","call_id":"call_1","output":"README.md"}}""",
        ).single() as ConversationEvent.ToolResult

        assertEquals("call:call_1", call.id)
        assertEquals("result:call_1", output.id)
        assertEquals("shell", call.name)
        assertEquals("call_1", output.toolCallId)
        assertEquals("README.md", output.output)
    }
}
