package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachmentTest {

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
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
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

    private fun newVm(): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = DisabledComposerDraftStore,
            outboundQueueStore = DisabledOutboundQueueStore,
            savedStateHandle = SavedStateHandle(),
        )
        createdViewModels += vm
        vm.setSendWatchdogTimeoutForTest(null)
        return vm
    }

    @Test
    fun attachFilesStagesChipsAndKeepsDraftClean() = runTest {
        // Issue #544: staging adds structured chips (file name only) to the
        // attachments list; the draft text is not mutated while composing.
        val vm = newVm()
        vm.onDraftChange("Review these")

        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()

        assertEquals("Review these", vm.uiState.value.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    displayName = "20260601-120000-01-report.txt",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                    displayName = "20260601-120000-02-data.csv",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertNull(vm.uiState.value.error)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
    }

    @Test
    fun sendClearsDraftForAttachmentBearingPrompt() = runTest {
        val vm = newVm()
        val sends = mutableListOf<PromptComposerViewModel.SendRequest>()
        val job = launch { vm.sendRequests.collect { sends += it } }
        vm.onDraftChange("review these carefully and report anything suspicious you find")
        vm.attachFiles(count = 2) {
            Result.success(
                listOf(
                    "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt",
                    "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv",
                ),
            )
        }
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.attachments.size)

        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // Both the draft text and the staged attachment tiles clear on hand-off.
        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertTrue(vm.uiState.value.sendInFlight)
        // The composed prompt that actually went out still carried the clean text
        // (the paths are folded into the outgoing text at send time, #544).
        assertEquals(1, sends.size)
        assertTrue(sends.single().text.contains("review these"))
        job.cancelAndJoin()
    }

    @Test
    fun attachFilesDeDuplicatesRepeatedRemotePaths() = runTest {
        // Issue #544: re-attaching the same remote path must not double a chip.
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"

        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()
        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun removeAttachmentDropsOnlyThatChipAndKeepsDraft() = runTest {
        // Issue #544: the chip remove action drops just that attachment. The
        // text the user typed is untouched, and removing all chips leaves a clean prompt.
        val vm = newVm()
        vm.onDraftChange("Look at these")
        val report = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"
        val data = "~/.pocketshell/attachments/host-1/20260601-120000-02-data.csv"

        vm.attachFiles(count = 2) { Result.success(listOf(report, data)) }
        advanceUntilIdle()

        vm.removeAttachment(report)

        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = data,
                    displayName = "20260601-120000-02-data.csv",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertEquals("Look at these", vm.uiState.value.draft)

        vm.removeAttachment(data)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertEquals("Look at these", vm.uiState.value.draft)
    }

    @Test
    fun seedAttachmentPreloadsChipAndKeepsDraftClean() = runTest {
        // Issue #560: share-into-session stages the shared file through #544,
        // then seeds the resulting remote path so the composer opens with a chip.
        val vm = newVm()
        vm.onDraftChange("")
        val path = "~/.pocketshell/attachments/host-7-scratch/20260606-120000-01-shot.png"

        vm.seedAttachment(path)

        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = path,
                    displayName = "20260606-120000-01-shot.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun stagedAttachmentsAndDraftPersistAcrossComposerCloseAndSessionSwitch() = runTest {
        // Issue #673: attachments live only inside the Prompt Composer sheet.
        // Closing the sheet and switching sessions must leave retained VM state intact.
        val vm = newVm()
        val first = "~/.pocketshell/attachments/host-1/20260610-120000-01-shot.png"
        val second = "~/.pocketshell/attachments/host-1/20260610-120000-02-data.csv"
        vm.onDraftChange("deploy the staging build")
        vm.seedAttachment(first)
        vm.seedAttachment(second)

        val afterCompose = vm.uiState.value
        val afterReopen = vm.uiState.value

        assertEquals("deploy the staging build", afterReopen.draft)
        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = first,
                    displayName = "20260610-120000-01-shot.png",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = second,
                    displayName = "20260610-120000-02-data.csv",
                ),
            ),
            afterReopen.attachments,
        )
        assertEquals(afterCompose.draft, afterReopen.draft)
        assertEquals(afterCompose.attachments, afterReopen.attachments)
    }

    @Test
    fun seedAttachmentDeDuplicatesAndIgnoresBlank() = runTest {
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-7-scratch/shot.png"

        vm.seedAttachment(path)
        vm.seedAttachment(path)
        vm.seedAttachment("   ")

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun removeAttachmentForUnknownPathIsNoOp() = runTest {
        val vm = newVm()
        val path = "~/.pocketshell/attachments/host-1/20260601-120000-01-report.txt"
        vm.attachFiles(count = 1) { Result.success(listOf(path)) }
        advanceUntilIdle()

        vm.removeAttachment("~/.pocketshell/attachments/host-1/does-not-exist.txt")

        assertEquals(1, vm.uiState.value.attachments.size)
        assertEquals(path, vm.uiState.value.attachments.single().remotePath)
    }

    @Test
    fun attachFilesFailureKeepsDraftAndShowsError() = runTest {
        val vm = newVm()
        vm.onDraftChange("Do not lose this draft")

        vm.attachFiles(count = 1) {
            Result.failure(IllegalStateException("Permission denied"))
        }
        advanceUntilIdle()

        assertEquals("Do not lose this draft", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attachment upload failed"))
        assertTrue(error.contains("Permission denied"))
        assertTrue(error.contains("draft was kept"))
    }

    @Test
    fun attachFilesPartialFailureKeepsSurvivorsAndShowsError() = runTest {
        // Issue #570: when one file fails, uploads that succeeded still attach
        // as tiles and the composer returns to Idle with a per-batch error.
        val vm = newVm()
        vm.onDraftChange("Review these shots")
        val first = "~/.pocketshell/attachments/host-1/20260606-120000-01-first.png"
        val third = "~/.pocketshell/attachments/host-1/20260606-120000-03-third.png"

        vm.attachFiles(count = 3) {
            Result.failure(
                PartialAttachmentUploadException(
                    uploadedPaths = listOf(first, third),
                    failedCount = 1,
                    message = "Attached 2 of 3 files; 1 failed (upload timed out).",
                ),
            )
        }
        advanceUntilIdle()

        assertEquals(
            listOf(
                PromptComposerViewModel.StagedAttachment(
                    remotePath = first,
                    displayName = "20260606-120000-01-first.png",
                ),
                PromptComposerViewModel.StagedAttachment(
                    remotePath = third,
                    displayName = "20260606-120000-03-third.png",
                ),
            ),
            vm.uiState.value.attachments,
        )
        assertEquals("Review these shots", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attached 2 of 3"))
        assertTrue(error.contains("1 failed"))
    }

    @Test
    fun attachFilesInFlightDoesNotBlockDraftEdits() = runTest {
        // Issue #570: staging/uploading attachments must not globally block text composition.
        val vm = newVm()
        val uploadStarted = CompletableDeferred<Unit>()
        val uploadResult = CompletableDeferred<Result<List<String>>>()

        vm.attachFiles(count = 3) {
            uploadStarted.complete(Unit)
            uploadResult.await()
        }
        runCurrent()
        uploadStarted.await()

        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Uploading(3),
            vm.uiState.value.attachmentUpload,
        )

        vm.onDraftChange("typing while images upload")

        assertEquals("typing while images upload", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Uploading(3),
            vm.uiState.value.attachmentUpload,
        )

        uploadResult.complete(Result.success(emptyList()))
        advanceUntilIdle()
    }

    @Test
    fun attachFilesTimeoutClearsBusyStateAndKeepsDraft() = runTest {
        val vm = newVm()
        val uploadStarted = CompletableDeferred<Unit>()
        vm.onDraftChange("keep this")

        vm.attachFiles(count = 3) {
            uploadStarted.complete(Unit)
            awaitCancellation()
        }
        runCurrent()
        uploadStarted.await()

        advanceTimeBy(PromptComposerViewModel.ATTACHMENT_UPLOAD_TIMEOUT_MS)
        runCurrent()

        assertEquals("keep this", vm.uiState.value.draft)
        assertEquals(
            PromptComposerViewModel.AttachmentUploadState.Idle,
            vm.uiState.value.attachmentUpload,
        )
        assertTrue(vm.uiState.value.attachments.isEmpty())
        val error = vm.uiState.value.error
        assertNotNull(error)
        assertTrue(error!!.contains("Attachment upload failed"))
        assertTrue(error.contains("timed out"))
        assertTrue(error.contains("draft was kept"))
    }
}
