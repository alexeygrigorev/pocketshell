package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests over [RemoteEntry] — issue #528. No network, no Docker.
 *
 * Pins the folders-first / case-insensitive sort the explorer relies on, plus
 * the [RemoteListing] / typed-exception shapes.
 */
class RemoteEntryTest {

    private fun dir(name: String) = RemoteEntry(name, RemoteEntry.Type.DIRECTORY, 0L, null)
    private fun file(name: String, size: Long = 0L) =
        RemoteEntry(name, RemoteEntry.Type.FILE, size, null)

    @Test
    fun `FOLDERS_FIRST sorts directories before files`() {
        val sorted = listOf(file("apple"), dir("zebra"), file("banana"), dir("alpha"))
            .sortedWith(RemoteEntry.FOLDERS_FIRST)
        assertEquals(
            listOf("alpha", "zebra", "apple", "banana"),
            sorted.map { it.name },
        )
    }

    @Test
    fun `FOLDERS_FIRST is case-insensitive within a kind`() {
        val sorted = listOf(file("Beta"), file("alpha"), file("Charlie"))
            .sortedWith(RemoteEntry.FOLDERS_FIRST)
        assertEquals(listOf("alpha", "Beta", "Charlie"), sorted.map { it.name })
    }

    @Test
    fun `symlink and other sort with files after directories`() {
        val link = RemoteEntry("link", RemoteEntry.Type.SYMLINK, 0L, null)
        val other = RemoteEntry("sock", RemoteEntry.Type.OTHER, 0L, null)
        val sorted = listOf(file("zfile"), link, other, dir("dir"))
            .sortedWith(RemoteEntry.FOLDERS_FIRST)
        assertEquals("dir", sorted.first().name)
        // Everything non-directory keeps case-insensitive name order after the dir.
        assertEquals(listOf("dir", "link", "sock", "zfile"), sorted.map { it.name })
    }

    @Test
    fun `RemoteListing carries entries and truncated flag`() {
        val listing = RemoteListing(entries = listOf(dir("a")), truncated = true)
        assertEquals(1, listing.entries.size)
        assertEquals(true, listing.truncated)
    }

    @Test
    fun `typed exceptions are SshException subclasses with the path`() {
        val notDir = SshNotADirectoryException("/etc/hosts")
        val denied = SshPermissionDeniedException("/root")
        assertEquals("/etc/hosts", notDir.remotePath)
        assertEquals("/root", denied.remotePath)
        assert(notDir is SshException)
        assert(denied is SshException)
    }
}
