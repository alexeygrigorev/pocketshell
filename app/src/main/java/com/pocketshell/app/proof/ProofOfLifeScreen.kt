package com.pocketshell.app.proof

import android.content.Context
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
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.rememberTerminalSurfaceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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
        var shellRef: Session.Shell? = null
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

                // sshj's high-level `SshSession` does not currently expose a
                // `startShell()` — the public interface only has `exec` and
                // `tail`. For the proof-of-life we need a long-lived
                // interactive shell, so we open one directly via the sshj
                // primitives. The shell channel's stdin and stdout are
                // exposed as ordinary blocking JDK streams.
                //
                // A follow-up issue ("expose interactive shell on
                // SshSession") will wrap this neatly inside `core-ssh`; for
                // now we crack it open here to keep #9 scope small.
                val shellPair = openShell(host, port, user, key)
                shellRef = shellPair.shell

                val outputFlow = createStdoutFlow(shellPair.shell)
                producerJob = state.attachExternalProducer(
                    scope = scope,
                    stdout = outputFlow,
                    remoteStdin = shellPair.shell.outputStream,
                )

                // Kick the shell so we have something rendered before the
                // user types anything. `\r` triggers a fresh prompt from
                // the remote sh.
                shellPair.shell.outputStream.write("\r".toByteArray())
                shellPair.shell.outputStream.flush()

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
 * Holder for the live SSH shell + the underlying [SSHClient]. We surface the
 * client so the caller can close it; `sshj`'s `Shell.close()` does not tear
 * down the connection.
 */
internal data class SshShellHandle(
    val client: SSHClient,
    val sessionChannel: net.schmizz.sshj.connection.channel.direct.Session,
    val shell: Session.Shell,
)

/**
 * Open a fresh interactive shell. Bypasses `core-ssh`'s public surface for
 * now (which only knows `exec` + `tail`) and uses sshj directly. The same
 * primitives `SshConnection.connect` uses are exposed here so the proof
 * screen and `ProofPipelineTest` can share the helper.
 */
internal suspend fun openShell(
    host: String,
    port: Int,
    user: String,
    key: SshKey,
    passphrase: CharArray? = null,
): SshShellHandle = withContext(Dispatchers.IO) {
    val client = SSHClient(DefaultConfig())
    client.addHostKeyVerifier(PromiscuousVerifier())
    client.connect(host, port)

    val keyProvider = when (key) {
        is SshKey.Path -> if (passphrase != null) {
            client.loadKeys(
                key.file.absolutePath,
                net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(passphrase.copyOf()),
            )
        } else {
            client.loadKeys(key.file.absolutePath)
        }
        is SshKey.Pem -> client.loadKeys(
            key.content,
            null,
            passphrase?.let {
                net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(it.copyOf())
            },
        )
    }
    client.authPublickey(user, keyProvider)

    val sessionChannel = client.startSession()
    sessionChannel.allocateDefaultPTY()
    val shell = sessionChannel.startShell()
    SshShellHandle(client = client, sessionChannel = sessionChannel, shell = shell)
}

/**
 * Wrap the blocking [Session.Shell.getInputStream] into a coroutine-friendly
 * [Flow] that emits a `ByteArray` for every chunk read from the SSH
 * channel. The flow terminates when the stream returns -1 (remote closed).
 *
 * Reads happen on [Dispatchers.IO]. We do not buffer beyond the underlying
 * stream's defaults; downstream collectors (the bridge's `feedBytes`) are
 * responsible for not lagging — but `MutableSharedFlow(extraBufferCapacity)`
 * inside [com.pocketshell.core.terminal.ui.TerminalSurfaceState] absorbs the
 * common burst case.
 */
internal fun createStdoutFlow(shell: Session.Shell): Flow<ByteArray> {
    val flow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val readerThread = Thread({
        val input = shell.inputStream
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                if (n > 0) {
                    // tryEmit honours `extraBufferCapacity`; if collectors
                    // lag, the byte chunk is dropped. For Phase 0 this is
                    // acceptable — we are visualising the stream, not
                    // logging it for replay.
                    val chunk = buffer.copyOf(n)
                    if (!flow.tryEmit(chunk)) {
                        // Buffer full + no collector ready. Drop and
                        // continue; the SSH channel will keep delivering.
                    }
                }
            }
        } catch (_: Throwable) {
            // Stream closed underneath us — fine, the flow ends naturally
            // when this thread exits.
        }
    }, "PocketShellSshReader")
    readerThread.isDaemon = true
    readerThread.start()
    return flow
}

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
