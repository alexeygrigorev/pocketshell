package com.pocketshell.app.composer

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.MicCapture
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.composer.PromptComposerViewModel.VoiceSettingsSnapshot
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #511 (also resolves #509): dismissing the composer **while a
 * recording is in progress** must stop and RELEASE the microphone — not
 * merely hide the sheet.
 *
 * The bug: [PromptComposerSheet]'s `×` close button (`onClose`) and the
 * scrim / system-back (`onDismissRequest`) used to wire straight to the
 * host's `onDismiss`, which only hides the sheet. The
 * [PromptComposerViewModel] is scoped to the **session screen**, not the
 * sheet, so `onCleared()` — the only other place the mic is released — does
 * NOT run on a sheet dismiss. The orphaned [android.media.AudioRecord] kept
 * holding the microphone (and the sampler timer kept ticking); the *next*
 * recording then captured silence because the mic was still held, and
 * Whisper returned "no speech detected". The maintainer lost several
 * multi-minute recordings in a row this way.
 *
 * This test renders the REAL [PromptComposerSheet] (the composable that
 * owns the dismiss wiring) backed by a real [PromptComposerViewModel] with
 * a fake [MicCapture] that counts `start()` / `stop()`. It:
 *
 *  1. taps the mic to begin recording (`startCount == 1`, FSM Recording),
 *  2. taps the header `×` to dismiss mid-recording,
 *  3. asserts the recorder was STOPPED (`stopCount == 1`) and the FSM left
 *     Recording (mic freed, sampler stopped) — this is the leak the bug
 *     produced and fails on the unfixed wiring,
 *  4. re-opens the sheet and records again, asserting a SECOND `start()`
 *     fires (`startCount == 2`) so the subsequent recording is a fresh
 *     capture against a released recorder, not a no-op against the orphan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class PromptComposerDismissReleasesMicTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * Grant `RECORD_AUDIO` up front so the mic tap drives straight into the
     * recording FSM instead of launching the runtime permission dialog. The
     * mic capture itself is a [FakeMicCapture], so no real microphone is
     * touched — the permission is only what [PromptComposerSheet]'s mic-tap
     * gate checks before calling `onMicTap()`. Granting in [setUp] keeps the
     * test deterministic regardless of the app's install/permission state on
     * the AVD.
     */
    @Before
    fun grantRecordAudio() {
        // Issue #682 / #672: grant to the ACTUAL target package, not a hardcoded
        // `com.pocketshell.app`. Under the parallel-agent suffix-coexist scheme
        // the APK installs as `com.pocketshell.app.i<issue>`, so a hardcoded
        // grant lands on the wrong package and the mic tap falls into the
        // runtime-permission path instead of the recording FSM (the test then
        // times out waiting for Recording). Resolve the package at runtime so the
        // grant works for both the base and any suffixed install.
        val targetPackage = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant $targetPackage android.permission.RECORD_AUDIO",
        ).close()
    }

    /**
     * Counts `start()` / `stop()` so the test can prove the dismiss path
     * released the mic and a re-record opened a fresh capture. `stop()`
     * returns a WAV carrying real speech energy so the transcription path
     * (if it were ever reached) would behave like production.
     */
    private class FakeMicCapture : MicCapture {
        var startCount = 0
        var stopCount = 0
        private var running = false

        override fun start() {
            startCount++
            running = true
        }

        override fun stop(): ByteArray {
            stopCount++
            running = false
            return SpeechAudioGuard.speechWavForTesting()
        }

        override fun currentAmplitude(): Float = if (running) 0.5f else 0f
    }

    private class FakeVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class FakeVoiceSettings : VoiceSettingsSnapshot {
        // A very long silence window so the watchdog never auto-stops the
        // recording out from under the test before we dismiss.
        override fun silenceWindowMs(): Long = 600_000L
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(mic: MicCapture): PromptComposerViewModel {
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                Result.success("unused")
        }
        return PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = FakeVault(),
            voiceSettings = FakeVoiceSettings(),
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun dismissWhileRecordingReleasesMicAndNextRecordingStartsFresh() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)

        var sheetVisible by mutableStateOf(true)

        compose.setContent {
            PocketShellTheme {
                if (sheetVisible) {
                    PromptComposerSheet(
                        onDismiss = { sheetVisible = false },
                        onSend = { _ -> true },
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitForIdle()

        // 1. Start recording from Idle.
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording
        }
        assertEquals(1, mic.startCount)

        // 2. Dismiss mid-recording via the header `×`.
        compose.onNodeWithTag(COMPOSER_CLOSE_TAG).performClick()
        compose.waitForIdle()

        // 3. The recorder must have been STOPPED + the FSM must have left
        //    Recording. On the unfixed wiring the close button only hid the
        //    sheet, so the mic was never stopped (stopCount stays 0) and the
        //    FSM stayed parked on Recording — the leak that fed silence into
        //    the next capture.
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording != RecordingState.Recording
        }
        assertEquals(
            "Dismiss mid-recording must stop+release the mic",
            1,
            mic.stopCount,
        )
        assertEquals(
            "Dismiss mid-recording must leave the Recording state (mic freed, timer stopped)",
            RecordingState.Idle,
            viewModel.uiState.value.recording,
        )

        // 4. Re-open the sheet and record again. A fresh capture must call
        //    start() a SECOND time — proving the recorder was released and is
        //    available, not a no-op against a still-held orphan.
        sheetVisible = true
        compose.waitForIdle()
        compose.onNodeWithTag(COMPOSER_MIC_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording
        }
        assertEquals(
            "A subsequent recording must open a fresh capture (start() again)",
            2,
            mic.startCount,
        )
        assertTrue(
            "The fresh recording must be actively capturing",
            viewModel.uiState.value.recording == RecordingState.Recording,
        )

        // Settle: stop the active recording so no sampler loop outlives the test.
        viewModel.cancelRecording()
        compose.waitForIdle()
    }

    /**
     * Issue #560: the share-into-session flow pre-stages the shared file into
     * the session composer as a #544 attachment chip (via
     * [PromptComposerViewModel.seedAttachment]) and opens the composer
     * focused. This renders the REAL [PromptComposerSheet] backed by a real
     * VM, seeds an already-uploaded remote path the way the share launch
     * does, opens the sheet, and asserts the chip renders by file name (never
     * the full remote path) — the visible end state the user lands on after
     * picking a session destination. A screenshot is captured as evidence.
     */
    @Test
    fun seededAttachmentRendersAsComposerChipWhenSheetOpens() {
        val viewModel = newViewModel(FakeMicCapture())
        // Pre-stage the attachment the same way the share-into-session launch
        // does before the composer opens.
        viewModel.seedAttachment(
            "~/.pocketshell/attachments/host-7-scratch/20260606-120000-01-diagram.png",
        )

        compose.setContent {
            PocketShellTheme {
                PromptComposerSheet(
                    onDismiss = {},
                    onSend = { _ -> true },
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()

        // The chip must show only the file name, never the full remote path.
        compose.onNodeWithTag(COMPOSER_ATTACHMENT_CHIPS_TAG).assertExists()
        compose.onNodeWithText("20260606-120000-01-diagram.png").assertIsDisplayed()
        compose.onNodeWithText(".pocketshell/attachments", substring = true)
            .assertDoesNotExist()

        // Exactly one chip is staged from the single seeded path.
        assertEquals(1, viewModel.uiState.value.attachments.size)
        assertEquals(
            "~/.pocketshell/attachments/host-7-scratch/20260606-120000-01-diagram.png",
            viewModel.uiState.value.attachments.single().remotePath,
        )

        com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
            .capture("issue560-composer-seeded-attachment-chip")
    }
}
