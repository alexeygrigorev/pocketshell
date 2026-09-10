package com.pocketshell.next.terminal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2632: "opening a previously-used session restores its last-viewed
 * tab/window rather than a hardcoded default".
 *
 * Two halves, both pinned here: what the device REMEMBERS (per host, surviving
 * process death because it is on disk), and what that memory is allowed to
 * DO — which is nothing at all unless the host's own live listing still has
 * that session.
 */
@RunWith(AndroidJUnit4::class)
class LastSessionStoreTest {

    private val store = LastSessionStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `a host with no history has nothing to resume`() {
        assertNull(store.get(hostId = 1L))
    }

    @Test
    fun `the last opened session round-trips with its workspace path`() {
        store.record(hostId = 1L, sessionName = "pocketshell:review", workspacePath = "/home/a/git/x")

        assertEquals(
            RememberedSession("pocketshell:review", "/home/a/git/x"),
            store.get(hostId = 1L),
        )
    }

    @Test
    fun `each host remembers its own session`() {
        store.record(hostId = 1L, sessionName = "one", workspacePath = "/a")
        store.record(hostId = 2L, sessionName = "two", workspacePath = "/b")

        assertEquals("one", store.get(1L)?.sessionName)
        assertEquals("two", store.get(2L)?.sessionName)
    }

    @Test
    fun `a later open replaces the remembered session, including dropping its path`() {
        store.record(hostId = 1L, sessionName = "one", workspacePath = "/a")
        store.record(hostId = 1L, sessionName = "two", workspacePath = null)

        assertEquals(RememberedSession("two", null), store.get(1L))
    }

    @Test
    fun `a blank session name is not a resume target`() {
        store.record(hostId = 1L, sessionName = "   ", workspacePath = "/a")

        assertNull(store.get(1L))
    }

    @Test
    fun `clear forgets the host`() {
        store.record(hostId = 1L, sessionName = "one", workspacePath = "/a")
        store.clear(1L)

        assertNull(store.get(1L))
    }

    @Test
    fun `nothing remembered means no resume target`() {
        assertNull(resolveResumeTarget(remembered = null, sessions = listOf(row("one"))))
    }

    /**
     * The load-bearing rule: a session that ended while the app was closed
     * must NOT be resumed into. Without this the resume would land the user on
     * a dead-PTY error screen every morning.
     */
    @Test
    fun `a remembered session the host no longer lists is not resumed`() {
        val remembered = RememberedSession("gone", "/a")

        assertNull(resolveResumeTarget(remembered, listOf(row("one"), row("two"))))
    }

    @Test
    fun `a still-running remembered session resolves to the host's own row`() {
        val remembered = RememberedSession("two", "/stale/path/from/last/time")

        val target = resolveResumeTarget(remembered, listOf(row("one"), row("two", "/live/path")))

        assertEquals("two", target?.name)
        // The HOST's workspace path wins, not the one stored on this device:
        // the session may have been moved since it was last opened here.
        assertEquals("/live/path", target?.workspace)
    }

    @Test
    fun `matching is on the host identity, not a display label`() {
        val remembered = RememberedSession("pocketshell:main", null)

        // `readableSessionName` renders both of these as "main"; only the
        // full aplexer name identifies which session to attach to.
        val target = resolveResumeTarget(
            remembered,
            listOf(row("other:main"), row("pocketshell:main")),
        )

        assertEquals("pocketshell:main", target?.name)
    }

    // --- workspace-scoped memory (maintainer follow-up 2026-09-10) ---------

    /**
     * The host-scoped and workspace-scoped memories answer different
     * questions, so opening a session in project B must not change what a tap
     * on project A resolves to.
     */
    @Test
    fun `each workspace remembers its own session independently of the host's`() {
        store.record(hostId = 1L, sessionName = "a:main", workspacePath = "/git/a")
        store.record(hostId = 1L, sessionName = "b:main", workspacePath = "/git/b")

        assertEquals("a:main", store.getForWorkspace(1L, "/git/a"))
        assertEquals("b:main", store.getForWorkspace(1L, "/git/b"))
        // The host-level memory is the LAST thing touched anywhere.
        assertEquals("b:main", store.get(1L)?.sessionName)
    }

    @Test
    fun `a trailing slash does not split one workspace's memory in two`() {
        store.record(hostId = 1L, sessionName = "a:main", workspacePath = "/git/a/")

        assertEquals("a:main", store.getForWorkspace(1L, "/git/a"))
        assertEquals("a:main", store.getForWorkspace(1L, "/git/a/"))
    }

    @Test
    fun `a workspace never opened has no remembered session`() {
        store.record(hostId = 1L, sessionName = "a:main", workspacePath = "/git/a")

        assertNull(store.getForWorkspace(1L, "/git/other"))
        assertNull(store.getForWorkspace(2L, "/git/a"))
    }

    @Test
    fun `an empty workspace resolves to no entry session at all`() {
        assertNull(resolveWorkspaceEntrySession("anything", emptyList()))
    }

    @Test
    fun `a workspace entry prefers the session remembered for it`() {
        val sessions = listOf(row("one", activityEpoch = 900), row("two", activityEpoch = 100))

        // Even though "one" is more recently active, the explicit memory wins.
        assertEquals("two", resolveWorkspaceEntrySession("two", sessions)?.name)
    }

    /**
     * The difference from [resolveResumeTarget]: an unusable memory here must
     * NOT mean "show another list" — the maintainer asked for the
     * intermediate screen to disappear, so an unknown workspace still opens a
     * terminal.
     */
    @Test
    fun `an unknown or missing memory falls back to the most recently active session`() {
        val sessions = listOf(row("one", activityEpoch = 100), row("two", activityEpoch = 900))

        assertEquals("two", resolveWorkspaceEntrySession(null, sessions)?.name)
        assertEquals("two", resolveWorkspaceEntrySession("ended-overnight", sessions)?.name)
    }

    @Test
    fun `sessions with no reported activity keep the host's listing order`() {
        val sessions = listOf(row("first"), row("second"))

        assertEquals("first", resolveWorkspaceEntrySession(null, sessions)?.name)
    }

    @Test
    fun `a session with reported activity outranks one with none`() {
        val sessions = listOf(row("quiet"), row("busy", activityEpoch = 5))

        assertEquals("busy", resolveWorkspaceEntrySession(null, sessions)?.name)
    }

    private fun row(
        name: String,
        workspace: String? = null,
        activityEpoch: Long? = null,
    ): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = "shell",
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = false,
        createdEpoch = null,
        activityEpoch = activityEpoch,
    )
}
