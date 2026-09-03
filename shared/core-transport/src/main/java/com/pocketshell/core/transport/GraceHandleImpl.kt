package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The delayed-close machinery behind [HostConnection.scheduleGraceClose]
 * (rewrite task T-5, decision D21).
 *
 * D21 lets the app hold the transport open across a short background window so
 * an app-switch returns to a live session instead of a reconnect — but ONLY as
 * a single bounded timer, never an open-ended background hold. That contract
 * has three hard parts, and each is a separate mechanism here:
 *
 * 1. **One pending close at a time.** A second [schedule] supersedes the first
 *    handle before arming its own, so two overlapping foreground/background
 *    flips can never leave two timers racing to close the same connection.
 * 2. **Cancel means nothing ever fires.** [GraceHandleImpl.cancel] both flips
 *    the handle out of its armed phase AND cancels the coroutine, so neither a
 *    still-parked `delay` nor a `delay` that just resumed can reach
 *    [onDeadline]. The phase flag is what makes this airtight: cancelling the
 *    job alone would leave a (tiny) window where the job had already resumed
 *    past `delay` and would still run the close.
 * 3. **Virtual-time testable.** Both the parked wait ([dispatcher]) and the
 *    deadline arithmetic ([nowMs]) are injected, so a unit test can drive the
 *    whole thing on a `TestScheduler` and PROVE the no-work-after-cancel
 *    contract by advancing far past the deadline, instead of sleeping in real
 *    time and hoping.
 *
 * Owned by exactly one [HostConnection]; created once per connection.
 */
internal class GraceCloseScheduler(
    dispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onDeadline: suspend () -> Unit,
) {

    /**
     * Supervisor so a failed close can never cancel the scope (and with it a
     * later grace timer) — each armed close is independent.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val lock = Any()

    /** The one handle that may still fire; null before the first [schedule]. */
    private var pending: GraceHandleImpl? = null

    /** The handle armed right now, for tests and diagnostics. */
    val pendingHandle: GraceHandleImpl?
        get() = synchronized(lock) { pending }

    /**
     * Arms a close [graceMs] from now, superseding any previously armed one.
     * Non-suspending: the caller (a lifecycle callback) must not block.
     */
    fun schedule(graceMs: Long): GraceHandle {
        val delayMs = graceMs.coerceAtLeast(0)
        val handle = GraceHandleImpl(deadlineMs = nowMs() + delayMs)

        val superseded = synchronized(lock) {
            val previous = pending
            pending = handle
            previous
        }
        // Outside the lock: cancelling a Job can run completion handlers.
        superseded?.supersede()

        // LAZY so the job exists before it can run: attach() decides whether to
        // start it or cancel it, which closes the race where a zero-length
        // grace fires before `handle` has a job to cancel.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            // claimFire() is the single point that decides a close happens.
            // It fails if this handle was cancelled or superseded, including in
            // the instant between `delay` resuming and this line.
            if (handle.claimFire()) {
                synchronized(lock) {
                    if (pending === handle) pending = null
                }
                // A background timer must never take the process down: close()
                // is already best-effort/idempotent, and an exception escaping
                // here would reach the uncaught-exception handler.
                runCatching { onDeadline() }
            }
        }
        handle.attach(job)
        return handle
    }
}

/**
 * The real [GraceHandle]: a phase flag plus the coroutine that would fire.
 *
 * The phase is a one-way transition out of [Phase.ARMED] — whichever of
 * cancel / supersede / fire wins the CAS decides the outcome, and the losers
 * become no-ops. That makes [cancel] idempotent and makes "a cancelled handle
 * never fires" a property of the state machine rather than of coroutine
 * cancellation timing.
 */
internal class GraceHandleImpl(override val deadlineMs: Long) : GraceHandle {

    internal enum class Phase { ARMED, CANCELLED, SUPERSEDED, FIRED }

    private val phase = AtomicReference(Phase.ARMED)

    @Volatile
    private var job: Job? = null

    val currentPhase: Phase get() = phase.get()

    /** True while this handle would still close the connection. */
    val isLive: Boolean get() = phase.get() == Phase.ARMED

    val isCancelled: Boolean get() = phase.get() == Phase.CANCELLED

    /** True when a later [GraceCloseScheduler.schedule] took this handle's place. */
    val isSuperseded: Boolean get() = phase.get() == Phase.SUPERSEDED

    override fun cancel() {
        leaveArmed(Phase.CANCELLED)
    }

    /** Replaced by a newer armed close; identical effect to [cancel], distinct phase. */
    internal fun supersede() {
        leaveArmed(Phase.SUPERSEDED)
    }

    /** Returns true exactly once, and only for a still-armed handle. */
    internal fun claimFire(): Boolean = phase.compareAndSet(Phase.ARMED, Phase.FIRED)

    /**
     * Binds the timer coroutine. Starts it, unless the handle already left the
     * armed phase (cancelled between [GraceCloseScheduler.schedule] creating
     * the job and this call), in which case the job is cancelled before it ever
     * runs.
     */
    internal fun attach(job: Job) {
        this.job = job
        if (phase.get() == Phase.ARMED) job.start() else job.cancel()
    }

    private fun leaveArmed(target: Phase) {
        if (phase.compareAndSet(Phase.ARMED, target)) {
            // Belt and braces: the phase flag alone already stops the close, but
            // cancelling frees the parked delay immediately instead of leaving
            // it to wake up and no-op at the deadline.
            job?.cancel()
        }
    }
}
