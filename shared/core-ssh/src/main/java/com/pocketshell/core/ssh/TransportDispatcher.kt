package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-writer dispatch owner for ONE live sshj transport (issue #847 / #766
 * slice 1).
 *
 * ## Why this exists
 *
 * The maintainer's real host logged `ssh_dispatch_run_fatal: ... Connection
 * corrupted` ~60-76s after a successful handshake on every PocketShell
 * connection, while plain OpenSSH held the same host 90s+ clean. The research
 * spike on #847 traced it to a **client-side encoder sequence-number / cipher
 * desync**: PocketShell opens/writes the SAME warm transport from MANY
 * concurrent owners — the `-CC` control shell, a constant churn of short-lived
 * `exec` channels (tree hydrate, agent-kind, folder enumeration, jobs/profiles/
 * file-viewer), the liveness probe's `refresh-client`, the PTY `resizePty`
 * window-change, and teardown — with no single owner arbitrating channel
 * lifecycle vs. a KEX/rekey boundary vs. `die()`.
 *
 * sshj's `TransportImpl.write` already takes a process-wide `writeLock` so raw
 * bytes can never interleave mid-packet. The fault is one layer up: a channel
 * open or write that lands in the rekey window (cipher + sequence counters
 * rotating) or against a transport entering `die()` advances the encoder
 * sequence counter inconsistently with what reaches the wire, so the server
 * MAC-verify fails. Constant concurrent channel churn maximises that window.
 *
 * The durable fix (D28 single active path, D22 hard-cut) is a **single owner of
 * the connection's channel + command lifecycle**: every transport-touching
 * operation funnels through this dispatcher and runs strictly one-at-a-time, in
 * submission order, on a single dedicated thread. That removes concurrent
 * channel-open churn against the `-CC` shell and guarantees no write is
 * dispatched once teardown is enqueued (the dispatcher drains-then-dies in
 * order), closing the KEX/`die()` race window.
 *
 * The `-CC` continuous READ is deliberately NOT routed through here — it stays a
 * separate reader loop on the shell's stdout, exactly mirroring sshj's own
 * Reader-thread / writeLock split. This dispatcher owns WRITES + channel
 * LIFECYCLE only.
 *
 * ## Invariants (pinned by [TransportDispatcherTest])
 *
 * 1. **Mutual exclusion.** No two submitted operations ever run concurrently.
 *    Each [run] holds [mutex] for the whole operation, and acquisition is FIFO,
 *    so a channel open can never overlap another op's write.
 * 2. **Single thread.** Every operation body runs on [context], one dedicated
 *    thread, so even non-suspending sshj calls (`startSession`, blocking stdin
 *    `write`/`flush`) cannot run on two threads at once.
 * 3. **Teardown ordering.** Once [closeAndAwaitDrain] is called the dispatcher
 *    is [closed]; any operation submitted after that point is rejected with
 *    [TransportClosedException] BEFORE it touches the transport. Operations
 *    already enqueued/in-flight finish first (the teardown takes the mutex
 *    behind them), so the final `disconnect()` is the last write on the wire.
 */
internal class TransportDispatcher {

    /**
     * One dedicated daemon thread per connection. A dedicated single thread
     * (not a shared `Dispatchers.IO` view) is required so blocking sshj calls
     * — `startSession`, `OutputStream.write/flush`, `changeWindowDimensions`,
     * `disconnect` — are physically serialised: invariant (2). Daemon so a
     * leaked dispatcher never holds the JVM open.
     */
    private val executor = Executors.newSingleThreadExecutor(
        object : ThreadFactory {
            private val n = AtomicLong(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "ps-ssh-dispatch-${SEQ.incrementAndGet()}-${n.incrementAndGet()}").apply {
                    isDaemon = true
                }
        },
    )

    private val context: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** FIFO mutual-exclusion lock across all operations: invariant (1). */
    private val mutex = Mutex()

    private val closed = AtomicBoolean(false)

    /** True once [closeAndAwaitDrain] has run — no new ops are accepted. */
    val isClosed: Boolean
        get() = closed.get()

    /**
     * Run [block] as the sole transport operation: serialised against every
     * other submitted op (invariant 1) and pinned to the dispatch thread
     * (invariant 2).
     *
     * Rejects with [TransportClosedException] if the dispatcher is already
     * [closed] — checked under the lock so it can never race a concurrent
     * [closeAndAwaitDrain] (invariant 3).
     */
    suspend fun <T> run(block: () -> T): T = mutex.withLock {
        if (closed.get()) {
            throw TransportClosedException()
        }
        withContext(context) { block() }
    }

    /**
     * Blocking variant of [run] for the non-suspending `AutoCloseable` /
     * `SshShell` surface (the `-CC` stdin write/flush, `resizePty`, shell
     * close). The calling thread blocks until the operation runs on the
     * dispatch thread — same FIFO mutual-exclusion and same teardown rejection
     * as [run], just bridged for callers that cannot suspend.
     *
     * MUST NOT be called from the dispatch thread itself (would deadlock on the
     * mutex); all current callers invoke it from an IO/UI coroutine, never from
     * inside another [run] block.
     */
    fun <T> runBlockingDispatch(block: () -> T): T = runBlocking { run(block) }

    /**
     * Drain all pending/in-flight operations, then run [disconnect] as the
     * final operation under the lock, then mark [closed] and shut the executor
     * down. Idempotent.
     *
     * Taking [mutex] here means teardown queues BEHIND any op already submitted
     * (so we never disconnect underneath an in-flight write), and setting
     * [closed] under the lock means any op that had not yet acquired the lock
     * sees `closed == true` and is rejected before touching the transport.
     */
    suspend fun closeAndAwaitDrain(disconnect: () -> Unit) {
        if (closed.get()) return
        mutex.withLock {
            if (closed.getAndSet(true)) return@withLock
            runCatching { withContext(context) { disconnect() } }
        }
        executor.shutdown()
    }

    private companion object {
        /** Per-process dispatcher id, for thread-name uniqueness in logs. */
        private val SEQ = AtomicLong(0)
    }
}

/**
 * Thrown when an operation is submitted to a [TransportDispatcher] that has
 * already begun (or completed) teardown. The transport is gone.
 *
 * The message INTENTIONALLY contains the exact substring
 * `SSH session is not connected` so the cross-module heal matcher
 * `isSessionNotConnected` (FolderListGateway / TmuxSessionViewModel, #680/#687)
 * classifies a rejected-after-close exec as a transient stale-channel fault and
 * drives evict-and-retry-once instead of a false "not connected" banner —
 * identical to the message [RealSshSession.ensureConnected] produces. Pinned by
 * `SshIntegrationTest.execOnAClosedSessionThrowsExactStaleChannelMessage`.
 */
internal class TransportClosedException :
    SshException("SSH session is not connected (transport is closed)")
