package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.Session
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
        runCatching { shell.close() }
        runCatching { sessionChannel.close() }
    }
}
