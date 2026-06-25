package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Session.Command
import net.schmizz.sshj.transport.TransportException
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

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

    /**
     * Single-writer dispatch owner for this connection's transport (issue
     * #847 / #766 slice 1). EVERY transport-touching write + channel-lifecycle
     * operation — exec channel open/command/close, the `-CC` shell's stdin
     * write / resize / close, tail channel open/close, upload/download channel
     * open/close, and the final `disconnect()` — funnels through here so it
     * runs strictly one-at-a-time on one thread, in submission order, with no
     * op dispatched once teardown is enqueued.
     *
     * This kills the `Connection corrupted` desync (a channel open or command
     * write racing a KEX/rekey boundary or `die()` while another owner churns
     * exec channels on the SAME transport). Long-lived READS (the `-CC` reader
     * loop, `tail -F` line reads, `cat` download streaming) run OUTSIDE the
     * dispatcher — only the open/write/close packets that advance the encoder
     * sequence counter are serialised — so concurrent reads don't regress
     * connect/enumeration latency.
     */
    private val dispatcher = TransportDispatcher()

    override val isConnected: Boolean
        get() = !dispatcher.isClosed && client.isConnected && client.isAuthenticated

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun exec(command: String): ExecResult {
        val callerJob = currentCoroutineContext()[Job]
        val sessionChannelRef = AtomicReference<Session?>()
        val cmdRef = AtomicReference<Command?>()
        val cancelHandle = callerJob?.invokeOnCompletion(onCancelling = true) { cause ->
            if (cause != null) {
                // Channel close is itself a transport write — serialise it
                // through the dispatcher rather than racing the wire from the
                // cancellation thread.
                scope.launch {
                    runCatching { dispatcher.run { runCatching { cmdRef.get()?.close() } } }
                    runCatching { dispatcher.run { runCatching { sessionChannelRef.get()?.close() } } }
                }
            }
        }
        return try {
            // Phase 1 — open the channel + send the command, serialised against
            // every other transport op (the corruption-prone packets).
            val liveCommand = dispatcher.run {
                ensureConnected()
                val sessionChannel = try {
                    client.startSession()
                } catch (t: Throwable) {
                    throw SshException("Failed to open exec channel for `$command`: ${t.message}", t)
                }
                sessionChannelRef.set(sessionChannel)
                val cmd = try {
                    sessionChannel.exec(command)
                } catch (t: Throwable) {
                    runCatching { sessionChannel.close() }
                    sessionChannelRef.set(null)
                    throw SshException("Failed to start exec channel for `$command`: ${t.message}", t)
                }
                cmdRef.set(cmd)
                cmd
            }
            // Phase 2 — read stdout/stderr to EOF OUTSIDE the dispatcher so a
            // slow command never wedges the `-CC` write or other execs.
            withContext(Dispatchers.IO) {
                val stdout = liveCommand.inputStream.readBytes().toString(Charsets.UTF_8)
                val stderr = liveCommand.errorStream.readBytes().toString(Charsets.UTF_8)
                liveCommand.join()
                // sshj returns null exitStatus when the server didn't send one
                // (e.g. signal-killed). Map to -1 so the caller can still tell
                // it wasn't a clean 0.
                val exitCode = liveCommand.exitStatus ?: -1
                ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
            }
        } finally {
            cancelHandle?.dispose()
            // Phase 3 — close the channel, serialised through the dispatcher
            // (channel close writes SSH_MSG_CHANNEL_CLOSE on the transport).
            runCatching { dispatcher.run { runCatching { cmdRef.get()?.close() } } }
            runCatching { dispatcher.run { runCatching { sessionChannelRef.get()?.close() } } }
        }
    }

    override fun tail(path: String, onLine: (String) -> Unit): Job =
        tail(path, fromLineExclusive = -1, onLine = onLine)

    override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job {
        // Each tail owns its own exec channel — running `tail -F` keeps the
        // channel open for the lifetime of the job. Cancelling the job
        // closes the channel via `Command.close()` which signals the remote
        // tail to exit.
        return scope.launch {
            // Issue #621: the connectivity check must run INSIDE the launched
            // coroutine, not synchronously before it. A `startAgentConversationForPane`
            // crash (app v0.3.29) was traced to `tail()` calling `ensureConnected()`
            // on the caller's thread over an already-dead SSH session, throwing
            // `SshException("SSH session is not connected")` straight into
            // `AgentConversationRepository.tailEventsFromLine` / the main thread.
            // A silently-dead transport (sshj's `isConnected` lies until the 60s
            // keepalive trips) is an ordinary network-loss event, NOT a crash:
            // the reconnect state machine relaunches the tail on a fresh session
            // after reattach. Handle it like the `startSession()` transport-drop
            // below — log it as recoverable and end the tail job cleanly — so it
            // never reaches the crash reporter even if a caller forgets to wrap
            // the launch in a try/catch.
            if (!isConnected) {
                logTailRecoverableFailure(path, SshException("SSH session is not connected"))
                return@launch
            }
            val coroutineJob = currentCoroutineContext()[Job]
            // Issue #239 — agent-log tail must NOT propagate transport
            // failures to the coroutine root.
            //
            // The v0.2.8 maintainer device captured a crash where the
            // remote SSH socket aborted mid-tail (`Software caused
            // connection abort`). `client.startSession()` returned with
            // a `ConnectionException` (which extends `SSHException` /
            // `IOException`), the existing wrap turned it into an
            // `SshException`, and that propagated to the coroutine root
            // on the supervisor-scoped `launch`. The default uncaught
            // exception handler then routed it to `CrashReporter` —
            // turning an ordinary network-loss event into a crash
            // report.
            //
            // Per D21 (no background work) and the orthogonal reconnect
            // state machine in `TmuxSessionViewModel` (#145 + #173):
            // when the transport drops, the tmux event-loop coroutine
            // observes the same drop through its producer job and
            // routes through `_disconnected` -> reconnect; on
            // reconnect, `reconcilePanes` calls
            // `startAgentConversationForPane` again and a fresh
            // `tail()` is launched on the new session. The tail's own
            // job just needs to end cleanly so it doesn't cascade into
            // the crash reporter.
            //
            // Catch shape:
            //  - `SshException`: anything we wrapped ourselves
            //    (`Failed to start tail session ...` and friends).
            //  - `IOException`: covers `SocketException`,
            //    sshj's `SSHException` family (`TransportException`,
            //    `ConnectionException`, all extend `SSHException` which
            //    extends `IOException`), and any other I/O failure
            //    reading from the channel stream.
            //  - `CancellationException` is deliberately NOT caught:
            //    coroutine cancellation must always propagate so the
            //    structured-concurrency contract is preserved (Job
            //    cancellation by the caller still tears down the
            //    channel via the `invokeOnCompletion` handler below).
            //  - Genuine programming errors (NPE, IAE, ...) still
            //    propagate to the supervisor scope so they aren't
            //    silently swallowed.
            // Channel open + the `tail -F` exec write are transport-mutating
            // packets — serialise them through the dispatcher (issue #847). The
            // long-lived line READ below runs OUTSIDE the dispatcher so the
            // follow loop never wedges the `-CC` write or other ops.
            val sessionChannelRef = AtomicReference<Session?>()
            var cmd: Command? = null
            val cancelHandle = coroutineJob?.invokeOnCompletion {
                // Close is a transport write — funnel through the dispatcher.
                scope.launch {
                    runCatching { dispatcher.run { runCatching { cmd?.close() } } }
                    runCatching { dispatcher.run { runCatching { sessionChannelRef.get()?.close() } } }
                }
            }
            try {
                // -F follows by name, surviving rotation. Quote the path so
                // weird filenames don't break the shell parsing.
                val quoted = shellSingleQuote(path)
                val lineArg = if (fromLineExclusive >= 0) {
                    "-n +${fromLineExclusive + 1}"
                } else {
                    "-n 0"
                }
                cmd = try {
                    dispatcher.run {
                        val channel = client.startSession()
                        sessionChannelRef.set(channel)
                        channel.exec("tail -F $lineArg $quoted")
                    }
                } catch (e: SshException) {
                    logTailRecoverableFailure(path, e)
                    return@launch
                } catch (e: IOException) {
                    // Channel-open / exec race against transport drop —
                    // same recoverable-disconnect story as the
                    // startSession() catch above.
                    logTailRecoverableFailure(path, e)
                    return@launch
                } catch (t: Throwable) {
                    throw SshException("Failed to start tail session for `$path`: ${t.message}", t)
                }
                BufferedReader(InputStreamReader(cmd!!.inputStream, Charsets.UTF_8)).use { reader ->
                    while (isActive) {
                        val line = try {
                            reader.readLine() ?: break
                        } catch (e: IOException) {
                            // Mid-stream socket abort. The
                            // `invokeOnCompletion` handler below will
                            // close the channel for us. Surface as a
                            // clean job exit, not a crash.
                            logTailRecoverableFailure(path, e)
                            return@launch
                        }
                        onLine(line)
                        // Suspend per-line so a cancelled tail job exits
                        // promptly even when the remote is gushing output.
                        yield()
                    }
                }
            } finally {
                cancelHandle?.dispose()
                val closeCmd = cmd
                val closeChannel = sessionChannelRef.get()
                runCatching { dispatcher.run { runCatching { closeCmd?.close() } } }
                runCatching { dispatcher.run { runCatching { closeChannel?.close() } } }
            }
        }
    }

    /**
     * Diagnostic log when [tail] swallows a recoverable transport failure
     * (issue #239). Logged through `java.util.logging` (same channel as
     * [SshjTransportThreadGuard]) so the same swallowed-disconnect event
     * is visible in logcat under a stable, grep-able tag without pulling
     * `android.util.Log` into the shared module.
     */
    private fun logTailRecoverableFailure(path: String, cause: Throwable) {
        TAIL_LOGGER.log(
            Level.INFO,
            "[$TAIL_LOG_TAG] tail($path) ended on transport drop; reconnect path will resume on next attach: ${cause.javaClass.simpleName}: ${cause.message}",
        )
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
        // Channel open + PTY alloc + shell start are all transport-mutating
        // packets — serialise the whole open through the dispatcher (issue
        // #847) so it can't race an exec channel open / the liveness probe / a
        // KEX boundary on the same transport.
        return dispatcher.runBlockingDispatch {
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
                RealSshShell(
                    sessionChannel = sessionChannel,
                    shell = shell,
                    dispatcher = dispatcher,
                )
            } catch (t: Throwable) {
                runCatching { sessionChannel.close() }
                throw SshException("Failed to start remote shell: ${t.message}", t)
            }
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

    override suspend fun listDirectory(
        remotePath: String,
        maxEntries: Int,
    ): RemoteListing = withContext(Dispatchers.IO) {
        ensureConnected()
        // Listing route: a structured `exec` over `find -maxdepth 1` + `stat`,
        // NOT SFTP. The Alpine fixtures (`tests/docker/Dockerfile.ssh`) and many
        // minimal OpenSSH servers ship `openssh-server` *without* the separate
        // `openssh-sftp-server` package, so `Subsystem sftp` points at a missing
        // binary and `newSFTPClient()` dies with "EOF while reading packet".
        // The same reasoning is why `downloadFile`/`uploadStream` use a `cat`
        // exec channel rather than SCP/SFTP — we only require a POSIX shell plus
        // `find`/`stat`, which are present on busybox and coreutils alike.
        val probe = exec(buildListDirCommand(remotePath))
        if (probe.exitCode != PROBE_EXIT_OK) {
            throw classifyListFailure(remotePath, probe)
        }
        parseListing(probe.stdout, remotePath, maxEntries)
    }

    override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray =
        withContext(Dispatchers.IO) {
            ensureConnected()
            // 1. Size + existence probe. A single shell command prints either
            //    the regular-file byte count or a `no file` sentinel. This
            //    lets us refuse a huge file *before* streaming any bytes, so
            //    a multi-gigabyte file never reaches the JVM heap.
            val probe = exec(buildSizeProbeCommand(remotePath))
            when (val size = parseSizeProbe(probe.stdout)) {
                SIZE_PROBE_NO_FILE -> throw SshFileNotFoundException(remotePath)
                SIZE_PROBE_UNPARSEABLE -> {
                    // Size probe failed unexpectedly (shell missing wc, weird
                    // output). Fall through to a capped streaming read rather
                    // than assuming the file is fine or missing — the read
                    // itself enforces the cap.
                }
                else -> if (size > maxBytes) {
                    throw SshFileTooLargeException(remotePath, size, maxBytes)
                }
            }
            // 2. Stream the raw bytes via `cat`, enforcing the cap a second
            //    time while reading (defence against TOCTOU growth / a remote
            //    that ignored the probe).
            readRemoteBytesCapped(remotePath, maxBytes)
        }

    /**
     * Stream the raw bytes of [remotePath] over an `exec` channel running
     * `cat`, aborting with [SshFileTooLargeException] if more than [maxBytes]
     * arrive. Binary-safe — reads from sshj's raw channel stream with no
     * charset round-trip.
     */
    private fun readRemoteBytesCapped(remotePath: String, maxBytes: Long): ByteArray {
        // Channel open + `cat` exec are transport-mutating packets — serialise
        // through the dispatcher (issue #847). The capped streaming read below
        // runs OUTSIDE the dispatcher so a large file never wedges the `-CC`
        // write or other ops.
        val quoted = quoteRemotePathForShell(remotePath)
        val (sessionChannel, command) = dispatcher.runBlockingDispatch {
            val channel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException("Could not open session channel to read $remotePath: ${t.message}", t)
            }
            val cmd: Command = try {
                channel.exec("cat $quoted")
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw SshException("Could not start remote `cat` for $remotePath: ${t.message}", t)
            }
            channel to cmd
        }
        try {
            try {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                command.inputStream.use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw SshFileTooLargeException(remotePath, -1, maxBytes)
                        }
                        buffer.write(chunk, 0, read)
                    }
                }
                command.join()
                val exit = command.exitStatus ?: -1
                if (exit != 0) {
                    val stderr = runCatching {
                        command.errorStream.readBytes().toString(Charsets.UTF_8)
                    }.getOrDefault("").trim()
                    // `cat` on a missing/unreadable file exits non-zero; map a
                    // "No such file" stderr to the friendly not-found type.
                    if (stderr.contains("No such file", ignoreCase = true)) {
                        throw SshFileNotFoundException(remotePath)
                    }
                    throw SshException("Remote `cat` exited with status $exit reading $remotePath: $stderr")
                }
                return buffer.toByteArray()
            } finally {
                runCatching { dispatcher.runBlockingDispatch { runCatching { command.close() } } }
            }
        } catch (e: SshException) {
            throw e
        } catch (t: Throwable) {
            throw SshException("Reading $remotePath failed: ${t.message}", t)
        } finally {
            runCatching { dispatcher.runBlockingDispatch { runCatching { sessionChannel.close() } } }
        }
    }

    /**
     * Stream-to-remote-file primitive shared by [uploadFile] and
     * [uploadStream]. Uploads ATOMICALLY (issue #930): the bytes stream via an
     * `exec` channel running `cat > <temp-path>` on the remote, the transferred
     * size is verified, and ONLY on a fully-successful transfer is the temp file
     * renamed (`mv`) onto [remotePath]. Any mid-stream drop / timeout / short
     * read therefore leaves the REAL attachment path untouched, never a 0-byte
     * or partial corrupt artifact at the destination.
     *
     * The previous design ran `cat > <final-path>` directly, which truncated the
     * destination to 0 bytes the instant the channel opened — so a disconnect
     * mid-transfer left a 0-byte file at the real path (the #928 D7 device
     * forensics: 9 zero-byte attachment files). That non-atomic path is gone.
     *
     * Why not SCP or SFTP? Both require an extra binary on the remote
     * (`scp` from `openssh-client`, `sftp-server` from
     * `openssh-sftp-server`). The Alpine-based Docker fixtures used
     * by the connected emulator tests ship the SSH server alone, and
     * minimal real-world servers often do too. The exec-channel +
     * `cat` approach only needs a POSIX shell, `cat`, `wc`, `mv` and `rm`,
     * which are universally present.
     *
     * Bounding (issue #930, folds in #928 D5 W-4): the blocking byte-copy +
     * `join()` is wrapped in a [withTimeoutOrNull] ceiling so a wedged channel
     * fails fast with a clear error instead of hanging indefinitely.
     *
     * [length] is the declared content length. When known (>= 0) it is also
     * verified against the bytes the remote actually received before the rename,
     * so a truncated source (declares more than it emits) is rejected rather
     * than renamed as a short/corrupt file.
     */
    private suspend fun uploadStreamInternal(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ) {
        // Atomic temp sibling of the final path: `<final>.part-<rand>`. A
        // dropped/timed-out transfer corrupts only THIS temp name, never the
        // real attachment path. The random suffix avoids collisions between
        // concurrent retries of the same attachment.
        val tempRemotePath = remotePath + ".part-" + java.util.UUID.randomUUID().toString().take(8)
        try {
            val copied = streamToRemoteTemp(input, name, remotePath, tempRemotePath)

            // Integrity check BEFORE the rename: the bytes that actually landed
            // in the temp file must match what we copied, and — when the caller
            // declared a length — must match that too. A mismatch means a
            // truncated/short transfer; fail (and clean up) rather than promote
            // a corrupt file to the real path.
            verifyTempSizeOrThrow(
                name = name,
                remotePath = remotePath,
                tempRemotePath = tempRemotePath,
                copiedBytes = copied,
                declaredLength = length,
            )

            // Promote to the real path ONLY now that the full, verified bytes
            // are on disk. `mv` within the same directory/filesystem is atomic.
            val mv = exec(
                "mv -f ${shellSingleQuote(tempRemotePath)} ${shellSingleQuote(remotePath)}",
            )
            if (mv.exitCode != 0) {
                throw SshException(
                    "Could not finalise upload of $name to $remotePath " +
                        "(rename failed, exit ${mv.exitCode}): ${mv.stderr.trim()}",
                )
            }
        } catch (e: CancellationException) {
            // The coroutine context is cancelled here, so we cannot suspend on
            // it — detach the temp cleanup onto the session scope.
            cleanupTempDetached(tempRemotePath)
            throw e
        } catch (t: Throwable) {
            // ANY failure leaves the real path untouched; remove the temp file
            // SYNCHRONOUSLY (the context is still active on this path) so a
            // partial upload never accumulates as a stray artifact and the
            // removal is observable by the time we return to the caller.
            cleanupTempBestEffort(tempRemotePath)
            if (t is SshException) throw t
            throw SshException("Upload of $name to $remotePath failed: ${t.message}", t)
        }
    }

    /**
     * Stream [input] into the remote [tempRemotePath] via `cat > <temp>`,
     * bounded by [UPLOAD_TRANSFER_TIMEOUT_MS]. Returns the number of bytes
     * copied. Throws [SshException] on a transport failure, a non-zero remote
     * exit, or a timeout — the caller cleans up the temp file.
     */
    private suspend fun streamToRemoteTemp(
        input: InputStream,
        name: String,
        remotePath: String,
        tempRemotePath: String,
    ): Long {
        val coroutineJob = currentCoroutineContext()[Job]
        // Channel open + `cat >` exec are transport-mutating packets — serialise
        // through the dispatcher (issue #847). The byte copy below runs OUTSIDE
        // the dispatcher so a large upload never wedges the `-CC` write.
        val quoted = shellSingleQuote(tempRemotePath)
        var command: Command? = null
        val sessionChannel = dispatcher.run {
            val channel = try {
                client.startSession()
            } catch (t: Throwable) {
                throw SshException(
                    "Could not open session channel for upload of $name to $remotePath: ${t.message}",
                    t,
                )
            }
            command = try {
                channel.exec("cat > $quoted")
            } catch (t: Throwable) {
                runCatching { channel.close() }
                throw SshException(
                    "Could not start remote `cat` for upload of $name to $remotePath: ${t.message}",
                    t,
                )
            }
            channel
        }
        val cancelHandle = coroutineJob?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { input.close() }
                scope.launch {
                    runCatching { dispatcher.run { runCatching { command?.close() } } }
                    runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
                }
            }
        }
        try {
            // Issue #930 (folds in #928 D5 W-4): bound the blocking transfer so a
            // wedged channel fails fast instead of hanging. `withTimeoutOrNull`
            // returning null == we blew the ceiling. The byte-copy + `join()` run
            // inside `runInterruptible` so the timeout's cancellation becomes a
            // real `Thread.interrupt()` — that unblocks a stuck blocking
            // `read()`/`write()` on the wedged channel (a plain `withTimeout`
            // alone can't preempt a thread parked in a JDK blocking call). We
            // also force-close the channel so the remote `cat` tears down.
            val copied = withTimeoutOrNull(UPLOAD_TRANSFER_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) {
                    val n = command!!.outputStream.use { output ->
                        copyToRemoteBlocking(input, output)
                    }
                    // `outputStream.close()` sends EOF on the channel. The remote
                    // `cat` exits on EOF; `join()` waits for it so we can read
                    // the exit code.
                    command!!.join()
                    n
                }
            } ?: run {
                runCatching { input.close() }
                runCatching { dispatcher.run { runCatching { command?.close() } } }
                runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
                throw SshException(
                    "Upload of $name to $remotePath timed out after " +
                        "${UPLOAD_TRANSFER_TIMEOUT_MS}ms (stalled transfer)",
                )
            }
            val exit = command!!.exitStatus ?: -1
            if (exit != 0) {
                val stderr = runCatching {
                    command!!.errorStream.readBytes().toString(Charsets.UTF_8)
                }.getOrDefault("")
                throw SshException(
                    "Remote `cat` exited with status $exit while writing $tempRemotePath: ${stderr.trim()}",
                )
            }
            return copied
        } finally {
            cancelHandle?.dispose()
            runCatching { dispatcher.run { runCatching { command?.close() } } }
            runCatching { dispatcher.run { runCatching { sessionChannel.close() } } }
        }
    }

    /**
     * Confirm the bytes that landed in [tempRemotePath] match what we copied (and
     * the caller-declared [declaredLength], when known). A mismatch is a
     * truncated/short transfer — throw so the temp file is cleaned up and never
     * promoted to [remotePath].
     */
    private suspend fun verifyTempSizeOrThrow(
        name: String,
        remotePath: String,
        tempRemotePath: String,
        copiedBytes: Long,
        declaredLength: Long,
    ) {
        val stat = exec(
            "wc -c < ${shellSingleQuote(tempRemotePath)} 2>/dev/null || echo MISSING",
        )
        val out = stat.stdout.trim()
        val actual = out.toLongOrNull()
        if (actual == null) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "could not stat temp file $tempRemotePath (got '$out')",
            )
        }
        if (actual != copiedBytes) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "remote received $actual bytes but $copiedBytes were sent",
            )
        }
        if (declaredLength >= 0 && actual != declaredLength) {
            throw SshException(
                "Upload integrity check failed for $name -> $remotePath: " +
                    "declared $declaredLength bytes but $actual were transferred " +
                    "(truncated/short source)",
            )
        }
    }

    /**
     * Synchronously remove a partial upload temp file on a failure whose
     * coroutine context is still active. Best-effort: never throws (a failed
     * `rm` must not mask the original upload error). Bounded by a short timeout
     * so a half-dead transport can't wedge the cleanup.
     */
    private suspend fun cleanupTempBestEffort(tempRemotePath: String) {
        runCatching {
            withTimeoutOrNull(UPLOAD_CLEANUP_TIMEOUT_MS) {
                exec("rm -f ${shellSingleQuote(tempRemotePath)}")
            }
        }
    }

    /**
     * Detached temp cleanup for the caller-cancellation path, where the current
     * coroutine context is already cancelled and cannot be suspended on. Runs on
     * the session scope so the partial upload is still removed.
     */
    private fun cleanupTempDetached(tempRemotePath: String) {
        runCatching {
            scope.launch {
                runCatching { exec("rm -f ${shellSingleQuote(tempRemotePath)}") }
            }
        }
    }

    /**
     * Blocking byte-copy from [input] to [output]. Runs inside
     * [runInterruptible] (issue #930), so the upload timeout cancels by
     * interrupting this thread: each loop checks [Thread.interrupted] and the
     * blocking `read`/`write` JDK calls themselves throw on interrupt, so a
     * wedged transfer unblocks promptly instead of hanging. Returns the number
     * of bytes copied.
     */
    private fun copyToRemoteBlocking(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("SSH upload interrupted")
            val read = input.read(buffer)
            if (read < 0) break
            if (Thread.interrupted()) throw InterruptedException("SSH upload interrupted")
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    override fun close() {
        scope.cancel()
        // Issue #151 + #239: `close()` is idempotent and silent by
        // contract. The v0.2.7 crash report showed the original
        // narrow-catch race: a teardown-before-reattach left the
        // transport already half-disconnected (cancelled mid-flight from
        // the tmux event-loop coroutine in `TmuxSessionViewModel`), and
        // sshj's `SSHClient.disconnect()` threw `TransportException` with
        // `DisconnectReason.BY_APPLICATION` ("Disconnected") because it
        // tried to send a disconnect packet over a transport that had
        // already gone down. That was swallowed for the
        // `BY_APPLICATION` case only — leaving every other
        // `TransportException` reason able to crash on the
        // `ViewModel.onCleared` -> `closeCurrentConnection` ->
        // `RealSshSession.close()` cascade.
        //
        // v0.2.8 confirmed the narrow catch is still wrong shape: the
        // maintainer device hit twin crashes (A + B in issue #239)
        // during `onCleared`, again with `BY_APPLICATION`, again from
        // the supervisor-scoped IO coroutine root. The Android lifecycle
        // (activity destroy -> ViewModelStore.clear ->
        // `onCleared` on every ViewModel) is the canonical close path
        // under D21 (no background work), and any `TransportException`
        // surfacing from that path has nowhere useful to go — the
        // session is being torn down anyway and the caller has no
        // actionable recovery.
        //
        // Per the issue: "swallows `TransportException` with reason
        // `BY_APPLICATION` (and likely any TransportException — close
        // is idempotent)." We widen to the full `TransportException`
        // family so every teardown-time transport fault (KEX failure,
        // MAC error, unexpected protocol errors, half-closed transport)
        // is treated the same way: log and no-op, never propagate.
        // Genuine "transport blew up while connected" diagnostics still
        // surface through the regular read/write path (sshj raises the
        // same exception on the producer-coroutine boundary), so
        // swallowing here does not hide a live-connection fault.
        //
        // The outer best-effort catches preserve the idempotency
        // guarantee for non-TransportException teardown failures
        // (`IOException`, exotic `RuntimeException`).
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
        // catch below hid the crash from the maintainer device but the
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
            // Issue #847: drain the dispatcher and run `disconnect()` as the
            // FINAL serialised operation. `closeAndAwaitDrain` (a) queues the
            // disconnect BEHIND any in-flight write/channel op so we never tear
            // the transport down underneath one (which is exactly the
            // write-racing-`die()` desync the actor exists to prevent), and (b)
            // marks the dispatcher closed under its lock so any later op is
            // rejected before it can touch the dead transport. The disconnect
            // socket write still happens on the dispatch (IO) thread, so the
            // StrictMode `NetworkOnMainThreadException` guard (issue #166) holds.
            runBlocking {
                dispatcher.closeAndAwaitDrain {
                    client.disconnect()
                }
            }
        } catch (e: TransportException) {
            // Issue #239: close() is idempotent and silent by contract.
            // Every TransportException here is teardown-time and not
            // actionable — swallow and log.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed TransportException during close() " +
                    "(reason=${e.disconnectReason}): ${e.message}",
            )
        } catch (e: SSHException) {
            // sshj's `SSHException` is the parent of `TransportException`
            // (already handled) and `ConnectionException`. A
            // `ConnectionException` surfacing here is the same shape of
            // already-down-transport teardown noise — silently no-op so
            // the idempotency contract holds.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed SSHException during close(): ${e.message}",
            )
        } catch (e: IOException) {
            // sshj declares `disconnect()` as `throws IOException`. A
            // non-SSHException IO failure during shutdown is best-effort:
            // nothing the caller can do, propagating it would defeat the
            // idempotency contract. Swallowed deliberately to preserve the
            // pre-#151 `runCatching` semantics for this path.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed IOException during close(): ${e.message}",
            )
        } catch (e: RuntimeException) {
            // Belt-and-suspenders: the pre-#151 implementation wrapped the
            // whole disconnect in `runCatching`, which silently swallowed
            // every Throwable. With #166 the socket write now runs on
            // `Dispatchers.IO` so StrictMode `NetworkOnMainThreadException`
            // can no longer originate here — but we keep this catch for
            // any other RuntimeException sshj may surface during teardown
            // (e.g. an exotic state-machine error) so close() stays
            // idempotent in the face of unknown teardown failure modes.
            CLOSE_LOGGER.log(
                Level.INFO,
                "[$CLOSE_LOG_TAG] swallowed RuntimeException during close(): ${e.message}",
            )
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw SshException("SSH session is not connected")
    }
}

/**
 * Quote [remotePath] for safe interpolation into a single remote shell command,
 * while still letting a leading `~` / `~/` expand to the remote `$HOME`
 * (issue #558 bug 3).
 *
 * The previous code single-quoted the whole path (`'...'`), which is correct for
 * arbitrary filenames but suppresses `~` expansion — so tapping
 * `~/git/pocketshell/.tmp/host-list-screen.png` reached the server literally and
 * produced a false "No such file". The client cannot expand `~` itself without
 * an extra round-trip (it does not know the remote `$HOME`), so instead we leave
 * the tilde unquoted and single-quote only the remainder:
 *
 *  - `~`            → `~`            (the shell expands `~` to `$HOME`)
 *  - `~/a b/c.png`  → `~/'a b/c.png'`(`~` expands; the rest is quote-safe)
 *  - `/etc/hosts`   → `'/etc/hosts'` (absolute path unchanged behaviour)
 *
 * Only a leading bare `~` or `~/` is treated as expandable; `~user/...` and any
 * `~` that is not the very first character are quoted literally, matching how a
 * POSIX shell only expands `~` at the start of a word.
 */
internal fun quoteRemotePathForShell(remotePath: String): String {
    return when {
        remotePath == "~" -> "~"
        remotePath.startsWith("~/") -> {
            val rest = remotePath.substring(2)
            if (rest.isEmpty()) "~/" else "~/" + shellSingleQuote(rest)
        }
        else -> shellSingleQuote(remotePath)
    }
}

/**
 * Exit code [buildListDirCommand] emits on a successful listing. Distinct from
 * the not-a-dir / permission / not-found sentinels so [classifyListFailure] can
 * map each shell-side outcome onto a typed exception.
 */
internal const val PROBE_EXIT_OK: Int = 0

/** [buildListDirCommand] exit code: the path exists but is not a directory. */
internal const val PROBE_EXIT_NOT_A_DIR: Int = 20

/** [buildListDirCommand] exit code: the path does not exist. */
internal const val PROBE_EXIT_NO_SUCH: Int = 21

/** [buildListDirCommand] exit code: the directory could not be read (perms). */
internal const val PROBE_EXIT_DENIED: Int = 22

/**
 * Field separator between `type|size|mtime|path` in [buildListDirCommand]
 * output. A vertical bar is shell-safe in `stat -c` and rare in real
 * filenames; the path is the *last* field so any bars inside a filename are
 * preserved by [parseListing] splitting on only the first three separators.
 */
internal const val LIST_FIELD_SEP: Char = '|'

/**
 * Build the remote directory-listing command for [remotePath] (issue #528).
 *
 * Strategy (POSIX shell, busybox- and coreutils-safe — same baseline as
 * `downloadFile`'s `cat`):
 *  1. Guard the path: `! -e` -> exit [PROBE_EXIT_NO_SUCH]; not a directory ->
 *     [PROBE_EXIT_NOT_A_DIR]; not readable/executable -> [PROBE_EXIT_DENIED].
 *  2. List with `find <dir> -maxdepth 1` and `stat -c "%F|%s|%Y|%n"` each entry.
 *     `find` includes the directory itself as the first hit; [parseListing]
 *     drops the row whose path equals the listed directory. `stat` does not
 *     follow symlinks, so a link is reported as `symbolic link`.
 *  3. On success the guard already returned 0 via the `find` pipeline's own
 *     exit; we force a clean 0 so [classifyListFailure] only fires on the
 *     guard sentinels.
 *
 * The path is quoted via [quoteRemotePathForShell] so arbitrary filenames
 * (spaces, quotes, `$`) don't break parsing while a leading `~`/`~/` still
 * expands to `$HOME` (issue #558 bug 3). Filenames containing a literal newline
 * are a known limitation of the line-based parse (busybox `stat` cannot
 * NUL-terminate); such names are extremely rare and degrade to a skipped/garbled
 * row rather than a crash.
 */
internal fun buildListDirCommand(remotePath: String): String {
    val quoted = quoteRemotePathForShell(remotePath)
    return buildString {
        append("d=").append(quoted).append("; ")
        append("if [ ! -e \"\$d\" ]; then exit ").append(PROBE_EXIT_NO_SUCH).append("; fi; ")
        append("if [ ! -d \"\$d\" ]; then exit ").append(PROBE_EXIT_NOT_A_DIR).append("; fi; ")
        // Need read (to list names) and execute (to stat children).
        append("if [ ! -r \"\$d\" ] || [ ! -x \"\$d\" ]; then exit ")
            .append(PROBE_EXIT_DENIED).append("; fi; ")
        append("find \"\$d\" -maxdepth 1 -exec stat -c '%F")
            .append(LIST_FIELD_SEP).append("%s")
            .append(LIST_FIELD_SEP).append("%Y")
            .append(LIST_FIELD_SEP).append("%n' {} ").append("\\;").append(" 2>/dev/null; ")
        append("exit ").append(PROBE_EXIT_OK)
    }
}

/**
 * Map a non-zero [buildListDirCommand] exit onto a typed [SshException].
 * Visible-for-test so the sentinel mapping is pinned without a live server.
 */
internal fun classifyListFailure(remotePath: String, probe: ExecResult): SshException =
    when (probe.exitCode) {
        PROBE_EXIT_NOT_A_DIR -> SshNotADirectoryException(remotePath)
        PROBE_EXIT_NO_SUCH -> SshFileNotFoundException(remotePath)
        PROBE_EXIT_DENIED -> SshPermissionDeniedException(remotePath)
        else -> SshException(
            "Listing $remotePath failed (exit ${probe.exitCode}): ${probe.stderr.trim()}",
        )
    }

/**
 * Parse [buildListDirCommand] stdout (one `type|size|mtime|path` line per
 * entry) into a [RemoteListing] relative to [listedDir]. Drops the listed
 * directory's own row and any `.`/`..`, caps at [maxEntries] (setting
 * `truncated`), and folds the busybox/coreutils `stat -c %F` human type string
 * onto [RemoteEntry.Type]. Pure — unit-tested without SSH.
 */
internal fun parseListing(
    stdout: String,
    listedDir: String,
    maxEntries: Int,
): RemoteListing {
    val normalizedDir = listedDir.trimEnd('/')
    val entries = ArrayList<RemoteEntry>()
    var truncated = false
    for (raw in stdout.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        // Split on only the first three separators so a `|` inside a filename
        // is preserved in the path field.
        val parts = line.split(LIST_FIELD_SEP, limit = 4)
        if (parts.size < 4) continue
        val typeStr = parts[0].trim()
        val size = parts[1].trim().toLongOrNull() ?: 0L
        val mtime = parts[2].trim().toLongOrNull()?.takeIf { it > 0L }
        val fullPath = parts[3]
        // The listed directory itself is the first `find` hit — skip it.
        if (fullPath.trimEnd('/') == normalizedDir) continue
        val name = fullPath.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") continue
        if (entries.size >= maxEntries) {
            truncated = true
            break
        }
        val type = parseStatType(typeStr)
        entries += RemoteEntry(
            name = name,
            type = type,
            sizeBytes = if (type == RemoteEntry.Type.FILE) size else 0L,
            modifiedEpochSec = mtime,
        )
    }
    return RemoteListing(entries = entries, truncated = truncated)
}

/**
 * Fold a `stat -c %F` human-readable file-type string (busybox and coreutils
 * use the same words) onto [RemoteEntry.Type]. Unknown / device / socket /
 * fifo types map to [RemoteEntry.Type.OTHER]. Visible-for-test.
 */
internal fun parseStatType(statType: String): RemoteEntry.Type = when (statType.lowercase()) {
    "directory" -> RemoteEntry.Type.DIRECTORY
    "regular file", "regular empty file" -> RemoteEntry.Type.FILE
    "symbolic link" -> RemoteEntry.Type.SYMLINK
    else -> RemoteEntry.Type.OTHER
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
 * terminfo entry is grep-able from the SSH-shell entry point per #102.
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

/**
 * Logcat-grep tag for `RealSshSession.close()` swallowing a teardown-time
 * transport fault (issue #239). Routed through `java.util.logging` for
 * the same reason [SshjTransportThreadGuard] uses that channel — no
 * Android-only dependency from this shared module.
 */
internal const val CLOSE_LOG_TAG: String = "issue239-close-teardown"

/**
 * Wall-clock ceiling for the blocking byte-copy + `join()` of a single
 * attachment upload (issue #930, folds in #928 D5 W-4). A wedged channel must
 * fail fast with a clear error rather than hang forever. 60s comfortably covers
 * a multi-MB attachment over a slow mobile link while still bounding a stall.
 */
internal const val UPLOAD_TRANSFER_TIMEOUT_MS: Long = 60_000L

/**
 * Wall-clock ceiling for removing a partial upload's temp file after a failed
 * transfer (issue #930). Short and bounded so a half-dead transport can't wedge
 * the cleanup; a failed/timed-out `rm` is swallowed best-effort.
 */
internal const val UPLOAD_CLEANUP_TIMEOUT_MS: Long = 10_000L

private val CLOSE_LOGGER: Logger = Logger.getLogger(RealSshSession::class.java.name + ".close")

/**
 * Logcat-grep tag for `RealSshSession.tail()` swallowing a recoverable
 * transport drop (issue #239). The reconnect state machine in
 * `TmuxSessionViewModel` (#145 + #173) will resume the tail on the next
 * attach; this log line is the diagnostic breadcrumb that says the tail
 * coroutine ended cleanly instead of crashing the worker.
 */
internal const val TAIL_LOG_TAG: String = "issue239-tail-recover"

private val TAIL_LOGGER: Logger = Logger.getLogger(RealSshSession::class.java.name + ".tail")

/**
 * Sentinel emitted by [buildSizeProbeCommand] when the remote path is not a
 * regular file (missing, a directory, a device, ...). Kept distinct from any
 * numeric byte count so [parseSizeProbe] can map it to
 * [SshFileNotFoundException].
 */
internal const val SIZE_PROBE_NO_FILE_SENTINEL: String = "__PS_NOFILE__"

/** [parseSizeProbe] result for the [SIZE_PROBE_NO_FILE_SENTINEL] case. */
internal const val SIZE_PROBE_NO_FILE: Long = -1L

/** [parseSizeProbe] result when the probe output couldn't be parsed at all. */
internal const val SIZE_PROBE_UNPARSEABLE: Long = -2L

/**
 * Build the remote size-probe command for [remotePath] (issue #497).
 *
 * For a regular file it prints the byte count (`wc -c` — busybox-safe on the
 * Alpine fixtures and present on any POSIX shell); for anything that is not a
 * regular file it prints [SIZE_PROBE_NO_FILE_SENTINEL]. The path is resolved
 * by the remote login shell, so `~`-relative paths expand server-side (issue
 * #558 bug 3 — see [quoteRemotePathForShell]).
 *
 * Quoted via [quoteRemotePathForShell] so arbitrary filenames don't break shell
 * parsing while a leading `~`/`~/` still expands to `$HOME`.
 */
internal fun buildSizeProbeCommand(remotePath: String): String {
    val quoted = quoteRemotePathForShell(remotePath)
    return "if [ -f $quoted ]; then wc -c < $quoted; else echo $SIZE_PROBE_NO_FILE_SENTINEL; fi"
}

/**
 * Parse the stdout of [buildSizeProbeCommand] into a byte count.
 *
 * Returns:
 *  - the parsed non-negative size for a regular file,
 *  - [SIZE_PROBE_NO_FILE] when the sentinel was printed,
 *  - [SIZE_PROBE_UNPARSEABLE] when the output is neither (e.g. `wc` missing).
 *
 * `wc -c` on busybox may emit leading whitespace, so the numeric line is
 * trimmed before parsing. Visible-for-test so the parse rules are pinned
 * without a live SSH server.
 */
internal fun parseSizeProbe(stdout: String): Long {
    val trimmed = stdout.trim()
    if (trimmed == SIZE_PROBE_NO_FILE_SENTINEL) return SIZE_PROBE_NO_FILE
    // Take the last non-blank token — guards against a shell that prints a
    // banner before the count. `wc -c < file` outputs just the number.
    val token = trimmed.lines()
        .map { it.trim() }
        .lastOrNull { it.isNotEmpty() }
        ?: return SIZE_PROBE_UNPARSEABLE
    if (token == SIZE_PROBE_NO_FILE_SENTINEL) return SIZE_PROBE_NO_FILE
    return token.toLongOrNull()?.takeIf { it >= 0 } ?: SIZE_PROBE_UNPARSEABLE
}
