package com.pocketshell.app.snippets

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.pocketshell.uikit.theme.PocketShellThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator + Docker regression coverage for issues #60, #187, and #227.
 *
 * Drives the picker's explicit `Send` / `Send + ↵` chips instead of
 * calling the ViewModel directly: each chip tap must route into
 * [SessionViewModel] state, through [SessionViewModel.terminalState],
 * and out the same Termux/SSH bridge that keyboard input uses.
 *
 * Per D22 (issue #227) the picker exposes a single explicit-intent
 * `onSnippetSend(snippet, withEnter)` callback — the legacy row-body
 * smart-default tap surface was removed. These tests pin the only
 * remaining dispatch path.
 *
 * Preconditions match [com.pocketshell.app.proof.EmulatorDockerSshSmokeTest]:
 * start `tests/docker`'s `agents` target on host port 2222 before running
 * `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SnippetTerminalE2eTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Issue #187 / #227: tapping the explicit `Send + ↵` chip on a
     * command snippet must press Enter so the body executes on the
     * remote shell.
     *
     * Discriminator: the snippet body uses arithmetic expansion
     * (`echo $((A + B))`) so the *sum* — which is never literally
     * present in the typed input — only appears in stdout after the
     * shell actually evaluates the line. If Enter were missing, bash's
     * line buffer would echo the typed characters back but never
     * compute the sum.
     */
    @Test
    fun tappingSendWithEnterChip_executesCommandSnippet() = runBlocking {
        val key = readTestKey()
        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        // Arithmetic-expansion marker: the sum 88888 only shows up in
        // stdout if the shell actually executes the body, never as a
        // side-effect of echoing the typed characters.
        val snippet = SnippetEntity(
            id = 187,
            hostId = 1,
            label = "with-enter probe",
            body = "echo SUM=\$((44444 + 44444))",
            kind = "command",
        )
        val executionMarker = "SUM=88888"

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
                PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                    SnippetPickerContent(
                        snippets = listOf(snippet),
                        totalCount = 1,
                        query = "",
                        onQueryChange = {},
                        onSnippetSend = { picked, withEnter ->
                            // Production wiring (issues #187 / #227):
                            // the real SessionScreen call site invokes
                            // `viewModel.sendSnippet(snippet, withEnter)`,
                            // and that is exactly what this test calls
                            // — no test-only shortcut around the
                            // ViewModel. If sendSnippet ever stops
                            // forwarding `withEnter` honestly into the
                            // input bridge, this test will fail.
                            viewModel.sendSnippet(picked, withEnter)
                        },
                        onManageTap = {},
                        onClose = {},
                    )
                }
            }

            // Tap the explicit Send + ↵ chip (the only dispatch surface
            // post-#227).
            compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = true))
                .performClick()

            withTimeout(10_000) {
                while (synchronized(received) { !received.contains(executionMarker) }) {
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

    /**
     * Issue #187 / #227: tapping the plain `Send` chip on a prompt
     * snippet must NOT press Enter. The snippet body is parked on the
     * shell's input line but never executed; the user can continue
     * typing or dismiss the line manually.
     *
     * The discriminator mirrors [tappingSendWithEnterChip_executesCommandSnippet]
     * but inverted: the arithmetic sum (`SUM=99999`) is only emitted
     * once the line actually runs. Within the negative-timeout window
     * the sum must remain absent. To prove the shell is alive and the
     * body really was on the input line, we then push a CR by hand
     * down the same SSH stdin and verify the sum DOES appear after
     * that — anything else would mean the body never reached the
     * remote in the first place (false-negative on the assertion).
     */
    @Test
    fun tappingSendChip_onPromptSnippet_doesNotPressEnter() = runBlocking {
        val key = readTestKey()
        val viewModel = SessionViewModel(
            applicationContext = InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val snippet = SnippetEntity(
            id = 1870,
            hostId = 1,
            label = "no-enter probe",
            body = "echo SUM=\$((33333 + 66666))",
            kind = "prompt",
        )
        val executionMarker = "SUM=99999"

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
                PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                    SnippetPickerContent(
                        snippets = listOf(snippet),
                        totalCount = 1,
                        query = "",
                        onQueryChange = {},
                        onSnippetSend = { picked, withEnter ->
                            viewModel.sendText(picked.body, withEnter = withEnter)
                        },
                        onManageTap = {},
                        onClose = {},
                    )
                }
            }

            // Tap the plain Send chip (no Enter) on the prompt snippet.
            compose.onNodeWithTag(snippetSendChipTag(snippet.id, withEnter = false))
                .performClick()

            // Negative-assert window: give the shell ~3s to do nothing.
            // Bash's typed-character echo will surface the literal body
            // characters in `received`, but never the *sum* unless the
            // line is actually executed.
            val negativeWindowMs = 3_000L
            val negativeDeadline = System.currentTimeMillis() + negativeWindowMs
            while (System.currentTimeMillis() < negativeDeadline) {
                synchronized(received) {
                    assertFalse(
                        "Send-without-enter unexpectedly executed the snippet body. " +
                            "stdout=$received",
                        received.contains(executionMarker),
                    )
                }
                delay(100)
            }

            // Sanity: prove the body really is sitting on the input
            // line by pressing Enter ourselves via the same SSH stdin
            // the picker would have used. If the body never reached
            // the remote, the manual CR would print the shell's
            // PS1 prompt but never the sum — guarding against a
            // false-negative on the assertion above.
            handle.shell.outputStream.apply {
                write("\r".toByteArray(Charsets.UTF_8))
                flush()
            }
            withTimeout(10_000) {
                while (synchronized(received) { !received.contains(executionMarker) }) {
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

    private fun readTestKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
}
