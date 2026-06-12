package com.pocketshell.app.fileviewer

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #719 — proves the Save action's MediaStore.Downloads path actually
 * writes to the device's public Downloads (the real
 * `ContentResolver.insert` + `openOutputStream` + clear-pending sequence) and
 * does NOT crash on modern Android, the regression that the previous
 * `DownloadManager.addCompletedDownload` call hit
 * (`SecurityException: Invalid value for visibility: 2`).
 *
 * It also confirms byte fidelity: the bytes read back from the inserted URI
 * match the source exactly, for both a text and a binary payload.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerSaveMediaStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun saveTextFileWritesToDownloadsWithMatchingBytes() {
        // The MediaStore.Downloads scoped-storage path is Q+ (the prod code
        // takes a legacy branch on < Q, which has no scoped storage to test).
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val name = "pocketshell-test-${System.nanoTime()}.txt"
        val bytes = "hello from PocketShell\nсохранение файла\n".toByteArray(Charsets.UTF_8)

        val uri = saveViaMediaStore(context, name, "text/plain", bytes)

        val written = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(bytes, written)

        // Cleanup so reruns don't accumulate Downloads entries.
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun saveBinaryFileWritesToDownloadsWithMatchingBytes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val name = "pocketshell-test-${System.nanoTime()}.bin"
        val bytes = ByteArray(2048) { (it % 256).toByte() }

        val uri = saveViaMediaStore(context, name, "application/octet-stream", bytes)

        val written = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(bytes, written)
        assertTrue(uri.toString().isNotBlank())

        context.contentResolver.delete(uri, null, null)
    }
}
