package com.pocketshell.core.ssh

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared REAL wall-clock watchdog for bounding blocking sshj operations
 * (issues #937 / #940 / #935 S4).
 *
 * ## Why a wall clock and not `withTimeout`
 *
 * `kotlinx.coroutines.withTimeout` / `withTimeoutOrNull` read the delay source
 * from the CALLER's coroutine context. Under `runTest`'s virtual, auto-advancing
 * test clock that fires the ceiling INSTANTLY in virtual time while the real
 * sshj op is still legitimately in progress on a worker thread — interrupting
 * every healthy connect/exec/open/read and aborting it as an
 * `InterruptedException` / `ConnectionException`. That is exactly the #937/#940
 * regression (the `13/21` integration-suite signature) and it is the trap #935
 * S4-2's first attempt fell into.
 *
 * [runUnderWallClockCeiling] interrupts the worker thread only after
 * [timeoutMs] of REAL elapsed time, identically in production and under any test
 * scheduler — so a healthy op is never aborted while a genuinely-wedged op is
 * still reclaimed. This is the SAME mechanism [TransportDispatcher] uses for its
 * per-op ceiling; factored out here so the exec-read bound (which runs OUTSIDE
 * the dispatcher by design — #935 S4-2) reuses one watchdog instead of a
 * caller-clock `withTimeout`.
 */
internal object WallClockCeiling {

    /**
     * Shared real wall-clock watchdog. A single daemon scheduler across the
     * whole module schedules a per-op interrupt that fires after the requested
     * real elapsed time — decoupled from the caller's coroutine delay source.
     * Each scheduled task is cancelled the instant its op returns, so a healthy
     * fast op leaves no pending timer; the scheduler is effectively idle between
     * wedges. Daemon so it never holds the JVM open.
     */
    private val WATCHDOG: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ps-ssh-wall-clock-ceiling").apply { isDaemon = true }
        }

    /**
     * Run [block] on the CURRENT thread under a real wall-clock ceiling. A
     * watchdog task is scheduled to interrupt THIS thread after [timeoutMs] of
     * real elapsed time; it is cancelled the instant [block] returns.
     *
     * On expiry the thread's interrupt unblocks a parked blocking JDK
     * `read()`/`write()`/`join()`; [onTimeout] is then invoked to map the
     * resulting failure into the caller's chosen exception (the interrupt may
     * surface as `InterruptedException`, `InterruptedIOException`, or an sshj
     * wrapper). The thread's interrupt status is CLEARED in `finally` so it can
     * never leak onto a later op reusing the thread.
     *
     * MUST be called from inside `runInterruptible` (so the surrounding
     * coroutine machinery does not also try to interrupt the thread — the
     * watchdog is the sole source of interruption here), on a thread the caller
     * owns for the duration of [block].
     *
     * @param onTimeout maps the wedged-op failure into the exception to throw;
     *   receives the underlying cause (the interrupt-derived throwable, if any).
     */
    fun <T> runUnderWallClockCeiling(
        timeoutMs: Long,
        onTimeout: (cause: Throwable?) -> Throwable,
        block: () -> T,
    ): T {
        val target = Thread.currentThread()
        val firedByWatchdog = AtomicBoolean(false)
        val watchdog = WATCHDOG.schedule(
            {
                firedByWatchdog.set(true)
                target.interrupt()
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        try {
            return block()
        } catch (t: Throwable) {
            if (firedByWatchdog.get()) {
                throw onTimeout(t)
            }
            throw t
        } finally {
            watchdog.cancel(false)
            // Clear any interrupt status the watchdog set so it can never leak
            // onto the next op reusing this thread. No-op when it never fired.
            Thread.interrupted()
        }
    }
}
