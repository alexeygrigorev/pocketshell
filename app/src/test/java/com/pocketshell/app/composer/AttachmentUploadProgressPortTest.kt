package com.pocketshell.app.composer

import com.pocketshell.core.ssh.QueueSidecarUploadProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AttachmentUploadProgressPortTest {

    @Before
    fun setUp() {
        AttachmentUploadProgressPort.resetForTest()
    }

    @After
    fun tearDown() {
        AttachmentUploadProgressPort.resetForTest()
    }

    @Test
    fun attachTimePublishesZeroMidTotalThenClears() {
        AttachmentUploadProgressPort.beginAttach(
            listOf(AttachmentFileSpec("a", "report.zip", 5_242_880L)),
        )
        AttachmentUploadProgressPort.onAttachFileProgress(0, 0L, 5_242_880L, force = true)
        assertEquals(0L, AttachmentUploadProgressPort.attachProgress.value?.bytesTransferred)
        AttachmentUploadProgressPort.onAttachFileProgress(0, 2_621_440L, 5_242_880L, force = true)
        val mid = requireNotNull(AttachmentUploadProgressPort.attachProgress.value)
        assertEquals(2_621_440L, mid.bytesTransferred)
        assertEquals("Uploading 2.5 / 5.0 MB · 50%", formatAttachmentTransferLabel(mid))
        AttachmentUploadProgressPort.onAttachFileProgress(0, 5_242_880L, 5_242_880L, force = true)
        assertEquals(100, AttachmentUploadProgressPort.attachProgress.value?.percent)
        AttachmentUploadProgressPort.endAttach()
        assertNull(AttachmentUploadProgressPort.attachProgress.value)
    }

    @Test
    fun queueProgressIsKeyedToExactRowAndSession() {
        val rowA = sidecar("row-a", "file-a", 52L)
        val rowB = sidecar("row-b", "file-b", 52L)
        val rowC = sidecar("row-c", "file-c", 52L, outboundItemId = "row-c")
        AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-1",
            rowId = "row-a",
            files = listOf(AttachmentFileSpec(rowA.id, rowA.displayName, rowA.byteSize)),
        )
        AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-1",
            rowId = "row-b",
            files = listOf(AttachmentFileSpec(rowB.id, rowB.displayName, rowB.byteSize)),
        )
        AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-2",
            rowId = "row-c",
            files = listOf(AttachmentFileSpec(rowC.id, rowC.displayName, rowC.byteSize)),
        )

        AttachmentUploadProgressPort.onSidecarProgress(
            rowA,
            QueueSidecarUploadProgress(
                checkpointPath = "/tmp/.part",
                resumedFromBytes = 30L,
                bytesTransferred = 40L,
                totalBytes = 52L,
            ),
        )

        val a = AttachmentUploadProgressPort.progressFor("session-1", "row-a")
        val b = AttachmentUploadProgressPort.progressFor("session-1", "row-b")
        val c = AttachmentUploadProgressPort.progressFor("session-2", "row-c")
        assertNotNull(a)
        assertEquals(40L, a!!.bytesTransferred)
        assertEquals(0L, b!!.bytesTransferred)
        assertEquals(0L, c!!.bytesTransferred)
        assertNull(AttachmentUploadProgressPort.progressFor("session-2", "row-a"))
    }

    @Test
    fun staleAttemptCallbackIsIgnoredAndCompletionClearsLiveProgress() {
        val ref = sidecar("row-a", "file-a", 100L)
        val first = AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-1",
            rowId = "row-a",
            files = listOf(AttachmentFileSpec(ref.id, ref.displayName, 100L)),
        )
        AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-1",
            rowId = "row-a",
            files = listOf(AttachmentFileSpec(ref.id, ref.displayName, 100L)),
        )
        AttachmentUploadProgressPort.onSidecarProgress(
            ref,
            QueueSidecarUploadProgress("/tmp/.part", 0L, 80L, 100L),
        )
        assertEquals(80L, AttachmentUploadProgressPort.progressFor("session-1", "row-a")?.bytesTransferred)
        AttachmentUploadProgressPort.endQueueUpload("session-1", "row-a", first)
        assertEquals(
            "stale end must not clear the live generation",
            80L,
            AttachmentUploadProgressPort.progressFor("session-1", "row-a")?.bytesTransferred,
        )
        AttachmentUploadProgressPort.endQueueUpload("session-1", "row-a")
        assertNull(AttachmentUploadProgressPort.progressFor("session-1", "row-a"))
    }

    @Test
    fun resumedQueueUploadStartsAboveZero() {
        val ref = sidecar("row-a", "big.zip", 52L)
        AttachmentUploadProgressPort.beginQueueUpload(
            sessionKey = "session-1",
            rowId = "row-a",
            files = listOf(AttachmentFileSpec(ref.id, ref.displayName, 52L)),
        )
        AttachmentUploadProgressPort.onSidecarProgress(
            ref,
            QueueSidecarUploadProgress("/tmp/.part", 30L, 30L, 52L),
        )
        val progress = requireNotNull(AttachmentUploadProgressPort.progressFor("session-1", "row-a"))
        assertTrue(progress.percent!! > 0)
        assertEquals(30L, progress.bytesTransferred)
    }

    private fun sidecar(
        rowId: String,
        name: String,
        bytes: Long,
        outboundItemId: String = rowId,
    ): LocalAttachmentSidecarRef = LocalAttachmentSidecarRef(
        id = "$rowId-$name",
        outboundItemId = outboundItemId,
        localPath = "/tmp/$name",
        displayName = name,
        mimeType = "application/zip",
        byteSize = bytes,
        createdAtMs = 1L,
    )
}
