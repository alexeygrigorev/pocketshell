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
    fun parsesHarnessXmlBlocksAsSystemNotesInsteadOfRawMessageText() {
        val events = parser.parseLine(
            """{"type":"user","uuid":"u-harness-1","message":{"role":"user","content":"<task-notification>Claude resuming /loop wakeup</task-notification><tool-use-id>toolu_123</tool-use-id><output-file>/tmp/out.log</output-file>show status"}}""",
        )

        assertEquals(4, events.size)
        val notes = events.take(3).map { it as ConversationEvent.SystemNote }
        assertEquals(listOf("task-notification", "tool-use-id", "output-file"), notes.map { it.tag })
        assertEquals("Claude resuming /loop wakeup", notes[0].content)
        assertEquals("toolu_123", notes[1].content)
        assertEquals("/tmp/out.log", notes[2].content)
        val message = events[3] as ConversationEvent.Message
        assertEquals("show status", message.text)
        assertTrue("raw harness XML must not remain in a Message: $message", "<task-notification>" !in message.text)
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

    // ---- Issue #474: per-message timestamps from ISO-8601 `timestamp` ----

    @Test
    fun extractsIsoTimestampForUserMessage() {
        val event = parser.parseLine(
            """{"uuid":"u-ts","timestamp":"2026-05-22T10:00:00Z","message":{"role":"user","content":"hello"}}""",
        ).single() as ConversationEvent.Message

        // 2026-05-22T10:00:00Z == 1779444000000 ms epoch.
        assertEquals(1779444000000L, event.atMillis)
    }

    @Test
    fun extractsIsoTimestampWithMillisForAssistantContent() {
        val events = parser.parseLine(
            """{"uuid":"a-ts","timestamp":"2026-05-20T15:12:00.878Z","message":{"role":"assistant","content":[{"type":"text","text":"done"}]}}""",
        )
        val msg = events.single() as ConversationEvent.Message
        assertEquals(1779289920878L, msg.atMillis)
    }

    @Test
    fun missingTimestampLeavesAtMillisNull() {
        val event = parser.parseLine(
            """{"uuid":"u-no-ts","message":{"role":"user","content":"hi"}}""",
        ).single() as ConversationEvent.Message
        assertEquals(null, event.atMillis)
    }

    // ---- Issue #474: previously-unparsed structural blocks ----

    @Test
    fun parsesScheduledTaskFireSystemEntryAsSystemNote() {
        // The maintainer's "Task Notification"-like block: a real Claude
        // Code top-level system entry, previously dropped entirely.
        val note = parser.parseLine(
            """{"type":"system","subtype":"scheduled_task_fire","content":"Claude resuming /loop wakeup (May 20 5:12pm)","timestamp":"2026-05-20T15:12:00.878Z","uuid":"sys-1"}""",
        ).single() as ConversationEvent.SystemNote

        assertEquals("scheduled_task_fire", note.tag)
        assertEquals("Claude resuming /loop wakeup (May 20 5:12pm)", note.content)
        assertEquals(1779289920878L, note.atMillis)
    }

    @Test
    fun parsesCompactBoundarySystemEntry() {
        val note = parser.parseLine(
            """{"type":"system","subtype":"compact_boundary","content":"Conversation compacted","uuid":"sys-2"}""",
        ).single() as ConversationEvent.SystemNote
        assertEquals("compact_boundary", note.tag)
        assertEquals("Conversation compacted", note.content)
    }

    @Test
    fun synthesizesTurnDurationSystemEntry() {
        val note = parser.parseLine(
            """{"type":"system","subtype":"turn_duration","durationMs":88254,"messageCount":23,"uuid":"sys-3"}""",
        ).single() as ConversationEvent.SystemNote
        assertEquals("turn_duration", note.tag)
        assertEquals("Turn took 88s across 23 messages", note.content)
    }

    @Test
    fun dropsContentlessSystemEntry() {
        val events = parser.parseLine(
            """{"type":"system","subtype":"api_error","content":null,"uuid":"sys-4"}""",
        )
        assertTrue("contentless system entries should be dropped; got $events", events.isEmpty())
    }

    @Test
    fun dropsThinkingContentBlockButKeepsText() {
        // Issue #474: thinking blocks stay dropped (not rendered as SystemNotes)
        // to avoid flooding the conversation with ~135 muted rows per transcript.
        // Compressed thinking display is deferred to the #459 redesign.
        val events = parser.parseLine(
            """{"type":"assistant","uuid":"a-think","message":{"role":"assistant","content":[
              {"type":"thinking","thinking":"Let me reconsider the approach.","signature":"abc"},
              {"type":"text","text":"Here is my answer."}
            ]}}""".trimIndent(),
        )
        assertTrue(
            "thinking blocks should be dropped, not rendered; got $events",
            events.none { it is ConversationEvent.SystemNote && it.tag == "thinking" },
        )
        val msg = events.filterIsInstance<ConversationEvent.Message>().single()
        assertEquals("Here is my answer.", msg.text)
    }
}
