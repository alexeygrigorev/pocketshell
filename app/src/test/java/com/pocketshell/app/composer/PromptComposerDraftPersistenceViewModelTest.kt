package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class PromptComposerDraftPersistenceViewModelTest {

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

    private fun newVm(
        samplerDispatcher: TestDispatcher? = null,
        composerDraftStore: ComposerDraftStore = DisabledComposerDraftStore,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): PromptComposerViewModel {
        val vm = PromptComposerViewModel(
            audioRecorder = FakeMicCapture(),
            whisperClientFactory = WhisperClientFactory { null },
            apiKeyStorage = FakeVault().also { it.save("sk-test".toCharArray()) },
            voiceSettings = FakeVoiceSettings(),
            speechRecognitionProvider = UnavailableSpeechRecognitionProvider,
            composerDraftStore = composerDraftStore,
            outboundQueueStore = DisabledOutboundQueueStore,
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

    @Test
    fun typedDraftIsEditableAndPreservedUntilCallerClearsIt() {
        val vm = newVm()

        vm.onDraftChange("first dictated idea")
        vm.onDraftChange("first dictated idea with an edit")

        assertEquals("first dictated idea with an edit", vm.uiState.value.draft)
    }

    // -- Issue #746: discard draft + session scoping ------------------------

    @Test
    fun discardDraftClearsTextAttachmentsBannerAndSavedState() = runTest {
        val savedStateHandle = SavedStateHandle()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )
        vm.onDraftChange("ничего не происходит")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.draft.isNotEmpty())
        assertTrue(vm.uiState.value.attachments.isNotEmpty())

        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(
                text = "ничего не происходит",
                withEnter = true,
                attachments = vm.uiState.value.attachments,
            ),
        )
        assertTrue(vm.uiState.value.draft.isNotEmpty())
        assertNotNull(vm.uiState.value.error)

        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
        assertNull(vm.uiState.value.error)
        assertEquals("", savedStateHandle.get<String>(PromptComposerViewModel.KEY_DRAFT).orEmpty())
        assertNull(savedStateHandle.get<String>(PromptComposerViewModel.KEY_DRAFT_OWNER))
    }

    @Test
    fun discardDraftClearsStagedAttachmentChipsBeforeSend() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onDraftChange("with a chip")
        vm.attachFiles(count = 1) {
            Result.success(listOf("~/.pocketshell/attachments/host-1/shot.png"))
        }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isNotEmpty())

        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.attachments.isEmpty())
    }

    @Test
    fun draftFromOneSessionDoesNotBleedIntoAnother() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )
        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)

        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun draftSurvivesReturningToTheOwningSession() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )

        vm.onComposerTargetChanged("1/sessionA")

        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)
    }

    @Test
    fun processRestoredDraftIsAdoptedByFirstTargetNotDropped() = runTest {
        val savedStateHandle = SavedStateHandle()
        savedStateHandle[PromptComposerViewModel.KEY_DRAFT] = "recovered draft"
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            savedStateHandle = savedStateHandle,
        )
        assertEquals("recovered draft", vm.uiState.value.draft)

        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("recovered draft", vm.uiState.value.draft)

        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun emptyDraftSwitchJustReassignsTargetWithoutClearingFutureDrafts() = runTest {
        val vm = newVm(samplerDispatcher = StandardTestDispatcher(testScheduler))
        vm.onComposerTargetChanged("1/sessionA")
        vm.onComposerTargetChanged("1/sessionB")
        vm.onDraftChange("typed in B")

        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("typed in B", vm.uiState.value.draft)

        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("", vm.uiState.value.draft)
    }

    // -- Issue #832: durable per-session draft store ------------------------

    @Test
    fun failedSendDraftSurvivesSwitchAwayAndBack() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/sessionA")

        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "draft for A", withEnter = true),
        )
        assertEquals("draft for A", vm.uiState.value.draft)
        assertNotNull(vm.uiState.value.error)

        vm.onComposerTargetChanged("1/sessionB")
        assertEquals("", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)

        vm.onComposerTargetChanged("1/sessionA")
        assertEquals("draft for A", vm.uiState.value.draft)
    }

    @Test
    fun multipleSessionsEachKeepTheirOwnDurableDraft() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )

        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("alpha draft")
        vm.onComposerTargetChanged("1/b")
        assertEquals("", vm.uiState.value.draft)
        vm.onDraftChange("bravo draft")
        vm.onComposerTargetChanged("2/c")
        assertEquals("", vm.uiState.value.draft)
        vm.onDraftChange("charlie draft")

        vm.onComposerTargetChanged("1/a")
        assertEquals("alpha draft", vm.uiState.value.draft)
        vm.onComposerTargetChanged("2/c")
        assertEquals("charlie draft", vm.uiState.value.draft)
        vm.onComposerTargetChanged("1/b")
        assertEquals("bravo draft", vm.uiState.value.draft)
    }

    @Test
    fun editingDraftAfterSwitchBackUpdatesTheRightSessionsStore() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("a v1")
        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("a v2")

        assertEquals("a v2", store.load("1/a"))
        assertNull(store.load("1/b"))
    }

    @Test
    fun sharedPrefsDraftWritesAreScheduledOffCallerThreadButVisibleToSessionSwitches() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPrefsComposerDraftStore(ApplicationProvider.getApplicationContext())
        val target = "test/off-main-${System.nanoTime()}"
        val vm = newVm(
            samplerDispatcher = dispatcher,
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged(target)

        vm.onDraftChange("first")
        vm.onDraftChange("second")

        assertEquals("second", vm.uiState.value.draft)
        assertNull(store.load(target))

        vm.onComposerTargetChanged("$target/other")
        vm.onComposerTargetChanged(target)

        assertEquals("second", vm.uiState.value.draft)
        assertNull(store.load(target))

        advanceUntilIdle()

        assertEquals("second", store.load(target))
    }

    @Test
    fun clearDraftWinsOverPendingSharedPrefsDraftSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SharedPrefsComposerDraftStore(ApplicationProvider.getApplicationContext())
        val target = "test/clear-wins-${System.nanoTime()}"
        val vm = newVm(
            samplerDispatcher = dispatcher,
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged(target)

        vm.onDraftChange("stale draft")
        vm.discardDraft()

        assertEquals("", vm.uiState.value.draft)
        advanceUntilIdle()

        assertNull(store.load(target))
    }

    @Test
    fun discardClearsTheDurableSlotSoSwitchBackIsEmpty() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.restoreFailedSend(
            PromptComposerViewModel.SendRequest(text = "stale prompt", withEnter = true),
        )
        assertEquals("stale prompt", store.load("1/a"))

        vm.discardDraft()
        assertNull(store.load("1/a"))

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun deliveredSendDoesNotClearCurrentDurableSlot() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        assertEquals("about to send", store.load("1/a"))

        vm.markSendDelivered()
        assertEquals("about to send", store.load("1/a"))

        vm.onComposerTargetChanged("1/b")
        vm.onComposerTargetChanged("1/a")
        assertEquals("about to send", vm.uiState.value.draft)
    }

    @Test
    fun restoreFailedSendUsesRequestTargetWhenCurrentComposerMoved() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        val request = PromptComposerViewModel.SendRequest(
            text = "draft for A",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a"),
        )

        vm.onComposerTargetChanged("1/b")
        vm.onDraftChange("draft for B")
        vm.restoreFailedSend(request)

        assertEquals("draft for B", vm.uiState.value.draft)
        assertNull(vm.uiState.value.error)
        assertEquals("draft for A", store.load("1/a"))
        assertEquals("draft for B", store.load("1/b"))

        vm.onComposerTargetChanged("1/a")
        assertEquals("draft for A", vm.uiState.value.draft)
    }

    @Test
    fun deliveredSendDoesNotClearEitherTargetWhenCurrentComposerMoved() = runTest {
        val store = InMemoryComposerDraftStore()
        val vm = newVm(
            samplerDispatcher = StandardTestDispatcher(testScheduler),
            composerDraftStore = store,
        )
        vm.onComposerTargetChanged("1/a")
        vm.onDraftChange("about to send")
        val request = PromptComposerViewModel.SendRequest(
            text = "about to send",
            withEnter = true,
            sendTarget = PromptComposerViewModel.SendTargetSnapshot(sessionKey = "1/a"),
        )

        vm.onComposerTargetChanged("1/b")
        vm.onDraftChange("draft for B")
        vm.markSendDelivered(request)

        assertEquals("about to send", store.load("1/a"))
        assertEquals("draft for B", vm.uiState.value.draft)
        assertEquals("draft for B", store.load("1/b"))

        vm.onComposerTargetChanged("1/a")
        assertEquals("about to send", vm.uiState.value.draft)
    }
}
