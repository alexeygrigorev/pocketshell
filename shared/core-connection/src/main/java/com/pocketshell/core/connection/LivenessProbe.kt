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
 * silent half-open Wi-Fi drop had happened. The tmux control channel can wedge
 * (the `-CC` FIFO stops answering `refresh-client`) even while the underlying
 * transport is fine, and `TmuxClient.disconnected` only flips on a reader EOF or a
 * watchdog-covered DISPATCHED command. With the user reading or recording a voice
 * note (the exact #822 scenario), no command is in flight, so a wedged channel is
 * invisible — the "detection void". This probe closes that void.
 *
 * **Coordinated with the transport keepalive (#964), WITHOUT regressing #822.**
 * There IS now an always-on SSH transport keepalive
 * ([com.pocketshell.core.ssh.TransportKeepAlive], #945 — the safe successor to the
 * #847-removed sshj `KeepAliveRunner`). It pings the SSH TRANSPORT (a
 * global-request, ~90s budget); this probe pings the tmux `-CC` CHANNEL
 * (refresh-client/%output, ~48s budget). They are DIFFERENT channels. The #964
 * bug: on a live-but-slow link the `-CC` probe declared dead at ~48s and
 * force-redialed a FINE link before the keepalive's ~90s could prove the transport
 * alive.
 *
 * The fix THREADS THE NEEDLE rather than a blanket keepalive veto. A successful
 * keepalive ([ProbeIo.transportProvenAliveRecently]) lets the probe DEFER its
 * redial for up to [maxKeepAliveDeferrals] back-to-back failure runs — absorbing
 * the slow-but-live `-CC` blip (#964) — BUT if the `-CC` channel keeps not
 * answering across that bound WHILE the keepalive stays healthy, that is no longer
 * transport jitter: it is a genuinely WEDGED `-CC` channel on a live transport
 * (the #822 failure mode the keepalive CANNOT see — transport fine, control
 * channel stuck). The probe then ESCALATES the drop anyway, so the wedged session
 * still recovers within a sane bound (≈ `(maxKeepAliveDeferrals + 1) × rawBudget`).
 * A blanket "keepalive alive ⇒ never redial" would re-open #822. A single
 * successful `-CC` probe resets the deferral run, so a link that un-congests never
 * escalates. The probe's raw budget still bounds detection of a `-CC` wedge
 * whenever no keepalive signal is available to defer to.
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
    private val maxKeepAliveDeferrals: Int = DEFAULT_MAX_KEEPALIVE_DEFERRALS,
    private val log: (String) -> Unit = {},
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(perProbeTimeoutMs > 0) { "perProbeTimeoutMs must be > 0" }
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
        require(maxKeepAliveDeferrals >= 1) { "maxKeepAliveDeferrals must be >= 1" }
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

        /**
         * Issue #964 — the keepalive-coordination guard. True iff the always-on
         * transport keepalive ([com.pocketshell.core.ssh.TransportKeepAlive], #945)
         * has observed INBOUND transport activity within its ride-through window
         * ([com.pocketshell.core.ssh.TransportKeepAlive.RIDE_THROUGH_BUDGET_MS],
         * ~90s) — i.e. the transport is PROVABLY still alive.
         *
         * Consulted immediately before [onProbeFailed] would fire. When it returns
         * `true` the probe DEFERS: it does NOT declare a drop, because the
         * keepalive is still successfully riding through and the link is fine — it
         * is only the tmux control channel that is momentarily slow. This is the
         * #964 fix: the probe's old ~48s budget used to force a redial BEFORE the
         * keepalive's ~90s ride-through could prove the link alive, spuriously
         * reconnecting a slow-but-live link. The probe now defers to the single
         * transport-liveness authority; only when the keepalive ITSELF stops
         * seeing activity (a genuinely dead transport, so this returns `false`)
         * does the probe declare the drop. The keepalive's own ~90s budget detects
         * a real transport death independently (it closes the dead transport), so
         * deferring here can never cause an infinite hang.
         *
         * Default body returns `false` (no keepalive signal available, so the
         * probe keeps its own authority) so the existing probe fakes need not
         * override it; only the production VM wires it to the live session's
         * keepalive liveness.
         */
        fun transportProvenAliveRecently(): Boolean = false
    }

    private var job: Job? = null
    private var consecutiveFailures: Int = 0

    /**
     * Issue #964 / #822 — how many times in a row the probe has reached its
     * failure threshold AND deferred to a still-healthy transport keepalive,
     * WITHOUT any intervening successful `-CC` probe. Reset to 0 on the next
     * successful `-CC` probe.
     *
     * This BOUNDS the deferral so it threads the needle between the two failure
     * modes the keepalive cannot tell apart from the transport's vantage:
     *   - a brief `-CC` non-response that coincides with transport jitter (the
     *     #964 slow-but-live case) clears within one or two deferrals as the link
     *     un-congests and the `-CC` probe answers again → no redial; and
     *   - a `-CC` channel that is genuinely WEDGED while the transport stays
     *     perfectly healthy (the #822 case: keepalive instant, `refresh-client`
     *     never answers) keeps failing across every deferral → after
     *     [maxKeepAliveDeferrals] back-to-back deferrals the probe ESCALATES the
     *     drop EVEN THOUGH the keepalive is alive, because sustained `-CC` silence
     *     on a provably-live transport IS the wedged-channel signal and must
     *     recover. A blanket "keepalive alive ⇒ never redial" would re-open #822.
     */
    private var consecutiveDeferrals: Int = 0

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
        consecutiveDeferrals = 0
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!io.shouldProbe()) {
                    // Not foregrounded+Live (or not the current client): reset
                    // the counter so a prior partial run never leaks into the
                    // next live window, and skip this tick entirely.
                    consecutiveFailures = 0
                    consecutiveDeferrals = 0
                    continue
                }
                val alive = runProbe()
                if (alive) {
                    if (consecutiveFailures != 0 || consecutiveDeferrals != 0) {
                        log(
                            "liveness-probe recovered after $consecutiveFailures failure(s) / " +
                                "$consecutiveDeferrals deferral(s)",
                        )
                    }
                    // A successful `-CC` probe clears BOTH the failure run AND the
                    // deferral run: the control channel is answering again, so the
                    // slow-but-live blip is over and we are nowhere near the #822
                    // wedge.
                    consecutiveFailures = 0
                    consecutiveDeferrals = 0
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
                        if (!io.shouldProbe()) {
                            // The recovery path / grace owner already governs the
                            // channel; reset and skip.
                            consecutiveFailures = 0
                            consecutiveDeferrals = 0
                        } else if (
                            io.transportProvenAliveRecently() &&
                            consecutiveDeferrals < maxKeepAliveDeferrals
                        ) {
                            // Issue #964 — DEFER to the transport keepalive, but only
                            // up to [maxKeepAliveDeferrals] back-to-back times. The
                            // `-CC` probe failed N times, but the always-on keepalive
                            // has seen inbound TRANSPORT activity within its
                            // ride-through window, so the LINK is provably reachable —
                            // the `-CC` reply is just parked behind a momentarily slow
                            // / congested channel. Forcing a redial here would be the
                            // exact spurious reconnect on a slow-but-live link the
                            // keepalive exists to ride through (#964). Reset the
                            // failure run (so we re-probe next interval) but COUNT the
                            // deferral: if the `-CC` channel keeps not answering across
                            // [maxKeepAliveDeferrals] deferrals WHILE the keepalive
                            // stays healthy, that is no longer transport jitter — it is
                            // a genuinely WEDGED `-CC` channel on a live transport
                            // (#822), and the branch below escalates it.
                            consecutiveDeferrals += 1
                            log(
                                "liveness-probe DEFERRED to keepalive (transport proven alive) " +
                                    "consecutive=$consecutiveFailures deferrals=$consecutiveDeferrals " +
                                    "max=$maxKeepAliveDeferrals",
                            )
                            consecutiveFailures = 0
                        } else {
                            // Declare the drop. Either the keepalive ALSO stopped
                            // proving the transport alive (a genuine transport death —
                            // the #964 deferral correctly ends), OR the keepalive is
                            // still healthy but the `-CC` channel has now failed across
                            // [maxKeepAliveDeferrals] back-to-back deferrals — the
                            // #822 wedged-`-CC`-on-a-live-transport case, which MUST
                            // recover even though the keepalive says the transport is
                            // fine (a blanket keepalive veto would re-open #822).
                            val wedgedDespiteKeepAlive = io.transportProvenAliveRecently()
                            log(
                                "liveness-probe DECLARED DROP consecutive=$consecutiveFailures " +
                                    "deferrals=$consecutiveDeferrals " +
                                    "wedgedDespiteKeepAlive=$wedgedDespiteKeepAlive",
                            )
                            io.onProbeFailed(consecutiveFailures)
                            // The recovery path now owns the channel; reset so the
                            // probe does not re-fire every interval against the
                            // already-reconnecting session.
                            consecutiveFailures = 0
                            consecutiveDeferrals = 0
                        }
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
        consecutiveDeferrals = 0
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
         * This 48s is the probe's RAW budget — the bound on detecting a tmux
         * control-channel wedge when no transport-keepalive liveness signal is
         * available to defer to (a test fake, or a transport that has itself gone
         * silent). When the always-on transport keepalive
         * ([com.pocketshell.core.ssh.TransportKeepAlive], #945) IS proving the link
         * alive, the probe DEFERS past this raw budget (the #964 coordination — see
         * [ProbeIo.transportProvenAliveRecently]) and lets the keepalive's ~90s
         * ride-through be the death authority, so it never force-redials a
         * slow-but-live link. The raw budget still lands with a real margin BELOW the
         * three coupled 60s windows it would otherwise race when it IS the acting
         * detector:
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

        /**
         * Issue #964 / #822 — how many back-to-back failure runs the probe defers
         * to a still-healthy transport keepalive before it ESCALATES the drop
         * anyway (the wedged-`-CC`-on-a-live-transport bound).
         *
         * Each failure run is [DEFAULT_FAILURE_THRESHOLD] consecutive missed `-CC`
         * probes ≈ the raw 48s budget. With [DEFAULT_MAX_KEEPALIVE_DEFERRALS] = 1
         * the probe RIDES THROUGH one full ~48s window of `-CC` silence while the
         * keepalive proves the transport alive (absorbing the #964 slow-but-live
         * blip), but if the `-CC` channel is STILL silent through a SECOND ~48s
         * window with the keepalive still healthy, it declares the drop — so a
         * genuinely WEDGED `-CC` channel on a live transport (#822) recovers within
         * ≈ 2 × 48s ≈ 96s, a sane bound, instead of hanging forever behind a
         * blanket keepalive veto. A single successful `-CC` probe in between resets
         * the deferral run, so a link that slowly un-congests never escalates.
         *
         * 1 is deliberately conservative: the slow-but-live link the maintainer
         * hits resolves well within one deferral (the `-CC` reply lands once the
         * bufferbloat clears), while a never-answering `-CC` channel is the wedge
         * that must recover. [LivenessProbeWedgeEscalationTest] pins both halves.
         */
        const val DEFAULT_MAX_KEEPALIVE_DEFERRALS: Int = 1
    }
}
