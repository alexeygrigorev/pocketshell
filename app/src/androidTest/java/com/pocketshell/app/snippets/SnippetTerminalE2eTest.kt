package com.pocketshell.app.snippets

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.session.SessionViewModel
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.SnippetEntity
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
 * Emulator + Docker regression coverage for issue #60.
 *
 * This deliberately drives the UI tap surface instead of calling the
 * ViewModel directly: the picker row click must route into
 * [SessionViewModel.onSnippetPicked], through [SessionViewModel.terminalState],
 * and out the same Termux/SSH bridge that keyboard input uses.
 *
 * Preconditions match [com.pocketshell.app.proof.EmulatorDockerSshSmokeTest]:
 * start `tests/docker`'s `agents` target on host port 2222 before running
 * `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SnippetTerminalE2eTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingCommandSnippetSendsInputToDockerShell() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val marker = "pocketshell-snippet-e2e-${System.currentTimeMillis()}"
        val snippet = SnippetEntity(
            id = 60,
            hostId = 1,
            label = "emit marker",
            body = "printf '$marker\\n'",
            kind = "command",
        )

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
            compose.setContent {
                PocketShellTheme {
                    SnippetPickerContent(
                        snippets = listOf(snippet),
                        totalCount = 1,
                        query = "",
                        onQueryChange = {},
                        onSnippetTap = viewModel::onSnippetPicked,
                        onManageTap = {},
                        onClose = {},
                    )
                }
            }

            compose.onNodeWithText("emit marker").performClick()

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
}
