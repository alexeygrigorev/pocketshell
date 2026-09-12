package com.pocketshell.next.workspaces

import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.WorkspaceMembership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProjectionTest {

    @Test
    fun `equivalent spellings share one canonical durable workspace`() {
        val roots = listOf(RegisteredWorkspaceRoot("/home/alexey/git", "Git"))
        val result = projectWorkspaceRoots(
            sessions = listOf(session("live", "/home/alexey/git/app/.")),
            memberships = listOf(
                WorkspaceMembership("/home/alexey/git/./app/", "~/git/app"),
                WorkspaceMembership("/home/alexey/git/app", "~/git/app (duplicate)"),
            ),
            registeredRoots = roots,
        )

        val workspace = result.single().workspaces.single()
        assertEquals("/home/alexey/git/app", workspace.path)
        assertEquals("app", workspace.label)
        assertTrue(workspace.durable)
        assertEquals(listOf("live"), workspace.sessions.map { it.name })
    }

    @Test
    fun `same workspace names under different roots remain separate`() {
        val result = projectWorkspaceRoots(
            sessions = emptyList(),
            memberships = listOf(
                WorkspaceMembership("/home/alexey/git/app", "~/git/app"),
                WorkspaceMembership("/home/alexey/work/app", "~/work/app"),
            ),
            registeredRoots = listOf(
                RegisteredWorkspaceRoot("/home/alexey/git", "Git"),
                RegisteredWorkspaceRoot("/home/alexey/work", "Work"),
            ),
        )

        assertEquals(listOf("~/git", "~/work"), result.map { it.displayPath })
        assertEquals(listOf("app"), result[0].workspaces.map { it.label })
        assertEquals(listOf("app"), result[1].workspaces.map { it.label })
        assertFalse(result[0].workspaces.single().path == result[1].workspaces.single().path)
    }

    @Test
    fun `empty durable workspace persists as a navigable row`() {
        val result = projectWorkspaceRoots(
            sessions = emptyList(),
            memberships = listOf(WorkspaceMembership("/home/alexey/git/empty", "~/git/empty")),
        )

        val workspace = result.single().workspaces.single()
        assertEquals("empty", workspace.label)
        assertTrue(workspace.durable)
        assertTrue(workspace.sessions.isEmpty())
    }

    @Test
    fun `a session at the root is grouped under In this root data`() {
        val result = projectWorkspaceRoots(
            sessions = listOf(session("root-shell", "/home/alexey/git")),
            memberships = listOf(WorkspaceMembership("/home/alexey/git/app", "~/git/app")),
            registeredRoots = listOf(RegisteredWorkspaceRoot("/home/alexey/git", "Git")),
        )

        val root = result.single()
        assertEquals("Git", root.label)
        assertEquals(listOf("root-shell"), root.rootSessions.map { it.name })
        assertEquals(listOf("app"), root.workspaces.map { it.label })
    }

    @Test
    fun `a membership equal to a configured root does not create a duplicate child`() {
        val result = projectWorkspaceRoots(
            sessions = listOf(session("root-shell", "/home/alexey/git")),
            memberships = listOf(
                WorkspaceMembership("/home/alexey/git", "~/git"),
                WorkspaceMembership("/home/alexey/git/app", "~/git/app"),
            ),
            registeredRoots = listOf(RegisteredWorkspaceRoot("/home/alexey/git", "Git")),
        )

        val root = result.single()
        assertEquals(listOf("root-shell"), root.rootSessions.map { it.name })
        assertEquals(listOf("app"), root.workspaces.map { it.label })
    }

    @Test
    fun `an unregistered live cwd remains reachable without becoming durable`() {
        val result = projectWorkspaceRoots(
            sessions = listOf(session("observed", "/home/alexey/git/observed")),
            memberships = emptyList(),
        )

        val workspace = result.single().workspaces.single()
        assertEquals("observed", workspace.label)
        assertFalse(workspace.durable)
        assertEquals(listOf("observed"), workspace.sessions.map { it.name })
    }

    @Test
    fun `workspace summary names session kinds and collapses duplicate kinds`() {
        val sessions = listOf(
            session("claude-1", "/home/x/git/app").copy(agent = "claude"),
            session("claude-2", "/home/x/git/app").copy(agent = "claude"),
            session("codex", "/home/x/git/app").copy(agent = "codex"),
            session("shell", "/home/x/git/app"),
        )

        assertEquals(
            "Claude Code ×2 · Codex · Terminal",
            workspaceSessionSummary(sessions),
        )
    }

    @Test
    fun `workspace summary limits visible kinds without using a path`() {
        val sessions = listOf(
            session("claude", "/home/x/git/app").copy(agent = "claude"),
            session("codex", "/home/x/git/app").copy(agent = "codex"),
            session("grok", "/home/x/git/app").copy(agent = "grok"),
            session("open", "/home/x/git/app").copy(agent = "opencode"),
        )

        assertEquals(
            "Claude Code · Codex · Grok · +1 more kinds",
            workspaceSessionSummary(sessions),
        )
    }

    @Test
    fun `workspace session metadata uses the server reported status`() {
        assertEquals(
            "Claude Code · Working",
            sessionKindStatusLabel(
                session("claude", "/home/x/git/app").copy(
                    agent = "claude",
                    agentState = AgentState.WORKING,
                ),
            ),
        )
        assertEquals(
            "Shell · Running",
            sessionKindStatusLabel(session("shell", "/home/x/git/app").copy(agent = "shell")),
        )
    }

    @Test
    fun `explicit workspace order survives live session refresh`() {
        val result = projectWorkspaceRoots(
            sessions = emptyList(),
            memberships = listOf(
                WorkspaceMembership("/home/alexey/git/first", "~/git/first"),
                WorkspaceMembership("/home/alexey/git/second", "~/git/second"),
            ),
            registeredRoots = listOf(RegisteredWorkspaceRoot("/home/alexey/git", "Git")),
            workspaceOrders = mapOf(
                "/home/alexey/git" to listOf(
                    "/home/alexey/git/second",
                    "/home/alexey/git/first",
                ),
            ),
        )

        assertEquals(listOf("second", "first"), result.single().workspaces.map { it.label })
    }

    @Test
    fun `saved roots use manual order before creation time`() {
        val result = projectWorkspaceRoots(
            sessions = emptyList(),
            memberships = emptyList(),
            registeredRoots = listOf(
                RegisteredWorkspaceRoot("/home/alexey/work", "Work", createdAt = 20L, sortOrder = 1L),
                RegisteredWorkspaceRoot("/home/alexey/git", "Git", createdAt = 10L, sortOrder = 0L),
            ),
        )

        assertEquals(listOf("Git", "Work"), result.map { it.label })
    }

    private fun session(name: String, workspace: String?): SessionRow = SessionRow(
        name = name,
        id = null,
        workspace = workspace,
        tag = null,
        engine = null,
        profile = null,
        agent = null,
        agentState = null,
        agentStateSource = null,
        attached = false,
        createdEpoch = 1L,
        activityEpoch = null,
    )
}
