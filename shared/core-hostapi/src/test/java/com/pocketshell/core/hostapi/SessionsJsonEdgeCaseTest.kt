package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases the live dev box did not happen to be in when
 * `sessions-list-real.json` was captured: a failed backend, a manager this
 * build has never heard of, an outdated host CLI, and corrupt payloads.
 * Every fixture used here is hand-built.
 */
class SessionsJsonEdgeCaseTest {

    // --- errors[] ---------------------------------------------------------

    @Test
    fun `a failed backend surfaces in errors while the healthy one still lists`() {
        val listing =
            SessionsJson.parseSessionsList(fixture("sessions-list-errors.json")).getOrThrow()

        assertEquals(listOf("git-pocketshell"), listing.sessions.map { it.name })
        assertEquals(
            listOf(
                BackendError(
                    manager = "aplexer",
                    message = "a list --json failed: exit 127 (command not found)",
                ),
            ),
            listing.errors,
        )
    }

    @Test
    fun `an all-backends-failed listing is a success with zero sessions and every error kept`() {
        // The regression this guards: an empty `sessions` list plus dropped
        // errors is indistinguishable from "the host genuinely has no
        // sessions". Both errors must survive, in order.
        val listing =
            SessionsJson.parseSessionsList(fixture("sessions-list-errors-only.json")).getOrThrow()

        assertEquals(emptyList<SessionRow>(), listing.sessions)
        assertEquals(listOf("tmux", "aplexer"), listing.errors.map { it.manager })
        assertEquals(
            "tmuxctl list failed: no server running on /tmp/tmux-1000/default",
            listing.errors.first().message,
        )
        assertEquals("probe failed", listing.errors.last().message)
    }

    // --- forward compatibility -------------------------------------------

    @Test
    fun `an unknown manager keeps the row as UNKNOWN instead of dropping it`() {
        val listing =
            SessionsJson.parseSessionsList(fixture("sessions-list-unknown-manager.json"))
                .getOrThrow()

        assertEquals(3, listing.sessions.size)
        assertEquals(
            listOf(Backend.TMUX, Backend.UNKNOWN, Backend.APLEXER),
            listing.sessions.map { it.backend },
        )

        val unknown = listing.sessions.single { it.backend == Backend.UNKNOWN }
        assertEquals("future-manager-session", unknown.name)
        assertEquals("zj-0001", unknown.id)
        assertTrue(unknown.attached)
        assertEquals(1788400000L, unknown.createdEpoch)
    }

    @Test
    fun `unknown keys, a newer schema, and unknown enum values never fail the parse`() {
        val listing =
            SessionsJson.parseSessionsList(fixture("sessions-list-forward-compat.json"))
                .getOrThrow()

        val row = listing.sessions.single()
        assertEquals("aplexer-follow:next", row.name)
        assertEquals(Backend.APLEXER, row.backend)
        // "thinking" / "psychic" are states this build predates: null, not a
        // crash, and the row itself survives with everything else intact.
        assertNull(row.agentState)
        assertNull(row.agentStateSource)
        assertEquals("claude", row.engine)
        assertEquals("zlaude", row.profile)
    }

    @Test
    fun `every documented agent state and source maps`() {
        val listing =
            SessionsJson.parseSessionsList(fixture("sessions-list-agent-states.json")).getOrThrow()

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

    // --- schema gate ------------------------------------------------------

    @Test
    fun `schema 1 is rejected as TooOld and never coerced into a listing`() {
        val result = SessionsJson.parseSessionsList(fixture("sessions-list-schema1.json"))

        assertTrue("expected failure, got ${result.getOrNull()}", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected TooOld, got $error", error is HostCliError.TooOld)
        error as HostCliError.TooOld
        assertEquals(1, error.foundSchema)
        assertEquals(2, error.requiredSchema)
        assertEquals(SessionsJson.REQUIRED_SCHEMA, error.requiredSchema)
        assertTrue(
            "message should tell the user to update the host CLI: ${error.userMessage}",
            error.userMessage.contains("too old") && error.userMessage.contains("Update it"),
        )
    }

    @Test
    fun `schema 0 is TooOld too`() {
        val result = SessionsJson.parseSessionsList("""{"schema": 0, "sessions": [], "errors": []}""")

        assertEquals(0, (result.exceptionOrNull() as HostCliError.TooOld).foundSchema)
    }

    // --- malformed payloads ----------------------------------------------

    @Test
    fun `one bad row fails the whole listing rather than silently shortening it`() {
        val result = SessionsJson.parseSessionsList(fixture("sessions-list-malformed-row.json"))

        assertTrue("expected failure, got ${result.getOrNull()}", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected Malformed, got $error", error is HostCliError.Malformed)
        assertTrue(
            "detail should name the missing field: ${(error as HostCliError.Malformed).detail}",
            error.detail.contains("name"),
        )
    }

    @Test
    fun `a mistyped field fails the listing`() {
        val raw = """
            {"schema": 2, "managers": ["tmux"], "errors": [],
             "sessions": [{"name": "s", "manager": "tmux", "attached": "yes"}]}
        """.trimIndent()

        assertTrue(SessionsJson.parseSessionsList(raw).exceptionOrNull() is HostCliError.Malformed)
    }

    @Test
    fun `non-JSON output is a Malformed failure, not a thrown exception`() {
        // What a real host produces when the CLI is missing or a shell rc
        // printed something first.
        val result = SessionsJson.parseSessionsList("bash: pocketshell: command not found\n")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HostCliError.Malformed)
    }

    @Test
    fun `empty output is a Malformed failure`() {
        assertTrue(SessionsJson.parseSessionsList("").exceptionOrNull() is HostCliError.Malformed)
    }

    @Test
    fun `a JSON array root is a Malformed failure`() {
        val result = SessionsJson.parseSessionsList("""[{"name": "s"}]""")

        assertTrue(result.exceptionOrNull() is HostCliError.Malformed)
    }

    @Test
    fun `a missing schema field is Malformed, not TooOld`() {
        // No `schema` key at all means we cannot tell what we're reading;
        // claiming "too old" would send the user down the wrong repair path.
        val result = SessionsJson.parseSessionsList("""{"sessions": [], "errors": []}""")

        val error = result.exceptionOrNull()
        assertTrue("expected Malformed, got $error", error is HostCliError.Malformed)
        assertTrue((error as HostCliError.Malformed).detail.contains("schema"))
    }

    @Test
    fun `a non-integer schema is Malformed`() {
        val result = SessionsJson.parseSessionsList("""{"schema": "two", "sessions": []}""")

        assertTrue(result.exceptionOrNull() is HostCliError.Malformed)
    }

    // --- shape tolerances -------------------------------------------------

    @Test
    fun `an absent errors key parses as an empty error list`() {
        val result = SessionsJson.parseSessionsList("""{"schema": 2, "sessions": []}""")

        assertEquals(SessionsListing(emptyList(), emptyList()), result.getOrThrow())
    }

    @Test
    fun `optional row fields may be absent entirely, not just null`() {
        val raw = """{"schema": 2, "sessions": [{"name": "s", "manager": "tmux", "attached": false}]}"""

        val row = SessionsJson.parseSessionsList(raw).getOrThrow().sessions.single()

        assertEquals(
            SessionRow(
                name = "s",
                backend = Backend.TMUX,
                id = null,
                workspace = null,
                tag = null,
                engine = null,
                profile = null,
                agentState = null,
                agentStateSource = null,
                attached = false,
                createdEpoch = null,
                activityEpoch = null,
            ),
            row,
        )
    }
}
