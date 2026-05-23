package com.pocketshell.app.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
        var state by mutableStateOf(
            PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Recording,
                amplitude = 0.8f,
            ),
        )
        renderComposer { state }

        compose.onNodeWithTag(COMPOSER_STATUS_TAG)
            .assertExists()
        compose.onNodeWithText("LISTENING")
            .assertExists()
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer recording waveform"))

        compose.runOnIdle {
            state = PromptComposerViewModel.UiState(
                draft = "check deploy logs",
                recording = PromptComposerViewModel.RecordingState.Transcribing,
                amplitude = 0f,
            )
        }

        compose.onNodeWithText("TRANSCRIBING")
            .assertExists()
        compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG)
            .assert(hasContentDescription("Prompt composer transcribing"))
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
