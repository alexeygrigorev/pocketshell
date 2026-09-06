package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The happy path, driven by a REAL capture.
 *
 * `sessions-list-real.json` is genuine `pocketshell sessions list --json`
 * output taken live off the dev box (schema 2, 15 sessions across tmux and
 * aplexer, `errors: []`). It is the fixture that proves the parser reads what
 * the host actually emits, not what this module wishes it emitted — every
 * other fixture in this suite is hand-built for an edge case the live box did
 * not happen to be in.
 */
class SessionsJsonRealCaptureTest {

    private fun listing(): SessionsListing =
        SessionsJson.parseSessionsList(fixture("sessions-list-real.json")).getOrThrow()

    @Test
    fun `real capture parses every row from both managers`() {
        val listing = listing()

        assertEquals(15, listing.sessions.size)
        assertEquals(emptyList<BackendError>(), listing.errors)
        assertEquals(11, listing.sessions.count { it.backend == Backend.TMUX })
        assertEquals(4, listing.sessions.count { it.backend == Backend.APLEXER })
        assertEquals(0, listing.sessions.count { it.backend == Backend.UNKNOWN })
    }

    @Test
    fun `real capture preserves row order`() {
        val names = listing().sessions.map { it.name }

        assertEquals("git-pocketshell-2", names.first())
        assertEquals("aplexer-follow:live", names.last())
        assertEquals(
            listOf(
                "zcode-acp:zcodex-test",
                "aplexer-follow:yolo",
                "aplexer-follow:zsp",
                "aplexer-follow:live",
            ),
            names.takeLast(4),
        )
    }

    @Test
    fun `real tmux row maps every field including the nulls`() {
        val row = listing().sessions.single { it.name == "git-pocketshell-2" }

        assertEquals(
            SessionRow(
                name = "git-pocketshell-2",
                backend = Backend.TMUX,
                id = null,
                workspace = "/home/alexey/git/pocketshell",
                tag = null,
                engine = null,
                profile = null,
                agent = null,
                agentState = null,
                agentStateSource = null,
                attached = true,
                createdEpoch = 1788381061L,
                activityEpoch = 1788409253L,
            ),
            row,
        )
    }

    @Test
    fun `real aplexer row maps id tag engine profile and agent state`() {
        val row = listing().sessions.single { it.name == "aplexer-follow:zsp" }

        assertEquals(
            SessionRow(
                name = "aplexer-follow:zsp",
                backend = Backend.APLEXER,
                id = "a9e4cb9b-2293-4cf4-ac66-7ccc759c5909",
                workspace = "/tmp/aplexer-follow",
                tag = "zsp",
                engine = "claude",
                profile = "zlaude",
                agent = null,
                agentState = AgentState.WAITING,
                agentStateSource = AgentStateSource.HEURISTIC,
                attached = false,
                createdEpoch = 1787765485L,
                activityEpoch = 1787814371L,
            ),
            row,
        )
    }

    @Test
    fun `real aplexer row without a profile keeps profile null but keeps engine`() {
        // The two managers populate different subsets; `null` must mean "the
        // host did not report this", never a defaulted empty string.
        val row = listing().sessions.single { it.name == "aplexer-follow:yolo" }

        assertNull(row.profile)
        assertEquals("codex", row.engine)
        assertEquals("yolo", row.tag)
        assertEquals("52a2508e-c902-4bd6-9ea8-dd3668381749", row.id)
    }

    @Test
    fun `real capture reports exactly one attached session`() {
        val attached = listing().sessions.filter { it.attached }

        assertEquals(listOf("git-pocketshell-2"), attached.map { it.name })
    }

    /**
     * The capture predates the `agent` key entirely (issue #2579): it was
     * taken off a host CLI that never emitted it. That is precisely the
     * "older host CLI keeps working" case, so it is pinned HERE, on a
     * genuine old payload, rather than only on a hand-built fixture.
     */
    @Test
    fun `a capture from a host CLI predating the agent key parses with a null agent`() {
        val listing = listing()

        assertTrue(listing.sessions.isNotEmpty())
        assertTrue(listing.sessions.all { it.agent == null })
        // Not a null-everything parse: the aplexer rows still carry engines.
        assertEquals(
            listOf("codex", "codex", "claude", "grok"),
            listing.sessions.filter { it.backend == Backend.APLEXER }.map { it.engine },
        )
    }

    // --- the agent-carrying capture ---------------------------------------

    private fun agentListing(): SessionsListing =
        SessionsJson.parseSessionsList(fixture("sessions-list-agent.json")).getOrThrow()

    /**
     * The shape the maintainer's box emits once aplexer detects the workload's
     * agent (#2579/#2580): `engine` says "shell" — the session was started as
     * `a start … -- /bin/bash -l` — while `agent` says "claude". A client that
     * read `engine` to answer "which agent" would get the wrong answer here,
     * which is the whole reason the field exists.
     */
    @Test
    fun `an agent-carrying aplexer row maps agent independently of engine`() {
        val row = agentListing().sessions.single { it.name == "pocketshell:git-pocketshell" }

        assertEquals(
            SessionRow(
                name = "pocketshell:git-pocketshell",
                backend = Backend.APLEXER,
                id = "b31c7f21-4b40-4a1e-8d0f-4b2f5c9e77aa",
                workspace = "/home/alexey/git/pocketshell",
                tag = "git-pocketshell",
                engine = "shell",
                profile = null,
                agent = "claude",
                agentState = AgentState.WORKING,
                agentStateSource = AgentStateSource.HEURISTIC,
                attached = true,
                createdEpoch = 1788381061L,
                activityEpoch = 1788409253L,
            ),
            row,
        )
    }

    @Test
    fun `agent stays null for a tmux row and for an aplexer row with no detection`() {
        val listing = agentListing()

        assertNull(listing.sessions.single { it.name == "git-pocketshell" }.agent)
        assertNull(listing.sessions.single { it.name == "aplexer-follow:idle" }.agent)
    }

    @Test
    fun `real tmux rows carry no agent metadata at all`() {
        val tmuxRows = listing().sessions.filter { it.backend == Backend.TMUX }

        assertTrue(tmuxRows.isNotEmpty())
        assertTrue(tmuxRows.all { it.id == null })
        assertTrue(tmuxRows.all { it.tag == null })
        assertTrue(tmuxRows.all { it.engine == null })
        assertTrue(tmuxRows.all { it.profile == null })
        assertTrue(tmuxRows.all { it.agent == null })
        assertTrue(tmuxRows.all { it.agentState == null })
        assertTrue(tmuxRows.all { it.agentStateSource == null })
        // ...but they do carry timestamps, so a null-everything bug can't hide
        // behind this assertion.
        assertTrue(tmuxRows.all { (it.createdEpoch ?: 0L) > 0L })
        assertTrue(tmuxRows.all { (it.activityEpoch ?: 0L) > 0L })
    }
}
