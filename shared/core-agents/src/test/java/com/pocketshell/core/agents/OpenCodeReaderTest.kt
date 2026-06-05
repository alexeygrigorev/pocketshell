package com.pocketshell.core.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OpenCodeReaderTest {
    @Test
    fun parsesSingleJsonlLineForTailing() {
        val events = OpenCodeReader().parseLine(
            """{"id":"u1","role":"user","content":"check","createdAtMillis":12}""",
        )

        val msg = events.single() as ConversationEvent.Message
        assertEquals("u1", msg.id)
        assertEquals(ConversationRole.User, msg.role)
        assertEquals("check", msg.text)
        assertEquals(12L, msg.atMillis)
    }

    @Test
    fun parseLineTolerantOfBlankAndMalformedRows() {
        val reader = OpenCodeReader()
        assertTrue(reader.parseLine("").isEmpty())
        assertTrue(reader.parseLine("  ").isEmpty())
        assertTrue(reader.parseLine("{not json").isEmpty())
        assertTrue(reader.parseLine("""{"role":"system","content":"x"}""").isEmpty())
        assertTrue(reader.parseLine("""{"id":"x","role":"user","content":""}""").isEmpty())
    }

    @Test
    fun parsesSqliteMessageAndPartRowsIntoMessages() {
        val events = OpenCodeReader().parseSqliteRows(
            listOf(
                OpenCodeSqliteRow(
                    messageId = "m-user",
                    messageData = """{"role":"user"}""",
                    messageCreatedAtMillis = 10L,
                    partId = "p-user",
                    partData = """{"type":"text","text":"check the app"}""",
                    partCreatedAtMillis = 11L,
                ),
                OpenCodeSqliteRow(
                    messageId = "m-assistant",
                    messageData = """{"role":"assistant"}""",
                    messageCreatedAtMillis = 20L,
                    partId = "p-assistant",
                    partData = """{"type":"text","text":"The app is healthy."}""",
                    partCreatedAtMillis = 21L,
                ),
            ),
        )

        assertEquals(2, events.size)
        val user = events[0] as ConversationEvent.Message
        val assistant = events[1] as ConversationEvent.Message
        assertEquals("p-user", user.id)
        assertEquals(ConversationRole.User, user.role)
        assertEquals("check the app", user.text)
        assertEquals(11L, user.atMillis)
        assertEquals(ConversationRole.Assistant, assistant.role)
        assertEquals("The app is healthy.", assistant.text)
    }

    @Test
    fun parsesSqliteJsonArrayAndLineDelimitedRows() {
        val arrayOutput = """
            [
              {
                "message_id":"m1",
                "message_data":"{\"role\":\"user\"}",
                "message_time_created":100,
                "part_id":"p1",
                "part_data":"{\"type\":\"text\",\"text\":\"hello\"}"
              }
            ]
        """.trimIndent()
        val arrayEvents = OpenCodeReader().parseSqliteJsonRows(arrayOutput)

        assertEquals("hello", (arrayEvents.single() as ConversationEvent.Message).text)

        val lineOutput = """
            {"messageId":"m2","messageRole":"assistant","messageCreatedAtMillis":200,"partId":"p2","partData":"{\"type\":\"output_text\",\"text\":\"hi\"}"}
            {"messageId":"m3","messageRole":"user","messageContent":"fallback text","createdAtMillis":300}
        """.trimIndent()
        val lineEvents = OpenCodeReader().parseSqliteJsonRows(lineOutput)

        assertEquals(2, lineEvents.size)
        assertEquals("hi", (lineEvents[0] as ConversationEvent.Message).text)
        assertEquals("fallback text", (lineEvents[1] as ConversationEvent.Message).text)
        assertEquals(300L, lineEvents[1].atMillis)
    }

    @Test
    fun parsesSqliteToolReasoningAndToolResultParts() {
        val events = OpenCodeReader().parseSqliteRows(
            listOf(
                OpenCodeSqliteRow(
                    messageId = "m1",
                    messageData = """{"role":"assistant"}""",
                    partId = "reason",
                    partData = """{"type":"reasoning","text":"Need to inspect files."}""",
                ),
                OpenCodeSqliteRow(
                    messageId = "m1",
                    messageData = """{"role":"assistant"}""",
                    partId = "tool",
                    partData = """{"type":"tool_use","id":"call-1","name":"Bash","input":{"command":"ls"}}""",
                ),
                OpenCodeSqliteRow(
                    messageId = "m1",
                    messageData = """{"role":"assistant"}""",
                    partId = "result",
                    partData = """{"type":"tool_result","tool_use_id":"call-1","content":"ok"}""",
                ),
            ),
        )

        assertEquals(3, events.size)
        val reasoning = events[0] as ConversationEvent.SystemNote
        val tool = events[1] as ConversationEvent.ToolCall
        val result = events[2] as ConversationEvent.ToolResult
        assertEquals("reasoning", reasoning.tag)
        assertEquals("Need to inspect files.", reasoning.content)
        assertEquals("call-1", tool.id)
        assertEquals("Bash", tool.name)
        assertEquals("ls", JSONObject(tool.input).getString("command"))
        assertEquals("call-1", result.toolCallId)
        assertEquals("ok", result.output)
    }

    @Test
    fun sqliteParsingToleratesMalformedUnknownAndSchemaVariation() {
        val events = OpenCodeReader().parseSqliteRows(
            listOf(
                OpenCodeSqliteRow(
                    messageId = "bad-part",
                    messageData = """{"role":"user"}""",
                    partId = "bad-json",
                    partData = """{"type":"text","text":""",
                ),
                OpenCodeSqliteRow(
                    messageId = "unknown-role",
                    messageData = """{"role":"system"}""",
                    messageContent = "hidden",
                ),
                OpenCodeSqliteRow(
                    messageId = "fallback",
                    messageRole = "assistant",
                    messageContent = "visible fallback",
                ),
                OpenCodeSqliteRow(
                    messageId = "unknown-part",
                    messageData = """{"role":"assistant"}""",
                    partId = "unknown",
                    partData = """{"kind":"delta","content":[{"type":"text","text":"nested text"}]}""",
                ),
            ),
        )

        assertEquals(2, events.size)
        assertEquals("visible fallback", (events[0] as ConversationEvent.Message).text)
        assertEquals("nested text", (events[1] as ConversationEvent.Message).text)
    }
}
