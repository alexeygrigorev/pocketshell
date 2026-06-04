package com.pocketshell.app.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator hooks for issue #68.
 *
 * The first test renders the composer with fake states so recording and
 * processing are asserted without a microphone or provider credentials.
 * The Docker smoke test drives a typed draft through the same
 * [SessionViewModel.sendText] bridge used by the real session screen.
 */
@RunWith(AndroidJUnit4::class)
class PromptComposerSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingAndTranscribingStatesAreVisible() {
        // Issue #453: the recording UI is now indicator-driven (no
        // redundant "CAPTURING"/"LISTENING"/"TRANSCRIBING" text). Step
        // through pre-speech, capturing, and transcribing and assert on the
        // authoritative surfaces: the waveform a11y description + the mm:ss
        // timer (Recording) and the transcribing surface + "Transcribing…".
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0f,
                hasDetectedSpeech = false,
                recordingElapsedMs = 3_000L,
            ),
        )
        renderComposer { state }

        // Pre-speech sub-state: idle waveform a11y label + the timer.
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer waiting for speech"))
        compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertIsDisplayed()
        compose.onNodeWithText("00:03").assertExists()
        // No redundant status text any more (declutter).
        compose.onNodeWithText("CAPTURING").assertDoesNotExist()
        compose.onNodeWithText("LISTENING").assertDoesNotExist()
        // The Auto-send toggle is present in the Recording state.
        compose.onNodeWithTag(COMPOSER_AUTO_SEND_TAG).assertIsDisplayed()

        compose.runOnIdle {
            // Active-speech sub-state: the sampler loop has seen at least
            // one amplitude sample over `SILENCE_AMPLITUDE_THRESHOLD`.
            state = state.copy(amplitude = 0.8f, hasDetectedSpeech = true, recordingElapsedMs = 17_000L)
        }

        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer capturing speech"))
        compose.onNodeWithText("00:17").assertExists()

        compose.runOnIdle {
            state = PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
                hasDetectedSpeech = false,
            )
        }

        // Transcribing surface: the spinner row + the "Transcribing…" label.
        compose.onNodeWithTag(COMPOSER_TRANSCRIBING_SPINNER_TAG)
            .assert(hasContentDescription("Prompt composer transcribing"))
        compose.onNodeWithText("Transcribing…").assertExists()
        compose.onNodeWithText("TRANSCRIBING").assertDoesNotExist()
        // Cancel + Auto-send are available during transcription.
        compose.onNodeWithTag(COMPOSER_CANCEL_RECORDING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_AUTO_SEND_TAG).assertIsDisplayed()
    }

    @Test
    fun longDraftScrollsInsideComposerAndKeepsControlsTappable() {
        val longDraft = (1..80).joinToString(separator = "\n") { line ->
            "line $line: keep writing the prompt without hiding controls"
        }
        var micTaps = 0
        var attachTaps = 0
        val sendModes = mutableListOf<Boolean>()

        compose.setContent {
            PocketShellTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                ) {
                    SheetContent(
                        state = PromptComposerViewModel.UiState(draft = longDraft),
                        onClose = {},
                        onDraftChange = {},
                        onMicTap = { micTaps += 1 },
                        onSend = { withEnter -> sendModes += withEnter },
                        onAttachFiles = { attachTaps += 1 },
                    )
                }
            }
        }

        // Issue #453: the Idle controls collapse to attach + mic + a single
        // Send (the Insert button is gone). With a non-empty draft the Send
        // affordance is enabled and tappable even under a tall draft.
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMPOSER_ATTACH_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COMPOSER_MIC_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).assertIsDisplayed().performClick()

        compose.runOnIdle {
            assertEquals(1, attachTaps)
            assertEquals(1, micTaps)
            // The single Send always submits with Enter.
            assertEquals(listOf(true), sendModes)
        }
    }

    @Test
    fun typedDraftSendEnterReachesDockerShell() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val marker = "pocketshell-composer-e2e-${System.currentTimeMillis()}"

        val handle = withTimeout(20_000) {
            openShell(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
            )
        }

        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val received = StringBuilder()
        val outputJob = launch(Dispatchers.Default) {
            viewModel.terminalState.output.collect { bytes ->
                synchronized(received) {
                    received.append(bytes.toString(Charsets.UTF_8))
                }
            }
        }
        val producerJob = viewModel.terminalState.attachExternalProducer(
            scope = producerScope,
            stdout = createStdoutFlow(handle.shell),
            remoteStdin = handle.shell.outputStream,
        )

        try {
            var state by mutableStateOf(PromptComposerViewModel.UiState())
            compose.setContent {
                PocketShellTheme {
                    SheetContent(
                        state = state,
                        onClose = {},
                        onDraftChange = { state = state.copy(draft = it) },
                        onMicTap = {},
                        onSend = { withEnter -> viewModel.sendText(state.draft, withEnter) },
                    )
                }
            }

            compose.onNodeWithTag(COMPOSER_DRAFT_TAG)
                .performTextInput("printf '$marker\\n'")
            compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG)
                .performClick()

            withTimeout(10_000) {
                while (synchronized(received) { !received.contains(marker) }) {
                    delay(100)
                }
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            outputJob.cancel()
            viewModel.terminalState.detachExternalProducer()
            runCatching { handle.shell.close() }
            runCatching { handle.sessionChannel.close() }
            runCatching { handle.client.disconnect() }
        }
    }

    private fun renderComposer(state: () -> PromptComposerViewModel.UiState) {
        compose.setContent {
            PocketShellTheme {
                SheetContent(
                    state = state(),
                    onClose = {},
                    onDraftChange = {},
                    onMicTap = {},
                    onSend = {},
                )
            }
        }
    }
}
