package com.pocketshell.app.composer

import androidx.lifecycle.SavedStateHandle
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.voice.WhisperClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
 * Issue #1108 — a failed send must NOT silently drop the attachment, so the
 * reconnect-then-resend still carries the file (#694 regression guard).
 *
 * This mirrors the on-device journey locked by
 * [PromptComposerSendDismissE2eTest.attachThenTypeShowsBothAndFailedSendKeepsAttachmentPaths]
 * at the JVM/Robolectric level (no emulator) so it runs in the per-push Unit
 * gate. The E2E variant asserted on the wrong surface — it checked the DRAFT
 * TEXT for the attachment path, but the #872/#971 composer model keeps the
 * draft CLEAN and re-shows the attachment as a separate TILE
 * ([PromptComposerViewModel.UiState.attachments]). The path was never dropped;
 * it just lives in the tile, not the text. This test asserts the real property:
 *
 *  - after a degraded-connection "Not sent", the typed draft AND the attachment
 *    tile both survive, and
 *  - the resend STILL carries the attachment (text + tiles), never a silent drop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PromptComposerFailedSendAttachmentResendTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val createdViewModels = mutableListOf<PromptComposerViewModel>()

    @After
    fun tearDown() {
        createdViewModels.forEach { it.clearForTest() }
        createdViewModels.clear()
    }

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() {}
        override fun stop(): ByteArray = ByteArray(0)
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class TestVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(): PromptComposerViewModel = PromptComposerViewModel(
        audioRecorder = TestMicCapture(),
        whisperClientFactory = WhisperClientFactory {
            object : WhisperClient {
                override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                    Result.success("")
            }
        },
        apiKeyStorage = TestVault(),
        voiceSettings = TestVoiceSettings(),
        savedStateHandle = SavedStateHandle(),
    ).also {
        createdViewModels += it
        // The overall-send watchdog (#891) is irrelevant here and its long delay
        // would be drained by runTest's terminal advanceUntilIdle; disable it so
        // the failed/resend path is driven only by the explicit collector below.
        it.setSendWatchdogTimeoutForTest(null)
    }

    @Test
    fun failedSendKeepsAttachmentTileAndResendStillCarriesIt() = runTest {
        val attachPath = "~/.pocketshell/attachments/host-1/20260611-report.png"
        val vm = newViewModel()

        // Attach a single file (no preview — the SAF path the picker callback
        // takes), then type after it. Both the chip and the typed text survive.
        vm.attachFiles(count = 1) { Result.success(listOf(attachPath)) }
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.attachments.size)
        vm.onDraftChange("what is wrong here")
        assertEquals(1, vm.uiState.value.attachments.size)

        // Host whose send fails on a degraded connection -> restoreFailedSend.
        // Record every request so the resend assertion can prove the attachment
        // still travels.
        val received = mutableListOf<PromptComposerViewModel.SendRequest>()
        var sendSucceeds = false
        val collector: Job = launch {
            vm.sendRequests.collect { request ->
                received += request
                if (!sendSucceeds) vm.restoreFailedSend(request)
            }
        }

        // First send fails.
        vm.requestSend(withEnter = true)
        advanceUntilIdle()

        // #872/#971: the CLEAN typed draft comes back …
        assertEquals("what is wrong here", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sendInFlight)
        // … and the attachment survives as a TILE (NOT folded into draft text),
        // so it is not silently dropped (#1108).
        assertEquals(
            listOf(attachPath),
            vm.uiState.value.attachments.map { it.remotePath },
        )
        assertTrue(
            "draft must stay clean — the path lives in the tile, not the text",
            !vm.uiState.value.draft.contains("20260611-report.png"),
        )

        // Reconnect: the resend STILL carries the attachment, never a silent drop.
        sendSucceeds = true
        vm.requestSend(withEnter = true)
        advanceUntilIdle()
        collector.cancel()

        assertEquals(2, received.size)
        val resend = received.last()
        assertTrue(
            "resend text must carry the attachment path",
            resend.text.contains("20260611-report.png"),
        )
        assertEquals(
            "resend must carry the attachment tile",
            listOf(attachPath),
            resend.attachments.map { it.remotePath },
        )
    }
}
