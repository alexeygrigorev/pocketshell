package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun parsesIsoTimestampFromEnvelope() {
        // Issue #474: real Codex rollout JSONL carries an ISO-8601
        // `timestamp` on the envelope; it must reach the model's atMillis.
        val user = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":"add a smoke test"},"timestamp":"2026-05-22T10:01:00Z"}""",
        ).single() as ConversationEvent.Message
        // 2026-05-22T10:01:00Z == 1779444060000 ms epoch.
        assertEquals(1779444060000L, user.atMillis)
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

    // ---------------------------------------------------------------------
    // Issue #838: the Codex AGENTS.md / <INSTRUCTIONS> first user turn must be
    // emitted as a collapsed-by-default SystemNote, NOT a full-weight user
    // Message. The full text is preserved in the note (collapse, not remove).
    // ---------------------------------------------------------------------

    @Test
    fun collapsesAgentsMdInstructionsUserMessageToSystemNote() {
        val raw =
            "AGENTS.md instructions for /home/alexey/git/ai-shipping-labs\n" +
                "<INSTRUCTIONS>\n" +
                "# Agent Notes\n" +
                "## Development Process\n" +
                "- Before continuing development work, read _docs/PROCESS.md.\n" +
                "## Production Data Access\n" +
                "- Production URL: https://aishippinglabs.com.\n" +
                "</INSTRUCTIONS>"
        val note = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":${raw.toJsonString()}}}""",
        ).single() as ConversationEvent.SystemNote

        assertEquals(CodexParser.AGENTS_INSTRUCTIONS_TAG, note.tag)
        // Collapse, NOT remove: the full instruction wall stays in the note.
        assertEquals(raw, note.content)
        assertTrue(note.content.contains("Production Data Access"))
    }

    @Test
    fun collapsesAgentsMdInstructionsResponseItemUserMessage() {
        // The screenshot shape: a `type:"message"` item with role "user".
        val raw =
            "AGENTS.md instructions for /home/alexey/git/ai-shipping-labs\n" +
                "<INSTRUCTIONS>\nAgent Notes ...\n</INSTRUCTIONS>"
        val note = parser.parseLine(
            """{"type":"response_item","item":{"type":"message","id":"u1","role":"user","content":${
                "[{\"type\":\"input_text\",\"text\":${raw.toJsonString()}}]"
            }}}""",
        ).single() as ConversationEvent.SystemNote

        assertEquals("u1", note.id)
        assertEquals(CodexParser.AGENTS_INSTRUCTIONS_TAG, note.tag)
        assertEquals(raw, note.content)
    }

    @Test
    fun genuineFirstUserPromptIsNotCollapsed() {
        // A real user prompt that merely mentions AGENTS.md must render normally.
        val message = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":"please update AGENTS.md to mention the new test"}}""",
        ).single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals("please update AGENTS.md to mention the new test", message.text)
    }

    @Test
    fun genuineUserInstructionsPasteIsNotCollapsed() {
        // Detection requires BOTH the preamble AND the <INSTRUCTIONS> wrapper.
        // A genuine user turn that legitimately pastes an <INSTRUCTIONS> block
        // (without the synthetic Codex preamble) must render as a normal
        // Message — never swallowed/mislabeled as the AGENTS.md injection.
        val raw = "rewrite this xml please: <INSTRUCTIONS>do X then Y</INSTRUCTIONS>"
        val message = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":${raw.toJsonString()}}}""",
        ).single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals(raw, message.text)
    }

    @Test
    fun userMessageStartingWithInstructionsTagIsNotCollapsed() {
        // A user turn that *opens* with <INSTRUCTIONS> but lacks the preamble
        // is still a real message, not the injection.
        val raw = "<INSTRUCTIONS>\nuse high-capability model settings\n</INSTRUCTIONS>"
        val message = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":${raw.toJsonString()}}}""",
        ).single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals(raw, message.text)
    }

    @Test
    fun preambleWithoutInstructionsBlockIsNotCollapsed() {
        // The preamble alone (no <INSTRUCTIONS> wrapper) does not match — both
        // markers are required, so a user prompt that happens to start with the
        // preamble text but is otherwise prose renders normally.
        val raw = "AGENTS.md instructions for the new repo are missing — can you draft them?"
        val message = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"user_message","message":${raw.toJsonString()}}}""",
        ).single() as ConversationEvent.Message
        assertEquals(ConversationRole.User, message.role)
        assertEquals(raw, message.text)
    }

    @Test
    fun assistantInstructionsMessageIsNotCollapsed() {
        // Only the synthetic USER injection is collapsed; an assistant turn
        // that happens to echo <INSTRUCTIONS> stays a normal Message.
        val message = parser.parseLine(
            """{"type":"event_msg","payload":{"type":"agent_message","message":"Here is the block: <INSTRUCTIONS> ... </INSTRUCTIONS>"}}""",
        ).single() as ConversationEvent.Message
        assertEquals(ConversationRole.Assistant, message.role)
    }
}

private fun String.toJsonString(): String = org.json.JSONObject.quote(this)
