package com.pocketshell.next.files

import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.core.transport.SftpFileTooLargeException
import com.pocketshell.uikit.components.FileIconClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The explorer's pure presentation helpers (task P-3a).
 *
 * Robolectric only for [OpenableColumns]' constants; nothing here renders.
 */
@RunWith(AndroidJUnit4::class)
class FileFormattingTest {

    @Test
    fun `sizes are formatted at the unit a human reads`() {
        assertEquals("0 B", formatSize(0))
        assertEquals("512 B", formatSize(512))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("2.0 MB", formatSize(2L * 1024 * 1024))
        assertEquals("1.5 GB", formatSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a display name with separators reduces to one safe leaf`() {
        assertEquals("notes.txt", sanitizeUploadName("notes.txt"))
        assertEquals("authorized_keys", sanitizeUploadName("../../.ssh/authorized_keys"))
        assertEquals("shot.png", sanitizeUploadName("C:\\Users\\me\\shot.png"))
        assertEquals("upload", sanitizeUploadName(""))
        assertEquals("upload", sanitizeUploadName("../"))
        assertEquals("upload", sanitizeUploadName("."))
    }

    @Test
    fun `a control character in a provider-supplied name is stripped`() {
        assertEquals("evil.txt", sanitizeUploadName("evil\u0007.txt"))
        assertEquals("ab.txt", sanitizeUploadName("a\nb.txt"))
    }

    @Test
    fun `relative times read forwards and a skewed clock reads as nothing`() {
        val now = 1_700_000_000_000L
        assertNull("no mtime reported", relativeTime(0, now))
        assertNull("a future mtime must not render as negative", relativeTime(now + 60_000, now))
        assertEquals("just now", relativeTime(now - 5_000, now))
        assertEquals("3m ago", relativeTime(now - TimeUnit.MINUTES.toMillis(3), now))
        assertEquals("5h ago", relativeTime(now - TimeUnit.HOURS.toMillis(5), now))
        assertEquals("2d ago", relativeTime(now - TimeUnit.DAYS.toMillis(2), now))
        assertEquals("1y ago", relativeTime(now - TimeUnit.DAYS.toMillis(400), now))
    }

    @Test
    fun `a directory row shows no size, because SFTP reports the inode's not the tree's`() {
        val now = 1_700_000_000_000L
        val directory = SftpEntry("/w/src", isDirectory = true, sizeBytes = 4096, modifiedEpochMs = now)
        val file = SftpEntry("/w/a.txt", isDirectory = false, sizeBytes = 2048, modifiedEpochMs = now)

        assertEquals("just now", rowSubtitle(directory, now))
        assertEquals("2.0 KB · just now", rowSubtitle(file, now))
        assertNull(rowSubtitle(directory.copy(modifiedEpochMs = 0), now))
    }

    @Test
    fun `icons come from the shared ui-kit map, with folders overriding the name`() {
        assertEquals(
            FileIconClass.FOLDER,
            iconClassFor(SftpEntry("/w/images", isDirectory = true, sizeBytes = 0, modifiedEpochMs = 0)),
        )
        assertEquals(
            FileIconClass.IMAGE,
            iconClassFor(SftpEntry("/w/a.png", isDirectory = false, sizeBytes = 0, modifiedEpochMs = 0)),
        )
    }

    @Test
    fun `an over-size read is explained with the real numbers, not a bare IOException`() {
        val text = message(
            SftpFileTooLargeException("/w/huge.log", sizeBytes = 50L * 1024 * 1024, maxBytes = 12L * 1024 * 1024),
        )

        assertTrue("expected the file name, got $text", text.contains("huge.log"))
        assertTrue("expected the actual size, got $text", text.contains("50.0 MB"))
        assertTrue("expected the limit, got $text", text.contains("12.0 MB"))
    }

    @Test
    fun `a throwable with no message still produces something readable`() {
        assertEquals("IllegalStateException", message(IllegalStateException()))
    }

    @Test
    fun `a picked document reports its provider name and size`() {
        val document = describeDocument(
            queryColumns = {
                mapOf(
                    OpenableColumns.DISPLAY_NAME to "report.pdf",
                    OpenableColumns.SIZE to "2048",
                )
            },
            fallbackName = "unused",
        )

        assertEquals("report.pdf", document.name)
        assertEquals(2048L, document.size)
    }

    @Test
    fun `a provider that reports nothing falls back to the URI's last segment`() {
        val document = describeDocument(queryColumns = { null }, fallbackName = "image-42.jpg")

        assertEquals("image-42.jpg", document.name)
        assertEquals(-1L, document.size)
    }

    @Test
    fun `a provider that throws does not take the upload down with it`() {
        val document = describeDocument(
            queryColumns = { error("provider died") },
            fallbackName = "fallback.bin",
        )

        assertEquals("fallback.bin", document.name)
        assertEquals(-1L, document.size)
    }

    @Test
    fun `a provider-supplied path in the display name is sanitised at the source`() {
        val document = describeDocument(
            queryColumns = { mapOf(OpenableColumns.DISPLAY_NAME to "../../etc/passwd") },
            fallbackName = "unused",
        )

        assertEquals("passwd", document.name)
    }
}
