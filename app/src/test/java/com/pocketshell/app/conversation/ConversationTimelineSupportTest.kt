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

    private fun systemNote(tag: String, content: String): ConversationEvent.SystemNote =
        ConversationEvent.SystemNote(
            id = "note-$tag",
            agent = AgentKind.ClaudeCode,
            tag = tag,
            content = content,
        )
}
