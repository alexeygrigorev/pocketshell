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
    fun `comparator NAME ascending matches FOLDERS_FIRST default`() {
        val entries = listOf(file("apple"), dir("zebra"), file("banana"), dir("alpha"))
        val viaComparator = entries.sortedWith(RemoteEntry.comparator(SortField.NAME, ascending = true))
        val viaDefault = entries.sortedWith(RemoteEntry.FOLDERS_FIRST)
        assertEquals(viaDefault.map { it.name }, viaComparator.map { it.name })
    }

    @Test
    fun `comparator NAME descending reverses within folders-first`() {
        val sorted = listOf(file("apple"), dir("zebra"), file("banana"), dir("alpha"))
            .sortedWith(RemoteEntry.comparator(SortField.NAME, ascending = false))
        // Folders still first, but each group is reverse-name ordered.
        assertEquals(listOf("zebra", "alpha", "banana", "apple"), sorted.map { it.name })
    }

    @Test
    fun `comparator SIZE orders files by bytes within folders-first`() {
        val sorted = listOf(
            file("big", 9000L),
            file("small", 10L),
            dir("dir"),
            file("mid", 500L),
        ).sortedWith(RemoteEntry.comparator(SortField.SIZE, ascending = true))
        assertEquals(listOf("dir", "small", "mid", "big"), sorted.map { it.name })

        val desc = listOf(file("big", 9000L), file("small", 10L), file("mid", 500L))
            .sortedWith(RemoteEntry.comparator(SortField.SIZE, ascending = false))
        assertEquals(listOf("big", "mid", "small"), desc.map { it.name })
    }

    @Test
    fun `comparator MODIFIED orders by mtime and sorts null mtime last`() {
        val a = RemoteEntry("a", RemoteEntry.Type.FILE, 0L, 100L)
        val b = RemoteEntry("b", RemoteEntry.Type.FILE, 0L, 300L)
        val c = RemoteEntry("c", RemoteEntry.Type.FILE, 0L, null)
        val ascending = listOf(b, c, a)
            .sortedWith(RemoteEntry.comparator(SortField.MODIFIED, ascending = true))
        // null mtime always sorts last regardless of direction.
        assertEquals(listOf("a", "b", "c"), ascending.map { it.name })

        val descending = listOf(b, c, a)
            .sortedWith(RemoteEntry.comparator(SortField.MODIFIED, ascending = false))
        assertEquals("c", descending.last().name)
        assertEquals(listOf("b", "a"), descending.take(2).map { it.name })
    }

    @Test
    fun `comparator keeps folders first regardless of field and direction`() {
        val entries = listOf(
            file("zfile", 1L),
            dir("zdir"),
            file("afile", 9L),
            dir("adir"),
        )
        for (field in SortField.values()) {
            for (asc in listOf(true, false)) {
                val sorted = entries.sortedWith(RemoteEntry.comparator(field, asc))
                val firstTwo = sorted.take(2).map { it.type }
                assertEquals(
                    "folders first for $field asc=$asc",
                    listOf(RemoteEntry.Type.DIRECTORY, RemoteEntry.Type.DIRECTORY),
                    firstTwo,
                )
            }
        }
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
