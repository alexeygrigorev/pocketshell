package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session.Command
import java.io.BufferedReader
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
        // recipe: `startSession()` to get a session channel, allocate the
        // default PTY (xterm-256color, 80x24 — the emulator will resize on
        // first layout), then `startShell()` to bind the channel to the
        // user's login shell.
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
            sessionChannel.allocateDefaultPTY()
            val shell = sessionChannel.startShell()
            return RealSshShell(sessionChannel = sessionChannel, shell = shell)
        } catch (t: Throwable) {
            runCatching { sessionChannel.close() }
            throw SshException("Failed to start remote shell: ${t.message}", t)
        }
    }

    override fun close() {
        scope.cancel()
        runCatching { client.disconnect() }
    }

    private fun ensureConnected() {
        if (!isConnected) throw SshException("SSH session is not connected")
    }
}
