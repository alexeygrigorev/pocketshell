package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import java.io.InputStream
import java.io.OutputStream

/**
 * sshj-backed implementation of [SshShell].
 *
 * Wraps a single [Session] channel that has had a default PTY allocated and
 * the user's login shell started on it. The three stdio streams are the
 * channel's own — sshj exposes them as ordinary blocking JDK streams.
 *
 * Issue #847 / #766 slice 1: every WRITE side of this shell — the `-CC`
 * control-mode stdin write/flush, the [resizePty] window-change, and the
 * channel [close] — is funnelled through the connection's single
 * [TransportDispatcher] so it runs serialised against every exec channel open,
 * the liveness probe, and teardown on the same transport. That removes the
 * concurrent-writer race that desynced the encoder sequence counter and made
 * the server log `Connection corrupted`. The READ side ([stdout] / [stderr])
 * is intentionally NOT wrapped — the `-CC` reader loop runs concurrently on its
 * own coroutine, mirroring sshj's Reader-thread / writeLock split.
 *
 * `core-ssh`-internal: callers receive instances as the public [SshShell]
 * interface from [SshSession.startShell] and never see this concrete type.
 */
internal class RealSshShell(
    private val sessionChannel: Session,
    private val shell: Session.Shell,
    private val dispatcher: TransportDispatcher,
) : SshShell {

    /**
     * stdin wrapped so every `write`/`flush` is dispatched through
     * [dispatcher]. The `-CC` command bytes are the highest-frequency
     * foreground writer; serialising them against exec channel opens is the
     * core of the #847 fix.
     */
    private val serializedStdin: OutputStream = DispatchedOutputStream(shell.outputStream, dispatcher)

    override val stdin: OutputStream
        get() = serializedStdin

    override val stdout: InputStream
        get() = shell.inputStream

    override val stderr: InputStream
        get() = shell.errorStream

    override fun close() {
        // Order matters: close the shell-level resource first so any in-flight
        // `read` returns -1 promptly, then drop the parent session channel.
        // Both `Session.Shell.close()` and `Session.close()` send
        // `SSH_MSG_CHANNEL_CLOSE` over the live transport — real socket writes,
        // so they go through the dispatcher (issue #847) which also serialises
        // them against any in-flight `-CC` write. The dispatcher runs on its
        // own IO thread, so this also keeps the channel-close socket write off
        // the Main thread (issue #166 StrictMode `NetworkOnMainThreadException`).
        //
        // sshj's `close` calls are idempotent, so the runCatching guards are
        // belt-and-braces against the channel already being torn down by the
        // remote side. If the dispatcher is already closed (parent session
        // teardown drained it), the operation is rejected — there is nothing
        // left to close, so swallow that too.
        runCatching {
            dispatcher.runBlockingDispatch {
                runCatching { shell.close() }
                runCatching { sessionChannel.close() }
            }
        }
    }

    override fun resizePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val channel = sessionChannel as? SessionChannel ?: return
        // `changeWindowDimensions` writes `SSH_MSG_CHANNEL_REQUEST window-change`
        // on the shell channel — another transport write that previously raced
        // the `-CC` stream and exec opens (writer #5 in the #847 inventory).
        runCatching {
            dispatcher.runBlockingDispatch {
                runCatching { channel.changeWindowDimensions(columns, rows, 0, 0) }
            }
        }
    }
}

/**
 * [OutputStream] decorator that runs every `write`/`flush` of [delegate] on the
 * connection's [TransportDispatcher], so the `-CC` control-command bytes are
 * serialised against every other transport operation (exec channel opens, the
 * liveness probe, resize, teardown). Issue #847 / #766 slice 1.
 *
 * Writes/flush submitted after the dispatcher is closed surface a
 * [TransportClosedException] — an ordinary "transport gone" condition the
 * tmux/reconnect layer already handles as a recoverable drop.
 */
private class DispatchedOutputStream(
    private val delegate: OutputStream,
    private val dispatcher: TransportDispatcher,
) : OutputStream() {

    override fun write(b: Int) {
        dispatcher.runBlockingDispatch { delegate.write(b) }
    }

    override fun write(b: ByteArray) {
        dispatcher.runBlockingDispatch { delegate.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        dispatcher.runBlockingDispatch { delegate.write(b, off, len) }
    }

    override fun flush() {
        dispatcher.runBlockingDispatch { delegate.flush() }
    }

    override fun close() {
        // The channel/transport close path owns tearing the delegate down
        // (RealSshShell.close / RealSshSession.close). Closing the stdin stream
        // directly is not part of the public SshShell contract, so this is a
        // no-op to avoid a teardown ordering race with the dispatcher.
    }
}
