package com.pocketshell.core.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * EPIC #792 Slice D — the proactive mid-session drop detector (the headline
 * #822 fix, audit §3 / requirement V7a).
 *
 * The connection core can DECIDE a drop's lifecycle (`ConnectionController`
 * walks `Live --TransportDropped--> Reattaching --> Reconnecting(n) -->
 * Unreachable`), but on a QUIET, idle control channel nothing was telling it a
 * silent half-open Wi-Fi drop had happened: there is NO SSH transport keepalive
 * backstop (it was removed in #847 — see [com.pocketshell.core.ssh.SshConnection]
 * — because a background keepalive writer is a SECOND transport writer that
 * corrupts the KEX sequence), so `sshj.isConnected` lies indefinitely on a
 * half-open socket, and `TmuxClient.disconnected` only flips on a reader EOF or a
 * watchdog-covered DISPATCHED command. With the user reading or recording a voice
 * note (the exact #822 scenario), no command is in flight, so the dead channel is
 * invisible — the "detection void". **This probe is therefore the SOLE dead-peer
 * detector on a foregrounded session — its budget IS the entire detection
 * guarantee, not a fast-path in front of a transport keepalive (there is none).**
 *
 * [LivenessProbe] closes that void. While the session is FOREGROUNDED + `Live`
 * (and only then — D21: no background work, no probe inside grace, no probe
 * while already reattaching/reconnecting), it periodically pings the active
 * control channel via the injected [ProbeIo]. On a sustained, repeated probe
 * failure it fires [ProbeIo.onProbeFailed], whose production body closes the dead
 * client so the EXISTING tested recovery machinery surfaces the connection-lost
 * indicator AND drives the single reconnect entrypoint (Slice C
 * `TransportEffects`) — no second writer.
 *
 * ## No false positives (the cardinal risk)
 * A too-eager probe that declared a HEALTHY-but-slow/busy channel dead would be
 * a WORSE regression than the original silent drop (a spurious "connection lost"
 * + reconnect mid-work). Three guards make a false positive structurally
 * unlikely:
 *
 *  1. **Gate** ([ProbeIo.shouldProbe]): the loop probes ONLY when the session is
 *     foregrounded AND `Live`. A backgrounded app, an in-grace detach, an
 *     in-flight attach/reconnect, or a non-current client all return `false`, so
 *     the probe never competes with a reconnect or trips while backgrounded.
 *  2. **Generous per-probe timeout** ([perProbeTimeoutMs], default 5s): a single
 *     probe round-trip is given far more than a healthy RTT (a `refresh-client`
 *     round-trip is sub-second even on a congested link). On a BUSY channel (a
 *     heavy `%output` burst) the probe both waits behind the control-mode FIFO
 *     AND — crucially — the busy-vs-dead guard in
 *     [com.pocketshell.core.tmux.TmuxClient.probeLiveness] reports ALIVE off
 *     recent reader activity even if the reply is parked, so the timeout only
 *     bites a channel that is genuinely silent.
 *  3. **N consecutive failures** ([failureThreshold], default 4): a SINGLE slow
 *     probe (a momentary stall, a long burst that outlasts one timeout) does NOT
 *     declare the channel dead — the counter resets on the next success. Only
 *     [failureThreshold] probes in a row trip the drop, so up to THREE consecutive
 *     missed probes on a flaky-but-alive link are absorbed (the #927 fix — a
 *     conservative SSH client's `ServerAliveCountMax` of 3–6). See the companion
 *     constants for the exact worst-case detection budget and why it must stay
 *     well under the three coupled 60s windows (lease idle TTL, passive grace,
 *     controller grace).
 *
 * ## Determinism (the test seam)
 * The loop's cadence is driven entirely by [delay] on the [CoroutineScope]'s
 * dispatcher, so a `TestScope` virtual clock advances the probe window with zero
 * wall-clock waiting (the analogue of `BackgroundGraceTestOverride`). The
 * [intervalMs], [perProbeTimeoutMs], and [failureThreshold] are all constructor
 * parameters so a connected/emulator proof can shorten the window WITHOUT
 * weakening any assertion or self-skipping — a synthetic-drop seam can flip
 * [ProbeIo.probe] to "dead" and the loop fires the drop deterministically within
 * the shortened window.
 *
 * No `android.*`, no real IO inside this class: the actual ping + close are the
 * VM's [ProbeIo] adapter. This stays a pure, deterministically-testable loop.
 */
class LivenessProbe(
    private val io: ProbeIo,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val perProbeTimeoutMs: Long = DEFAULT_PER_PROBE_TIMEOUT_MS,
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val log: (String) -> Unit = {},
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(perProbeTimeoutMs > 0) { "perProbeTimeoutMs must be > 0" }
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
    }

    /**
     * The narrow capability the VM supplies. All three methods are consulted
     * from the probe loop; none performs connection-lifecycle decisions (that is
     * the controller's job) — they only gate, ping, and react to a confirmed
     * sustained failure.
     */
    interface ProbeIo {
        /**
         * True iff the session is FOREGROUNDED + `Live` on the CURRENT control
         * client — the only state in which an active probe is allowed (D21 +
         * no-false-positive guard 1). Re-evaluated before EVERY probe so a
         * background / reconnect transition immediately stops probing.
         */
        fun shouldProbe(): Boolean

        /**
         * Ping the active control channel. Return `true` if the channel
         * answered (alive), `false` if it did not. MUST NOT itself tear the
         * channel down or block longer than the probe loop's timeout governs —
         * the loop wraps this in [perProbeTimeoutMs] and treats a timeout or a
         * thrown error as a single failure.
         */
        suspend fun probe(): Boolean

        /**
         * A sustained drop was confirmed ([failureThreshold] consecutive probe
         * failures). The production body closes the dead client so the existing
         * recovery machinery (controller `TransportDropped` + the single
         * `TransportEffects` reconnect entrypoint) surfaces the indicator and
         * recovers — NEVER a second reconnect writer.
         */
        fun onProbeFailed(consecutiveFailures: Int)
    }

    private var job: Job? = null
    private var consecutiveFailures: Int = 0

    /**
     * Start the probe loop in [scope]. Idempotent: a second [start] while a loop
     * is active is a no-op. The loop sleeps [intervalMs], then — only if
     * [ProbeIo.shouldProbe] — runs one probe under [perProbeTimeoutMs], counting
     * consecutive failures; on [failureThreshold] it fires [ProbeIo.onProbeFailed]
     * ONCE and stops counting (the recovery path now owns the channel) until a
     * later success resets it.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        consecutiveFailures = 0
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!io.shouldProbe()) {
                    // Not foregrounded+Live (or not the current client): reset
                    // the counter so a prior partial run never leaks into the
                    // next live window, and skip this tick entirely.
                    consecutiveFailures = 0
                    continue
                }
                val alive = runProbe()
                if (alive) {
                    if (consecutiveFailures != 0) {
                        log("liveness-probe recovered after $consecutiveFailures failure(s)")
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures += 1
                    log(
                        "liveness-probe failed consecutive=$consecutiveFailures " +
                            "threshold=$failureThreshold",
                    )
                    if (consecutiveFailures >= failureThreshold) {
                        // Re-check the gate immediately before acting: a
                        // background/reconnect could have raced in during the
                        // probe's timeout window, in which case the single grace
                        // owner / reconnect ladder already governs recovery and
                        // an extra close here would be a spurious teardown.
                        if (io.shouldProbe()) {
                            log("liveness-probe DECLARED DROP consecutive=$consecutiveFailures")
                            io.onProbeFailed(consecutiveFailures)
                        }
                        // The recovery path now owns the channel; reset so the
                        // probe does not re-fire every interval against the
                        // already-reconnecting session.
                        consecutiveFailures = 0
                    }
                }
            }
        }
    }

    /** Stop the probe loop. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        consecutiveFailures = 0
    }

    private suspend fun runProbe(): Boolean =
        try {
            withTimeoutOrNull(perProbeTimeoutMs) { io.probe() } ?: false
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A probe that throws (transport gone, client closed) is a failure,
            // not a crash — count it like a timeout.
            log("liveness-probe threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }

    companion object {
        /**
         * ### #927 detection budget — the load-bearing arithmetic
         *
         * The loop is `delay(intervalMs)` then ONE probe bounded by
         * [perProbeTimeoutMs], per iteration (see [start]). So the WORST-CASE time
         * to declare a genuine silent half-open drop is:
         *
         *     worstCase = failureThreshold × (intervalMs + perProbeTimeoutMs)
         *               = 4 × (7s + 5s) = 48s
         *
         * This probe is the SOLE dead-peer detector (there is NO 60s SSH keepalive
         * backstop — #847 removed it; the old "below the 60s keep-alive" reasoning
         * was vacuous). The budget therefore must land with a real margin BELOW the
         * three coupled 60s windows it would otherwise race:
         *   - lease idle TTL (`SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS = 60_000`),
         *   - passive disconnect grace (`PASSIVE_DISCONNECT_GRACE_MS = 60_000`),
         *   - controller grace (`ConnectionController.DEFAULT_GRACE_MS = 60_000`).
         *
         * 48s is **under the D3-mandated hard ceiling of < 55s** with a ~12s margin
         * below the 60s floor. The `< 50_000` ceiling is enforced by
         * `LivenessProbeBudgetUnder55sTest` so a future bump that brushes the floor
         * fails at PR time. The tolerance for a flaky-but-alive link comes from the
         * threshold ([DEFAULT_FAILURE_THRESHOLD]) AND the busy-vs-dead reader-
         * activity guard ([com.pocketshell.core.tmux.TmuxClient.probeLiveness]) —
         * NOT from inflating the raw budget toward the floor.
         */

        /**
         * How often the probe fires while foregrounded + `Live`. 7s keeps
         * detection proactive and, paired with [DEFAULT_PER_PROBE_TIMEOUT_MS] and
         * [DEFAULT_FAILURE_THRESHOLD], lands the worst-case budget (see above) at
         * 48s — under the < 55s ceiling. A single `refresh-client` round-trip per
         * 7s is negligible control-channel traffic.
         */
        const val DEFAULT_INTERVAL_MS: Long = 7_000L

        /**
         * Per-probe response budget. Generous (5s) so a busy / momentarily-slow
         * but HEALTHY channel answers within it rather than false-positiving; a
         * healthy `refresh-client` round-trip is sub-second even on a congested
         * home/train link, so 5s is comfortably above a real RTT while keeping each
         * missed-probe iteration short enough to land the 48s budget below the
         * 60s floor (#927). The busy-vs-dead guard
         * ([com.pocketshell.core.tmux.TmuxClient.probeLiveness]) absorbs a reply
         * parked behind a legitimate `%output` burst, so this timeout only ever
         * bites a genuinely silent channel.
         */
        const val DEFAULT_PER_PROBE_TIMEOUT_MS: Long = 5_000L

        /**
         * Consecutive probe failures before a drop is declared.
         *
         * #927: raised 2 → 4 so a flaky-but-alive link survives. The previous
         * value of 2 declared a drop — and force-redialed the warm lease (the
         * visible "restart") — after only TWO back-to-back missed `refresh-client`
         * probes (~16–26s of imperfect connectivity). With 4, up to THREE
         * consecutive slow/missed probes are absorbed — the counter resets the
         * moment one succeeds — so only sustained silence trips a drop (a
         * conservative SSH client's `ServerAliveCountMax` of 3–6).
         *
         * Worst-case dead-peer detection = `threshold × (interval + per-probe
         * timeout)` = `4 × (7s + 5s)` = **48s** — under the D3 < 55s ceiling with a
         * ~12s margin below the three coupled 60s windows (#822 not regressed),
         * while no longer false-positiving on a flaky-but-alive link. The tolerance
         * is raised WITHOUT inflating the raw budget toward the floor because the
         * busy-vs-dead reader-activity guard handles `%output`-burst parking.
         */
        const val DEFAULT_FAILURE_THRESHOLD: Int = 4
    }
}
