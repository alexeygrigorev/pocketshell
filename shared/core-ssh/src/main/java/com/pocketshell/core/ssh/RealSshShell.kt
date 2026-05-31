package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.io.InputStream
import java.io.OutputStream

/**
 * sshj-backed implementation of [SshShell].
 *
 * Wraps a single [Session] channel that has had a default PTY allocated and
 * the user's login shell started on it. The three stdio streams are the
 * channel's own — sshj exposes them as ordinary blocking JDK streams so we
 * forward them verbatim.
 *
 * `core-ssh`-internal: callers receive instances as the public [SshShell]
 * interface from [SshSession.startShell] and never see this concrete type.
 */
internal class RealSshShell(
    private val sessionChannel: Session,
    private val shell: Session.Shell,
) : SshShell {

    override val stdin: OutputStream
        get() = shell.outputStream

    override val stdout: InputStream
        get() = shell.inputStream

    override val stderr: InputStream
        get() = shell.errorStream

    override fun close() {
        // Order matters: close the shell-level resource first so any
        // in-flight `read` returns -1 promptly, then drop the parent
        // session channel. sshj's `close` calls are idempotent, so the
        // runCatching guards are belt-and-braces against the channel
        // already being torn down by the remote side.
        //
        // Issue #166: both `Session.Shell.close()` and
        // `Session.close()` send `SSH_MSG_CHANNEL_CLOSE` over the live
        // transport — real socket writes. The non-suspending `close()`
        // contract comes from `AutoCloseable`, and several call sites
        // (`TmuxClient.close()` invoked from
        // `TmuxSessionViewModel.closeCurrentConnection()` under
        // `onCleared()`, Compose `onDispose` in
        // `app/.../proof/ProofOfLifeScreen.kt`,
        // `TerminalLabActivity`) reach this from the Android Main thread.
        // Without the IO dispatch below, StrictMode
        // detectNetwork()/`BlockGuard$Policy.onNetwork()` would fire
        // `NetworkOnMainThreadException` on the channel-close write.
        // `runBlocking(Dispatchers.IO) { ... }` runs the two close calls
        // on an IO worker while still blocking the caller until the
        // channel is torn down, preserving the historical close ordering
        // (e.g. a follow-up `session.close()` on the parent will not see
        // a still-live shell channel).
        runBlocking(Dispatchers.IO) {
            runCatching { shell.close() }
            runCatching { sessionChannel.close() }
        }
    }

    override fun resizePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val channel = sessionChannel as? SessionChannel ?: return
        runBlocking(Dispatchers.IO) {
            runCatching { channel.changeWindowDimensions(columns, rows, 0, 0) }
        }
    }
}
