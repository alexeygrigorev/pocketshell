package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2193: Grok Build ACP `updates.jsonl` must coalesce streaming chunks
 * into one user Message, one assistant Message, plus tool call/result. A
 * one-event-per-chunk implementation must fail the exact counts below (G6).
 */
class GrokBuildParserTest {

    @Test
    fun parseLinesCoalescesChunksAndDropsThoughtsAndHooks() {
        val lines = listOf(
            acpLine(
                sessionUpdate = "user_message_chunk",
                contentText = "Look at this wrapper.",
                eventId = "evt-user-1",
            ),
            acpLine(
                sessionUpdate = "agent_thought_chunk",
                contentText = "I should inspect the repo first.",
                eventId = "evt-thought-1",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "I'll start by checking ",
                eventId = "evt-asst-1",
                promptId = "prompt-1",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "how other agents are wrapped.",
                eventId = "evt-asst-2",
                promptId = "prompt-1",
            ),
            hookLine(),
            toolCallLine(
                toolCallId = "call-1",
                title = "run_terminal_command",
                command = "ls -la",
            ),
            toolResultLine(
                toolCallId = "call-1",
                output = "total 12\ndrwxr-xr-x 3 me me 4096 Jan 1 .",
            ),
        )

        val events = GrokBuildReader().parseLines(lines)

        assertEquals(
            "one user, one assistant, one tool call, one tool result — " +
                "not one event per ACP chunk",
            4,
            events.size,
        )
        val user = events[0] as ConversationEvent.Message
        val assistant = events[1] as ConversationEvent.Message
        val toolCall = events[2] as ConversationEvent.ToolCall
        val toolResult = events[3] as ConversationEvent.ToolResult

        assertEquals(ConversationRole.User, user.role)
        assertEquals("Look at this wrapper.", user.text)
        assertEquals(AgentKind.GrokBuild, user.agent)

        assertEquals(ConversationRole.Assistant, assistant.role)
        assertEquals(
            "I'll start by checking how other agents are wrapped.",
            assistant.text,
        )
        assertEquals(AgentKind.GrokBuild, assistant.agent)

        assertEquals("run_terminal_command", toolCall.name)
        assertTrue(toolCall.input.contains("ls -la"))
        assertEquals("call-1", toolCall.id)

        assertEquals("call-1", toolResult.toolCallId)
        assertEquals("total 12\ndrwxr-xr-x 3 me me 4096 Jan 1 .", toolResult.output)
        assertTrue(events.none { it is ConversationEvent.SystemNote })
    }

    @Test
    fun parseLinesKeepsMultiTurnUsersDistinctWithoutPromptId() {
        // Live user_message_chunk rows carry eventId only — no promptId /
        // promptIndex. G6 mutation: User messageId falling back to literal
        // "0" (`promptIndex ?: promptId ?: "0"`) collapses both turns onto
        // grok:user:<session>:0 and the 2-User count / distinct-text
        // asserts fail.
        val lines = listOf(
            acpLine(
                sessionUpdate = "user_message_chunk",
                contentText = "First question.",
                eventId = "evt-user-1",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "Answer one ",
                eventId = "evt-asst-1a",
                promptId = "prompt-1",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "part A.",
                eventId = "evt-asst-1b",
                promptId = "prompt-1",
            ),
            toolCallLine(
                toolCallId = "call-1",
                title = "run_terminal_command",
                command = "pwd",
            ),
            toolResultLine(
                toolCallId = "call-1",
                output = "/workspace",
            ),
            acpLine(
                sessionUpdate = "user_message_chunk",
                contentText = "Second question.",
                eventId = "evt-user-2",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "Answer two ",
                eventId = "evt-asst-2a",
                promptId = "prompt-2",
            ),
            acpLine(
                sessionUpdate = "agent_message_chunk",
                contentText = "part B.",
                eventId = "evt-asst-2b",
                promptId = "prompt-2",
            ),
        )

        val events = GrokBuildReader().parseLines(lines)
        val users = events.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User }
        val assistants = events.filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.Assistant }
        val toolCalls = events.filterIsInstance<ConversationEvent.ToolCall>()
        val toolResults = events.filterIsInstance<ConversationEvent.ToolResult>()

        assertEquals("two user turns, not one collapsed Message", 2, users.size)
        assertEquals("two assistant replies (promptId-coalesced)", 2, assistants.size)
        assertEquals(1, toolCalls.size)
        assertEquals(1, toolResults.size)

        assertEquals("First question.", users[0].text)
        assertEquals("Second question.", users[1].text)
        assertEquals("Answer one part A.", assistants[0].text)
        assertEquals("Answer two part B.", assistants[1].text)
        assertTrue(
            "user ids must differ; literal 0 fallback makes them identical",
            users[0].id != users[1].id,
        )
        assertTrue(users.none { it.id.endsWith(":0") })
        assertEquals("run_terminal_command", toolCalls[0].name)
        assertEquals("/workspace", toolResults[0].output)
    }

    @Test
    fun parseLineDropsThoughtsAndHooksOnTheirOwn() {
        val parser = GrokBuildParser()
        assertEquals(emptyList<ConversationEvent>(), parser.parseLine(hookLine()))
        assertEquals(
            emptyList<ConversationEvent>(),
            parser.parseLine(
                acpLine(
                    sessionUpdate = "agent_thought_chunk",
                    contentText = "thinking",
                    eventId = "t1",
                ),
            ),
        )
    }

    private fun acpLine(
        sessionUpdate: String,
        contentText: String,
        eventId: String,
        promptId: String? = null,
    ): String {
        val prompt = if (promptId != null) {
            """, "promptId": "$promptId""""
        } else {
            ""
        }
        return """
            {"timestamp": 1787072184, "method": "session/update", "params": {
              "sessionId": "sess-1",
              "update": {
                "sessionUpdate": "$sessionUpdate",
                "content": {"type": "text", "text": "$contentText"}
              },
              "_meta": {"eventId": "$eventId"$prompt}
            }}
        """.trimIndent().replace("\n", "")
    }

    private fun hookLine(): String = """
        {"timestamp": 1787072195, "method": "_x.ai/session/update", "params": {
          "sessionId": "sess-1",
          "update": {
            "sessionUpdate": "hook_execution",
            "event_name": "pre_tool_use",
            "tool_name": "run_terminal_command",
            "runs": [{"name": "hook", "status": {"status": "success"}}]
          }
        }}
    """.trimIndent().replace("\n", "")

    private fun toolCallLine(toolCallId: String, title: String, command: String): String = """
        {"timestamp": 1787072195, "method": "session/update", "params": {
          "sessionId": "sess-1",
          "update": {
            "sessionUpdate": "tool_call",
            "toolCallId": "$toolCallId",
            "title": "$title",
            "rawInput": {"command": "$command", "description": "list files"}
          }
        }}
    """.trimIndent().replace("\n", "")

    private fun toolResultLine(toolCallId: String, output: String): String {
        val escaped = output.replace("\n", "\\n")
        return """
            {"timestamp": 1787072196, "method": "session/update", "params": {
              "sessionId": "sess-1",
              "update": {
                "sessionUpdate": "tool_call_update",
                "toolCallId": "$toolCallId",
                "status": "completed",
                "content": [{"type": "content", "content": {"type": "text", "text": "$escaped"}}]
              }
            }}
        """.trimIndent().replace("\n", "")
    }
}
