package com.pocketshell.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #859 (Slice D): the `agent_card` data-message parser, sibling to
 * [ResetPushPayloadTest]. Proves the app accepts a well-formed agent-card push,
 * falls back gracefully on optional copy, and rejects non-agent-card /
 * missing-session messages (the deep-link can't route without a session).
 */
class AgentCardPushPayloadTest {

    @Test
    fun fromData_fullPayload_parsesAllFields() {
        val payload = AgentCardPushPayload.fromData(
            mapOf(
                "type" to "agent_card",
                "session" to "claude-main",
                "host" to "agent-box.example",
                "card_id" to "checklist",
                "card_type" to "checklist",
                "title" to "Release steps",
                "summary" to "checklist 1/3 checked",
                "card_key" to "claude-main|checklist|abcd",
            ),
        )!!
        assertEquals("claude-main", payload.session)
        assertEquals("agent-box.example", payload.host)
        assertEquals("checklist", payload.cardId)
        assertEquals("checklist", payload.cardType)
        assertEquals("Release steps", payload.title)
        assertEquals("checklist 1/3 checked", payload.summary)
        assertEquals("claude-main|checklist|abcd", payload.cardKey)
    }

    @Test
    fun fromData_missingCopy_fallsBackToTypeDerivedDefaults() {
        val payload = AgentCardPushPayload.fromData(
            mapOf(
                "type" to "agent_card",
                "session" to "work",
                "card_id" to "checklist",
                "card_type" to "checklist",
            ),
        )!!
        assertEquals("Checklist", payload.title)
        assertEquals("Tap to open the session feed.", payload.summary)
        // No card_key -> deterministic fallback from session|card_id so a retry
        // still de-dups.
        assertEquals("work|checklist", payload.cardKey)
        // No host -> empty (the app resolves the host itself / falls back home).
        assertEquals("", payload.host)
    }

    @Test
    fun fromData_unknownType_defaultsCardTypeAndTitle() {
        val payload = AgentCardPushPayload.fromData(
            mapOf(
                "type" to "agent_card",
                "session" to "work",
            ),
        )!!
        assertEquals("card", payload.cardType)
        assertEquals("Card", payload.title)
    }

    @Test
    fun fromData_nonAgentCardType_returnsNull() {
        assertNull(
            AgentCardPushPayload.fromData(
                mapOf("type" to "usage_reset", "session" to "work"),
            ),
        )
    }

    @Test
    fun fromData_missingType_returnsNull() {
        assertNull(AgentCardPushPayload.fromData(mapOf("session" to "work")))
    }

    @Test
    fun fromData_missingSession_returnsNull() {
        // Without a session the notification can't deep-link → dropped.
        assertNull(
            AgentCardPushPayload.fromData(
                mapOf("type" to "agent_card", "card_id" to "checklist"),
            ),
        )
    }

    @Test
    fun fromData_blankSession_returnsNull() {
        assertNull(
            AgentCardPushPayload.fromData(
                mapOf("type" to "agent_card", "session" to "   "),
            ),
        )
    }
}
