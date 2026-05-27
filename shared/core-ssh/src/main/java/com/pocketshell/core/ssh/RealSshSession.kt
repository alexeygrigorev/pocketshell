package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.channel.direct.Session.Command
import net.schmizz.sshj.transport.TransportException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * sshj-backed implementation of [SshSession].
 *
 * Internal to the module — callers only see the [SshSession] interface and
 * obtain instances via [SshConnection.connect].
 */
internal class RealSshSession(
    private val client: SSHClient,
) : SshSession {

    /**
     * Coroutine scope owning any background work (e.g. [tail] jobs). Closed
     * when the session is [close]d so all child jobs cancel deterministically.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    override suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        ensureConnected()
        client.startSession().use { sessionChannel ->
            val cmd: Command = try {
                sessionChannel.exec(command)
            } catch (t: Throwable) {
                throw SshException("Failed to start exec channel for `$command`: ${t.message}", t)
            }
            // Read both streams to EOF before joining. sshj sets exitStatus
            // only after the remote side has closed its end of the channel.
            val stdout = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
            cmd.join()
            // sshj returns null exitStatus when the server didn't send one
            // (e.g. signal-killed). Map to -1 so the caller can still tell
            // it wasn't a clean 0.
            val exitCode = cmd.exitStatus ?: -1
            ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
        }
    }

    override fun tail(path: String, onLine: (String) -> Unit): Job =
        tail(path, fromLineExclusive = -1, onLine = onLine)

    override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
        ensureConnected()
        // Each tail owns its own exec channel — running `tail -F` keeps the
        // channel open for the lifetime of the job. Cancelling the job
        // closes the channel via `Command.close()` which signals the remote
        // tail to exit.
        return scope.launch {
            val coroutineJob = currentCoroutineContext()[Job]
            val sessionChannel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException("Failed to start tail session for `$path`: ${t.message}", t)
            }
            var cmd: Command? = null
            val cancelHandle = coroutineJob?.invokeOnCompletion {
                runCatching { cmd?.close() }
                runCatching { sessionChannel.close() }
            }
            try {
                // -F follows by name, surviving rotation. Quote the path so
                // weird filenames don't break the shell parsing.
                val quoted = path.replace("'", "'\\''")
                val lineArg = if (fromLineExclusive >= 0) {
                    "-n +${fromLineExclusive + 1}"
                } else {
                    "-n 0"
                }
                cmd = sessionChannel.exec("tail -F $lineArg '$quoted'")
                BufferedReader(InputStreamReader(cmd!!.inputStream, Charsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                        // Suspend per-line so a cancelled tail job exits
                        // promptly even when the remote is gushing output.
                        yield()
                    }
                }
            } finally {
                cancelHandle?.dispose()
                runCatching { cmd?.close() }
                runCatching { sessionChannel.close() }
            }
        }
    }

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward {
        ensureConnected()
        return try {
            RealSshPortForward(
                client = client,
                remoteHost = remoteHost,
                remotePort = remotePort,
                localPort = localPort,
            )
        } catch (t: Throwable) {
            throw SshException(
                "Failed to open local port forward 127.0.0.1:$localPort -> $remoteHost:$remotePort: ${t.message}",
                t,
            )
        }
    }

    override fun startShell(): SshShell {
        ensureConnected()
        // Two-step open mirroring sshj's idiomatic interactive-shell
        // recipe: `startSession()` to get a session channel, allocate a
        // PTY advertising [INTERACTIVE_PTY_TERM] ("xterm-256color") at
        // [INTERACTIVE_PTY_INITIAL_COLUMNS]x[INTERACTIVE_PTY_INITIAL_ROWS]
        // (the on-device TerminalView resizes the remote PTY to the real
        // grid on first layout), then `startShell()` to bind the channel
        // to the user's login shell.
        //
        // Issue #106: this used to call `sessionChannel.allocateDefaultPTY()`,
        // which advertises `TERM=vt100` in sshj 0.40. Real interactive
        // agent CLIs (opencode, Codex, Claude Code) probe TERM at startup
        // and fall back to a degraded line-mode rendering when they see
        // vt100 — the prompt input drops to the bottom of the scrolling
        // shell instead of rendering inside their alternate-screen input
        // box. The same root cause was fixed for the proof-of-life shell
        // entry point in #102; this is the second SSH-shell entry point.
        // The two entry points are deliberately not refactored into a
        // shared helper here (out of scope per #106 non-goals); the
        // [INTERACTIVE_PTY_TERM] constant exists so both call sites are
        // grep-able and the chosen terminfo entry is reviewable in one
        // place.
        //
        // Failures at any of the three steps are wrapped in SshException
        // so callers don't have to know about sshj's exception hierarchy.
        // If `startShell` itself fails we close the half-opened session
        // channel before propagating, so we never leak a channel on error.
        val sessionChannel = try {
            client.startSession()
        } catch (t: Throwable) {
            throw SshException("Failed to open SSH session channel for shell: ${t.message}", t)
        }
        try {
            sessionChannel.allocatePTY(
                /* term = */ INTERACTIVE_PTY_TERM,
                /* cols = */ INTERACTIVE_PTY_INITIAL_COLUMNS,
                /* rows = */ INTERACTIVE_PTY_INITIAL_ROWS,
                /* widthPx = */ 0,
                /* heightPx = */ 0,
                /* modes = */ emptyMap(),
            )
            val shell = sessionChannel.startShell()
            return RealSshShell(sessionChannel = sessionChannel, shell = shell)
        } catch (t: Throwable) {
            runCatching { sessionChannel.close() }
            throw SshException("Failed to start remote shell: ${t.message}", t)
        }
    }

    override suspend fun uploadFile(file: File, remotePath: String): String =
        withContext(Dispatchers.IO) {
            ensureConnected()
            if (!file.exists()) {
                throw SshException("Local file does not exist: ${file.absolutePath}")
            }
            file.inputStream().use { input ->
                uploadStreamInternal(
                    input = input,
                    length = file.length(),
                    name = file.name,
                    remotePath = remotePath,
                )
            }
            remotePath
        }

    override suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = withContext(Dispatchers.IO) {
        ensureConnected()
        uploadStreamInternal(input, length, name, remotePath)
        remotePath
    }

    /**
     * Stream-to-remote-file primitive shared by [uploadFile] and
     * [uploadStream]. Uploads via an `exec` channel that runs
     * `cat > <path>` on the remote and pipes bytes through stdin.
     *
     * Why not SCP or SFTP? Both require an extra binary on the remote
     * (`scp` from `openssh-client`, `sftp-server` from
     * `openssh-sftp-server`). The Alpine-based Docker fixtures used
     * by the connected emulator tests ship the SSH server alone, and
     * minimal real-world servers often do too. The exec-channel +
     * `cat` approach only needs a POSIX shell and `cat`, which are
     * universally present.
     *
     * The remote `cat` reads stdin until EOF, then writes to
     * [remotePath]. We close stdin (via `sendEOF`) to signal completion
     * and then `join` the command, checking the exit status.
     *
     * Stream + length are taken so the caller can pass either —
     * length is informational only; `cat` does not need it.
     */
    private fun uploadStreamInternal(
        input: InputStream,
        @Suppress("UNUSED_PARAMETER") length: Long,
        name: String,
        remotePath: String,
    ) {
        val sessionChannel = try {
            client.startSession()
        } catch (t: Throwable) {
            throw SshException(
                "Could not open session channel for upload of $name to $remotePath: ${t.message}",
                t,
            )
        }
        try {
            val quoted = remotePath.replace("'", "'\\''")
            val command: Command = try {
                sessionChannel.exec("cat > '$quoted'")
            } catch (t: Throwable) {
                throw SshException(
                    "Could not start remote `cat` for upload of $name to $remotePath: ${t.message}",
                    t,
                )
            }
            try {
                command.outputStream.use { output ->
                    input.copyTo(output)
                }
                // `outputStream.close()` sends EOF on the channel, but
                // sshj also exposes `signal()` to nudge a stuck remote.
                // The remote `cat` exits on EOF; `join()` waits for the
                // command to finish so we can read the exit code.
                command.join()
                val exit = command.exitStatus ?: -1
                if (exit != 0) {
                    val stderr = runCatching {
                        command.errorStream.readBytes().toString(Charsets.UTF_8)
                    }.getOrDefault("")
                    throw SshException(
                        "Remote `cat` exited with status $exit while writing $remotePath: ${stderr.trim()}",
                    )
                }
            } finally {
                runCatching { command.close() }
            }
        } catch (e: SshException) {
            throw e
        } catch (t: Throwable) {
            throw SshException(
                "Upload of $name to $remotePath failed: ${t.message}",
                t,
            )
        } finally {
            runCatching { sessionChannel.close() }
        }
    }

    override fun close() {
        scope.cancel()
        // Issue #151: a teardown-before-reattach race could leave the
        // transport already half-disconnected (cancelled mid-flight from the
        // tmux event-loop coroutine in `TmuxSessionViewModel`) when this
        // `close()` runs. sshj's `SSHClient.disconnect()` then throws
        // `TransportException` with `DisconnectReason.BY_APPLICATION`
        // ("Disconnected") because it tries to send a disconnect packet over
        // a transport that has already gone down. That exception was
        // surfaced to the user as a crash on the v0.2.7 dogfood device.
        //
        // The orthogonal `TmuxSessionViewModel` fix joins the event-loop
        // job before calling `close()`, which closes the race in the normal
        // path. The `BY_APPLICATION` catch below is the belt-and-suspenders
        // side of that fix: if any other code path ever calls close on an
        // already-disconnected transport (including a double-close), we
        // swallow the already-disconnected signal silently because that is
        // exactly the no-op outcome `close()` already wanted. Other
        // `TransportException` reasons (KEX failure, MAC error, unexpected
        // protocol errors) propagate so genuine transport faults still
        // surface.
        //
        // The outer best-effort catch keeps the previous `runCatching`
        // semantics for non-TransportException failures during teardown
        // (e.g. socket-level IO faults). `close()` is supposed to be a
        // best-effort cleanup — propagating a stray IO error during shutdown
        // does not give the caller anything actionable and breaks the
        // idempotency guarantee. We re-throw the configured "real
        // transport fault" path explicitly so the BY_APPLICATION skip is
        // the only path that silently no-ops, and everything else either
        // succeeds or is logged as best-effort.
        //
        // Issue #166: `SSHClient.disconnect()` sends an
        // `SSH_MSG_DISCONNECT` packet over the live transport — a real
        // socket write. The non-suspending `close()` contract is dictated
        // by `AutoCloseable`, and historically several callers
        // (Compose `onDispose`, `ViewModel.onCleared`,
        // `HostTmuxSessionsGateway`, screen disposers) invoke this from
        // the Android Main thread. With Android's StrictMode
        // detectNetwork() enabled — and on real devices that policy is
        // always on for the Main thread — that socket write trips
        // `NetworkOnMainThreadException`. The pre-#166 RuntimeException
        // catch below hid the crash from the dogfood device but the
        // policy violation still aborted the disconnect mid-write,
        // leaving the sshj transport in a half-closed state and producing
        // logcat noise on every teardown.
        //
        // The fix dispatches the network-touching `disconnect()` call onto
        // `Dispatchers.IO` via `runBlocking(Dispatchers.IO) { ... }`:
        // the calling thread (which may be Main) still blocks until the
        // disconnect finishes (preserving the AutoCloseable ordering
        // contract), but the actual SSH_MSG_DISCONNECT socket write
        // happens on an IO worker thread, so the
        // BlockGuard / StrictMode `onNetwork` probe never fires on Main.
        // Hopping the suspending boundary higher (e.g. making
        // `SshSession.close()` itself a suspend) would ripple through
        // every caller; the AutoCloseable-preserving thread hop is the
        // surgical fix called for by the issue.
        try {
            runBlocking(Dispatchers.IO) {
                client.disconnect()
            }
        } catch (e: TransportException) {
            if (e.disconnectReason != DisconnectReason.BY_APPLICATION) {
                throw e
            }
            // BY_APPLICATION = already-disconnected; close() is idempotent
            // here by design — silently no-op.
        } catch (e: IOException) {
            // sshj declares `disconnect()` as `throws IOException`. A
            // non-TransportException IO failure during shutdown is
            // best-effort: nothing the caller can do, propagating it would
            // defeat the idempotency contract. Swallowed deliberately to
            // preserve the pre-#151 `runCatching` semantics for this path.
        } catch (e: RuntimeException) {
            // Belt-and-suspenders: the pre-#151 implementation wrapped the
            // whole disconnect in `runCatching`, which silently swallowed
            // every Throwable. With #166 the socket write now runs on
            // `Dispatchers.IO` so StrictMode `NetworkOnMainThreadException`
            // can no longer originate here — but we keep this catch for
            // any other RuntimeException sshj may surface during teardown
            // (e.g. an exotic state-machine error) so close() stays
            // idempotent in the face of unknown teardown failure modes.
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw SshException("SSH session is not connected")
    }
}

/**
 * Terminfo entry advertised when allocating the PTY for [RealSshSession.startShell].
 *
 * `xterm-256color` is the AOSP / Termux baseline and the terminfo entry that
 * real interactive agent CLIs (opencode, Codex, Claude Code) target. Anything
 * more conservative — notably the `vt100` that sshj 0.40's
 * `Session.allocateDefaultPTY` defaults to — pushes those CLIs into a degraded
 * line-mode rendering where the prompt input drops to the bottom of the
 * scrolling shell instead of rendering inside their alternate-screen input
 * box.
 *
 * Issue #106: kept as an `internal const` so the unit test in
 * `RealSshSessionPtyAllocationTest` can pin the value and so the chosen
 * terminfo entry is grep-able from both SSH-shell entry points (the second
 * one lives in `app/src/main/java/com/pocketshell/app/proof/ProofOfLifeScreen.kt`
 * as `INTERACTIVE_PTY_TERM` per #102). Update both call sites in lock-step.
 */
internal const val INTERACTIVE_PTY_TERM: String = "xterm-256color"

/**
 * Initial PTY column count advertised on shell allocation.
 *
 * Matches sshj's historical `allocateDefaultPTY` default (80) so well-behaved
 * login shells that read the SSH-time TIOCGWINSZ see the same starting
 * geometry as before. The on-device terminal resizes the remote PTY to the
 * real on-screen grid via `changeWindowDimensions` once it lays out, so this
 * value is only ever observed by the brief pre-layout window.
 */
internal const val INTERACTIVE_PTY_INITIAL_COLUMNS: Int = 80

/**
 * Initial PTY row count. See [INTERACTIVE_PTY_INITIAL_COLUMNS] for the
 * rationale on keeping the 80x24 default; the real grid replaces this on
 * first layout.
 */
internal const val INTERACTIVE_PTY_INITIAL_ROWS: Int = 24
