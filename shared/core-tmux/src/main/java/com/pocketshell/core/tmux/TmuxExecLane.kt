package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Issue #1516 — run ONE `SshSession.exec` round-trip on the dedicated exec lane
 * under a bound the CALLER can actually enforce.
 *
 * ## The defect this exists to fix
 * Every exec-lane call used to be written as a STRUCTURED child:
 *
 * ```
 * withTimeoutOrNull(effectiveTimeoutMs) { session.exec(command) }   // OLD
 * ```
 *
 * That reads like a bound but is not one. `withTimeoutOrNull` cancels its child
 * and then — by structured concurrency — **cannot return until that child has
 * fully completed**. `RealSshSession.exec`'s `finally` closes the exec channel
 * inside `withContext(NonCancellable)` through the single-writer
 * `TransportDispatcher`, whose per-op wall-clock ceiling is
 * `DEFAULT_PER_OP_TIMEOUT_MS` = 8 s. On a genuinely half-open socket (no
 * FIN/RST) that channel-close write never gets an answer, so the "2.5 s" heal
 * capture parked its caller for the bound PLUS the whole 8 s teardown ceiling.
 *
 * Measured on the real transport (Docker sshd behind a paused in-JVM TCP relay,
 * `Zz1516` probe, 2026-07-27): the render-heal-shaped
 * `withTimeoutOrNull(2500) { session.exec(...) }` returned after **10 506 ms** —
 * 4× its nominal bound and 2.6× the #1494 watchdog tick it is supposed to sit
 * under. With no caller bound at all the same call took **38 052 ms** (the 30 s
 * session-wide no-progress read watchdog + the same 8 s teardown tail).
 *
 * ## The fix
 * Own the exec as a **sibling** of the caller on the client's own [CoroutineScope]
 * instead of as its child, and bound the *await*. The bound is then a real
 * deadline: when it trips, the caller returns a definite failure immediately and
 * the cancelled exec finishes unwinding OFF the caller.
 *
 * Cancelling the owned exec is what reclaims the resources, and it happens at the
 * bound, not at the teardown tail:
 *  - `RealSshSession.exec` runs its blocking stdout/stderr drain inside
 *    `runInterruptible(Dispatchers.IO)`, so cancellation `Thread.interrupt()`s
 *    the parked JDK read and the drain workers (`Future.cancel(true)`) —
 *    the wedged read is unparked at the bound;
 *  - the `finally` that releases the exec-drain permit (1 of
 *    `EXEC_DRAIN_MAX_CONCURRENT_EXECS` = 4) runs on that same unwind, so the
 *    permit pool is not stranded either;
 *  - only the `NonCancellable` channel-close tail is left, and it now runs
 *    detached instead of on the caller's clock.
 *
 * This is why #1516 does NOT add another nested coroutine timeout (the rejected
 * round-1 approach: a `withTimeoutOrNull` nested *outside* another
 * `withTimeoutOrNull` on the same call can never fire first, and inherits the
 * same "wait for the child" semantics), and does not need a per-call
 * `execReadTimeoutMs` override in core-ssh: on this lane the caller's bound
 * already unparks the read strictly EARLIER than any read watchdog we could
 * install (2.5 s < 3 s < the 30 s session default), so such an override would be
 * unreachable dead code on the render-heal path.
 *
 * ## How the bound composes with the #1494 watchdog budgets
 * ```
 * exec-lane caller bound  (SEED_CAPTURE_TIMEOUT_MS)              2.5 s   <-- enforced here
 *   < render-heal watchdog tick  (RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS)  4.0 s
 *   < per-pane single-flight force-reset (HELD_TOO_LONG_MS)     15.0 s
 * ```
 * Because the capture now genuinely returns by 2.5 s, a wedged heal scores a
 * DEFINITE failure (`TmuxClientException` -> `Unverified`, hot cadence) *inside*
 * the 4 s tick instead of being abandoned by it at 4 s while the capture kept
 * parking to 10.5 s — and the force-mode reseed path (which #1494's tick does NOT
 * wrap at all) gets the same real bound. The detached teardown tail is bounded
 * independently by the transport dispatcher's 8 s per-op ceiling and completes
 * well inside the 15 s single-flight reset.
 *
 * Returns `null` when [timeoutMs] elapsed first (identical to the old
 * `withTimeoutOrNull` contract, so callers keep their existing
 * "null -> throw TmuxClientException" mapping); rethrows whatever the exec threw.
 */
internal suspend fun runBoundedExecLane(
    scope: CoroutineScope,
    session: SshSession,
    command: String,
    timeoutMs: Long,
): ExecResult? {
    val owned = scope.async { session.exec(command) }
    return try {
        withTimeoutOrNull(timeoutMs) { owned.await() }
    } finally {
        // Bound exceeded, caller cancelled, or the exec threw: never leave the
        // detached exec running. Cancelling unparks its blocking read and frees
        // its exec-drain permit immediately; the NonCancellable channel teardown
        // then completes off the caller. A no-op on the success path (cancelling
        // an already-completed Deferred does nothing).
        owned.cancel()
    }
}
