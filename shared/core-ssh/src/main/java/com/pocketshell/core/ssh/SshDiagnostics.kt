package com.pocketshell.core.ssh

import java.util.concurrent.ArrayBlockingQueue

/**
 * Issue #1683 — the minimal transport-level diagnostics seam for core-ssh.
 *
 * core-ssh previously emitted NOTHING to the diagnostics timeline (the #1642
 * finding, restated by the #1680 Track A audit): keepalive misses, the
 * ride-through window state, and every transport-liveness input were recorded
 * only as `Level.FINE` logger lines that never reach the exported connection
 * trace. So a transport-keepalive-driven death showed up in the log as a bare
 * VERDICT (`KeepaliveDead` close) with none of the INPUTS (which tick missed,
 * how many consecutive) that would let us tell an over-eager false-dead from a
 * real silent-peer death.
 *
 * Like [com.pocketshell.core.connection.ConnectionDiagnostics], this is a
 * fire-and-forget sink the app installs once at startup, forwarding into the
 * real recorder under the `connection` category (so keepalive inputs are
 * mirrored to the host alongside the verdicts). Both [SshDiagnostics.record]
 * and [SshDiagnostics.recordNonBlocking] use the bounded mailbox, so no
 * installable sink ever runs on the transport/lease thread. The default is a
 * no-op, so pure virtual-clock tests keep running with zero wiring and unchanged
 * loop cadence.
 */
fun interface SshDiagnosticsSink {
    fun record(event: String, fields: Map<String, Any?>)
}

object SshDiagnostics {
    private val noop = SshDiagnosticsSink { _, _ -> }
    private const val NON_BLOCKING_BUFFER_CAPACITY = 64

    private data class InstalledSink(
        val generation: Long,
        val sink: SshDiagnosticsSink,
    )

    private data class PendingEvent(
        val generation: Long,
        val event: String,
        val fields: Map<String, Any?>,
    )

    @Volatile
    private var installed = InstalledSink(generation = 0L, sink = noop)
    private val pending = ArrayBlockingQueue<PendingEvent>(NON_BLOCKING_BUFFER_CAPACITY)
    private val worker = Thread(::drain, "pocketshell-ssh-diagnostics")
        .apply {
            isDaemon = true
            start()
        }

    fun install(installed: SshDiagnosticsSink) {
        pending.clear()
        val nextGeneration = this.installed.generation + 1L
        this.installed = InstalledSink(nextGeneration, installed)
    }

    /** Test-only reset back to the no-op sink. */
    fun reset() {
        pending.clear()
        val nextGeneration = installed.generation + 1L
        installed = InstalledSink(nextGeneration, noop)
    }

    /** Record a diagnostic through the bounded, nonblocking mailbox. */
    fun record(event: String, vararg fields: Pair<String, Any?>) {
        recordNonBlocking(event, *fields)
    }

    /**
     * Queue a diagnostic without invoking the installable sink on the caller's
     * thread. The queue and its single daemon worker are bounded; a saturated
     * queue drops this best-effort diagnostic rather than blocking SSH/lease IO.
     * Sink exceptions are contained on the worker so they cannot cancel the
     * worker or escape a manager teardown path. A generation fence prevents
     * events queued for a previous installed test/app sink from crossing an
     * install/reset boundary.
     */
    fun recordNonBlocking(event: String, vararg fields: Pair<String, Any?>) {
        val current = installed
        val item = PendingEvent(
            generation = current.generation,
            event = event,
            fields = fields.toMap(),
        )
        if (!pending.offer(item)) return
    }

    private fun drain() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val item = pending.take()
                val current = installed
                if (item.generation != current.generation) continue
                runCatching { current.sink.record(item.event, item.fields) }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
