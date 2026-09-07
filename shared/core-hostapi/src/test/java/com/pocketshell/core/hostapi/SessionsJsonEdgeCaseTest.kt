package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schema-3 session-list edge cases, including an unavailable aplexer. */
class SessionsJsonEdgeCaseTest {

    @Test
    fun `an enumeration failure remains visible beside healthy rows`() {
        val listing = SessionsJson.parseSessionsList(
            fixture("sessions-list-errors.json"),
        ).getOrThrow()

        assertEquals(listOf("git-pocketshell"), listing.sessions.map { it.name })
        assertEquals(
            listOf("a list --json failed: exit 127 (command not found)"),
            listing.errors.map { it.message },
        )
    }

    @Test
    fun `an unavailable listing is still a successful parse with all errors`() {
        val listing = SessionsJson.parseSessionsList(
            fixture("sessions-list-errors-only.json"),
        ).getOrThrow()

        assertTrue(listing.sessions.isEmpty())
        assertEquals(
            listOf("a list --json failed: aplexer unavailable", "probe failed"),
            listing.errors.map { it.message },
        )
    }

    @Test
    fun `unknown fields and unknown state values preserve the row`() {
        val row = SessionsJson.parseSessionsList(
            fixture("sessions-list-forward-compat.json"),
        ).getOrThrow().sessions.single()

        assertEquals("aplexer-follow:next", row.name)
        assertEquals("claude", row.engine)
        assertEquals("zlaude", row.profile)
        assertNull(row.agentState)
        assertNull(row.agentStateSource)
    }

    @Test
    fun `documented agent states and sources map`() {
        val listing = SessionsJson.parseSessionsList(
            fixture("sessions-list-agent-states.json"),
        ).getOrThrow()

        assertEquals(
            listOf(AgentState.IDLE, AgentState.WAITING, AgentState.WORKING),
            listing.sessions.map { it.agentState },
        )
        assertEquals(
            listOf(
                AgentStateSource.REPORTED,
                AgentStateSource.REPORTED,
                AgentStateSource.HEURISTIC,
            ),
            listing.sessions.map { it.agentStateSource },
        )
    }

    @Test
    fun `schema 1 is rejected as too old`() {
        val result = SessionsJson.parseSessionsList(fixture("sessions-list-schema1.json"))
        val error = result.exceptionOrNull()

        assertTrue(error is HostCliError.TooOld)
        error as HostCliError.TooOld
        assertEquals(1, error.foundSchema)
        assertEquals(SessionsJson.REQUIRED_SCHEMA, error.requiredSchema)
        assertTrue(error.userMessage.contains("Update it"))
    }

    @Test
    fun `schema zero is too old`() {
        val result = SessionsJson.parseSessionsList(
            """{"schema": 0, "sessions": [], "errors": []}""",
        )
        assertEquals(0, (result.exceptionOrNull() as HostCliError.TooOld).foundSchema)
    }

    @Test
    fun `one malformed row fails the whole listing`() {
        val result = SessionsJson.parseSessionsList(
            fixture("sessions-list-malformed-row.json"),
        )
        val error = result.exceptionOrNull()

        assertTrue(error is HostCliError.Malformed)
        assertTrue((error as HostCliError.Malformed).detail.contains("name"))
    }

    @Test
    fun `mistyped fields fail the listing`() {
        val raw = """{"schema": 3, "sessions": [{"name": "s", "attached": "yes"}]}"""
        assertTrue(SessionsJson.parseSessionsList(raw).exceptionOrNull() is HostCliError.Malformed)
    }

    @Test
    fun `non-json output, empty output, arrays and missing schema are malformed`() {
        assertTrue(SessionsJson.parseSessionsList("not json").exceptionOrNull() is HostCliError.Malformed)
        assertTrue(SessionsJson.parseSessionsList("").exceptionOrNull() is HostCliError.Malformed)
        assertTrue(
            SessionsJson.parseSessionsList("[]").exceptionOrNull() is HostCliError.Malformed,
        )
        assertTrue(
            SessionsJson.parseSessionsList("{\"sessions\": []}").exceptionOrNull()
                is HostCliError.Malformed,
        )
    }

    @Test
    fun `optional fields may be absent and no backend is synthesized`() {
        val row = SessionsJson.parseSessionsList(
            """{"schema": 3, "sessions": [{"name": "s", "attached": false}]}""",
        ).getOrThrow().sessions.single()

        assertEquals("s", row.name)
        assertEquals(false, row.attached)
        assertNull(row.id)
        assertNull(row.agent)
    }

    @Test
    fun `agent is absent null blank and unknown-string tolerant`() {
        val raw = """
            {"schema": 3, "sessions": [
              {"name": "old-cli", "attached": false},
              {"name": "explicit-null", "attached": false, "agent": null},
              {"name": "blank", "attached": false, "agent": "  "},
              {"name": "unknown", "attached": false, "agent": "mystery-9"},
              {"name": "padded", "attached": false, "agent": " claude "}
            ]}
        """.trimIndent()
        val rows = SessionsJson.parseSessionsList(raw).getOrThrow().sessions.associateBy { it.name }

        assertNull(rows.getValue("old-cli").agent)
        assertNull(rows.getValue("explicit-null").agent)
        assertNull(rows.getValue("blank").agent)
        assertEquals("mystery-9", rows.getValue("unknown").agent)
        assertEquals("claude", rows.getValue("padded").agent)
    }

    @Test
    fun `non-string agent fails instead of being silently nulled`() {
        val raw = """{"schema": 3, "sessions": [{"name": "s", "attached": false, "agent": 7}]}"""
        assertTrue(SessionsJson.parseSessionsList(raw).exceptionOrNull() is HostCliError.Malformed)
    }
}
