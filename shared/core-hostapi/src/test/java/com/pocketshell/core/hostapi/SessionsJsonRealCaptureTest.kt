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

    @Test
    fun `real tmux rows carry no agent metadata at all`() {
        val tmuxRows = listing().sessions.filter { it.backend == Backend.TMUX }

        assertTrue(tmuxRows.isNotEmpty())
        assertTrue(tmuxRows.all { it.id == null })
        assertTrue(tmuxRows.all { it.tag == null })
        assertTrue(tmuxRows.all { it.engine == null })
        assertTrue(tmuxRows.all { it.profile == null })
        assertTrue(tmuxRows.all { it.agentState == null })
        assertTrue(tmuxRows.all { it.agentStateSource == null })
        // ...but they do carry timestamps, so a null-everything bug can't hide
        // behind this assertion.
        assertTrue(tmuxRows.all { (it.createdEpoch ?: 0L) > 0L })
        assertTrue(tmuxRows.all { (it.activityEpoch ?: 0L) > 0L })
    }
}
