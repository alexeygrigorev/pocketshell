package com.pocketshell.next.workspaces

import com.pocketshell.core.hostapi.SessionRow
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
