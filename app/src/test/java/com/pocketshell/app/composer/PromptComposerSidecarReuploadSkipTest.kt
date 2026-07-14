package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.ssh.SshException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1588 (H5 from the #1562 message-queue audit): the SEND-TIME attachment
 * upload leg re-uploaded the FULL sidecar on every delivery retry — the #1563
 * 52MB-re-upload pain recurring on the send leg. Deterministic remote names kept
 * payload identity stable (no DUPLICATE), but each flaky-link retry re-transferred
 * the whole file, wasting bandwidth and clogging the queue.
 *
 * D33/G10 reproduce-first. Each test documents the BASE (RED) symptom and asserts
 * the fixed (GREEN) behaviour. The load-bearing assertion is the upload/transfer
 * count: on base a retry after a completed upload invokes the uploader a SECOND
 * time; with the fix the retry SKIPS the re-upload and resumes straight to send.
 *
 * Class coverage (G2):
 *  - retry after a COMPLETED upload → skip (the reported bug).
 *  - retry after a FAILED upload → still re-uploads (must NOT skip an incomplete one).
 *  - first send → uploads normally (the skip never fires spuriously).
 *  - a MIXED row (one sidecar already uploaded, one still pending) → uploads only
 *    the not-yet-uploaded sidecar (synthetic injection, #780 model, since the
 *    all-or-nothing upload path cannot persist a partial state naturally).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerSidecarReuploadSkipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    private class FakeVault(initial: CharArray? = null) : ApiKeyVault {
        private var key: CharArray? = initial?.copyOf()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class FakeMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = byteArrayOf(1)
        override fun currentAmplitude(): Float = 0f
    }

    private class FakeVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.OpenAiWhisper
    }

    private fun newVm(
        dispatcher: TestDispatcher,
        outboundQueueStore: OutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore?,
        composerDraftStore: ComposerDraftStore = InMemoryComposerDraftStore(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = savedStateHandle,
        )
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    private fun TestScope.collectSendRequests(
        vm: PromptComposerViewModel,
    ): MutableList<PromptComposerViewModel.SendRequest> {
        val collected = java.util.Collections.synchronizedList(
            mutableListOf<PromptComposerViewModel.SendRequest>(),
        )
        backgroundScope.launch { vm.sendRequests.collect { collected += it } }
        runCurrent()
        return collected
    }

    private fun newSidecarStore(
        ioDispatcher: TestDispatcher,
        context: Context = ApplicationProvider.getApplicationContext(),
    ): OutboundAttachmentSidecarStore {
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also { store ->
            store.idGenerator = { "sidecar-${++nextId}" }
            store.clock = { nextId.toLong() }
            store.ioDispatcher = ioDispatcher
        }
    }

    private fun pickedFile(name: String, content: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(File(context.cacheDir, "picked"), name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        return Uri.fromFile(file)
    }

    private fun preview(uri: Uri, mime: String? = "image/png") =
        PromptComposerViewModel.AttachmentPreview(uri, mime)

    private suspend fun TestScope.settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000L
        while (true) {
            advanceUntilIdle()
            if (predicate()) return
            runCurrent()
            if (predicate()) return
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return
            withContext(Dispatchers.IO) { Thread.sleep(1L) }
            if (predicate()) return
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                assertTrue("settleUntil timed out before predicate held", predicate())
                return
            }
        }
    }

    // ---- Load-bearing: retry after a completed upload SKIPS the re-upload -------

    @Test
    fun deliveryRetryAfterCompletedUploadDoesNotReTransferTheSidecar() = runTest {
        // BASE (RED): [uploadSidecarsForOutboundItem] always called `uploader(sidecars)`
        // with no already-uploaded skip, so a delivery retry after a COMPLETED upload
        // re-transferred the whole sidecar (uploadCalls == 2 for one logical file).
        // GREEN: the retry detects the row's authoritative uploaded path and resumes
        // straight to send (uploadCalls stays 1), delivering the same remote path.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("please review")

        // A retained-on-failure local pick → the Send takes the sidecar upload leg.
        val picked = pickedFile("photo.png", "REAL-PNG-BYTES")
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload of photo.png failed: Stream closed"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        // The uploader ALWAYS succeeds; we count every transfer it is asked to make.
        val uploadCalls = mutableListOf<List<String>>()
        val uploadedPath = "~/.pocketshell/attachments/session-a/uploaded-photo.png"
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadCalls += refs.map { it.displayName }
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
        }

        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { sent.isNotEmpty() }

        // First send: uploaded exactly once and emitted a delivery with the real path.
        assertEquals("first send uploads once", 1, uploadCalls.size)
        assertEquals(listOf(uploadedPath), sent.single().attachments.map { it.remotePath })

        // The link flaps: the delivery did NOT land, so the host defers the row back to
        // the queue for auto-retry (the #987 Option-A drop path). The bytes are already
        // on the remote (the upload completed) — only the paste failed.
        vm.markOutboundSendDeferred(sent.single())
        settleUntil { queue.itemsFor("1/session-a").singleOrNull()?.state == OutboundState.Queued }
        assertFalse(vm.uiState.value.sendInFlight)

        // Retry the SAME durable row.
        vm.retryNextOutboundItem()
        settleUntil { sent.size == 2 }

        // LOAD-BEARING (GREEN): the retry did NOT re-transfer the sidecar — the uploader
        // was invoked exactly ONCE across both delivery attempts. (BASE: size == 2.)
        assertEquals(
            "a delivery retry after a completed upload must NOT re-transfer the sidecar",
            1,
            uploadCalls.size,
        )
        // The retry still delivered, carrying the same already-uploaded remote path.
        assertEquals(2, sent.size)
        assertEquals(listOf(uploadedPath), sent.last().attachments.map { it.remotePath })
    }

    // ---- Class coverage: a FAILED upload still re-uploads (never skip incomplete) --

    @Test
    fun retryAfterFailedUploadStillReUploadsBecauseItNeverCompleted() = runTest {
        // The skip is gated on the durable already-uploaded marker (an authoritative,
        // non-`pending-upload` path on the row). A row whose FIRST upload FAILED never
        // got that marker, so a retry MUST re-upload — otherwise a never-uploaded file
        // would be "sent" with a provisional path (regression). uploadCalls == 2 here,
        // and the second attempt (success) delivers the real path.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("retry me")

        val picked = pickedFile("doc.pdf", "PDF-BYTES")
        vm.attachFiles(count = 1, previews = listOf(preview(picked, "application/pdf"))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)

        val uploadCalls = mutableListOf<List<String>>()
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadCalls += refs.map { it.displayName }
            if (uploadCalls.size == 1) {
                Result.failure(SshException("upload failed: Stream closed"))
            } else {
                Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
            }
        }

        val target = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { queue.itemsFor("1/session-a").singleOrNull()?.state == OutboundState.Queued }

        // First upload failed → nothing delivered, row still queued/retryable.
        assertEquals("first upload attempt was made", 1, uploadCalls.size)
        assertTrue("no delivery on the failed upload", sent.isEmpty())

        // Retry: because the upload never completed, it MUST re-upload (attempt 2).
        vm.retryNextOutboundItem()
        settleUntil { sent.isNotEmpty() }
        assertEquals(
            "a retry after a FAILED upload must re-upload (never skip an incomplete one)",
            2,
            uploadCalls.size,
        )
        assertEquals(
            listOf("~/.pocketshell/attachments/session-a/uploaded-doc.pdf"),
            sent.single().attachments.map { it.remotePath },
        )
    }

    // ---- Class coverage: first send uploads normally (skip never fires spuriously) -

    @Test
    fun firstSendUploadsTheSidecarNormally() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")
        vm.onDraftChange("first")

        val picked = pickedFile("first.png", "FIRST")
        vm.attachFiles(count = 1, previews = listOf(preview(picked))) {
            Result.failure(SshException("Upload failed: Stream closed"))
        }
        advanceUntilIdle()

        val uploadCalls = mutableListOf<List<String>>()
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadCalls += refs.map { it.displayName }
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
        }
        vm.requestSend(
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/session-a"),
        )
        settleUntil { sent.isNotEmpty() }

        assertEquals("first send must upload the sidecar once", 1, uploadCalls.size)
        assertEquals(
            listOf("~/.pocketshell/attachments/session-a/uploaded-first.png"),
            sent.single().attachments.map { it.remotePath },
        )
    }

    // ---- Class coverage: a MIXED row uploads ONLY the not-yet-uploaded sidecar -----

    @Test
    fun mixedRowUploadsOnlyTheNotYetUploadedSidecar() = runTest {
        // Synthetic injection (#780 model): the all-or-nothing upload path cannot
        // persist a partial "one uploaded, one pending" state naturally, so we build
        // a durable row directly: two sidecars, one of which is durably MARKED as
        // already send-uploaded (its `uploadedRemotePath` recorded). The dispatch must
        // upload ONLY the not-yet-uploaded sidecar (index 1) and reuse the recorded
        // path for index 0.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = collectSendRequests(vm)
        vm.onComposerTargetChanged("1/session-a")

        val itemId = "item-mixed"
        val uriA = pickedFile("a.zip", "AAA")
        val uriB = pickedFile("b.log", "BBB")
        // Stage both files as sidecars for the row, index-aligned to the attachments.
        val staged = sidecars.stage(itemId, listOf(uriA, uriB), listOf(0, 1))
        assertEquals("both sidecars staged", 2, staged.size)

        val alreadyUploadedA = "~/.pocketshell/attachments/session-a/uploaded-a.zip"
        // a.zip's send-time upload already completed on a prior attempt — record it.
        val sidecarA = staged.single { it.attachmentIndex == 0 }
        sidecars.markUploaded(mapOf(sidecarA.id to alreadyUploadedA))

        val item = OutboundItem(
            id = itemId,
            sessionKey = "1/session-a",
            cleanText = "mixed",
            attachments = listOf(
                DurableAttachmentRef(
                    remotePath = pendingAttachmentRemotePath("1/session-a", 0, "a.zip"),
                    displayName = "a.zip",
                    mimeType = "application/zip",
                ),
                DurableAttachmentRef(
                    remotePath = pendingAttachmentRemotePath("1/session-a", 1, "b.log"),
                    displayName = "b.log",
                    mimeType = "text/plain",
                ),
            ),
            withEnter = true,
            state = OutboundState.Queued,
            createdAtMs = 1L,
        )
        queue.enqueueExisting(item)

        val uploadCalls = mutableListOf<List<String>>()
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadCalls += refs.map { it.displayName }
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/uploaded-${it.displayName}" })
        }

        vm.retryOutboundItem(itemId)
        settleUntil { sent.isNotEmpty() }

        // LOAD-BEARING: the uploader saw exactly ONE transfer, for the pending sidecar
        // (b.log) only — the already-uploaded a.zip was NOT re-transferred.
        assertEquals("only one sidecar re-uploaded", 1, uploadCalls.size)
        assertEquals(listOf("b.log"), uploadCalls.single())
        // The delivery carries the reused a.zip path AND the freshly-uploaded b.log path.
        assertEquals(
            listOf(alreadyUploadedA, "~/.pocketshell/attachments/session-a/uploaded-b.log"),
            sent.single().attachments.map { it.remotePath },
        )
    }
}
