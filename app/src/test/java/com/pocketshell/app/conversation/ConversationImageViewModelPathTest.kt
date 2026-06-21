package com.pocketshell.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #842: pure unit test for the transcript-image path resolution that the
 * host-file load runs before `downloadFile` over the warm lease. Absolute paths
 * pass through; `~` expands to home; a relative path resolves against the pane
 * cwd (then home) so a `shot.png` the agent referenced in its working dir reads.
 */
class ConversationImageViewModelPathTest {

    @Test
    fun absolutePathPassesThrough() {
        assertEquals(
            "/home/me/shot.png",
            ConversationImageViewModel.resolveRemotePath("/home/me/shot.png", cwd = "/work", home = "/home/me"),
        )
    }

    @Test
    fun tildeExpandsToHome() {
        assertEquals(
            "/home/me/shot.png",
            ConversationImageViewModel.resolveRemotePath("~/shot.png", cwd = "/work", home = "/home/me"),
        )
    }

    @Test
    fun bareTildeIsHome() {
        assertEquals(
            "/home/me",
            ConversationImageViewModel.resolveRemotePath("~", cwd = null, home = "/home/me"),
        )
    }

    @Test
    fun relativePathResolvesAgainstCwd() {
        assertEquals(
            "/work/proj/shot.png",
            ConversationImageViewModel.resolveRemotePath("shot.png", cwd = "/work/proj", home = "/home/me"),
        )
    }

    @Test
    fun relativePathFallsBackToHomeWhenNoCwd() {
        assertEquals(
            "/home/me/shot.png",
            ConversationImageViewModel.resolveRemotePath("shot.png", cwd = null, home = "/home/me"),
        )
    }
}
