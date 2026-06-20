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
import java.util.concurrent.atomic.AtomicLong

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
 *
 * Issue #854 (epic #848, #831 follow-on): this test used to assert on a
 * **real wall-clock window** (`System.currentTimeMillis() - pauseStart >=
 * 1_400`), which made the silence elapsed depend on how fast the CI runner
 * actually ran — a flaky-by-construction timing assertion (the #831 class).
 * It is now driven off the ViewModel's injectable [PromptComposerViewModel.clock]
 * seam: a **virtual clock** the test steps explicitly. The silence elapsed
 * is therefore exactly the value we set (not real wall time), so the
 * pass/fail verdict is deterministic regardless of runner contention while
 * still exercising the real watchdog FSM end-to-end through the production
 * sheet.
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
     * The watchdog's silence decision is `clock() - lastLoud >= window`.
     * We bind [PromptComposerViewModel.clock] to a **virtual clock** the
     * test steps explicitly, so the silence elapsed is exactly the value
     * we set rather than however much real wall time happened to pass on
     * a contended runner (the #854 / #831 de-flake). The watchdog still
     * polls every real [PromptComposerViewModel.SAMPLE_INTERVAL_MS]; only
     * the *threshold comparison input* is virtualised, so the FSM is
     * exercised for real but the verdict is deterministic.
     */
    @Test
    fun recordingSurvivesOneAndAHalfSecondsOfSilenceAtTwoSecondFloor() {
        // Virtual clock the test advances by hand. Starts at 0; the mic is
        // silent on every poll so `lastLoud` stays pinned at the recording
        // start (clock=0) and the watchdog's silence elapsed is exactly
        // `virtualNowMs - 0`. No real wall time enters the assertion.
        val virtualNowMs = AtomicLong(0L)

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
        vm.clock = { virtualNowMs.get() }

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

        // The stamped threshold matches the configured window — the value
        // the watchdog is enforcing for this recording.
        assertEquals(2f, vm.uiState.value.silenceThresholdSeconds, 0.001f)

        // Step the VIRTUAL clock to 1.4s of silence. This is INSIDE the 2s
        // window: the watchdog computes `1400 - 0 = 1400 < 2000` on its
        // next poll and must keep recording. Because the elapsed is read
        // from the virtual clock (not wall time), this verdict is identical
        // on the fastest and slowest runner — the #854 de-flake. The legacy
        // 0.5s minimum bound would have auto-stopped at `500 >= 500`, i.e.
        // the FSM would already be out of Recording at clock=1400.
        virtualNowMs.set(1_400L)
        // Give the watchdog a real poll cycle to observe the new clock
        // value, then confirm the FSM is STILL Recording — and stays so.
        // We wait on the deterministic FSM state, not on wall time: at
        // clock=1400 the loop can never auto-stop, so this only confirms
        // the watchdog ran at the stepped clock without flipping.
        repeat(3) { compose.waitForIdle() }
        compose.waitUntil(timeoutMillis = 5_000) {
            // hasDetectedSpeech / elapsed get published every poll; once
            // the watchdog has ticked at the new clock, recordingElapsedMs
            // reflects it. This proves a poll happened post-step without
            // racing wall time.
            vm.uiState.value.recordingElapsedMs >= 1_400L
        }
        assertEquals(
            "1.4s of silence under a 2s window must NOT auto-stop the recording — " +
                "this is the canonical regression scenario from the v0.2.8 feedback report",
            PromptComposerViewModel.RecordingState.Recording,
            vm.uiState.value.recording,
        )

        // Regression direction (proves the watchdog truly enforces the
        // window, so the test still FAILS if the floor logic breaks):
        // step the virtual clock PAST the 2s window. The very next poll
        // computes `2100 - 0 = 2100 >= 2000` and the watchdog must
        // auto-stop, leaving Recording. If the watchdog ever stopped
        // consulting the window (e.g. an infinite-window regression) this
        // wait would time out and the test fails.
        virtualNowMs.set(2_100L)
        compose.waitUntil(timeoutMillis = 5_000) {
            vm.uiState.value.recording != PromptComposerViewModel.RecordingState.Recording
        }

        // Tear down via cancel so the watchdog coroutine doesn't keep
        // polling past the test boundary.
        compose.runOnUiThread { vm.cancelRecording() }
        compose.waitForIdle()
    }
}
