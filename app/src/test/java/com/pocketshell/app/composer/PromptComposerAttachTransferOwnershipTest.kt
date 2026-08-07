package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression-first coverage for issue #2036's attach-time/send-time double upload. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachTransferOwnershipTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDownViewModels() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    @Test
    fun successfulAttachWithPreviewIsUploadedOnlyOnceAndSendReusesExactRemotePath() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()

        val target = "1/session-a"
        val picked = pickedFile("preview.png", "PNG-BYTES")
        val remotePath = "~/.pocketshell/attachments/session-a/preview.png"
        var attachUploadInvocations = 0
        var sendUploadInvocations = 0
        vm.onComposerTargetChanged(target)
        vm.onDraftChange("inspect this")
        vm.attachFiles(
            count = 1,
            previews = listOf(PromptComposerViewModel.AttachmentPreview(picked, "image/png")),
        ) {
            attachUploadInvocations++
            Result.success(listOf(remotePath))
        }
        advanceUntilIdle()

        assertNotNull("the local thumbnail must survive successful remote staging", vm.uiState.value.attachments.single().previewUri)
        assertEquals(
            AttachmentTransferState.RemoteComplete,
            vm.uiState.value.attachments.single().transferState,
        )
        vm.setOutboundAttachmentSidecarUploader { refs ->
            sendUploadInvocations++
            Result.success(refs.map { "~/.pocketshell/attachments/session-a/duplicate-${it.displayName}" })
        }

        vm.requestSend(
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = target),
        )
        settleUntil { sent.isNotEmpty() }

        assertEquals("one logical prompt must reach the delivery channel", 1, sent.size)
        assertEquals(
            "Send must reuse the authoritative attach-time remote path",
            listOf(remotePath),
            sent.single().attachments.map { it.remotePath },
        )
        assertEquals(
            "successful attach-time staging plus Send must perform one total remote upload",
            1,
            attachUploadInvocations + sendUploadInvocations,
        )
    }

    @Test
    fun successfulMultiAttachKeepsPreviewsAndReusesEveryExactRemotePathWithNoSendUpload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = "1/session-multi"
        val paths = listOf(
            "~/.pocketshell/attachments/session-multi/one.png",
            "~/.pocketshell/attachments/session-multi/two.png",
        )
        vm.onComposerTargetChanged(target)
        vm.onDraftChange("two files")
        vm.attachFiles(
            count = 2,
            previews = listOf(
                PromptComposerViewModel.AttachmentPreview(pickedFile("one.png", "ONE"), "image/png"),
                PromptComposerViewModel.AttachmentPreview(pickedFile("two.png", "TWO"), "image/png"),
            ),
        ) { Result.success(paths) }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.all { it.previewUri != null })
        var sendUploads = 0
        vm.setOutboundAttachmentSidecarUploader { refs ->
            sendUploads += refs.size
            Result.success(refs.map { "duplicate-${it.displayName}" })
        }

        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = target))
        settleUntil { sent.isNotEmpty() }

        assertEquals(0, sendUploads)
        assertEquals(paths, sent.single().attachments.map { it.remotePath })
        assertEquals(1, sent.size)
    }

    @Test
    fun partialAttachUploadsOnlyFailedPendingFileOnSendAndPreservesSuccessfulRemoteRef() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore(dispatcher)
        val vm = newVm(dispatcher, queue, sidecars)
        val sent = mutableListOf<PromptComposerViewModel.SendRequest>()
        backgroundScope.launch { vm.sendRequests.collect { sent += it } }
        runCurrent()
        val target = "1/session-partial"
        val successfulPath = "~/.pocketshell/attachments/session-partial/first.png"
        val failedUri = pickedFile("second.png", "SECOND-BYTES")
        vm.onComposerTargetChanged(target)
        vm.onDraftChange("partial batch")
        vm.attachFiles(
            count = 2,
            previews = listOf(
                PromptComposerViewModel.AttachmentPreview(pickedFile("first.png", "FIRST"), "image/png"),
                PromptComposerViewModel.AttachmentPreview(failedUri, "image/png"),
            ),
        ) {
            Result.failure(
                PartialAttachmentUploadException(
                    uploadedPaths = listOf(successfulPath),
                    uploadedAttachmentIndices = listOf(0),
                    failedAttachmentIndices = listOf(1),
                    failedCount = 1,
                    message = "Attached 1 of 2 files; 1 failed",
                ),
            )
        }
        advanceUntilIdle()
        assertEquals(
            "both the remote-complete and pending-local tiles must remain editable: ${vm.uiState.value}",
            2,
            vm.uiState.value.attachments.size,
        )
        val uploadNames = mutableListOf<List<String>>()
        val recoveredPath = "~/.pocketshell/attachments/session-partial/second.png"
        vm.setOutboundAttachmentSidecarUploader { refs ->
            uploadNames += refs.map { it.displayName }
            Result.success(listOf(recoveredPath))
        }

        vm.requestSend(true, PromptComposerViewModel.SendTargetSnapshot(sessionKey = target))
        settleUntil { sent.isNotEmpty() }

        assertEquals(listOf(listOf("second.png")), uploadNames)
        assertEquals(listOf(successfulPath, recoveredPath), sent.single().attachments.map { it.remotePath })
        assertEquals(1, sent.size)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.settleUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 40_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            runCurrent()
            if (predicate()) break
            advanceTimeBy(1L)
            runCurrent()
            withContext(Dispatchers.IO) { Thread.sleep(1L) }
        }
        check(predicate()) { "settleUntil timed out" }
    }

    private fun newVm(
        dispatcher: TestDispatcher,
        queue: OutboundQueueStore,
        sidecars: OutboundAttachmentSidecarStore,
    ): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = FakeMicCapture(),
        whisperClientFactory = WhisperClientFactory { null },
        apiKeyStorage = FakeVault(),
        voiceSettings = FakeVoiceSettings(),
        speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
        composerDraftStore = InMemoryComposerDraftStore(),
        outboundQueueStore = queue,
        outboundAttachmentSidecarStore = sidecars,
        savedStateHandle = SavedStateHandle(),
    ).also { vm ->
        vm.samplerDispatcher = dispatcher
        vm.outboundQueueDispatcher = dispatcher
        vm.setSendWatchdogTimeoutForTest(null)
        createdViewModels += vm
    }

    private fun newSidecarStore(dispatcher: TestDispatcher): OutboundAttachmentSidecarStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        return OutboundAttachmentSidecarStore(context).also {
            it.ioDispatcher = dispatcher
            it.idGenerator = { "sidecar-${System.nanoTime()}" }
        }
    }

    private fun pickedFile(name: String, content: String): Uri {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(File(context.cacheDir, "issue-2036"), name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
        return Uri.fromFile(file)
    }

    private class FakeVault : ApiKeyVault {
        override fun save(key: CharArray) = Unit
        override fun load(): CharArray = "sk-test".toCharArray()
        override fun clear() = Unit
    }

    private class FakeMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = byteArrayOf(1)
        override fun currentAmplitude(): Float = 0f
    }

    private class FakeVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider = VoiceTranscriptionProvider.OpenAiWhisper
    }
}
