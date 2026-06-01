package com.pocketshell.app.proof

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketshell.app.R
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.rememberTerminalSurfaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Phase 0 proof-of-life screen.
 *
 * Connects (on launch) to a hardcoded SSH host using the test key bundled in
 * `app/src/main/res/raw/proof_test_key`, opens a remote shell, and pipes the
 * stdout bytes into a [TerminalSurface] while forwarding user input back to
 * the remote stdin.
 *
 * Defaults match the `pocketshell-test:ssh` Docker container as documented in
 * `docs/testing.md`:
 *
 * - hostname `10.0.2.2` — the Android emulator's loopback to the host machine
 * - port `2222` — the docker-compose mapping in `tests/docker/docker-compose.yml`
 * - user `testuser`
 * - key from raw resource (matching `tests/docker/test_key`)
 *
 * **This is test-only**. There is no host management UI, no key import flow,
 * no host-key verification, no error UI beyond a status line. All of that
 * belongs to Phase 1; this screen exists solely to demonstrate that the
 * Phase 0 modules (`:shared:core-ssh` + `:shared:core-terminal`) wire
 * together end-to-end.
 */
@Composable
public fun ProofOfLifeScreen(
    modifier: Modifier = Modifier,
    host: String = DEFAULT_HOST,
    port: Int = DEFAULT_PORT,
    user: String = DEFAULT_USER,
) {
    val context = LocalContext.current
    val state = rememberTerminalSurfaceState()
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("connecting to $user@$host:$port") }

    DisposableEffect(host, port, user) {
        // Single-shot connector. The job and its cleanup live in the
        // returned DisposableEffect's `onDispose` so leaving the composition
        // tears everything down.
        var sessionRef: SshSession? = null
        var shellRef: SshShell? = null
        var producerJob: Job? = null

        val launchJob = scope.launch {
            try {
                val key = SshKey.Pem(readKeyFromRawResource(context))
                val result = SshConnection.connect(
                    host = host,
                    port = port,
                    user = user,
                    key = key,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                )
                val session = result.getOrElse { e ->
                    status = "connect failed: ${e.message}"
                    return@launch
                }
                sessionRef = session

                val shell = session.startShell()
                shellRef = shell

                val outputFlow = createStdoutFlow(shell.stdout)
                producerJob = state.attachExternalProducer(
                    scope = scope,
                    stdout = outputFlow,
                    remoteStdin = shell.stdin,
                )

                // Kick the shell so we have something rendered before the
                // user types anything. `\r` triggers a fresh prompt from
                // the remote sh.
                shell.stdin.write("\r".toByteArray())
                shell.stdin.flush()

                status = "connected ($user@$host:$port)"
            } catch (t: Throwable) {
                status = "error: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        onDispose {
            launchJob.cancel()
            producerJob?.cancel()
            state.detachExternalProducer()
            runCatching { shellRef?.close() }
            runCatching { sessionRef?.close() }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(8.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            TerminalSurface(state = state, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Public defaults exposed for tests / overrides. The `ProofPipelineTest`
 * uses different values driven by Testcontainers' dynamic port mapping.
 */
public const val DEFAULT_HOST: String = "10.0.2.2"
public const val DEFAULT_PORT: Int = 2222
public const val DEFAULT_USER: String = "testuser"

/**
 * Read the bundled raw-resource SSH key into a String. The resource is the
 * exact file shipped in `tests/docker/test_key` — an ed25519 OpenSSH-format
 * private key generated for the test container. **Do not reuse this key in
 * production**; it lives in version control and is purely a smoke-test
 * artifact.
 */
internal fun readKeyFromRawResource(context: Context): String {
    return context.resources.openRawResource(R.raw.proof_test_key)
        .bufferedReader()
        .use { it.readText() }
}

/**
 * Terminfo entry advertised on PTY allocation. xterm-256color is the AOSP /
 * Termux baseline and the one that real interactive agent CLIs (opencode,
 * Codex, Claude Code) target. Anything more conservative — notably the
 * `vt100` that sshj's `allocateDefaultPTY` defaults to — pushes those CLIs
 * into a degraded line-mode where the prompt input drops to the bottom of
 * the scrolling shell instead of rendering inside their alternate-screen
 * input box. Kept as a top-level constant so it is visible in code review
 * and easy to grep when bumping sshj or refactoring the SSH layer.
 */
internal const val INTERACTIVE_PTY_TERM: String = "xterm-256color"

/**
 * Initial PTY column count advertised on shell allocation. Matches sshj's
 * historical `allocateDefaultPTY` default (80) so well-behaved login shells
 * that read the SSH-time TIOCGWINSZ see the same starting geometry as
 * before; the on-device [com.termux.view.TerminalView] resizes the remote
 * PTY to the real on-screen grid via `changeWindowDimensions` once it lays
 * out, so this value is only ever seen by the brief pre-layout window.
 */
internal const val INTERACTIVE_PTY_INITIAL_COLUMNS: Int = 80

/**
 * Initial PTY row count. See [INTERACTIVE_PTY_INITIAL_COLUMNS] for the
 * rationale on keeping the 80x24 default; the real grid replaces this on
 * first layout.
 */
internal const val INTERACTIVE_PTY_INITIAL_ROWS: Int = 24

/**
 * Wrap the blocking [Session.Shell.getInputStream] into a coroutine-friendly
 * [Flow] that emits a `ByteArray` for every chunk read from the SSH
 * channel. The flow terminates when the stream returns -1 (remote closed).
 *
 * Reads happen on [Dispatchers.IO] and use normal Flow backpressure. Terminal
 * bytes must not be dropped: split escape sequences corrupt full-screen CLI
 * rendering.
 *
 * Issue #173: catch [IOException] / [SSHException] from `input.read` and end
 * the flow instead of propagating. When the user backgrounds the app (e.g. by
 * taking a screenshot or briefly switching apps) Android may tear the TCP
 * socket down underneath sshj's [SocketInputStream.read] blocking call. The
 * read then throws `SocketException` ("Software caused connection abort",
 * ECONNABORTED) which sshj wraps as [SSHException]. Before this catch the
 * exception escaped the [flow] block, propagated through the producer-scope
 * coroutine that owns the [Flow.collect] in
 * [com.pocketshell.core.terminal.ui.TerminalSurfaceState.attachExternalProducer],
 * and reached the default uncaught-exception handler — crashing the app on
 * the user's first resume after backgrounding (reproduced on Pixel 7a /
 * Android 16, v0.2.7).
 *
 * Ending the flow cleanly lets [SessionViewModel] observe the producer job's
 * completion via `invokeOnCompletion` and transition the connection state to
 * [com.pocketshell.app.session.SessionViewModel.ConnectionStatus.Failed], which
 * is the no-background-work behaviour the app already wants on resume (the
 * user re-establishes via the reconnect path rather than us keeping the
 * socket alive in the background).
 */
internal fun createStdoutFlow(shell: Session.Shell): Flow<ByteArray> =
    createStdoutFlow(shell.inputStream)

internal fun createStdoutFlow(input: InputStream): Flow<ByteArray> {
    return flow {
        val buffer = ByteArray(4096)
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (e: SSHException) {
                // Most common production trigger: sshj's Reader thread maps
                // the underlying SocketException to SSHException on transport
                // tear-down. Log once and end the flow cleanly so the
                // producer scope's invokeOnCompletion can flip the
                // ConnectionStatus.
                Log.i(
                    PRODUCT_FLOW_TAG,
                    "ssh-read-aborted ssh-exception cause=${e.javaClass.simpleName}: ${e.message}",
                )
                break
            } catch (e: IOException) {
                // Bare IOException (SocketException, EOFException,
                // ChannelClosedException, etc.) without an sshj wrap. Same
                // outcome: end the flow.
                Log.i(
                    PRODUCT_FLOW_TAG,
                    "ssh-read-aborted io-exception cause=${e.javaClass.simpleName}: ${e.message}",
                )
                break
            }
            if (n == -1) break
            if (n > 0) {
                emit(buffer.copyOf(n))
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Logcat tag used by [createStdoutFlow] when the SSH read fails with an
 * exception we have to swallow rather than rethrow (issue #173). Kept short
 * enough to satisfy `Log.isLoggable`'s 23-char limit on older Android
 * versions while remaining greppable from crash-report investigation.
 */
internal const val PRODUCT_FLOW_TAG: String = "issue173-stdout-flow"

/**
 * Convenience wrapper used by `ProofPipelineTest`: feed a [Flow] of bytes
 * into a [Session.Shell]'s stdin. Exposed at file scope so tests can drive
 * the same pipeline the Composable uses without standing up a Compose
 * tree.
 */
@Suppress("unused")
internal fun OutputStream.writeText(text: String) {
    write(text.toByteArray())
    flush()
}
