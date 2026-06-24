package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundAttachmentSidecarStoreTest {

    private lateinit var context: Context
    private var nextId = 0
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME)
            .deleteRecursively()
        nextId = 0
        nowMs = 1_000L
    }

    @Test
    fun stageCopiesUriBytesAndMetadataSurvivesRestart() = runTest {
        val saved = newStore().stage(
            outboundItemId = "queue-1",
            uris = listOf(Uri.fromFile(sourceFile("report.txt", "queued bytes"))),
        ).single()

        assertEquals("queue-1", saved.outboundItemId)
        assertEquals("report.txt", saved.displayName)
        assertEquals("queued bytes".length.toLong(), saved.byteSize)
        assertEquals("queued bytes", File(saved.localPath).readText())

        val reloaded = newStore().refsFor("queue-1").single()
        assertEquals(saved, reloaded)
    }

    @Test
    fun multipleOutboundItemsDoNotBleedIntoEachOther() = runTest {
        val store = newStore()
        val first = store.stage("queue-a", listOf(Uri.fromFile(sourceFile("a.txt", "a")))).single()
        val second = store.stage("queue-b", listOf(Uri.fromFile(sourceFile("b.txt", "b")))).single()

        assertEquals(listOf(first), newStore().refsFor("queue-a"))
        assertEquals(listOf(second), newStore().refsFor("queue-b"))
    }

    @Test
    fun removeOutboundItemDeletesMetadataAndLocalBytes() = runTest {
        val store = newStore()
        val first = store.stage("queue-a", listOf(Uri.fromFile(sourceFile("a.txt", "a")))).single()
        val second = store.stage("queue-b", listOf(Uri.fromFile(sourceFile("b.txt", "b")))).single()

        newStore().removeOutboundItem("queue-a")

        assertFalse(File(first.localPath).exists())
        assertTrue(newStore().refsFor("queue-a").isEmpty())
        assertTrue(File(second.localPath).exists())
        assertEquals(listOf(second), newStore().refsFor("queue-b"))
    }

    @Test
    fun reconcileRemovesRowsMissingLocalBytesAndOrphanFiles() = runTest {
        val saved = newStore().stage(
            outboundItemId = "queue-1",
            uris = listOf(Uri.fromFile(sourceFile("data.bin", "payload"))),
        ).single()
        File(saved.localPath).delete()

        val orphan = File(attachmentDir(), "queue-2/orphan.bin").apply {
            parentFile?.mkdirs()
            writeText("orphan")
        }

        newStore().reconcile()

        assertTrue(newStore().refsFor("queue-1").isEmpty())
        assertFalse(orphan.exists())
    }

    private fun newStore(): OutboundAttachmentSidecarStore =
        OutboundAttachmentSidecarStore(context).also { store ->
            store.idGenerator = { "ref-${++nextId}" }
            store.clock = { nowMs++ }
        }

    private fun sourceFile(name: String, content: String): File =
        File(context.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun attachmentDir(): File =
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME)
}
