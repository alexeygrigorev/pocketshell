package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.settings.VoiceTranscriptionProvider
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator acceptance for the maintainer's two 2026-06-21 dogfood reports on
 * the Android SpeechRecognizer recording surface (same screenshot):
 *
 *  - Issue #880: the recording elapsed timer was stuck at `00:00` and never
 *    incremented while dictating with the Android recognizer (it was driven off
 *    the Whisper PCM sampler, which the recognizer path doesn't feed).
 *  - Issue #870: a long live partial transcript clipped the END, so the newest
 *    recognized words scrolled out of view.
 *
 * Both assertions drive the **production** [PromptComposerViewModel] through its
 * real Android-speech FSM (a fake [PromptComposerViewModel.SpeechRecognitionProvider]
 * + an injected mutable test clock that the **production** `recordingTimerJob`
 * reads) and render the REAL [RecordingSurface] via [SheetContent]. The on-screen
 * timer advance is moved by the production ticker — NOT a manual `state.copy`
 * push — so this is the on-device behavior proof for #880 (G6: the load-bearing
 * assertion is the green one), and the live-transcript containment proof for #870
 * (G6/#657 F1: `assertNodeFullyWithinRoot`, not a bare `assertIsDisplayed`).
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerRecordingTimerAndTailTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // Mutable wall clock injected into the production ViewModel. The production
    // recordingTimerJob ticks recordingElapsedMs = clock() - recordingStartedAtMs
    // every SAMPLE_INTERVAL_MS on the sampler dispatcher, so advancing this value
    // here makes the REAL ticker move the on-screen timer.
    @Volatile
    private var nowMs: Long = 10_000_000L

    private class TestMicCapture : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = SpeechAudioGuard.speechWavForTesting()
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = null
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    // Selects the Android SpeechRecognizer path (the #870/#880 path).
    private class AndroidSpeechSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = PromptComposerViewModel.SILENCE_WINDOW_MS
        override fun whisperLanguageHint(): String? = null
        override fun transcriptionProvider(): VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.AndroidSpeech
    }

    private class FakeSpeechRecognitionProvider :
        PromptComposerViewModel.SpeechRecognitionProvider {
        @Volatile
        var listener: PromptComposerViewModel.SpeechRecognitionListener? = null

        override fun isAvailable(): Boolean = true

        override fun start(
            language: String?,
            listener: PromptComposerViewModel.SpeechRecognitionListener,
        ): PromptComposerViewModel.SpeechRecognitionSession {
            this.listener = listener
            return object : PromptComposerViewModel.SpeechRecognitionSession {
                override fun stopListening() = Unit
                override fun cancel() = Unit
            }
        }
    }

    @Test
    fun productionTickerAdvancesTimerOnAndroidPathAndLongTranscriptShowsTailWithinPanel() {
        val speech = FakeSpeechRecognitionProvider()
        val vm = PromptComposerViewModel(
            audioRecorder = TestMicCapture(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(
                        audio: ByteArray,
                        language: String?,
                    ): Result<String> = Result.success("unused")
                }
            },
            apiKeyStorage = TestVault(),
            voiceSettings = AndroidSpeechSettings(),
            speechRecognitionProvider = speech,
        )
        vm.clock = { nowMs }

        compose.setContent {
            PocketShellTheme {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { withEnter -> vm.requestSend(withEnter) },
                )
            }
        }

        // Start the Android-recognizer recording via the production mic tap.
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }

        // #880 — the production recordingTimerJob ticks from 00:00. Assert the
        // rendered timer text starts at 00:00...
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed().assertTextEquals("00:00")

        // ...then advances when the wall clock moves — driven by the PRODUCTION
        // ticker reading clock(), not a manual state push. waitUntil polls the
        // rendered node until the real ticker has applied the new elapsed value.
        nowMs += 9_000L
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertTextEquals("00:09")
            }.isSuccess
        }
        nowMs += 12_000L // -> 21s total
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertTextEquals("00:21")
            }.isSuccess
        }

        // #870 — feed a long live partial through the real recognizer listener so
        // the production applyLiveSpeechTranscript computes liveTranscriptDisplay.
        val longPartial =
            "open the deployment pipeline and check the logs for the failing " +
                "build then restart the worker and confirm the latest commit is " +
                "deployed to production right now"
        compose.runOnIdle { speech.listener!!.onPartial(longPartial) }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            !vm.uiState.value.liveTranscriptDisplay.isNullOrBlank()
        }

        val rendered = vm.uiState.value.liveTranscriptDisplay!!
        // The rendered display surfaces the TAIL (latest words), not the head.
        assertTrue(
            "live display must keep the latest words, was: $rendered",
            rendered.endsWith("deployed to production right now"),
        )
        assertFalse(
            "live display must not show the dropped head",
            rendered.contains("open the deployment pipeline"),
        )
        assertTrue("live display must mark truncation with a leading ellipsis", rendered.startsWith("…"))

        // #870 containment (#657 / F1): the live-transcript text must be FULLY
        // within the window root — so "the latest words are actually visible",
        // not merely present in the tree (a tail that overflowed the 2-line
        // panel and got re-clipped would fail this, where assertIsDisplayed
        // would not).
        compose.onNodeWithTag(COMPOSER_LIVE_TRANSCRIPT_TAG).assertIsDisplayed()
        compose.assertNodeFullyWithinRoot(COMPOSER_LIVE_TRANSCRIPT_TAG)
        compose.onNodeWithTag(COMPOSER_LIVE_TRANSCRIPT_TAG).assertTextEquals(rendered)
    }
}
