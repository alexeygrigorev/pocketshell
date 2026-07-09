package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerAttachmentWedgeTest {

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

    private fun newStorage(): ApiKeyVault = FakeVault()

    private fun newVm(
        samplerDispatcher: TestDispatcher? = null,
        outboundQueueStore: OutboundQueueStore = DisabledOutboundQueueStore,
        outboundAttachmentSidecarStore: OutboundAttachmentSidecarStore? = null,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = newStorage().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = DisabledComposerDraftStore,
            outboundQueueStore = outboundQueueStore,
            outboundAttachmentSidecarStore = outboundAttachmentSidecarStore,
            savedStateHandle = savedStateHandle,
        )
        if (samplerDispatcher != null) {
            vm.samplerDispatcher = samplerDispatcher
            vm.outboundQueueDispatcher = samplerDispatcher
        }
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
        backgroundScope.launch {
            vm.sendRequests.collect { collected += it }
        }
        runCurrent()
        return collected
    }

    private fun newSidecarStore(
        context: Context = ApplicationProvider.getApplicationContext(),
    ): OutboundAttachmentSidecarStore {
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
        var nextId = 0
        return OutboundAttachmentSidecarStore(context).also { store ->
            store.idGenerator = { "sidecar-${++nextId}" }
            store.clock = { nextId.toLong() }
        }
    }

    private fun localAttachmentFile(name: String, content: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    private suspend fun TestScope.advanceSchedulerUntil(
        predicate: suspend () -> Boolean,
        timeoutMs: Long = 40_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            advanceUntilIdle()
            if (predicate()) return true
            runCurrent()
            if (predicate()) return true
            advanceTimeBy(1L)
            runCurrent()
            if (predicate()) return true
            yieldToRealDispatchers()
            if (predicate()) return true
            if (System.currentTimeMillis() >= deadline) {
                advanceUntilIdle()
                return predicate()
            }
        }
    }

    private suspend fun yieldToRealDispatchers() {
        withContext(Dispatchers.IO) {
            Thread.sleep(1L)
        }
    }

    private suspend fun TestScope.settleUntil(predicate: () -> Boolean) {
        assertTrue(
            "settleUntil timed out before the predicate held; sidecar-backed dispatch did not drain",
            advanceSchedulerUntil(predicate = { predicate() }),
        )
    }

    private suspend fun TestScope.attachAndSendForWedge(
        vm: PromptComposerViewModel,
        sent: List<PromptComposerViewModel.SendRequest>,
        target: PromptComposerViewModel.SendTargetSnapshot,
        draft: String,
        fileName: String,
    ) {
        val local = localAttachmentFile(fileName, "local bytes for $fileName")
        vm.onComposerTargetChanged(target.sessionKey)
        vm.onDraftChange(draft)
        vm.attachFiles(
            count = 1,
            previews = listOf(
                PromptComposerViewModel.AttachmentPreview(Uri.fromFile(local), "text/plain"),
            ),
        ) {
            Result.success(listOf("~/.pocketshell/attachments/old/$fileName"))
        }
        settleUntil { vm.uiState.value.attachments.isNotEmpty() }
        assertTrue(vm.uiState.value.attachments.isNotEmpty())
        val before = sent.size
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { sent.size > before || !vm.uiState.value.sendInFlight }
    }

    private suspend fun TestScope.assertSubsequentPlainSendWorks(
        vm: PromptComposerViewModel,
        sent: MutableList<PromptComposerViewModel.SendRequest>,
        target: PromptComposerViewModel.SendTargetSnapshot,
    ) {
        assertFalse(
            "send pipeline wedged: sendInFlight still true after attachment send resolved",
            vm.uiState.value.sendInFlight,
        )
        vm.uiState.value.attachments.toList().forEach { vm.removeAttachment(it.remotePath) }
        val before = sent.size
        vm.onDraftChange("plain follow-up after attachment")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { sent.size > before }
        assertEquals(
            "the subsequent plain send did not reach the session",
            before + 1,
            sent.size,
        )
        assertEquals("plain follow-up after attachment", sent.last().text)
        assertTrue(vm.uiState.value.sendInFlight)
    }

    @Test
    fun attachmentDropMidUploadDoesNotWedgeSubsequentPlainSend_shellPane() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            Result.failure(RuntimeException("transport dropped mid-upload"))
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            route = OutboundRoute.RawBytes,
        )

        attachAndSendForWedge(vm, sent, target, "look at this", "drop.txt")
        assertSubsequentPlainSendWorks(vm, sent, target)
    }

    @Test
    fun attachmentDropMidUploadDoesNotWedgeSubsequentPlainSend_agentPane() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            Result.failure(RuntimeException("transport dropped mid-upload"))
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-agent",
            paneId = "%4",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )

        attachAndSendForWedge(vm, sent, target, "review for the agent", "agent-drop.txt")
        assertSubsequentPlainSendWorks(vm, sent, target)
    }

    @Test
    fun attachmentUploadTimeoutDoesNotWedgeSubsequentPlainSend() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            awaitCancellation()
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            route = OutboundRoute.RawBytes,
        )

        attachAndSendForWedge(vm, sent, target, "this will time out", "timeout.txt")
        advanceUntilIdle()
        assertSubsequentPlainSendWorks(vm, sent, target)
    }

    @Test
    fun attachmentSendClaimRaceEarlyReturnDoesNotWedgeSubsequentPlainSend() = runTest {
        val queue = object : InMemoryOutboundQueueStore() {
            override fun claim(id: String): OutboundItem? = null
        }
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            Result.success(it.map { ref -> "~/.pocketshell/attachments/uploaded/${ref.displayName}" })
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            route = OutboundRoute.RawBytes,
        )

        attachAndSendForWedge(vm, sent, target, "claim race", "claim.txt")
        assertSubsequentPlainSendWorks(vm, sent, target)
    }

    @Test
    fun attachmentSendInternalEarlyReturnDoesNotWedgeSubsequentPlainSend() = runTest {
        val queue = object : InMemoryOutboundQueueStore() {
            override fun markUploading(id: String, lastAttemptAtMs: Long): OutboundItem? = null
        }
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            Result.success(it.map { ref -> "~/.pocketshell/attachments/uploaded/${ref.displayName}" })
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            route = OutboundRoute.RawBytes,
        )

        attachAndSendForWedge(vm, sent, target, "internal strand", "strand.txt")
        assertSubsequentPlainSendWorks(vm, sent, target)
    }

    @Test
    fun successfulAttachmentSendThenPlainSendBothReachSession() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecars = newSidecarStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            outboundQueueStore = queue,
            outboundAttachmentSidecarStore = sidecars,
        )
        vm.setOutboundAttachmentSidecarUploader {
            Result.success(it.map { ref -> "~/.pocketshell/attachments/uploaded/${ref.displayName}" })
        }
        val sent = collectSendRequests(vm)
        val target = PromptComposerViewModel.SendTargetSnapshot(
            sessionKey = "1/session-a",
            route = OutboundRoute.RawBytes,
        )

        attachAndSendForWedge(vm, sent, target, "with attachment", "ok.txt")

        assertEquals(1, sent.size)
        assertTrue(sent.single().text.contains("uploaded/ok.txt"))
        assertEquals(1, sent.single().attachments.size)
        vm.markSendDelivered(sent.single())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sendInFlight)

        val before = sent.size
        vm.onDraftChange("plain after success")
        vm.requestSend(withEnter = true, sendTarget = target)
        settleUntil { sent.size > before }
        assertEquals(before + 1, sent.size)
        assertEquals("plain after success", sent.last().text)
    }
}
