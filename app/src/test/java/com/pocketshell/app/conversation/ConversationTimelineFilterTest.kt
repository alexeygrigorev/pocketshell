package com.pocketshell.app.conversation

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1267 (Part 2): the byte-clamp truncation marker (#1225) is rendered as
 * a [ConversationEvent.SystemNote]. With `showSystemNotes = false` the whole
 * system-notes class is filtered out — so a truncated giant line silently
 * vanishes AGAIN, the exact failure #1225 set out to avoid. The marker must
 * survive the filter regardless of the setting.
 */
class ConversationTimelineFilterTest {
    private fun message(id: String, text: String): ConversationEvent.Message =
        ConversationEvent.Message(
            id = id,
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = text,
        )

    private fun systemNote(id: String, tag: String, content: String): ConversationEvent.SystemNote =
        ConversationEvent.SystemNote(
            id = id,
            agent = AgentKind.ClaudeCode,
            tag = tag,
            content = content,
        )

    private fun truncationMarker(id: String = "ps-truncated-line-0"): ConversationEvent.SystemNote =
        systemNote(
            id = id,
            tag = TRUNCATED_LINE_SYSTEM_NOTE_TAG,
            content = "[A 5242880-byte transcript line was too large to load and was truncated.]",
        )

    @Test
    fun truncationMarkerSurvivesTheSystemNotesFilterWhenNotesAreOff() {
        val events = listOf(
            message("u1", "here is a screenshot"),
            truncationMarker(),
            systemNote("sn1", "system-reminder", "an ordinary system note"),
            message("a1", "got it"),
        )

        val visible = conversationTimelineVisibleEvents(events, showSystemNotes = false)

        // The truncation marker STAYS (RED without the tag-exemption: it was
        // filtered out with all other system notes).
        assertTrue(
            "the truncation marker must survive the system-notes filter; got $visible",
            visible.any { it is ConversationEvent.SystemNote && it.tag == TRUNCATED_LINE_SYSTEM_NOTE_TAG },
        )
        // The ordinary system note is still hidden (the setting is honoured for
        // everything except the always-visible truncation affordance).
        assertFalse(
            "an ordinary system note must still be hidden when notes are off; got $visible",
            visible.any { it is ConversationEvent.SystemNote && it.tag == "system-reminder" },
        )
        // Both real messages survive.
        assertEquals(
            listOf("here is a screenshot", "got it"),
            visible.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
    }

    @Test
    fun systemNotesVisibleKeepsEveryNoteIncludingTheMarker() {
        val events = listOf(
            message("u1", "hi"),
            truncationMarker(),
            systemNote("sn1", "system-reminder", "an ordinary system note"),
        )

        val visible = conversationTimelineVisibleEvents(events, showSystemNotes = true)

        assertTrue(
            "with notes on, the ordinary note is shown",
            visible.any { it is ConversationEvent.SystemNote && it.tag == "system-reminder" },
        )
        assertTrue(
            "with notes on, the truncation marker is shown",
            visible.any { it is ConversationEvent.SystemNote && it.tag == TRUNCATED_LINE_SYSTEM_NOTE_TAG },
        )
    }

    @Test
    fun internalProtocolNoiseRowsAreDroppedEvenWithNotesOn() {
        // The truncation exemption must not resurrect the internal-protocol-noise
        // hiding that isHiddenConversationTimelineRow already enforces.
        val events = listOf(
            message("u1", "hi"),
            systemNote("turn", "turn_duration", "42ms"),
        )

        val visible = conversationTimelineVisibleEvents(events, showSystemNotes = true)

        assertFalse(
            "turn_duration noise is always hidden; got $visible",
            visible.any { it is ConversationEvent.SystemNote && it.tag == "turn_duration" },
        )
    }
}
