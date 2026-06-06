package com.pocketshell.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests over the exec-based directory listing parse/build/classify
 * helpers — issue #528. No network, no Docker.
 */
class ListDirectoryParseTest {

    @Test
    fun `buildListDirCommand quotes the path and emits sentinels`() {
        val cmd = buildListDirCommand("/var/log")
        assertTrue("path is single-quoted", cmd.contains("d='/var/log'"))
        assertTrue("not-a-dir sentinel present", cmd.contains("exit $PROBE_EXIT_NOT_A_DIR"))
        assertTrue("no-such sentinel present", cmd.contains("exit $PROBE_EXIT_NO_SUCH"))
        assertTrue("denied sentinel present", cmd.contains("exit $PROBE_EXIT_DENIED"))
        assertTrue("stat format present", cmd.contains("-c '%F${LIST_FIELD_SEP}%s"))
    }

    @Test
    fun `buildListDirCommand escapes embedded single quotes`() {
        val cmd = buildListDirCommand("/it's/a dir")
        assertTrue(cmd.contains("d='/it'\\''s/a dir'"))
    }

    @Test
    fun `buildListDirCommand expands a leading tilde for HOME-relative dirs`() {
        // Issue #558 bug 3: a `~/...` directory must expand to $HOME.
        val cmd = buildListDirCommand("~/git/pocketshell")
        assertTrue(cmd, cmd.contains("d=~/'git/pocketshell'"))
        assertTrue(cmd, !cmd.contains("d='~/"))
    }

    @Test
    fun `parseListing drops the listed directory row and dotdot`() {
        val stdout = listOf(
            "directory|4096|1700000000|/home/u/proj",
            "directory|4096|1700000100|/home/u/proj/sub",
            "regular file|512|1700000200|/home/u/proj/file.txt",
        ).joinToString("\n")
        val listing = parseListing(stdout, "/home/u/proj", maxEntries = 100)
        assertFalse(listing.truncated)
        assertEquals(listOf("sub", "file.txt"), listing.entries.map { it.name })
        val sub = listing.entries.first { it.name == "sub" }
        assertEquals(RemoteEntry.Type.DIRECTORY, sub.type)
        assertEquals(0L, sub.sizeBytes)
        val file = listing.entries.first { it.name == "file.txt" }
        assertEquals(RemoteEntry.Type.FILE, file.type)
        assertEquals(512L, file.sizeBytes)
        assertEquals(1700000200L, file.modifiedEpochSec)
    }

    @Test
    fun `parseListing tolerates a trailing slash on the listed dir`() {
        val stdout = "directory|4096|1|/data/\nregular file|3|2|/data/a.txt"
        val listing = parseListing(stdout, "/data/", maxEntries = 100)
        assertEquals(listOf("a.txt"), listing.entries.map { it.name })
    }

    @Test
    fun `parseListing keeps a pipe inside a filename`() {
        val stdout = "regular file|7|10|/d/weird|name.txt"
        val listing = parseListing(stdout, "/d", maxEntries = 100)
        assertEquals(listOf("weird|name.txt"), listing.entries.map { it.name })
    }

    @Test
    fun `parseListing maps symlink and other types`() {
        val stdout = listOf(
            "symbolic link|10|1|/d/link",
            "fifo|0|1|/d/pipe",
            "socket|0|1|/d/sock",
        ).joinToString("\n")
        val listing = parseListing(stdout, "/d", maxEntries = 100)
        assertEquals(RemoteEntry.Type.SYMLINK, listing.entries[0].type)
        assertEquals(RemoteEntry.Type.OTHER, listing.entries[1].type)
        assertEquals(RemoteEntry.Type.OTHER, listing.entries[2].type)
    }

    @Test
    fun `parseListing treats mtime 0 as null`() {
        val stdout = "regular file|3|0|/d/a.txt"
        val listing = parseListing(stdout, "/d", maxEntries = 100)
        assertNull(listing.entries.single().modifiedEpochSec)
    }

    @Test
    fun `parseListing caps at maxEntries and flags truncated`() {
        val stdout = (1..10).joinToString("\n") { "regular file|1|1|/d/f$it" }
        val listing = parseListing(stdout, "/d", maxEntries = 4)
        assertEquals(4, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `parseListing skips malformed lines`() {
        val stdout = "garbage\n\nregular file|2|1|/d/ok.txt\nalso|bad"
        val listing = parseListing(stdout, "/d", maxEntries = 100)
        assertEquals(listOf("ok.txt"), listing.entries.map { it.name })
    }

    @Test
    fun `parseStatType folds the human type words`() {
        assertEquals(RemoteEntry.Type.DIRECTORY, parseStatType("directory"))
        assertEquals(RemoteEntry.Type.FILE, parseStatType("regular file"))
        assertEquals(RemoteEntry.Type.FILE, parseStatType("regular empty file"))
        assertEquals(RemoteEntry.Type.SYMLINK, parseStatType("symbolic link"))
        assertEquals(RemoteEntry.Type.OTHER, parseStatType("block special file"))
    }

    @Test
    fun `classifyListFailure maps sentinel exit codes`() {
        fun probe(code: Int) = ExecResult(stdout = "", stderr = "boom", exitCode = code)
        assertTrue(classifyListFailure("/p", probe(PROBE_EXIT_NOT_A_DIR)) is SshNotADirectoryException)
        assertTrue(classifyListFailure("/p", probe(PROBE_EXIT_NO_SUCH)) is SshFileNotFoundException)
        assertTrue(classifyListFailure("/p", probe(PROBE_EXIT_DENIED)) is SshPermissionDeniedException)
        // Any other non-zero exit is a generic SshException (not a subclass).
        val generic = classifyListFailure("/p", probe(99))
        assertEquals(SshException::class.java, generic.javaClass)
    }
}
