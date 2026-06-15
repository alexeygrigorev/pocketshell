package com.pocketshell.app.fileexplorer

import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.uikit.components.FileIconClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure unit tests over the explorer's row formatters / icon bucketing — issues
 * #528 and #762. No Compose, no Docker.
 */
class FileExplorerFormatTest {

    @Test
    fun `bytes under a kilobyte render raw`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun `kilobytes render with one decimal`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
    }

    @Test
    fun `megabytes and gigabytes scale up`() {
        assertEquals("1.0 MB", formatSize(1024L * 1024))
        assertEquals("2.0 GB", formatSize(2L * 1024 * 1024 * 1024))
    }

    // --- #762: modified-date formatter ---

    private val nowRef: ZonedDateTime =
        ZonedDateTime.of(2026, 6, 14, 12, 0, 0, 0, ZoneId.of("UTC"))

    private fun epoch(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 9, 0, 0, 0, ZoneId.of("UTC")).toEpochSecond()

    @Test
    fun `null mtime returns null so the subtitle collapses`() {
        assertNull(formatModifiedAt(null, nowRef))
    }

    @Test
    fun `this-year mtime renders as month and day`() {
        assertEquals("Jun 12", formatModifiedAt(epoch(2026, 6, 12), nowRef))
        assertEquals("Jan 3", formatModifiedAt(epoch(2026, 1, 3), nowRef))
    }

    @Test
    fun `older mtime renders as month and year`() {
        assertEquals("Jan 2024", formatModifiedAt(epoch(2024, 1, 15), nowRef))
        assertEquals("Dec 2025", formatModifiedAt(epoch(2025, 12, 31), nowRef))
    }

    // --- #762: row subtitle composition (size · modified) ---

    @Test
    fun `file subtitle is size dot modified when mtime is present`() {
        val sub = rowSubtitle(RemoteEntry("a.txt", RemoteEntry.Type.FILE, 1536, epochThisYear()))
        // The date half depends on system zone vs the test, so just check the
        // size half and the separator are present.
        assert(sub != null && sub.startsWith("1.5 KB · ")) { "got: $sub" }
    }

    @Test
    fun `file subtitle is just size when mtime is null`() {
        assertEquals("1.5 KB", rowSubtitle(RemoteEntry("a.txt", RemoteEntry.Type.FILE, 1536, null)))
    }

    @Test
    fun `folder subtitle is null when mtime is null`() {
        assertNull(rowSubtitle(RemoteEntry("dir", RemoteEntry.Type.DIRECTORY, 0L, null)))
    }

    private fun epochThisYear(): Long =
        Instant.now().minusSeconds(86_400).epochSecond

    // --- #762: icon bucketing ---

    @Test
    fun `directories and symlinks map to folder and link icons`() {
        assertEquals(FileIconClass.FOLDER, fileIconClass("anything", RemoteEntry.Type.DIRECTORY))
        assertEquals(FileIconClass.SYMLINK, fileIconClass("link", RemoteEntry.Type.SYMLINK))
    }

    @Test
    fun `image extensions map to the image icon`() {
        for (ext in listOf("png", "JPG", "jpeg", "gif", "webp", "svg")) {
            assertEquals("ext=$ext", FileIconClass.IMAGE, fileIconClass("photo.$ext", RemoteEntry.Type.FILE))
        }
    }

    @Test
    fun `code and text extensions map to the code icon`() {
        for (ext in listOf("kt", "java", "py", "sh", "json", "yaml", "md", "txt", "log", "patch", "diff")) {
            assertEquals("ext=$ext", FileIconClass.CODE, fileIconClass("file.$ext", RemoteEntry.Type.FILE))
        }
    }

    @Test
    fun `archive extensions map to the archive icon`() {
        for (ext in listOf("zip", "tar", "gz", "tgz", "7z")) {
            assertEquals("ext=$ext", FileIconClass.ARCHIVE, fileIconClass("bundle.$ext", RemoteEntry.Type.FILE))
        }
    }

    @Test
    fun `unknown extensions dotfiles and OTHER map to binary`() {
        assertEquals(FileIconClass.BINARY, fileIconClass("app.bin", RemoteEntry.Type.FILE))
        assertEquals(FileIconClass.BINARY, fileIconClass("noext", RemoteEntry.Type.FILE))
        // A leading-dot dotfile has no "extension" — treated as binary, not code.
        assertEquals(FileIconClass.BINARY, fileIconClass(".bashrc", RemoteEntry.Type.FILE))
        assertEquals(FileIconClass.BINARY, fileIconClass("socket", RemoteEntry.Type.OTHER))
    }
}
