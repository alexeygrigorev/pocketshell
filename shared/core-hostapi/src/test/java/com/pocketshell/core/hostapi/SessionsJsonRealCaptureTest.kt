package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Happy-path parsing for the schema-3 aplexer-only capture. */
class SessionsJsonRealCaptureTest {

    private fun listing(): SessionsListing =
        SessionsJson.parseSessionsList(fixture("sessions-list-real.json")).getOrThrow()

    @Test
    fun `capture parses every aplexer row without a discriminator`() {
        val listing = listing()
        assertEquals(4, listing.sessions.size)
        assertTrue(listing.errors.isEmpty())
        assertTrue(listing.sessions.all { it.name.isNotBlank() })
    }

    @Test
    fun `capture preserves row order and aplexer metadata`() {
        val rows = listing().sessions
        assertEquals("zcode-acp:zcodex-test", rows.first().name)
        assertEquals("aplexer-follow:live", rows.last().name)

        val row = rows.single { it.name == "aplexer-follow:zsp" }
        assertEquals("a9e4cb9b-2293-4cf4-ac66-7ccc759c5909", row.id)
        assertEquals("zsp", row.tag)
        assertEquals("claude", row.engine)
        assertEquals("zlaude", row.profile)
        assertEquals(AgentState.WAITING, row.agentState)
        assertEquals(AgentStateSource.HEURISTIC, row.agentStateSource)
    }

    @Test
    fun `capture preserves the attached flag and nullable profile`() {
        val rows = listing().sessions
        assertTrue(rows.none { it.attached })
        val yolo = rows.single { it.name == "aplexer-follow:yolo" }
        assertNull(yolo.profile)
        assertEquals("codex", yolo.engine)
        assertEquals("52a2508e-c902-4bd6-9ea8-dd3668381749", yolo.id)
    }

    @Test
    fun `agent-carrying capture keeps agent independent of engine`() {
        val rows = SessionsJson.parseSessionsList(
            fixture("sessions-list-agent.json"),
        ).getOrThrow().sessions
        val row = rows.single { it.name == "pocketshell:git-pocketshell" }

        assertEquals("shell", row.engine)
        assertEquals("claude", row.agent)
        assertEquals(AgentState.WORKING, row.agentState)
        assertEquals(AgentStateSource.HEURISTIC, row.agentStateSource)
        assertTrue(row.attached)
        assertNull(rows.single { it.name == "aplexer-follow:idle" }.agent)
    }
}
