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

    @Test
    fun parsesPayloadWrappedUserAndAgentMessages() {
        val user = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":"add a smoke test"},"timestamp":"2026-05-29T10:00:00Z"}""",
        ).single() as ConversationEvent.Message
        val agent = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"agent_message","message":"I added the test."}}""",
        ).single() as ConversationEvent.Message

        assertEquals(ConversationRole.User, user.role)
        assertEquals("add a smoke test", user.text)
        assertEquals(ConversationRole.Assistant, agent.role)
        assertEquals("I added the test.", agent.text)
    }

    @Test
    fun parsesPayloadWrappedResponseItems() {
        val message = parser.parseLine(
            """{"type":"response_item","payload":{"type":"message","id":"m2","role":"assistant","content":[{"type":"output_text","text":"Done from payload"}]}}""",
        ).single() as ConversationEvent.Message
        val call = parser.parseLine(
            """{"type":"response_item","payload":{"type":"function_call","id":"fc_1","call_id":"call_payload","name":"shell","arguments":"{\"cmd\":\"pwd\"}"}}""",
        ).single() as ConversationEvent.ToolCall
        val output = parser.parseLine(
            """{"type":"response_item","payload":{"type":"function_call_output","call_id":"call_payload","output":"ok"}}""",
        ).single() as ConversationEvent.ToolResult
        val reasoning = parser.parseLine(
            """{"type":"response_item","payload":{"type":"reasoning","summary":[{"text":"Checked the fixture schema."}]}}""",
        ).single() as ConversationEvent.SystemNote

        assertEquals("m2", message.id)
        assertEquals(ConversationRole.Assistant, message.role)
        assertEquals("Done from payload", message.text)
        assertEquals("call:call_payload", call.id)
        assertEquals("shell", call.name)
        assertEquals("result:call_payload", output.id)
        assertEquals("call_payload", output.toolCallId)
        assertEquals("ok", output.output)
        assertEquals("reasoning", reasoning.tag)
        assertEquals("Checked the fixture schema.", reasoning.content)
    }
}
