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
import java.util.concurrent.atomic.AtomicInteger
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
    private val activePairs: MutableSet<Pair<Socket, DirectConnection>> = CopyOnWriteArraySet(),
) : SshPortForward {

    private val serverSocket: ServerSocket =
        ServerSocket(localPort, /* backlog = */ 50, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val forwardedBytes = AtomicLong(0)
    private val receivedBytes = AtomicLong(0)
    private val activeConnectionSlots = AtomicInteger(0)

    /**
     * Serialises pair ownership and copy-thread registration with close().
     * Once [running] is false, no new copy thread may be registered. This
     * makes the snapshots used by close() complete: a thread cannot appear
     * after close has taken its join set.
     */
    private val lifecycleLock = Any()

    /**
     * Live copy threads (one per direction, per accepted connection). We
     * track these so [close] can `join` them deterministically rather than
     * relying on daemon-thread auto-cleanup, which would leak file
     * descriptors briefly after teardown and produce flaky tests.
     * Mutations are serialised by [lifecycleLock]. A live thread is never
     * removed by [close] merely because its join budget expired; its finally
     * block owns removal when it actually exits.
     */
    private val copyThreads: CopyOnWriteArraySet<Thread> = CopyOnWriteArraySet()

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
        if (!tryAcquireConnectionSlot()) {
            runCatching { local.close() }
            return
        }

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
            releaseConnectionSlot()
            runCatching { local.close() }
            return
        }

        // If close() has already flipped `running` to false between
        // accept() returning and us getting here, don't bother spinning
        // up the copy threads — tear the pair down and bail.
        if (!running.get()) {
            try {
                closePair(local, channel)
            } finally {
                releaseConnectionSlot()
            }
            return
        }

        val pair = local to channel
        val pairRegistered = synchronized(lifecycleLock) {
            if (!running.get()) {
                false
            } else {
                activePairs.add(pair)
                true
            }
        }
        if (!pairRegistered) {
            try {
                closePair(local, channel)
            } finally {
                releaseConnectionSlot()
            }
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
        val owned = synchronized(lifecycleLock) { activePairs.remove(pair) }
        if (owned) {
            try {
                closePair(pair.first, pair.second)
            } finally {
                releaseConnectionSlot()
            }
        }
    }

    private fun tryAcquireConnectionSlot(): Boolean {
        while (true) {
            val current = activeConnectionSlots.get()
            if (current >= MAX_ACTIVE_CONNECTIONS) return false
            if (activeConnectionSlots.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseConnectionSlot() {
        activeConnectionSlots.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
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
                synchronized(lifecycleLock) {
                    copyThreads.remove(Thread.currentThread())
                }
            }
        }, name).apply { isDaemon = true }
        synchronized(lifecycleLock) {
            if (!running.get()) return
            copyThreads.add(thread)
            thread.start()
        }
    }

    private fun copy(src: InputStream, dst: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (running.get()) {
                val n = src.read(buf)
                if (n < 0) break
                counter.addAndGet(n.toLong())
                dst.write(buf, 0, n)
                dst.flush()
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

    /**
     * Stops accepting connections and tears down owned pairs.
     *
     * The lifecycle lock seals pair/thread registration before the snapshots
     * are taken. A close that cannot prove every copy thread terminated within
     * the aggregate join budget interrupts the remaining threads and throws a
     * diagnostic; it never silently detaches a live thread from [copyThreads].
     */
    override fun close() {
        // Seal the lifecycle transition and pair snapshot together. A copy
        // callback that reaches closePairAndUntrack concurrently must either
        // claim the pair before this block or observe that close() owns it;
        // it cannot mutate activePairs between the snapshot and clear.
        val pairs = synchronized(lifecycleLock) {
            // `running` is written under the same lock that serialises every
            // activePairs mutation and snapshot. This also makes the
            // post-close startCopyThread check a single lifecycle decision.
            if (!running.compareAndSet(true, false)) return
            val snapshot = ArrayList(activePairs)
            activePairs.clear()
            snapshot
        }
        runCatching { serverSocket.close() }
        // Close every accepted-connection pair we still own. Copy
        // threads block on `src.read(buf)` inside [copy], which doesn't
        // check `running` until between iterations — so closing the
        // underlying socket and channel is what actually unblocks them
        // (`read` throws IOException, which the loop catches as normal
        // termination).
        for ((local, channel) in pairs) {
            try {
                closePair(local, channel)
            } finally {
                releaseConnectionSlot()
            }
        }
        // Join the in-flight copy threads so callers see deterministic
        // teardown (no file descriptors leak past close() returning). We
        // skip joining if we'd be deadlocking on ourselves (a copy thread
        // calling close() — unusual but cheap to guard).
        val self = Thread.currentThread()
        val deadlineNanos = System.nanoTime() + CLOSE_JOIN_TIMEOUT_MS * NANOS_PER_MILLI
        val threadsToJoin = synchronized(lifecycleLock) { ArrayList(copyThreads) }
        for (t in threadsToJoin) {
            if (t === self) continue
            val remainingMillis = (deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
            if (remainingMillis <= 0L) break
            try {
                t.join(remainingMillis)
            } catch (_: InterruptedException) {
                // Preserve interrupt status and stop joining. The final
                // live-thread check below turns an incomplete join into an
                // explicit close failure instead of silently detaching it.
                Thread.currentThread().interrupt()
                break
            }
        }
        // The join budget is aggregate and deliberately bounded. A callback
        // that ignores the closed streams remains registered and makes the
        // close fail explicitly; it is never detached while still alive.
        val lingeringThreads = threadsToJoin.filter { it !== self && it.isAlive }
        if (lingeringThreads.isNotEmpty()) {
            lingeringThreads.forEach(Thread::interrupt)
            // Interruptible callbacks can need a small scheduling window to
            // unwind their finally blocks. Give them that window before
            // declaring close failed; the first join remains the aggregate
            // cleanup budget, and this is only the short interrupt response
            // grace period.
            var interrupted = false
            if (Thread.interrupted()) interrupted = true
            val interruptDeadlineNanos =
                System.nanoTime() + POST_INTERRUPT_JOIN_TIMEOUT_MS * NANOS_PER_MILLI
            for (thread in lingeringThreads) {
                val remainingMillis =
                    (interruptDeadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
                if (remainingMillis <= 0L) break
                try {
                    thread.join(remainingMillis)
                } catch (_: InterruptedException) {
                    interrupted = true
                    break
                }
            }
            if (interrupted) Thread.currentThread().interrupt()

            // Check the actual Thread handles captured before teardown. A
            // callback removes itself from copyThreads in finally, slightly
            // before Thread.run() returns; registry emptiness alone would
            // therefore be an unsound close-success oracle.
            val stillAlive = lingeringThreads.filter(Thread::isAlive)
            synchronized(lifecycleLock) {
                copyThreads.filter { !it.isAlive }.forEach(copyThreads::remove)
            }
            if (stillAlive.isEmpty()) return
            val details = stillAlive.joinToString { thread ->
                "${thread.name}(${thread.state})"
            }
            throw IllegalStateException(
                "Port forward 127.0.0.1:$localPort close timed out after " +
                    "$CLOSE_JOIN_TIMEOUT_MS ms; live copy threads: $details",
            )
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

        // Each accepted connection creates two copy threads. Keep a runaway
        // local client burst from creating unbounded daemon threads and SSH
        // direct-tcpip channels while still allowing normal browser/dev-tool
        // concurrency.
        const val MAX_ACTIVE_CONNECTIONS = 32

        // Aggregate join budget in close(). The underlying sockets/channels
        // have already been closed, so this is only a deterministic cleanup
        // window, not a per-thread multiplier.
        const val CLOSE_JOIN_TIMEOUT_MS = 1_000L
        const val POST_INTERRUPT_JOIN_TIMEOUT_MS = 100L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
