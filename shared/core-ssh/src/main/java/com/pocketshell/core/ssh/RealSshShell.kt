package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Issue #974 — invoked on EVERY successful (>0 bytes) read from [stdout] /
     * [stderr] so the live `-CC` control-mode reader's decoded bytes prove the
     * transport alive. [RealSshSession] passes `::recordInboundActivity`, which
     * bumps the keepalive's inbound-activity timestamp — the signal the keepalive
     * loop's reset-on-inbound shortcut and #964's deferral oracle read. Defaults
     * to a no-op so the unit fakes that construct a shell need not supply it.
     */
    private val onInboundActivity: () -> Unit = {},
) : SshShell {

    /**
     * stdin wrapped so every `write`/`flush` is dispatched through
     * [dispatcher]. The `-CC` command bytes are the highest-frequency
     * foreground writer; serialising them against exec channel opens is the
     * core of the #847 fix.
     */
    private val serializedStdin: OutputStream = DispatchedOutputStream(shell.outputStream, dispatcher)

    /**
     * stdout/stderr wrapped so every successful read records inbound transport
     * activity (issue #974). The READ side is NOT dispatched (it runs on the
     * `-CC` reader coroutine, mirroring sshj's Reader-thread split) — the wrapper
     * only OBSERVES the byte count to bump the keepalive's inbound-activity
     * timestamp, never serialises the read.
     */
    private val activityStdout: InputStream =
        InboundActivityInputStream(shell.inputStream, onInboundActivity)
    private val activityStderr: InputStream =
        InboundActivityInputStream(shell.errorStream, onInboundActivity)

    override val stdin: OutputStream
        get() = serializedStdin

    override val stdout: InputStream
        get() = activityStdout

    override val stderr: InputStream
        get() = activityStderr

    override suspend fun writeStdin(bytes: ByteArray) {
        // The tmux control client is coroutine/cancellation driven. Use the
        // suspending dispatcher path so cancellation while queued behind another
        // transport op removes the waiter instead of parking a Dispatchers.IO
        // thread inside runBlockingDispatch.
        dispatcher.run {
            shell.outputStream.write(bytes)
            shell.outputStream.flush()
        }
    }

    override fun close() {
        // Order matters: close the shell-level resource first so any in-flight
        // `read` returns -1 promptly, then drop the parent session channel.
        // Both `Session.Shell.close()` and `Session.close()` send
        // `SSH_MSG_CHANNEL_CLOSE` over the live transport — real socket writes,
        // so they go through the dispatcher (issue #847) which also serialises
        // them against any in-flight `-CC` write.
        //
        // Issue #937 / S1-F1: this `close()` is called SYNCHRONOUSLY from the
        // Main thread during `TmuxSessionViewModel.onCleared` (activity
        // destroy). The previous body did a bare `dispatcher.runBlockingDispatch`
        // which blocks the CALLING thread (Main) until the dispatch-thread op
        // completes. On a half-open link that channel-close socket write wedges,
        // so Main parks → ANR and the activity never finishes destroying. We now
        // hop the whole teardown OFF Main onto `Dispatchers.IO` under a hard
        // [CLOSE_TIMEOUT_MS] ceiling: the Main thread waits at most that long,
        // and `Dispatchers.IO` (not Main) is the thread that parks if the wire
        // is truly wedged. The dispatcher's own per-op ceiling (#937 S4-1) then
        // interrupts the wedged op and reclaims its thread. The runBlocking here
        // is a bounded bridge for the non-suspending [SshShell.close] contract,
        // mirroring the proven `RecurringJobsViewModel`/`GitHistoryViewModel`
        // off-Main bounded teardown pattern.
        //
        // sshj's `close` calls are idempotent, so the runCatching guards are
        // belt-and-braces against the channel already being torn down by the
        // remote side. If the dispatcher is already closed (parent session
        // teardown drained it), the operation is rejected — there is nothing
        // left to close, so swallow that too.
        runCatching {
            runBlocking(Dispatchers.IO) {
                // Call the SUSPENDING `dispatcher.run` (not the blocking
                // `runBlockingDispatch`) so `withTimeoutOrNull` can actually
                // cancel the wait when the ceiling trips — a cancellation here
                // unparks the IO coroutine; the dispatcher's own per-op ceiling
                // (#937 S4-1) interrupts the wedged socket write and reclaims
                // the dispatch thread.
                //
                // Each channel close is its OWN `dispatcher.run` op so each is
                // independently bounded + interrupted: a single interrupt only
                // unblocks ONE blocking call, so two closes in one op would let
                // the second wedge silently after the first was interrupted.
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                    runCatching { dispatcher.run { shell.close() } }
                    runCatching { dispatcher.run { sessionChannel.close() } }
                }
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

    private companion object {
        /**
         * Hard ceiling on the Main-thread caller's wait inside [close] (issue
         * #937 / S1-F1). `onCleared`/activity-destroy hops the channel-close
         * teardown to `Dispatchers.IO` and waits at most this long, so a wedged
         * half-open close cannot ANR. Comfortably exceeds a healthy channel
         * close (low-millisecond) while staying well under the ~5s ANR window.
         */
        const val CLOSE_TIMEOUT_MS: Long = 2_000L
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

/**
 * [InputStream] decorator that fires [onActivity] on every successful read of
 * one or more decoded bytes from [delegate] (issue #974). The `-CC` reader loop
 * reads off [RealSshShell.stdout]; each non-empty read is decoded application
 * data from the server — positive proof the transport is alive — so it bumps the
 * keepalive's inbound-activity timestamp via this callback. A `-1` (EOF) or a
 * `0`-length read is NOT activity (no server bytes arrived), so it does not bump.
 *
 * Pure observer: it never buffers, transforms, or serialises the read — it only
 * counts. So it is safe on the un-dispatched read side and adds a single cheap
 * volatile store per read.
 */
private class InboundActivityInputStream(
    private val delegate: InputStream,
    private val onActivity: () -> Unit,
) : InputStream() {

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) onActivity()
        return b
    }

    override fun read(b: ByteArray): Int {
        val n = delegate.read(b)
        if (n > 0) onActivity()
        return n
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) onActivity()
        return n
    }

    override fun available(): Int = delegate.available()

    override fun skip(n: Long): Long = delegate.skip(n)

    override fun close() {
        // Read-side close is owned by RealSshShell.close / RealSshSession.close
        // (the channel teardown). Delegate the close so a caller that closes the
        // stream directly still tears the underlying channel stream down, but it
        // is idempotent at the sshj layer.
        delegate.close()
    }
}
