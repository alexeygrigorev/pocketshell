package com.pocketshell.app.fileviewer

import android.os.Environment
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #719 — pins the MediaStore.Downloads insert values used by the file
 * viewer's Save action (replacing the crashing
 * `DownloadManager.addCompletedDownload` path). `ContentValues` needs the
 * Android runtime, so this runs under Robolectric. The IO/insert itself is
 * exercised on the emulator; here we pin the value-builder contract.
 */
@RunWith(RobolectricTestRunner::class)
class FileViewerSaveValuesTest {

    @Test
    fun downloadContentValuesSetsNameRelativePathAndPendingForKnownMime() {
        val values = downloadContentValues("notes.txt", "text/plain", pending = true)
        assertEquals("notes.txt", values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals("text/plain", values.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals(
            Environment.DIRECTORY_DOWNLOADS,
            values.getAsString(MediaStore.Downloads.RELATIVE_PATH),
        )
        assertEquals(1, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
    }

    @Test
    fun downloadContentValuesOmitsOctetStreamMimeSoMediaStoreInfers() {
        val values =
            downloadContentValues("blob.bin", "application/octet-stream", pending = true)
        assertFalse(values.containsKey(MediaStore.Downloads.MIME_TYPE))
        assertEquals("blob.bin", values.getAsString(MediaStore.Downloads.DISPLAY_NAME))
    }

    @Test
    fun downloadContentValuesOmitsBlankMime() {
        val values = downloadContentValues("blob", "", pending = true)
        assertFalse(values.containsKey(MediaStore.Downloads.MIME_TYPE))
    }

    @Test
    fun downloadContentValuesHonoursPendingFlag() {
        val values = downloadContentValues("a.png", "image/png", pending = false)
        assertEquals(0, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
    }

    @Test
    fun clearPendingValuesPublishesEntry() {
        val values = clearPendingValues()
        assertEquals(0, values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertTrue(values.keySet() == setOf(MediaStore.Downloads.IS_PENDING))
    }
}
