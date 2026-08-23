package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSidecarCheckpointDiscardTest {

    @Test
    fun discardCommandNamesFinalFileAndOnlyItsExactHashedSiblings() {
        val remote = "~/inbox/notes.txt"
        val token = "sidecar-token-a"
        val paths = queueSidecarCheckpointPaths(remote, token)
        val foreign = queueSidecarCheckpointPaths(remote, "sidecar-token-b")
        val command = queueSidecarCheckpointDiscardCommand(remote, token)

        assertTrue(command.startsWith("rm -f -- "))
        assertFalse("must never glob", command.contains("*") || command.contains("?"))
        assertTrue(command.contains(quoteRemotePathForShell(remote)))
        assertTrue(command.contains(quoteRemotePathForShell(paths.dataPath)))
        assertTrue(command.contains(quoteRemotePathForShell(paths.identityPath)))
        assertFalse(command.contains(quoteRemotePathForShell(foreign.dataPath)))
        assertFalse(command.contains(quoteRemotePathForShell(foreign.identityPath)))
        assertEquals(paths.identityPath, paths.dataPath + ".identity")
    }
}
