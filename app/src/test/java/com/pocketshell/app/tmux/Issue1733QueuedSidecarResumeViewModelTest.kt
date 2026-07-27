package com.pocketshell.app.tmux

import com.pocketshell.app.composer.DurableAttachmentRef
import com.pocketshell.app.composer.InMemoryOutboundQueueStore
import com.pocketshell.app.composer.LocalAttachmentSidecarRef
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.appendAttachmentPaths
import com.pocketshell.app.composer.asWireAttemptDurableStore
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.QueueSidecarResumableUploadRequest
import com.pocketshell.core.ssh.QueueSidecarResumableUploadResult
import com.pocketshell.core.ssh.QueueSidecarUploadDisposition
import com.pocketshell.core.ssh.QueueSidecarUploadProgress
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class Issue1733QueuedSidecarResumeViewModelTest : TmuxSessionViewModelTestBase() {

    @Test
    fun realPaneAndStableQueueIdOwnTheDurableWireAttempt() {
        val paneId = "%12"
        val cleanText = "issue1733 resumable attachment prompt"
        val attachment = DurableAttachmentRef(
            remotePath = "~/.pocketshell/attachments/host/session/proof.bin",
            displayName = "proof.bin",
        )
        val payload = appendAttachmentPaths(cleanText, listOf(attachment.remotePath))
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue(
            sessionKey = "tmux:1:\$7:1784911681",
            cleanText = cleanText,
            attachments = listOf(attachment),
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
        )
        val otherSessionRow = store.enqueue(
            sessionKey = "tmux:1:\$7:1784919999",
            cleanText = cleanText,
            attachments = listOf(attachment),
            paneId = paneId,
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
        )
        val ledger = OutboundDeliveryLedger(durable = store.asWireAttemptDurableStore())
        val durableRow = DurableOutboundRowIdentity(
            sessionKey = row.sessionKey,
            rowId = row.id,
        )

        // Normal durable delivery uses the row id as the stable retry token, but
        // durable ownership is the exact session+row identity. Pane and payload
        // are deliberately not authorities: another session may own the same %12
        // and send the same bytes.
        ledger.recordWireAttempt(
            paneId = paneId,
            sendToken = row.id,
            payload = payload,
            baselineCount = 0,
            durableRow = durableRow,
        )

        assertTrue(store.hasWireAttempt(row.sessionKey, row.id))
        assertEquals(false, store.hasWireAttempt(otherSessionRow.sessionKey, otherSessionRow.id))

        val rebuiltLedger = OutboundDeliveryLedger(durable = store.asWireAttemptDurableStore())
        assertTrue(
            rebuiltLedger.hasAmbiguousAttempt(
                paneId = paneId,
                sendToken = row.id,
                payload = payload,
                durableRow = durableRow,
            ),
        )
        assertEquals(
            false,
            rebuiltLedger.hasAmbiguousAttempt(
                paneId = paneId,
                sendToken = otherSessionRow.id,
                payload = payload,
                durableRow = DurableOutboundRowIdentity(
                    sessionKey = otherSessionRow.sessionKey,
                    rowId = otherSessionRow.id,
                ),
            ),
        )
    }

    @Test
    fun durableSidecarIdentityAndFinalPathStayStableAcrossAttempts() = runTest(scheduler) {
        val local = File.createTempFile("issue1733-vm", ".bin").apply {
            writeBytes(ByteArray(64) { it.toByte() })
            deleteOnExit()
        }
        val ref = LocalAttachmentSidecarRef(
            id = "durable-sidecar-id",
            outboundItemId = "row-1733",
            localPath = local.absolutePath,
            displayName = "proof.bin",
            mimeType = "application/octet-stream",
            byteSize = local.length(),
            createdAtMs = 1_725_000_000_000L,
            attachmentIndex = 3,
        )
        val session = RecordingSession()
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1733L,
            hostName = "Issue 1733",
            host = "test",
            port = 22,
            user = "test",
            keyPath = "",
            sessionName = "resume",
            client = FakeTmuxClient(),
            session = session,
        )

        session.nextFailure = SshException("controlled transport cut")
        val first = vm.uploadQueuedAttachmentSidecars(listOf(ref))
        assertTrue(first.isFailure)

        val second = vm.uploadQueuedAttachmentSidecars(listOf(ref)).getOrThrow()
        assertEquals(1, second.size)
        assertEquals(2, session.requests.size)
        val firstRequest = session.requests[0]
        val secondRequest = session.requests[1]
        assertEquals(ref.id, firstRequest.stableToken)
        assertEquals(firstRequest.stableToken, secondRequest.stableToken)
        assertEquals(ref.byteSize, secondRequest.expectedBytes)
        assertEquals(firstRequest.remotePath, secondRequest.remotePath)
        assertTrue(
            "the original durable attachment index, not pending-batch order, owns the final name",
            secondRequest.remotePath.contains("-04-proof.bin"),
        )
        assertEquals(secondRequest.remotePath.replace(".pocketshell/attachments", "~/.pocketshell/attachments"), second.single())
    }

    @Test
    fun alreadyCompleteIsMappedToSuccessWithoutASecondByteTransfer() = runTest(scheduler) {
        val local = File.createTempFile("issue1733-complete", ".txt").apply {
            writeText("already complete")
            deleteOnExit()
        }
        val ref = LocalAttachmentSidecarRef(
            id = "already-complete-sidecar",
            outboundItemId = "row-complete",
            localPath = local.absolutePath,
            displayName = "complete.txt",
            mimeType = "text/plain",
            byteSize = local.length(),
            createdAtMs = 1_725_000_000_000L,
            attachmentIndex = 0,
        )
        val session = RecordingSession().apply {
            nextDisposition = QueueSidecarUploadDisposition.AlreadyComplete
        }
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1733L,
            hostName = "Issue 1733",
            host = "test",
            port = 22,
            user = "test",
            keyPath = "",
            sessionName = "resume",
            client = FakeTmuxClient(),
            session = session,
        )

        val result = vm.uploadQueuedAttachmentSidecars(listOf(ref))
        assertTrue(result.isSuccess)
        assertEquals(QueueSidecarUploadDisposition.AlreadyComplete, session.dispositions.single())
        assertEquals(0L, session.transmitted.single())
    }

    private class RecordingSession : SshSession {
        val requests = mutableListOf<QueueSidecarResumableUploadRequest>()
        val dispositions = mutableListOf<QueueSidecarUploadDisposition>()
        val transmitted = mutableListOf<Long>()
        var nextFailure: Throwable? = null
        var nextDisposition = QueueSidecarUploadDisposition.Uploaded

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult = ExecResult("", "", 0)

        override suspend fun uploadQueueSidecar(
            request: QueueSidecarResumableUploadRequest,
            onProgress: ((QueueSidecarUploadProgress) -> Unit)?,
        ): QueueSidecarResumableUploadResult {
            requests += request
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            val disposition = nextDisposition
            val sent = if (disposition == QueueSidecarUploadDisposition.AlreadyComplete) 0L else request.expectedBytes
            dispositions += disposition
            transmitted += sent
            return QueueSidecarResumableUploadResult(
                remotePath = request.remotePath,
                resumedFromBytes = if (sent == 0L) request.expectedBytes else 0L,
                transmittedBytes = sent,
                disposition = disposition,
            )
        }

        override fun tail(path: String, onLine: (String) -> Unit) = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }
}
