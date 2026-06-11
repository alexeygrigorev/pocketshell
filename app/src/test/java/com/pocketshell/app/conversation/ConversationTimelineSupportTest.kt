package com.pocketshell.app.conversation

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTimelineSupportTest {

    @Test
    fun hidesTurnDurationRowsFromTimeline() {
        val note = systemNote(tag = "turn_duration", content = "Turn took 9s across 1303 messages")

        assertTrue(note.isHiddenConversationTimelineRow())
    }

    @Test
    fun keepsHarnessNotesButNormalizesThemToClaudeRows() {
        val task = systemNote(tag = "task-notification", content = "Claude resuming /loop wakeup")
        val output = systemNote(tag = "output-file", content = "/tmp/result.txt")

        assertFalse(task.isHiddenConversationTimelineRow())
        assertEquals("CLAUDE", task.timelineActorLabel())
        assertEquals("Claude resuming /loop wakeup", task.timelinePreview())
        assertEquals("CLAUDE", output.timelineActorLabel())
        assertEquals("/tmp/result.txt", output.timelinePreview())
    }

    @Test
    fun hidesSystemNoteWhoseContentIsOnlyInternalProtocolNoise() {
        // #704 req #1: a CLAUDE row whose content is a bare <task-id> hash had
        // no user value and rendered as raw, unstyled XML — drop it entirely.
        val note = systemNote(
            tag = "task-notification",
            content = "<task-id>a1887b43e9b725929</task-id>",
        )

        assertTrue(note.isHiddenConversationTimelineRow())
    }

    @Test
    fun stripsInternalProtocolNoiseFromSystemNotePreview() {
        val note = systemNote(
            tag = "task-notification",
            content = "Resuming /loop\n<task-id>a1887b43e9b725929</task-id>",
        )

        assertFalse(note.isHiddenConversationTimelineRow())
        assertEquals("Resuming /loop", note.timelinePreview())
    }

    @Test
    fun hidesMessageWhoseTextIsOnlyInternalProtocolNoise() {
        val message = ConversationEvent.Message(
            id = "m1",
            agent = AgentKind.ClaudeCode,
            role = com.pocketshell.core.agents.ConversationRole.Assistant,
            text = "<task-id>a415fb6992433733a</task-id>",
        )

        assertTrue(message.isHiddenConversationTimelineRow())
    }

    @Test
    fun keepsOrdinaryMessageVisible() {
        val message = ConversationEvent.Message(
            id = "m2",
            agent = AgentKind.ClaudeCode,
            role = com.pocketshell.core.agents.ConversationRole.Assistant,
            text = "here is your answer",
        )

        assertFalse(message.isHiddenConversationTimelineRow())
    }

    private fun systemNote(tag: String, content: String): ConversationEvent.SystemNote =
        ConversationEvent.SystemNote(
            id = "note-$tag",
            agent = AgentKind.ClaudeCode,
            tag = tag,
            content = content,
        )
}
