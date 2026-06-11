package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SshFolderListGateway]'s remote-path helpers.
 *
 * Issue #703 hard-cut the old `composeStartCommand` env-export prelude:
 * the server-side `pocketshell agent <kind> --dir <dir> …` wrapper now
 * merges the folder's `.env`/`.envrc` itself, so the gateway just types the
 * one short start command verbatim (no prelude to compose). The remaining
 * path-quoting helpers are still gateway-owned and tested here.
 */
class FolderListGatewayCreateSessionTest {

    @Test
    fun normaliseProjectFolderNameRejectsPathTraversalAndSeparators() {
        assertEquals("scratch", SshFolderListGateway.normaliseProjectFolderName(" scratch "))
        assertEquals("scratch", SshFolderListGateway.normaliseProjectFolderName("/scratch/"))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName(""))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName(".."))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName("../bad"))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName("nested/bad"))
    }

    @Test
    fun childPathComposesUnderTargetFolder() {
        assertEquals(
            "/home/alexey/git/scratch",
            SshFolderListGateway.childPath("/home/alexey/git/", "scratch"),
        )
        assertEquals("/scratch", SshFolderListGateway.childPath("/", "scratch"))
        assertEquals("~/git/scratch", SshFolderListGateway.childPath("~/git", "scratch"))
    }

    @Test
    fun shellQuoteRemotePathExpandsHomeWithoutLettingPathInjectShell() {
        assertEquals("\$HOME", SshFolderListGateway.shellQuoteRemotePathValue("~"))
        assertEquals("\$HOME/'git/my project'", SshFolderListGateway.shellQuoteRemotePathValue("~/git/my project"))
        assertEquals(
            "'/tmp/it'\\''s-safe'",
            SshFolderListGateway.shellQuoteRemotePathValue("/tmp/it's-safe"),
        )
    }
}
