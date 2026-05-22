package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeParserTest {
    private val parser = ClaudeCodeParser()

    @Test
    fun parsesUserTextMessage() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"check logs"}}""",
        )

        assertEquals(
            ConversationEvent.Message(
                id = "u1",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.User,
                text = "check logs",
            ),
            events.single(),
        )
    }

    @Test
    fun parsesAssistantTextToolCallAndToolResult() {
        val events = parser.parseLine(
            """
            {"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[
              {"type":"text","text":"I'll inspect it."},
              {"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"kubectl logs"}},
              {"type":"tool_result","tool_use_id":"toolu_1","content":"timeout","is_error":true}
            ]}}
            """.trimIndent(),
        )

        assertEquals(3, events.size)
        assertEquals("I'll inspect it.", (events[0] as ConversationEvent.Message).text)
        assertEquals("Bash", (events[1] as ConversationEvent.ToolCall).name)
        assertTrue((events[2] as ConversationEvent.ToolResult).isError)
    }
}
