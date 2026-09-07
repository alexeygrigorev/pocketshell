package com.pocketshell.core.hostapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacesJsonTest {

    @Test
    fun `durable empty list stays empty and display path is presentation only`() {
        val listing = WorkspacesJson.parseWorkspacesList(
            """
            {
              "schema": 1,
              "host": "opaque-host-id",
              "workspaces": [
                {"path": "/home/alexey/git/empty", "display_path": "~/git/empty"}
              ]
            }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(
            listOf(WorkspaceMembership("/home/alexey/git/empty", "~/git/empty")),
            listing.workspaces,
        )
    }

    @Test
    fun `blank display path falls back to the canonical path without changing identity`() {
        val listing = WorkspacesJson.parseWorkspacesList(
            """{"schema":1,"workspaces":[{"path":"/home/a/app","display_path":"  "}]}""",
        ).getOrThrow()

        assertEquals("/home/a/app", listing.workspaces.single().path)
        assertEquals("/home/a/app", listing.workspaces.single().displayPath)
    }

    @Test
    fun `schema zero is rejected as an outdated host`() {
        val error = WorkspacesJson.parseWorkspacesList(
            """{"schema":0,"workspaces":[]}""",
        ).exceptionOrNull()

        assertTrue(error is HostCliError.TooOld)
        assertEquals(0, (error as HostCliError.TooOld).foundSchema)
        assertEquals(WorkspacesJson.REQUIRED_SCHEMA, error.requiredSchema)
    }

    @Test
    fun `malformed workspace row fails instead of inventing an empty path`() {
        val error = WorkspacesJson.parseWorkspacesList(
            """{"schema":1,"workspaces":[{"path":"   "}]}""",
        ).exceptionOrNull()

        assertTrue(error is HostCliError.Malformed)
        assertTrue((error as HostCliError.Malformed).userMessage.contains("empty"))
    }
}
