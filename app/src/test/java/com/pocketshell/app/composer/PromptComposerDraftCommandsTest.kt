package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerDraftCommandsTest {

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
    fun initialStateIsIdleWithEmptyDraft() {
        val vm = newVm()
        val state = vm.uiState.value
        assertEquals(RecordingState.Idle, state.recording)
        assertEquals("", state.draft)
        assertNull(state.error)
    }

    @Test
    fun diagnosticsRecordAttachmentSeedAndRemove() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()

            vm.seedAttachment("/home/alexey/private/report.txt")
            vm.removeAttachment("/home/alexey/private/report.txt")

            assertEquals(
                listOf("attachment_seeded_from_share", "attachment_remove"),
                diagnostics.events.map { it.name },
            )
            assertEquals(1, diagnostics.events[0].fields["attachmentCount"])
            assertEquals(1, diagnostics.events[1].fields["beforeCount"])
            assertEquals(0, diagnostics.events[1].fields["afterCount"])
            assertFalse(diagnostics.events.any { event ->
                event.fields.values.any { it == "/home/alexey/private/report.txt" }
            })
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun seedDraftPromptReplacesEmptyDraft() {
        val vm = newVm()
        vm.seedDraftPrompt("Apply the PocketShell review at ~/inbox/pocketshell/reviews/README.md-20260614-025147.yaml")
        assertEquals(
            "Apply the PocketShell review at ~/inbox/pocketshell/reviews/README.md-20260614-025147.yaml",
            vm.uiState.value.draft,
        )
    }

    @Test
    fun seedDraftPromptAppendsToExistingDraftOnNewLine() {
        val vm = newVm()
        vm.onDraftChange("look at this")
        vm.seedDraftPrompt("Apply the PocketShell review at /tmp/r.yaml")
        assertEquals(
            "look at this\nApply the PocketShell review at /tmp/r.yaml",
            vm.uiState.value.draft,
        )
    }

    @Test
    fun seedDraftPromptIgnoresBlank() {
        val vm = newVm()
        vm.onDraftChange("keep me")
        vm.seedDraftPrompt("   ")
        assertEquals("keep me", vm.uiState.value.draft)
    }

    @Test
    fun seedDraftPromptRecordsDiagnostic() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.seedDraftPrompt("Apply the PocketShell review at /tmp/r.yaml")
            assertEquals(
                listOf("review_prompt_seeded_into_composer"),
                diagnostics.events.map { it.name },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun prefillEngineCommandSetsTheDraftToTheCommand() {
        val vm = newVm()
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandReplacesAnExistingLeadingSlashToken() {
        val vm = newVm()
        vm.onDraftChange("/comp")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandPreservesTrailingTextAfterTheSlashToken() {
        val vm = newVm()
        vm.onDraftChange("/old keep this part")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clear keep this part", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandPrependsWhenDraftHasNoLeadingSlash() {
        val vm = newVm()
        vm.onDraftChange("already typed")
        vm.prefillEngineCommand("/clear")
        assertEquals("/clearalready typed", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandIgnoresBlank() {
        val vm = newVm()
        vm.onDraftChange("keep me")
        vm.prefillEngineCommand("   ")
        assertEquals("keep me", vm.uiState.value.draft)
    }

    @Test
    fun prefillEngineCommandRecordsDiagnostic() {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.prefillEngineCommand("/clear")
            assertEquals(
                listOf("engine_command_tapped_into_composer"),
                diagnostics.events.map { it.name },
            )
        } finally {
            diagnostics.close()
        }
    }
}
