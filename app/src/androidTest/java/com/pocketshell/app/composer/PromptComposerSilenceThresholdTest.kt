package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #185 / #453 connected acceptance test for the silence-watchdog
 * 2s minimum floor.
 *
 * Issue #453 removed the inline "Auto-stops after Xs silence" hint from
 * the composer UI (maintainer feedback: "Remove the 'Auto-stops after 30s
 * silence' label — unnecessary noise"). The watchdog *behaviour* — that a
 * sub-window pause does not auto-stop the recording — is unchanged and is
 * still pinned here as a connected-FSM regression, plus at the millisecond
 * level in
 * [com.pocketshell.app.composer.PromptComposerViewModelTest.silenceWindowAtMinimumFloorTwoSecondsKeepsRecordingThroughOneAndAHalfSecondSilence].
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSilenceThresholdTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * In-memory mic that goes silent immediately after `start()`. Used to
     * exercise the silence-watchdog auto-stop path — every amplitude
     * poll lands BELOW
     * [PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD] so the
     * lastLoud clock never resets and the watchdog will fire once the
     * configured window elapses.
     */
    private class SilentMic : PromptComposerViewModel.MicCapture {
        override fun start() = Unit
        override fun stop(): ByteArray = ByteArray(44) { 0 }
        // 0f is comfortably below the 0.04f speech threshold — the
        // watchdog treats every poll as silence.
        override fun currentAmplitude(): Float = 0f
    }

    private class TestVault : PromptComposerViewModel.ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) {
            this.key = key.copyOf()
        }

        override fun load(): CharArray? = key?.copyOf()
        override fun clear() {
            key = null
        }
    }

    private class FixedSilenceWindowSettings(
        private val windowMs: Long,
    ) : PromptComposerViewModel.VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = windowMs
        override fun whisperLanguageHint(): String? = null
    }

    /**
     * Issue #185 canonical regression scenario:
     *
     *   1. User taps mic.
     *   2. User speaks ("hey").
     *   3. User pauses for 1.5 seconds — natural mid-sentence breath.
     *   4. User continues speaking ("there").
     *
     * Pre-fix, a sub-2s silence threshold (or a default that drifted
     * below 2s) would auto-stop the recording during step 3 and the
     * "there" in step 4 would never reach Whisper. With the new 2s
     * floor enforced by [com.pocketshell.app.settings.AppSettings.MIN_VOICE_SILENCE_SECONDS]
     * the recording survives the pause and continues capturing the
     * trailing speech.
     *
     * This test drives the scriptable mic at virtual amplitudes that
     * mirror the user's behaviour. Real-time timing is not viable in a
     * connected test (the watchdog uses wall-clock by default), so we
     * exercise the same FSM directly via the public mic-tap surface.
     * The unit-level coverage in
     * [com.pocketshell.app.composer.PromptComposerViewModelTest.silenceWindowAtMinimumFloorTwoSecondsKeepsRecordingThroughOneAndAHalfSecondSilence]
     * pins the millisecond bound; this test pins the connected-FSM
     * shape.
     */
    @Test
    fun recordingSurvivesOneAndAHalfSecondsOfSilenceAtTwoSecondFloor() {
        // Mic returns 0f on every poll — every amplitude sample is
        // BELOW [PromptComposerViewModel.SILENCE_AMPLITUDE_THRESHOLD],
        // so the lastLoud clock never resets and the watchdog will
        // eventually fire after the configured window. With the window
        // pinned at the 2s minimum floor a 1.5s wall-clock pause is
        // comfortably inside the window — the FSM must still be on
        // Recording when we check after 1.5s. Pre-fix, a sub-2s
        // threshold (e.g. the old 0.5s minimum) would have auto-stopped
        // somewhere in the middle of that 1.5s window and the user's
        // continuing speech would never have been captured.
        val vm = PromptComposerViewModel(
            audioRecorder = SilentMic(),
            whisperClientFactory = WhisperClientFactory {
                object : WhisperClient {
                    override suspend fun transcribe(
                        audio: ByteArray,
                        language: String?,
                    ): Result<String> = Result.success("hey there")
                }
            },
            apiKeyStorage = TestVault(),
            // 2s floor — the documented minimum per #185.
            voiceSettings = FixedSilenceWindowSettings(windowMs = 2_000L),
        )

        compose.setContent {
            PocketShellTheme {
                val state by vm.uiState.collectAsState()
                SheetContent(
                    state = state,
                    onClose = {},
                    onDraftChange = vm::onDraftChange,
                    onMicTap = { vm.onMicTap() },
                    onSend = { _ -> },
                    onCancelRecording = vm::cancelRecording,
                )
            }
        }

        // Start recording.
        compose.runOnUiThread { vm.onMicTap() }
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording == PromptComposerViewModel.RecordingState.Recording
        }

        // Simulate a 1.5s wall-clock pause. Every poll is silent, so the
        // watchdog is counting against the 2s window. Use ~1.4s so we
        // stay safely under the 2s bound even on a slow CI emulator.
        // The legacy 0.5s minimum bound would have auto-stopped within
        // ~500ms — landing at Idle long before we check.
        val pauseStart = System.currentTimeMillis()
        compose.waitUntil(timeoutMillis = 5_000) {
            System.currentTimeMillis() - pauseStart >= 1_400
        }

        // After 1.4s of silence the recording is still active because
        // the 2s window has not yet elapsed. The regression would have
        // shown up as an auto-transition into Transcribing/Idle by now.
        assertEquals(
            "1.4s of silence under a 2s window must NOT auto-stop the recording — " +
                "this is the canonical regression scenario from the v0.2.8 feedback report",
            PromptComposerViewModel.RecordingState.Recording,
            vm.uiState.value.recording,
        )

        // The stamped threshold still matches the configured window — the
        // value the watchdog is enforcing.
        assertEquals(2f, vm.uiState.value.silenceThresholdSeconds, 0.001f)

        // Tear down via cancel so the watchdog coroutine doesn't keep
        // polling past the test boundary. Using cancel rather than a
        // second mic-tap because the watchdog might race to auto-stop
        // between our wait and the manual tap on a slow emulator.
        compose.runOnUiThread { vm.cancelRecording() }
        compose.waitForIdle()
    }
}

