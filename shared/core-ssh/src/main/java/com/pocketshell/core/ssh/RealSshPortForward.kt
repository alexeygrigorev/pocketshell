package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * sshj-backed implementation of [SshPortForward].
 *
 * Equivalent to `ssh -L <localPort>:<remoteHost>:<remotePort>`. We do not use
 * sshj's bundled [net.schmizz.sshj.connection.channel.direct.LocalPortForwarder]
 * because it copies bytes through internal `StreamCopier` instances we can't
 * instrument for the per-tunnel byte counters required by [SshPortForward].
 * Instead we accept connections on our own [ServerSocket] and open a
 * [DirectConnection] (direct-tcpip channel) per accepted client, copying bytes
 * in both directions through counting streams.
 *
 * ## Single-writer transport safety (issue #980)
 *
 * Each accepted local connection opens a `direct-tcpip` channel, and each
 * EOF/teardown closes it. Both are transport-mutating SSH packets that advance
 * the encoder sequence counter — they MUST NOT race the dispatcher-serialised
 * keepalive / `-CC` / exec writes on the same transport, or the #847
 * `Connection corrupted` desync resurfaces. This class therefore never holds the
 * raw [net.schmizz.sshj.SSHClient]: it opens and closes every channel through a
 * [PortForwardChannelTransport], which funnels the channel-lifecycle packets
 * through the connection's single-writer [TransportDispatcher]. The local-socket
 * `accept()` and the byte copy loops stay off-dispatcher (they touch only the
 * local socket and the already-decrypted channel streams); only the SSH-side
 * open/close packets serialise.
 *
 * Internal to `core-ssh` — callers obtain instances via
 * [SshSession.openLocalPortForward].
 */
internal class RealSshPortForward(
    private val channels: PortForwardChannelTransport,
    override val remoteHost: String,
    override val remotePort: Int,
    override val localPort: Int,
) : SshPortForward {

    private val serverSocket: ServerSocket =
        ServerSocket(localPort, /* backlog = */ 50, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val forwardedBytes = AtomicLong(0)
    private val receivedBytes = AtomicLong(0)

    /**
     * Live copy threads (one per direction, per accepted connection). We
     * track these so [close] can `join` them deterministically rather than
     * relying on daemon-thread auto-cleanup, which would leak file
     * descriptors briefly after teardown and produce flaky tests.
     * `CopyOnWriteArraySet` is a fine fit — writes are rare (only on
     * connection open/close), reads happen in [close].
     */
    private val copyThreads: CopyOnWriteArraySet<Thread> = CopyOnWriteArraySet()

    /**
     * Currently-open accepted-connection pairs. [close] iterates and
     * closes each one, which unblocks the per-direction copy threads
     * (they're stuck on `read()` on one of these streams, not on the
     * `running` flag — that's only checked between iterations).
     */
    private val activePairs: CopyOnWriteArraySet<Pair<Socket, DirectConnection>> =
        CopyOnWriteArraySet()

    /** Daemon thread that accepts incoming local connections. */
    private val acceptThread: Thread = Thread(::acceptLoop, "ssh-portfwd-accept-$localPort").apply {
        isDaemon = true
        start()
    }

    override val isActive: Boolean
        get() = running.get() && !serverSocket.isClosed

    override val bytesForwarded: Long
        get() = forwardedBytes.get()

    override val bytesReceived: Long
        get() = receivedBytes.get()

    private fun acceptLoop() {
        while (running.get()) {
            val client: Socket = try {
                serverSocket.accept()
            } catch (e: SocketException) {
                // serverSocket.close() unblocks accept() with a SocketException;
                // that's our cue to exit cleanly.
                if (!running.get()) return
                throw e
            } catch (e: IOException) {
                if (!running.get()) return
                throw e
            }
            startChannel(client)
        }
    }

    private fun startChannel(local: Socket) {
        // Open the direct-tcpip channel synchronously so an open failure is
        // visible *here* (we can log + close the local socket). Once open, we
        // hand off to two daemon copy threads — one per direction.
        val channel: DirectConnection = try {
            // Serialised through the single-writer dispatcher (#980): the
            // channel-open packet can never interleave with the keepalive / a
            // `-CC` write / another exec open on the same transport.
            channels.openChannel(remoteHost, remotePort)
        } catch (t: Throwable) {
            // Couldn't open the channel — drop the local connection. Don't
            // crash the accept loop; another connection might succeed.
            runCatching { local.close() }
            return
        }

        // If close() has already flipped `running` to false between
        // accept() returning and us getting here, don't bother spinning
        // up the copy threads — tear the pair down and bail.
        if (!running.get()) {
            closePair(local, channel)
            return
        }

        val pair = local to channel
        activePairs.add(pair)
        // Re-check `running` AFTER adding to the set: close() may have
        // raced past us and already swept activePairs (without seeing
        // ours). In that case we close eagerly and skip the copy threads.
        if (!running.get()) {
            activePairs.remove(pair)
            closePair(local, channel)
            return
        }

        val sockIn: InputStream = local.getInputStream()
        val sockOut: OutputStream = local.getOutputStream()
        val chanIn: InputStream = channel.inputStream
        val chanOut: OutputStream = channel.outputStream

        // local -> remote (bytes forwarded out)
        startCopyThread("ssh-portfwd-l2r-$localPort") {
            copy(sockIn, chanOut, forwardedBytes)
            // When one side EOFs, tear the whole pair down so the partner
            // copier doesn't hang on a half-closed channel.
            closePairAndUntrack(pair)
        }

        // remote -> local (bytes received from remote)
        startCopyThread("ssh-portfwd-r2l-$localPort") {
            copy(chanIn, sockOut, receivedBytes)
            closePairAndUntrack(pair)
        }
    }

    private fun closePairAndUntrack(pair: Pair<Socket, DirectConnection>) {
        if (activePairs.remove(pair)) {
            closePair(pair.first, pair.second)
        }
    }

    /**
     * Spin up a daemon copy thread and register it in [copyThreads] so
     * [close] can join it deterministically. The body always deregisters
     * itself when it exits, even on exception, so [copyThreads] tracks
     * only live threads.
     */
    private fun startCopyThread(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } finally {
                copyThreads.remove(Thread.currentThread())
            }
        }, name).apply { isDaemon = true }
        copyThreads.add(thread)
        thread.start()
    }

    private fun copy(src: InputStream, dst: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
            // Either side closed; normal termination of the copy loop.
        }
    }

    private fun closePair(local: Socket, channel: DirectConnection) {
        runCatching { local.close() }
        // Serialise the channel-close packet through the single-writer
        // dispatcher (#980) — a raw `channel.close()` here would be a SECOND
        // un-ownable writer racing the keepalive, exactly the #847 desync.
        channels.closeChannel(channel)
    }

    override fun close() {
        // `compareAndSet` gives us a single-shot guarantee: the first
        // caller wins and runs the teardown, every subsequent (including
        // concurrent) close() returns immediately. This is what makes
        // double-close + concurrent close + stop-during-copy safe — there
        // is exactly one writer to serverSocket.close(), and copy threads
        // observe `running` going false on their next iteration.
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        // Close every accepted-connection pair we still own. Copy
        // threads block on `src.read(buf)` inside [copy], which doesn't
        // check `running` until between iterations — so closing the
        // underlying sockets is what actually unblocks them (`read`
        // throws IOException, which the loop catches as normal
        // termination).
        val pairs = activePairs.toList()
        activePairs.clear()
        for ((local, channel) in pairs) {
            closePair(local, channel)
        }
        // Join the in-flight copy threads so callers see deterministic
        // teardown (no file descriptors leak past close() returning). We
        // skip joining if we'd be deadlocking on ourselves (a copy thread
        // calling close() — unusual but cheap to guard).
        val self = Thread.currentThread()
        for (t in copyThreads) {
            if (t === self) continue
            try {
                t.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                // Preserve interrupt status; stop joining the rest — they
                // are daemon threads and will eventually exit on their
                // own as the sockets/channels are closed.
                Thread.currentThread().interrupt()
                break
            }
        }
        // Don't join the accept thread — it's a daemon and the SocketException
        // wakeup is immediate. Joining would deadlock if close() is called
        // from inside the accept thread itself.
    }

    private companion object {
        // 32 KiB matches sshj's default channel max-packet size, which is the
        // largest single read we'd ever get back from the channel side. Keeps
        // the copy loop tight without over-allocating.
        const val BUFFER_SIZE = 32 * 1024

        // Per-thread join budget in close(). Two copy threads * 500 ms ==
        // 1 s worst-case stall, which is generous given the underlying
        // sockets are already closed by the time we get here.
        const val JOIN_TIMEOUT_MS = 500L
    }
}
