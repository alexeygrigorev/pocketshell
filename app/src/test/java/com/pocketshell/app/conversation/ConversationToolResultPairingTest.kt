package com.pocketshell.app.conversation

import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationToolResultPairingTest {

    @Test
    fun explicitToolCallIdPairsResultAndHidesSeparateResultRow() {
        val events = listOf(
            toolCall("call-1"),
            toolResult("result-1", toolCallId = "call-1"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")

        assertEquals("result-1", pairing.resultsByCallId["call-1"]?.id)
        assertEquals(setOf("result-1"), pairing.pairedResultIds)
        assertEquals(listOf("call-1"), filtered.events.map { it.id })
        assertFalse("call-1" in runningToolCallIds(events, pairing))
    }

    @Test
    fun adjacentUnlinkedToolResultPairsWithPreviousToolCall() {
        val events = listOf(
            toolCall("call-1"),
            toolResult("result-1", output = "needle-output"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")
        val searched = filterConversationRows(events, query = "needle-output")

        assertEquals("result-1", pairing.resultsByCallId["call-1"]?.id)
        assertEquals(listOf("call-1"), filtered.events.map { it.id })
        assertEquals(listOf("call-1"), searched.events.map { it.id })
        assertEquals(setOf("call-1"), searched.searchExpandedToolCallIds)
        assertFalse("call-1" in runningToolCallIds(events, pairing))
    }

    @Test
    fun nonAdjacentUnlinkedToolResultRemainsStandalone() {
        val events = listOf(
            toolCall("call-1"),
            ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.Codex,
                role = com.pocketshell.core.agents.ConversationRole.Assistant,
                text = "between",
            ),
            toolResult("result-1"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")

        assertTrue(pairing.resultsByCallId.isEmpty())
        assertEquals(listOf("call-1", "message-1", "result-1"), filtered.events.map { it.id })
        assertTrue("call-1" in runningToolCallIds(events, pairing))
    }

    @Test
    fun explicitPairingWinsOverAdjacentFallback() {
        val events = listOf(
            toolCall("call-1"),
            toolResult("result-1", toolCallId = "call-2"),
            toolCall("call-2"),
        )

        val pairing = events.toolResultPairing()

        assertEquals("result-1", pairing.resultsByCallId["call-2"]?.id)
        assertFalse(pairing.resultsByCallId.containsKey("call-1"))
    }

    @Test
    fun duplicateExplicitResultsKeepLaterOutputsStandalone() {
        val events = listOf(
            toolCall("call-1"),
            toolResult("result-1", toolCallId = "call-1", output = "first-output"),
            toolResult("result-2", toolCallId = "call-1", output = "second-output"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")
        val searched = filterConversationRows(events, query = "second-output")

        assertEquals("result-1", pairing.resultsByCallId["call-1"]?.id)
        assertEquals(setOf("result-1"), pairing.pairedResultIds)
        assertEquals(listOf("call-1", "result-2"), filtered.events.map { it.id })
        assertEquals(listOf("result-2"), searched.events.map { it.id })
    }

    @Test
    fun explicitMismatchedToolCallIdDoesNotFallBackToAdjacentCall() {
        val events = listOf(
            toolCall("call-1"),
            toolResult("result-1", toolCallId = "missing-call"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")

        assertTrue(pairing.resultsByCallId.isEmpty())
        assertEquals(listOf("call-1", "result-1"), filtered.events.map { it.id })
        assertTrue("call-1" in runningToolCallIds(events, pairing))
    }

    @Test
    fun codexPrefixedToolCallIdPairsNonAdjacentResult() {
        val events = listOf(
            toolCall("call:call_1"),
            ConversationEvent.SystemNote(
                id = "reasoning-1",
                agent = AgentKind.Codex,
                tag = "reasoning",
                content = "Need shell output before answering.",
            ),
            toolResult("result:call_1", toolCallId = "call_1", output = "codex-output"),
        )

        val pairing = events.toolResultPairing()
        val filtered = filterConversationRows(events, query = "")
        val searched = filterConversationRows(events, query = "codex-output")

        assertEquals("result:call_1", pairing.resultsByCallId["call:call_1"]?.id)
        assertEquals(setOf("result:call_1"), pairing.pairedResultIds)
        assertEquals(listOf("call:call_1", "reasoning-1"), filtered.events.map { it.id })
        assertEquals(listOf("call:call_1"), searched.events.map { it.id })
        assertEquals(setOf("call:call_1"), searched.searchExpandedToolCallIds)
        assertFalse("call:call_1" in runningToolCallIds(events, pairing))
    }

    private fun toolCall(id: String): ConversationEvent.ToolCall =
        ConversationEvent.ToolCall(
            id = id,
            agent = AgentKind.Codex,
            name = "exec_command",
            input = """{"cmd":"./gradlew test"}""",
        )

    private fun toolResult(
        id: String,
        toolCallId: String? = null,
        output: String = "BUILD SUCCESSFUL",
    ): ConversationEvent.ToolResult =
        ConversationEvent.ToolResult(
            id = id,
            agent = AgentKind.Codex,
            toolCallId = toolCallId,
            output = output,
        )
}
