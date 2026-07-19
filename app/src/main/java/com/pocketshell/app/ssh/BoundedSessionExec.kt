package com.pocketshell.app.ssh

import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ONE bounded-exec primitive for a caller that borrows the SHARED per-host
 * lease transport — issue #1641.
 *
 * ## Why this exists (and why it is the only implementation)
 *
 * Five remote sources had each independently copy-pasted the same shim:
 *
 * ```kotlin
 * withTimeoutOrNull(execReadTimeoutMs) { deferred.await() }
 *     ?: run {
 *         deferred.cancel()
 *         withContext(NonCancellable) { runCatching { close() } }  // <-- the bug
 *         null
 *     }
 * ```
 *
 * That `close()` is a FULL TEARDOWN of the shared per-host lease transport —
 * the one the live tmux `-CC` reader, the conversation loader and the upload
 * sidecar all ride — triggered by an exec merely being SLOW. On the degraded
 * links where #1610/#1539 live, a foreign-pane classify slower than its 3.5s
 * budget (a cold host Python CLI over mobile RTT routinely is) killed a
 * provably-alive transport. The `-CC` reader's read then threw `SSHException`,
 * which is indistinguishable from a genuine link drop, so the reconnect storm
 * re-ingested our OWN close as a fresh passive failure — an uncredited storm
 * entry trigger that emitted no log and no cause-trail event whatsoever.
 *
 * [com.pocketshell.core.ssh.RealSshSession.exec] (#1567, D28 — the root of the
 * #1562 self-close storm) already locked the correct contract at the session
 * level:
 *
 * > a stalled exec must close ONLY its own channel, NEVER the shared
 * > SshSession/transport ... A starved exec is NOT evidence of a dead link;
 * > only a genuine TRANSPORT-level death (keepalive/liveness owns that
 * > judgment) may close the session. Callers get the retryable timeout and
 * > retry on the SAME warm transport (D22 hard-cut — no per-caller
 * > close-on-timeout shim).
 *
 * Those five sites WERE exactly that banned per-caller shim; #1567 fixed the
 * session-level ceiling but never swept the callers. This primitive is that
 * sweep, and it is deliberately STRUCTURAL rather than a fifth hand-rolled
 * guard (#1660): there is now exactly ONE bounded-exec implementation, it
 * contains no `close()`, and it holds no reference by which a caller could
 * reach the transport's lifecycle. Re-introducing a close-on-timeout would mean
 * editing this one file, against this documentation — not quietly copy-pasting
 * a sixth shim.
 *
 * ## Why abandoning is safe (no leaked channel, no leaked thread)
 *
 * The old comment justified the close with *"cancellation alone cannot
 * interrupt a blocking SSH stream read"*. That premise is STALE: since #1567
 * the read runs inside `runInterruptible(Dispatchers.IO)` under a
 * `WallClockCeiling` watchdog, so [kotlinx.coroutines.Deferred.cancel] does
 * unpark it, and even if the cancel lands late the session's own no-progress
 * budget (30s) closes just THAT exec channel and frees it via `exec`'s
 * `use {}`. Either way the abandoned exec is bounded and channel-local. The
 * shared transport is never a casualty.
 *
 * ## What still closes a genuinely dead transport (the load-bearing negative)
 *
 * Nothing here — deliberately. Recovery is owned by the layers that can tell
 * DEAD from SLOW:
 *
 *  - the transport keepalive / liveness check (a real TRANSPORT-level death),
 *  - [com.pocketshell.core.ssh.SshLeaseManager.evictIdle], the refcount-aware
 *    primitive that discards a poisoned lease NO live consumer holds (and is a
 *    deliberate no-op on one an active session still rides).
 *
 * A probe that cannot distinguish a slow host from a dead link must not be
 * allowed to make that call. Over-guarding the other way — never closing
 * anything — would stop the app reconnecting at all, which is strictly worse
 * than the storm; that is why the negative case is pinned by
 * `Issue1641SlowExecMustNotCloseSharedTransportTest`.
 */
object BoundedSessionExec {

    /** Cause-trail stage for an abandoned slow exec. */
    const val TRAIL_STAGE: String = "bounded_exec_timeout"

    /** Cause-trail outcome: we walked away and the transport is still up. */
    const val TRAIL_OUTCOME_ABANDONED: String = "abandoned_transport_preserved"

    /**
     * Issue #1693 — the #780-model SYNTHETIC self-inflicted-close seam. Maps a
     * [callerSite] token to an action invoked when a bounded exec FOR THAT SITE
     * times out, deterministically forcing the self-inflicted close the pre-#1641
     * v0.4.38 shim did (the historical `close()`-on-timeout of the shared lease).
     *
     * Keyed strictly on [callerSite] so ONLY the armed site self-closes: arming
     * `agent_kind_classify` (the ONE site that rides the shared `-CC` lease) makes
     * the storm reproduce, while a sibling site like `session_cards_rpc` (a
     * SEPARATE lease that does NOT storm) is never touched — the exact strict-key
     * requirement the #1681 finding demands so the RED cannot pass vacuously.
     *
     * Default EMPTY: in production this map is never populated, so `execBounded`
     * is pure abandon (real #1641 semantics, no behaviour change, no prod flag —
     * D22 hard-cut). Only the #1693 reproduction arms it, and MUST clear it in
     * teardown so it never leaks onto a sibling test (mirrors the
     * `KeepAliveTestOverride` process-global test-seam contract). `@Volatile` so
     * the arming instrumentation thread and the exec's IO dispatcher agree.
     */
    @Volatile
    @androidx.annotation.VisibleForTesting
    internal var onTimeoutSyntheticActionsForTest: Map<String, (SshSession) -> Unit> = emptyMap()

    /**
     * Run [command] on [session] — a SHARED lease transport — bounded by
     * [timeoutMs].
     *
     * Returns the [ExecResult], or `null` when the exec did not finish within
     * the bound. On that timeout the exec is ABANDONED (its coroutine cancelled)
     * and **the shared transport is left untouched and connected**.
     *
     * [callerSite] is a short, stable, non-PII token identifying the probe (e.g.
     * `agent_kind_classify`) so a slow exec is attributable in an exported
     * diagnostics report instead of being invisible.
     */
    suspend fun execBounded(
        session: SshSession,
        command: String,
        timeoutMs: Long,
        dispatcher: CoroutineDispatcher,
        callerSite: String,
        nowNanos: () -> Long = { System.nanoTime() },
    ): ExecResult? = withContext(dispatcher) {
        val startNanos = nowNanos()
        val deferred = async { session.exec(command) }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (result != null) return@withContext result

        // Abandon the slow exec. NOTE: no close(), no disconnect(), no evict —
        // see the class doc. `cancel()` unparks the interruptible read; the
        // session's own no-progress budget is the backstop, and it is
        // channel-local by construction (#1567).
        deferred.cancel()

        // Issue #1683 — the actual wall-clock the exec ran before we abandoned it.
        // A lease-attributable close (or a degraded/empty result) must be pinned to
        // a specific SITE *and* the LATENCY that tripped it, so the log alone shows
        // whether the timeout was a genuine dead link or a slow-but-alive host that
        // over-ran its bound. Diagnostics-only: elapsed is RECORDED, never used in
        // any close/abandon decision.
        val elapsedMs = (nowNanos() - startNanos) / 1_000_000L

        // The teardown used to be invisible; the abandonment must not be.
        // Recorded BEFORE we return so the breadcrumb exists even if the caller
        // swallows the null into a degraded/empty result (all five do).
        ReconnectCauseTrail.record(
            stage = TRAIL_STAGE,
            outcome = TRAIL_OUTCOME_ABANDONED,
            cause = "exec_no_result_within_bound",
            "callerSite" to callerSite,
            "timeoutMs" to timeoutMs,
            // Issue #1683 — the INPUT behind the abandonment: the site's measured
            // latency at the moment we gave up. `timeoutMs` is the bound; `elapsedMs`
            // is what actually elapsed (≥ the bound), so the two together attribute
            // the close to "site X over-ran its Nms budget after Mms".
            "elapsedMs" to elapsedMs,
            // Proof, in the trail itself, that we walked away from a transport
            // that was still ALIVE — the exact fact the silent close destroyed.
            "transportAlive" to session.isConnected,
            "transportClosed" to false,
        )

        // Issue #1693 — the #780-model synthetic self-inflicted close. In
        // production `onTimeoutSyntheticActionsForTest` is EMPTY, so this is a
        // no-op and the abandon above is the whole story (real #1641 semantics).
        // The #1693 reproduction arms it ONLY for `agent_kind_classify`, so on a
        // RED run the shared `-CC` lease is force-killed here — reproducing the
        // pre-#1641 storm deterministically — while every other caller site is
        // untouched. Fires AFTER the breadcrumb so a GREEN (disarmed) run still
        // records the abandonment the attribution assertions read.
        onTimeoutSyntheticActionsForTest[callerSite]?.invoke(session)
        null
    }
}
