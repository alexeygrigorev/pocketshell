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

    // ------------------------------------------------------------------
    // Issue #176: XML-tagged system blocks become SystemNote events.
    // ------------------------------------------------------------------

    @Test
    fun parsesUserSystemReminderAsSystemNote() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u-sys-1","message":{"role":"user","content":"<system-reminder>\nThe date has changed. Today's date is now 2026-05-27.\n</system-reminder>"}}""",
        )

        assertEquals(1, events.size)
        val note = events.single() as ConversationEvent.SystemNote
        assertEquals("system-reminder", note.tag)
        assertEquals("The date has changed. Today's date is now 2026-05-27.", note.content.trim())
        assertEquals(AgentKind.ClaudeCode, note.agent)
    }

    @Test
    fun parsesCommandNameAndStdoutBlocksAsDistinctSystemNotes() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u-cmd-1","message":{"role":"user","content":"<command-name>/login</command-name><command-args>--scope=team</command-args><local-command-stdout>logged in as alice</local-command-stdout>"}}""",
        )

        assertEquals(3, events.size)
        val tags = events.map { (it as ConversationEvent.SystemNote).tag }
        assertEquals(listOf("command-name", "command-args", "local-command-stdout"), tags)
        assertEquals("/login", (events[0] as ConversationEvent.SystemNote).content)
        assertEquals("--scope=team", (events[1] as ConversationEvent.SystemNote).content)
        assertEquals("logged in as alice", (events[2] as ConversationEvent.SystemNote).content)
    }

    @Test
    fun splitsProseAndSystemNoteIntoSeparateEvents() {
        val events = parser.parseLine(
            """{"type":"assistant","uuid":"a-mix-1","message":{"role":"assistant","content":"Sure, I'll check the logs.\n<system-reminder>The date has changed.</system-reminder>\nLet me start now."}}""",
        )

        assertEquals(3, events.size)
        val first = events[0] as ConversationEvent.Message
        val note = events[1] as ConversationEvent.SystemNote
        val tail = events[2] as ConversationEvent.Message

        assertEquals("Sure, I'll check the logs.", first.text)
        assertEquals(ConversationRole.Assistant, first.role)
        assertEquals("system-reminder", note.tag)
        assertEquals("The date has changed.", note.content)
        assertEquals("Let me start now.", tail.text)
        assertEquals(ConversationRole.Assistant, tail.role)
    }

    @Test
    fun parsesContentArrayTextPartWithEmbeddedSystemReminder() {
        val events = parser.parseLine(
            """
            {"type":"user","uuid":"u-arr-1","message":{"role":"user","content":[
              {"type":"text","text":"<system-reminder>be careful</system-reminder>real prose"}
            ]}}
            """.trimIndent(),
        )

        assertEquals(2, events.size)
        val note = events[0] as ConversationEvent.SystemNote
        val msg = events[1] as ConversationEvent.Message
        assertEquals("system-reminder", note.tag)
        assertEquals("be careful", note.content)
        assertEquals("real prose", msg.text)
        assertEquals(ConversationRole.User, msg.role)
    }

    @Test
    fun emitsPlainMessageWhenNoSystemTagsPresent() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u-plain","message":{"role":"user","content":"This sentence has a <stray> tag that is not in the allow-list."}}""",
        )

        val msg = events.single() as ConversationEvent.Message
        assertEquals(
            "This sentence has a <stray> tag that is not in the allow-list.",
            msg.text,
        )
    }

    @Test
    fun systemNoteIdsAreStableAndUnique() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u-stable","message":{"role":"user","content":"<system-reminder>one</system-reminder><command-name>/two</command-name>"}}""",
        )

        val ids = events.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue("expected ids to include the base uuid; got $ids", ids.all { it.startsWith("u-stable") })
    }
}
