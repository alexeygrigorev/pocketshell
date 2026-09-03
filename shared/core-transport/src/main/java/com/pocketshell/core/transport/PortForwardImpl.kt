package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
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
 * One duplex byte channel to `remoteHost:remotePort` on the server's side.
 *
 * The seam over sshj's `direct-tcpip` channel. It exists so the forward's
 * accept/copy/teardown machinery — the part with the concurrency bugs worth
 * testing — can be driven on the host JVM against plain sockets, without an SSH
 * transport. Constructor-injected, never a `*ForTest` back door.
 */
internal interface ForwardedChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

/** Opens a [ForwardedChannel]. Blocking; called from the accept loop's thread. */
internal fun interface ForwardedChannelOpener {
    fun open(remoteHost: String, remotePort: Int): ForwardedChannel
}

/**
 * The sshj-backed [PortForward] (rewrite task P-4), adapted from the deleted
 * `core-ssh` `RealSshPortForward` onto [HostConnection].
 *
 * We do not use sshj's bundled `LocalPortForwarder`: it copies bytes through
 * internal `StreamCopier` instances that cannot be instrumented for the
 * per-tunnel byte counters [PortForward] exposes. Instead this class accepts
 * connections on its own [ServerSocket] and opens one `direct-tcpip` channel per
 * accepted client, copying bytes in both directions through counting loops.
 *
 * ## Why there is no single-writer dispatcher here
 *
 * The old implementation funnelled every channel open/close through a
 * `TransportDispatcher` (old issues #847/#980): a port forward sharing the ONE
 * interactive connection could otherwise race the `-CC` stdin writes and the
 * keepalive on the same transport. The rewrite removes both halves of that
 * problem. `core-transport` has no dispatcher at all (exec/pty/sftp open
 * channels straight off the sshj client, which serialises its own encoder), and
 * forwarding keeps the D21 carve-out of dialling its OWN connection — so a
 * forward's channel churn is never on the same transport as an interactive
 * session's writes. Re-introducing a dispatcher would be a shim for a race this
 * architecture does not have.
 */
internal class PortForwardImpl(
    private val channels: ForwardedChannelOpener,
    override val remoteHost: String,
    override val remotePort: Int,
    override val localPort: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : PortForward {

    private val serverSocket: ServerSocket =
        ServerSocket(localPort, /* backlog = */ 50, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val forwardedBytes = AtomicLong(0)
    private val receivedBytes = AtomicLong(0)
    private val activeConnectionSlots = AtomicInteger(0)

    /**
     * Serialises pair ownership and copy-thread registration against [close].
     * Once [running] is false no new copy thread may be registered, which is
     * what makes close's snapshots complete: a thread cannot appear after close
     * has taken its join set.
     */
    private val lifecycleLock = Any()

    private val activePairs = CopyOnWriteArraySet<Pair<Socket, ForwardedChannel>>()

    /**
     * Live copy threads (one per direction, per accepted connection), tracked so
     * [close] can join them deterministically instead of relying on daemon-thread
     * auto-cleanup (which would briefly leak file descriptors past close and make
     * tests flaky). A live thread is never removed by [close] merely because the
     * join budget expired; its own finally block owns removal.
     */
    private val copyThreads = CopyOnWriteArraySet<Thread>()

    @Suppress("unused")
    private val acceptThread: Thread =
        Thread(::acceptLoop, "portfwd-accept-$localPort").apply {
            isDaemon = true
            start()
        }

    override val isActive: Boolean
        get() = running.get() && !serverSocket.isClosed

    override val bytesForwarded: Long get() = forwardedBytes.get()

    override val bytesReceived: Long get() = receivedBytes.get()

    private fun acceptLoop() {
        while (running.get()) {
            val client: Socket = try {
                serverSocket.accept()
            } catch (e: SocketException) {
                // serverSocket.close() unblocks accept() with a SocketException;
                // that is our cue to exit cleanly.
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

        // Open synchronously so an open failure is visible here (we close the
        // local socket and keep the accept loop alive); only then hand off to
        // the two daemon copy threads.
        val channel: ForwardedChannel = try {
            channels.open(remoteHost, remotePort)
        } catch (_: Throwable) {
            releaseConnectionSlot()
            runCatching { local.close() }
            return
        }

        // close() may have flipped `running` between accept() returning and this
        // point; tear the pair down instead of spinning up copiers.
        if (!running.get()) {
            try {
                closePair(local, channel)
            } finally {
                releaseConnectionSlot()
            }
            return
        }

        val pair = local to channel
        val registered = synchronized(lifecycleLock) {
            if (!running.get()) false else activePairs.add(pair)
        }
        if (!registered) {
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
        startCopyThread("portfwd-l2r-$localPort") {
            copy(sockIn, chanOut, forwardedBytes)
            // When one side EOFs, tear the whole pair down so the partner copier
            // does not hang on a half-closed channel.
            closePairAndUntrack(pair)
        }

        // remote -> local (bytes received from remote)
        startCopyThread("portfwd-r2l-$localPort") {
            copy(chanIn, sockOut, receivedBytes)
            closePairAndUntrack(pair)
        }
    }

    private fun closePairAndUntrack(pair: Pair<Socket, ForwardedChannel>) {
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
     * Spins up a daemon copy thread registered in [copyThreads] so [close] can
     * join it. The body always deregisters itself on exit, even on exception, so
     * the registry tracks only live threads.
     */
    private fun startCopyThread(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } finally {
                synchronized(lifecycleLock) { copyThreads.remove(Thread.currentThread()) }
            }
        }, name).apply { isDaemon = true }
        synchronized(lifecycleLock) {
            if (!running.get()) return
            copyThreads.add(thread)
            thread.start()
        }
    }

    /**
     * Counts bytes the instant they are READ, before the write can be observed —
     * a counter that only advanced after a successful write would silently
     * under-report on a stalled peer.
     */
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

    private fun closePair(local: Socket, channel: ForwardedChannel) {
        runCatching { local.close() }
        runCatching { channel.close() }
    }

    override suspend fun close() {
        // Bounded, interruptible blocking teardown off the caller's thread. A
        // caller that cancels (e.g. AutoForwarder's per-forward close timeout)
        // interrupts the join loop rather than leaving the coroutine detached
        // from a still-blocked thread.
        withContext(ioDispatcher) { runInterruptible { closeBlocking() } }
    }

    /**
     * Stops accepting connections and tears down owned pairs.
     *
     * The lifecycle lock seals pair/thread registration before the snapshots are
     * taken. A close that cannot prove every copy thread terminated inside the
     * aggregate join budget interrupts the stragglers and throws a diagnostic; it
     * never silently detaches a live thread from [copyThreads].
     */
    private fun closeBlocking() {
        val pairs = synchronized(lifecycleLock) {
            // `running` is written under the same lock that serialises every
            // activePairs mutation and snapshot, so the post-close
            // startCopyThread check is a single lifecycle decision.
            if (!running.compareAndSet(true, false)) return
            val snapshot = ArrayList(activePairs)
            activePairs.clear()
            snapshot
        }
        runCatching { serverSocket.close() }
        // Copy threads block inside src.read(buf), which does not re-check
        // `running` until between iterations — closing the socket and the
        // channel is what actually unblocks them.
        for ((local, channel) in pairs) {
            try {
                closePair(local, channel)
            } finally {
                releaseConnectionSlot()
            }
        }

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
                Thread.currentThread().interrupt()
                break
            }
        }

        val lingering = threadsToJoin.filter { it !== self && it.isAlive }
        if (lingering.isEmpty()) return

        lingering.forEach(Thread::interrupt)
        // Interruptible copiers need a small scheduling window to unwind their
        // finally blocks; the first join was the aggregate cleanup budget, this
        // is only the interrupt-response grace period.
        var interrupted = Thread.interrupted()
        val interruptDeadlineNanos =
            System.nanoTime() + POST_INTERRUPT_JOIN_TIMEOUT_MS * NANOS_PER_MILLI
        for (thread in lingering) {
            val remainingMillis = (interruptDeadlineNanos - System.nanoTime()) / NANOS_PER_MILLI
            if (remainingMillis <= 0L) break
            try {
                thread.join(remainingMillis)
            } catch (_: InterruptedException) {
                interrupted = true
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()

        // Check the Thread handles captured before teardown: a copier removes
        // itself from copyThreads in `finally`, slightly before Thread.run()
        // returns, so registry emptiness alone is an unsound success oracle.
        val stillAlive = lingering.filter(Thread::isAlive)
        synchronized(lifecycleLock) {
            copyThreads.filter { !it.isAlive }.forEach(copyThreads::remove)
        }
        if (stillAlive.isEmpty()) return
        val details = stillAlive.joinToString { "${it.name}(${it.state})" }
        throw IllegalStateException(
            "Port forward 127.0.0.1:$localPort close timed out after " +
                "$CLOSE_JOIN_TIMEOUT_MS ms; live copy threads: $details",
        )
        // The accept thread is deliberately not joined: it is a daemon, the
        // SocketException wakeup is immediate, and joining would deadlock if
        // close() were ever reached from inside the accept thread itself.
    }

    internal companion object {
        // 32 KiB matches sshj's default channel max-packet size — the largest
        // single read the channel side can hand back.
        private const val BUFFER_SIZE = 32 * 1024

        /**
         * Each accepted connection creates two copy threads and one direct-tcpip
         * channel. Bounds a runaway local client burst while still allowing
         * normal browser / dev-tool concurrency.
         */
        internal const val MAX_ACTIVE_CONNECTIONS = 32

        /**
         * Aggregate join budget in close(). The sockets/channels are already
         * closed by then, so this is a deterministic cleanup window, not a
         * per-thread multiplier.
         */
        private const val CLOSE_JOIN_TIMEOUT_MS = 1_000L
        private const val POST_INTERRUPT_JOIN_TIMEOUT_MS = 100L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Production opener: one sshj `direct-tcpip` channel per accepted client. */
        fun sshjOpener(client: SSHClient): ForwardedChannelOpener =
            ForwardedChannelOpener { host, port ->
                val channel = client.newDirectConnection(host, port)
                object : ForwardedChannel {
                    override val inputStream: InputStream get() = channel.inputStream
                    override val outputStream: OutputStream get() = channel.outputStream
                    override fun close() {
                        channel.close()
                    }
                }
            }
    }
}
