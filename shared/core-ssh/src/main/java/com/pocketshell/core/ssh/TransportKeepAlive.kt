package com.pocketshell.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Issue #945 — the SAFE, dispatcher-serialized SSH transport keepalive (the
 * real "stays up like Terminus" fix).
 *
 * ## Why this exists (and why it is NOT sshj's keepalive)
 *
 * Terminus / OpenSSH survive a flaky Wi-Fi link because they run an always-on,
 * transport-level `ServerAliveInterval` ping over the encrypted channel: idle
 * gaps are absorbed, a real dead peer is detected on a conservative budget, and
 * — crucially — it is a SINGLE packet writer with no app contention. PocketShell
 * had NO such signal: #847 removed sshj's `KeepAliveProvider` because that
 * provider runs an **un-ownable `sshj-KeepAliveRunner` background thread** — a
 * SECOND writer on the live transport that could write `keepalive@openssh.com`
 * straight into a KEX/rekey window and desync the encoder sequence counter, so
 * the server logged `ssh_dispatch_run_fatal: ... Connection corrupted` ~one
 * keepalive interval after the handshake. The single-transport-writer rule
 * ([TransportDispatcher]) cannot tolerate an un-ownable background writer.
 *
 * The structural cause of #847 is exactly what [TransportDispatcher] eliminates:
 * every transport-touching op funnels through it and runs strictly one-at-a-time
 * in submission order, and no op is dispatched once teardown is enqueued. A
 * keepalive sent THROUGH the dispatcher ([SshSession.sendKeepAlive]) is just
 * another FIFO-serialized op — it cannot overlap a channel open, cannot
 * interleave a `-CC` write, cannot race a rekey/`die()`. So we own the keepalive
 * ourselves, serialized through the dispatcher; we do **NOT** re-enable sshj's
 * `KeepAliveRunner` (that would reopen #847 — [KeepAliveConfigTest] keeps that
 * door shut).
 *
 * ## Belt-and-suspenders with the #927 LivenessProbe
 *
 * This keepalive is the cheap, always-running, uncontended transport signal: it
 * keeps the encrypted channel warm and detects a transport-dead peer on a
 * ~90s budget (interval × countMax). The app-level
 * `com.pocketshell.core.connection.LivenessProbe` stays as the half-open
 * detector for the specific tmux-control-channel-wedged case the transport
 * keepalive cannot see. Together they close the Terminus gap; neither alone does.
 *
 * ## Determinism (the test seam)
 *
 * The loop's cadence is driven entirely by [delay] on the [CoroutineScope]'s
 * dispatcher, so a `TestScope` virtual clock advances the keepalive window with
 * zero wall-clock waiting (the same shape as [com.pocketshell.core.connection.LivenessProbe]).
 * The [intervalMs], [countMax], and the [KeepAliveIo] adapter are all injected so a
 * connected/Docker proof can shorten the window and flip a synthetic
 * dead/transient-gap state WITHOUT weakening any assertion or self-skipping.
 *
 * No `android.*`, no real IO inside this class: the actual ping + the dead-peer
 * reaction are the [KeepAliveIo] adapter. This stays a pure, deterministically
 * testable loop.
 */
internal class TransportKeepAlive(
    private val io: KeepAliveIo,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val countMax: Int = DEFAULT_COUNT_MAX,
    /**
     * Issue #1080 — the "now" clock the reset-on-activity shortcut compares the
     * [KeepAliveIo] activity watermarks against. It MUST be the SAME clock domain
     * the session stamps [KeepAliveIo.lastInboundActivityNanos] /
     * [KeepAliveIo.lastOutboundActivityNanos] with, or the
     * `now() - lastActivity` elapsed math mixes domains and is garbage.
     * Production [RealSshSession] stamps those watermarks with
     * `SystemClock.elapsedRealtimeNanos()` (CLOCK_BOOTTIME, which COUNTS deep
     * sleep) and passes the same clock here. Default is [System.nanoTime] so the
     * pure-JVM unit tests (which run against the android.jar stub where
     * `SystemClock` throws "not mocked") keep working — the [FakeIo] in those
     * tests supplies its watermarks in whatever domain matches this default.
     */
    private val now: () -> Long = { System.nanoTime() },
    private val log: (String) -> Unit = {},
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(countMax >= 1) { "countMax must be >= 1" }
    }

    /**
     * The narrow capability the session supplies. None of these performs
     * connection-lifecycle decisions beyond the final dead-peer reaction — they
     * only gate, observe inbound activity, send the ping, and react to a
     * confirmed sustained miss.
     */
    interface KeepAliveIo {
        /**
         * True iff the transport is alive and the keepalive loop should keep
         * running. Re-evaluated before every tick so a closed/disconnected
         * session immediately stops the loop (the loop ends itself, no ping).
         */
        fun isAlive(): Boolean

        /**
         * Nanos (in the loop's [now] clock domain — production uses the wall-
         * elapsed `SystemClock.elapsedRealtimeNanos()`, issue #1080) of the most
         * recent inbound
         * transport activity the session has observed — a keepalive reply, an
         * exec/tail round-trip, any bytes from the server. The loop SKIPS the
         * explicit ping when this is within [intervalMs] of now, so a busy link
         * is self-evidently alive and we never ping a channel that just answered
         * (OpenSSH's reset-on-server-traffic behaviour).
         */
        fun lastInboundActivityNanos(): Long

        /**
         * Issue #1072 — nanos (in the loop's [now] clock domain — production uses
         * the wall-elapsed `SystemClock.elapsedRealtimeNanos()`, issue #1080) of
         * the most
         * recent OUTBOUND payload progress the session has made: a steadily
         * advancing file/attachment upload pushing client→server bytes.
         *
         * Why outbound counts as proof of life: an upload's `output.write(...)`
         * only keeps returning while the kernel send buffer drains, which on a
         * sane TCP window means the PEER is still ACKing our segments — so RECENT
         * outbound progress proves the transport is reachable just as inbound
         * bytes do. A large/slow attachment over a QUIET `-CC` session is almost
         * pure outbound (`cat > tmp` emits nothing until EOF), so WITHOUT this
         * signal the loop saw ZERO inbound for the whole upload and tore the live
         * transport down mid-upload — the maintainer's "attaching breaks the
         * connection" (#1072). The loop folds this into the SAME reset-on-activity
         * shortcut and ride-through death decision as inbound.
         *
         * Crucially this is RECENT outbound PROGRESS, not "an upload was started":
         * a genuinely dead/half-open peer stalls the writes once the send buffer
         * fills, so this timestamp stops advancing and ages out of the ride-through
         * window — a truly dead transport (no inbound AND no outbound progress) is
         * STILL declared dead within the budget. Default returns [Long.MIN_VALUE]
         * ("no recent outbound") so a fake/test that does not override it behaves
         * exactly as before (inbound-only).
         */
        fun lastOutboundActivityNanos(): Long = Long.MIN_VALUE

        /**
         * Send ONE `keepalive@openssh.com` global request through the transport
         * dispatcher and await the reply. Returns `true` on ANY reply (including
         * the mandatory `SSH_MSG_REQUEST_FAILURE` for an unknown request type,
         * which still proves the peer is alive), `false` on a transport
         * error / timeout / no reply. MUST route through `dispatcher.run` so it
         * is FIFO-serialized — never a raw background write.
         */
        suspend fun sendKeepAlive(): Boolean

        /**
         * [countMax] consecutive keepalive misses confirmed — the transport is
         * dead. The production body closes the dead client so the EXISTING
         * recovery machinery (reader EOF -> the single reconnect entrypoint)
         * surfaces the indicator and recovers. NEVER a second reconnect writer.
         */
        fun onKeepAliveDead(consecutiveMisses: Int)
    }

    private var job: Job? = null
    private var consecutiveMisses: Int = 0

    /**
     * Issue #928 (T1a) — the inbound-activity watermark produced by the loop's
     * OWN most recent successful keepalive reply (snapshotted right after
     * [sendOne] returns true, when the reply has bumped
     * [KeepAliveIo.lastInboundActivityNanos]). The reset-on-activity skip must
     * key off REAL user/agent traffic only: if the watermark still equals this
     * snapshot at the next tick, the only "recent inbound" is our own ping's
     * reply — the link is genuinely idle and MUST be pinged on cadence, never
     * skipped. Any newer watermark is real traffic (or a late reply after a
     * miss — the #1059 proof-of-life, which must keep counting). The check errs
     * toward pinging: a real byte racing the snapshot to the same nanosecond
     * costs one extra harmless ping, never a suppressed one.
     */
    private var lastOwnKeepAliveReplyActivityNanos: Long = Long.MIN_VALUE

    /**
     * Issue #1059 (R2) — the inbound-activity watermark captured at the START of the
     * current miss streak. If it ADVANCES before the streak would declare the peer
     * dead, a late keepalive reply (or any server byte) landed mid-streak — the link
     * is slow-but-alive (idle high-RTT / bufferbloat cellular) and must NOT be
     * redialed. A genuinely half-open dead peer can never advance this timestamp, so
     * keying the death decision off a real inbound advance (not the raw per-reply-
     * budget miss count) bounds the false positive WITHOUT weakening the #945
     * truly-silent-peer detection.
     */
    private var missStreakStartActivityNanos: Long = 0L

    /**
     * Start the keepalive loop in [scope]. Idempotent: a second [start] while a
     * loop is active is a no-op. Each tick — only if [KeepAliveIo.isAlive] and
     * there was no recent REAL inbound/outbound activity — sends one keepalive
     * through the dispatcher, counting consecutive misses; on [countMax] it
     * fires [KeepAliveIo.onKeepAliveDead] ONCE and stops counting (the recovery
     * path now owns the transport).
     *
     * ## Issue #928 (T1a) — true-cadence anchoring (no tick-grid aliasing)
     *
     * The pre-#928 loop slept a FULL [intervalMs] from every tick, including a
     * tick that SKIPPED its ping because real activity landed mid-sleep. A
     * server byte arriving just after a tick was therefore next protected by a
     * keep-warm ping only ~2×interval later, and a peer dying right after it was
     * declared dead only at ~(countMax+1)×interval — so any carrier NAT whose
     * idle window sits in the (interval, 2×interval) band reaped the idle
     * mapping BETWEEN pings (the per-host idle flap: the busy host's own traffic
     * keeps its mapping warm, the idle host's does not). Two corrections:
     *
     *  1. the skip path anchors the NEXT wake at `activity + intervalMs` (sleeps
     *     only the remainder), so an idle-going link is pinged within one
     *     interval of its LAST real activity regardless of tick phase; and
     *  2. the loop's OWN reply is excluded from the skip decision
     *     ([lastOwnKeepAliveReplyActivityNanos]), so a fully idle link holds the
     *     true `ServerAliveInterval`-style cadence — the ping's reply can never
     *     suppress the next scheduled ping.
     *
     * The busy-link intent is unchanged: REAL inbound/outbound within the last
     * interval still skips the ping (OpenSSH reset-on-server-traffic), and the
     * #1059/#1072 late-reply/upload ride-through still keys off the watermark
     * advancing across a miss streak.
     *
     * ## Issue #1059 (R2) — idle high-RTT / bufferbloat false-positive bound
     *
     * Reset-on-inbound only credits inbound seen within the LAST interval. On an
     * idle `-CC` link with bufferbloat pushing the keepalive round-trip past the
     * 5s per-reply budget, the per-tick send reports a "miss" each tick even though
     * the reply DOES land (late) — so the raw `countMax` consecutive-miss count
     * could declare a live link dead and redial it. The death decision therefore
     * does NOT fire on the miss count alone: before declaring dead it confirms that
     * the inbound-activity watermark did NOT advance across the whole streak
     * ([missStreakStartActivityNanos]). A half-open DEAD peer produces zero inbound
     * and can never advance it, so a genuinely silent peer is STILL declared dead
     * within `countMax × interval` (~90s) — the #945 contract is preserved exactly,
     * while a slow-but-answering link rides through.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        consecutiveMisses = 0
        missStreakStartActivityNanos = 0L
        lastOwnKeepAliveReplyActivityNanos = Long.MIN_VALUE
        job = scope.launch {
            var sleepMs = intervalMs
            while (isActive) {
                delay(sleepMs)
                sleepMs = intervalMs
                if (!io.isAlive()) {
                    // Transport gone (closed/disconnected): end the loop. The
                    // recovery machinery owns it now.
                    consecutiveMisses = 0
                    break
                }
                // Reset-on-activity (OpenSSH semantics, extended for outbound in
                // #1072): a link that produced server bytes — OR made outbound
                // upload progress — within the last interval is self-evidently
                // alive, so skip the explicit ping and reset the miss counter.
                // This is why a heavy `%output` burst is POSITIVE proof of life,
                // not a missed probe; and why a steadily-streaming attachment
                // upload (pure outbound) must NOT be torn down mid-upload (#1072).
                // Issue #928 (T1a): our OWN reply is NOT such activity — a fully
                // idle link must hold the true cadence, never skip off its own
                // ping's echo.
                val activityNanos = io.lastActivityNanos()
                val sinceActivityNanos = now() - activityNanos
                val ownReplyOnly = activityNanos == lastOwnKeepAliveReplyActivityNanos
                if (!ownReplyOnly && sinceActivityNanos in 0 until intervalNanos()) {
                    if (consecutiveMisses != 0) {
                        log("keepalive: inbound activity, reset after $consecutiveMisses miss(es)")
                    }
                    consecutiveMisses = 0
                    // Issue #928 (T1a): anchor the NEXT ping one interval after
                    // the REAL activity (sleep only the remainder). Re-sleeping a
                    // full interval from the tick grid let the wire-idle gap
                    // reach ~2×interval — the aliasing that reaped idle carrier-
                    // NAT mappings between pings.
                    sleepMs = remainingMsUntil(activityNanos + intervalNanos())
                    continue
                }
                val alive = sendOne()
                if (alive) {
                    // Issue #928 (T1a): snapshot the watermark our own reply just
                    // bumped so it can never masquerade as real inbound at the
                    // next tick's skip decision.
                    lastOwnKeepAliveReplyActivityNanos = io.lastActivityNanos()
                    if (consecutiveMisses != 0) {
                        log("keepalive: recovered after $consecutiveMisses miss(es)")
                    }
                    consecutiveMisses = 0
                } else {
                    if (consecutiveMisses == 0) {
                        // Issue #1059 — snapshot the inbound-activity watermark at the
                        // START of this miss streak. A slow-but-alive idle/bufferbloat
                        // link answers LATE: its keepalive reply (or any server byte)
                        // lands after the 5s per-reply budget — so the per-tick send
                        // reports a "miss" — but well within the ~90s death budget. That
                        // late inbound advances this watermark; a half-open DEAD peer
                        // never can. #1072: outbound upload progress advances it too,
                        // so a steadily-progressing upload rides through the streak.
                        missStreakStartActivityNanos = io.lastActivityNanos()
                    }
                    consecutiveMisses += 1
                    log("keepalive: miss consecutive=$consecutiveMisses countMax=$countMax")
                    if (consecutiveMisses >= countMax) {
                        // Re-check liveness before acting: a teardown could have
                        // raced in during the send, in which case the existing
                        // close path already owns recovery and an extra reaction
                        // would be a spurious teardown.
                        if (io.isAlive()) {
                            if (io.lastActivityNanos() != missStreakStartActivityNanos) {
                                // Issue #1059 (R2) / #1072 — slow-but-alive: inbound
                                // activity OR outbound upload progress advanced during
                                // the streak, so the peer DID answer (late) or we are
                                // still actively pushing bytes it is ACKing. A genuinely
                                // half-open dead peer can never move this timestamp, so
                                // this can only be a real round-trip on a high-RTT/
                                // bufferbloat idle link, or a progressing upload — ride
                                // it through
                                // instead of redialing a live link (the #945 contract is
                                // preserved precisely because we only ride through on
                                // PROVEN inbound).
                                log(
                                    "keepalive: $consecutiveMisses misses but inbound activity " +
                                        "advanced during the streak — slow-but-alive idle " +
                                        "high-RTT/bufferbloat link, not declaring dead",
                                )
                            } else {
                                // Sustained silence across the full death budget (no
                                // inbound at all) — a genuine half-open dead peer.
                                log("keepalive: DECLARED DEAD consecutive=$consecutiveMisses")
                                io.onKeepAliveDead(consecutiveMisses)
                            }
                        }
                        // The recovery path now owns the transport; reset so we do
                        // not re-fire every interval against the dead/reconnecting
                        // session.
                        consecutiveMisses = 0
                    }
                }
            }
        }
    }

    /** Stop the keepalive loop. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        consecutiveMisses = 0
    }

    private fun intervalNanos(): Long = intervalMs * 1_000_000L

    /**
     * Issue #928 (T1a) — milliseconds (ceil, ≥1) until [deadlineNanos] on the
     * loop's [now] clock. Ceil so the wake never lands before the deadline in
     * this clock domain; the ≥1 floor keeps a raced/past deadline from becoming
     * a hot `delay(0)` spin — the next tick then pings promptly.
     */
    private fun remainingMsUntil(deadlineNanos: Long): Long {
        val remainingNanos = deadlineNanos - now()
        if (remainingNanos <= 0L) return 1L
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    /**
     * Issue #1072 — the single "most recent proof of life" timestamp the loop
     * keys every reset/ride-through decision off: the LATER of the last inbound
     * server byte and the last outbound upload progress. Either one proves the
     * transport is still carrying our traffic. The default
     * [KeepAliveIo.lastOutboundActivityNanos] of [Long.MIN_VALUE] makes this fall
     * back to inbound-only for fakes/tests that do not stream outbound, so the
     * pre-#1072 behaviour is preserved exactly when there is no upload in flight.
     */
    private fun KeepAliveIo.lastActivityNanos(): Long =
        maxOf(lastInboundActivityNanos(), lastOutboundActivityNanos())

    private suspend fun sendOne(): Boolean =
        try {
            io.sendKeepAlive()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A keepalive that throws (transport gone, dispatcher closed) is a
            // miss, not a crash — count it like a no-reply.
            log("keepalive: send threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }

    companion object {
        /**
         * ### #945 detection budget — the load-bearing arithmetic
         *
         * Each tick is `delay(intervalMs)` then, when idle, ONE keepalive bounded
         * by the dispatcher's own per-op ceiling (the send returns `false` rather
         * than hanging). So the WORST-CASE time to declare a genuinely dead
         * transport is:
         *
         *     worstCase ≈ countMax × intervalMs = 3 × 30s = 90s
         *
         * 90s matches a conservative OpenSSH/Terminus client
         * (`ServerAliveInterval 30 ServerAliveCountMax 3`). This is the always-on
         * transport backstop; the foreground app-level
         * [com.pocketshell.core.connection.LivenessProbe] (48s budget) detects the
         * narrower tmux-control-channel-wedged case faster. The two are
         * complementary (belt-and-suspenders, #927 + #945).
         *
         * A transient gap SHORTER than this budget is RIDDEN THROUGH — the miss
         * counter has not yet reached [countMax] when the link recovers and the
         * next keepalive (or any inbound byte) resets it — exactly the Terminus
         * "absorb the blip" behaviour PocketShell lacked.
         *
         * Issue #928 (T1a) made this arithmetic TRUE for every tick phase: the
         * anchored cadence pings within one interval of the last real activity,
         * so the worst case is genuinely countMax × interval — not the aliased
         * (countMax+1) × interval the fixed tick grid allowed.
         */

        /**
         * Keepalive interval. 30s matches OpenSSH `ServerAliveInterval 30`: a
         * single tiny global request per 30s of IDLE link is negligible traffic,
         * and reset-on-inbound means a busy link sends none at all.
         */
        const val DEFAULT_INTERVAL_MS: Long = 30_000L

        /**
         * Consecutive keepalive misses before the transport is declared dead.
         * 3 matches OpenSSH `ServerAliveCountMax 3`: up to TWO consecutive missed
         * pings on a flaky-but-alive link are absorbed (the counter resets the
         * moment one answers or any inbound byte arrives), so only a sustained
         * ~90s silence trips a drop. This is the tolerance that lets a transient
         * Wi-Fi gap ride through where PocketShell used to redial.
         */
        const val DEFAULT_COUNT_MAX: Int = 3

        /**
         * ### #964 ride-through budget — the single coherent liveness window
         *
         * The worst-case time this keepalive needs to RIDE THROUGH a transient
         * gap before it gives up and declares the transport dead:
         *
         *     rideThrough = countMax × intervalMs = 3 × 30s = 90s
         *
         * This is the budget the app-level
         * [com.pocketshell.core.connection.LivenessProbe] DEFERS to (#964): while
         * the transport has produced inbound activity within this window the
         * keepalive is still successfully proving the link alive, so the probe
         * must NOT force a redial — the two liveness mechanisms now share ONE
         * coherent budget instead of the probe's old ~48s racing ahead of this
         * ~90s and spuriously redialing a slow-but-live link.
         *
         * Kept derived from [DEFAULT_INTERVAL_MS] × [DEFAULT_COUNT_MAX] so a
         * future keepalive retune moves the probe's deferral window in lockstep
         * (no second hard-coded number to drift). [KeepAliveBudgetCoherenceTest]
         * pins this against the probe's own budget so they can never re-diverge.
         */
        const val RIDE_THROUGH_BUDGET_MS: Long = DEFAULT_INTERVAL_MS * DEFAULT_COUNT_MAX
    }
}
