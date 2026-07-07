package com.pocketshell.app.voice

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.COMPOSER_CANCEL_RECORDING_TAG
import com.pocketshell.app.composer.COMPOSER_MIC_TAG
import com.pocketshell.app.composer.COMPOSER_RECORDING_LOCKED_TAG
import com.pocketshell.app.composer.COMPOSER_STOP_SEND_TAG
import com.pocketshell.app.composer.COMPOSER_TO_FIELD_TAG
import com.pocketshell.app.composer.PromptComposerSheet
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.PromptComposerViewModel.ApiKeyVault
import com.pocketshell.app.composer.PromptComposerViewModel.MicCapture
import com.pocketshell.app.composer.PromptComposerViewModel.RecordingState
import com.pocketshell.app.composer.PromptComposerViewModel.VoiceSettingsSnapshot
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.core.voice.SpeechAudioGuard
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #585 (REOPENED — the TRUE desired behavior). The maintainer's vision is
 * an ENTRY gesture on the composer LAUNCHER button (NOT the mic inside the
 * already-open sheet, which prior attempts kept building): hold the launcher and
 * swipe UP → the Prompt Composer opens AND recording begins immediately, locked
 * hands-free, in ONE gesture. A plain tap on the launcher still opens the
 * composer with NO recording.
 *
 * This drives the REAL production wiring: the real [ConversationComposerLauncherRow]
 * launcher (tag [SESSION_COMPOSER_LAUNCHER_TAG]) with the same `onDictateTap` /
 * `onDictateHoldSwipeUp` split TmuxSessionScreen uses, opening the real
 * [PromptComposerSheet] inside its [androidx.compose.material3.ModalBottomSheet]
 * with a real [PromptComposerViewModel] and `autoStartRecording` threaded exactly
 * as production threads it. The launcher's hold+swipe-up detector therefore
 * competes with the same ancestor gesture arbitration it does on device.
 *
 * RED on base: without the launcher entry gesture + the sheet's
 * `autoStartRecording` auto-start, the hold+swipe-up on the launcher opens the
 * composer with NO recording (recording stays Idle), so
 * [holdLauncherThenSwipeUpOpensComposerWithLockedRecording] fails its
 * Recording+locked assertion. GREEN with the fix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ComposerLauncherHoldSwipeUpJourneyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantRecordAudio() {
        val targetPackage = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageName
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant $targetPackage android.permission.RECORD_AUDIO",
        ).close()
    }

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

        override fun currentAmplitude(): Float = if (running) 0.65f else 0f
    }

    private class FakeVault : ApiKeyVault {
        private var key: CharArray? = "sk-test".toCharArray()
        override fun save(key: CharArray) { this.key = key.copyOf() }
        override fun load(): CharArray? = key?.copyOf()
        override fun clear() { key = null }
    }

    private class FakeVoiceSettings : VoiceSettingsSnapshot {
        override fun silenceWindowMs(): Long = 600_000L
        override fun whisperLanguageHint(): String? = null
    }

    private fun newViewModel(mic: MicCapture): PromptComposerViewModel {
        val whisper = object : WhisperClient {
            override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> =
                Result.success("launcher dictation")
        }
        return PromptComposerViewModel(
            audioRecorder = mic,
            whisperClientFactory = WhisperClientFactory { whisper },
            apiKeyStorage = FakeVault(),
            voiceSettings = FakeVoiceSettings(),
            savedStateHandle = SavedStateHandle(),
        )
    }

    /**
     * Renders the production launcher + composer wired exactly as TmuxSessionScreen
     * wires them: a plain launcher tap opens the composer with `autoStartRecording
     * = false`; the launcher's hold+swipe-up entry gesture opens it with
     * `autoStartRecording = true`.
     */
    // Held outside setContent so a callback-driven flip survives recomposition
    // (a non-remembered in-composition mutableStateOf would reset to false).
    private val showComposer = mutableStateOf(false)
    private val autoRecord = mutableStateOf(false)

    private fun renderLauncherAndComposer(viewModel: PromptComposerViewModel) {
        compose.setContent {
            PocketShellTheme {
                // Pin the launcher to the BOTTOM (as TmuxSessionScreen does) so the
                // hold+swipe-up has the whole screen above it to pull into — a
                // top-anchored launcher would run out of screen before the swipe
                // threshold and never register the pull.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ConversationComposerLauncherRow(
                        onDictateTap = {
                            autoRecord.value = false
                            showComposer.value = true
                        },
                        onDictateHoldSwipeUp = {
                            autoRecord.value = true
                            showComposer.value = true
                        },
                        inputEnabled = true,
                    )
                }
                if (showComposer.value) {
                    PromptComposerSheet(
                        onDismiss = { showComposer.value = false },
                        onSend = { _ -> true },
                        viewModel = viewModel,
                        autoStartRecording = autoRecord.value,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test
    fun holdLauncherThenSwipeUpOpensComposerWithLockedRecording() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderLauncherAndComposer(viewModel)

        // Hold the launcher and pull up in one continuous gesture.
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
            .performTouchInput {
                down(center)
                advanceEventTime(80L)
                repeat(8) {
                    moveBy(Offset(0f, -28f))
                    advanceEventTime(16L)
                }
                up()
            }

        // The composer must OPEN from the swipe AND recording must go live +
        // locked hands-free from the SAME gesture. (Once recording starts, the
        // Idle mic disc COMPOSER_MIC_TAG is replaced by the recording controls, so
        // recording state — driven only from INSIDE the composed sheet — is itself
        // the proof the composer opened.)
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Recording &&
                viewModel.uiState.value.recordingLocked
        }
        compose.waitForIdle()

        assertEquals("Launcher swipe-up entry must start exactly one capture", 1, mic.startCount)
        assertEquals("Finger-up after the entry swipe must not stop capture", 0, mic.stopCount)
        assertEquals(RecordingState.Recording, viewModel.uiState.value.recording)
        assertTrue("Recording must be locked hands-free", viewModel.uiState.value.recordingLocked)
        // The live locked recording UI + its stop/cancel/send controls are present
        // (proves the composer is open in the recording state, not just that the
        // ViewModel flipped).
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_TO_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_STOP_SEND_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue-585-launcher-swipe-open-recording")

        // Stop/cancel still works from the launcher-opened locked recording.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.recording == RecordingState.Idle &&
                !viewModel.uiState.value.recordingLocked
        }
        assertEquals("Discard should stop the locked capture once", 1, mic.stopCount)
    }

    @Test
    fun plainTapOnLauncherOpensComposerWithoutRecording() {
        val mic = FakeMicCapture()
        val viewModel = newViewModel(mic)
        renderLauncherAndComposer(viewModel)

        // A plain tap must open the composer with NO recording (unchanged).
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(COMPOSER_MIC_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        // Composer is open, but recording never started.
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed()
        assertEquals("Plain tap must NOT start recording", 0, mic.startCount)
        assertEquals(RecordingState.Idle, viewModel.uiState.value.recording)
        assertFalse(viewModel.uiState.value.recordingLocked)
        compose.onNodeWithTag(COMPOSER_RECORDING_LOCKED_TAG).assertDoesNotExist()
    }
}
