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
    fun stagePersistsAttachmentIndicesAndRefsSortByAttachmentOrder() = runTest {
        newStore().stage(
            outboundItemId = "queue-1",
            uris = listOf(
                Uri.fromFile(sourceFile("third.txt", "third")),
                Uri.fromFile(sourceFile("first.txt", "first")),
            ),
            attachmentIndices = listOf(2, 0),
        )

        val reloaded = newStore().refsFor("queue-1")

        assertEquals(listOf("first.txt", "third.txt"), reloaded.map { it.displayName })
        assertEquals(listOf(0, 2), reloaded.map { it.attachmentIndex })
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
    fun reconcileAgainstLiveRowIdsRemovesOnlyProvenQueueOrphans() = runTest {
        val store = newStore()
        val orphan = store.stage(
            outboundItemId = "dead-row",
            uris = listOf(Uri.fromFile(sourceFile("gone.txt", "gone"))),
        ).single()
        val live = store.stage(
            outboundItemId = "live-row",
            uris = listOf(Uri.fromFile(sourceFile("keep.txt", "keep"))),
        ).single()
        val draft = store.stage(
            outboundItemId = "draft/tmux:1:\$2:3",
            uris = listOf(Uri.fromFile(sourceFile("draft.txt", "draft"))),
        ).single()

        // G6: if live-row-id membership were ignored, passing only live-row
        // would still keep dead-row (current reconcile() behavior).
        store.reconcile()
        assertEquals(listOf(orphan.id), newStore().refsFor("dead-row").map { it.id })

        newStore().reconcileAgainstLiveRowIds(setOf("live-row"))

        val repaired = newStore()
        assertTrue(repaired.refsFor("dead-row").isEmpty())
        assertFalse(File(orphan.localPath).exists())
        assertEquals(listOf(live.id), repaired.refsFor("live-row").map { it.id })
        assertEquals(listOf(draft.id), repaired.refsFor("draft/tmux:1:\$2:3").map { it.id })
        assertTrue(File(live.localPath).exists())
        assertTrue(File(draft.localPath).exists())
    }

    @Test
    fun reconcileReReadsLiveRowIdsBeforeDeletingEachSidecar() = runTest {
        val store = newStore()
        val live = store.stage(
            outboundItemId = "live-row",
            uris = listOf(Uri.fromFile(sourceFile("keep.txt", "keep"))),
        ).single()
        var reads = 0
        store.reconcileAgainstLiveRowIds {
            reads += 1
            // G6: if only the first snapshot were used, this live sidecar
            // would be deleted while its row is still queued.
            if (reads == 1) emptySet() else setOf("live-row")
        }
        assertTrue(File(live.localPath).exists())
        assertEquals(listOf(live.id), newStore().refsFor("live-row").map { it.id })
        assertTrue("must consult live ids more than once", reads >= 2)
    }

    @Test
    fun reconcileAgainstLiveRowIdsTombstonesRemotePathAfterMissingLocalCrashCut() = runTest {
        val store = newStore()
        val ref = store.stage(
            outboundItemId = "dead-row",
            uris = listOf(Uri.fromFile(sourceFile("uploaded.txt", "uploaded"))),
        ).single()
        store.markUploaded(mapOf(ref.id to "~/inbox/uploaded.txt"))

        // Deterministic crash cut: the local sidecar has already disappeared,
        // while the persisted ref still carries the completed remote upload.
        // A fresh store models the next process and must repair that evidence.
        File(ref.localPath).delete()
        val restarted = newStore()
        restarted.reconcileAgainstLiveRowIds(emptySet())

        assertTrue(restarted.allRefsIncludingMissingBlocking().isEmpty())
        assertFalse(File(ref.localPath).exists())
        assertEquals(
            "~/inbox/uploaded.txt",
            restarted.pendingTombstonesBlocking().single { it.sidecarId == ref.id }.remotePath,
        )
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
