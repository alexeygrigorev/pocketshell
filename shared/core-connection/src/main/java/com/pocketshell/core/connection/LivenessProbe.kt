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
 * **Coordinated with the transport keepalive (#982/#984), WITHOUT regressing #822.**
 * There IS now an always-on SSH transport keepalive
 * ([com.pocketshell.core.ssh.TransportKeepAlive], #945 — the safe successor to the
 * #847-removed sshj `KeepAliveRunner`). It pings the SSH TRANSPORT (a
 * global-request, ~90s budget); this probe pings the tmux `-CC` CHANNEL
 * (refresh-client/%output, ~48s budget). They are DIFFERENT channels. The #964
 * bug: on a live-but-slow link the `-CC` probe declared dead at ~48s and
 * force-redialed a FINE link before the keepalive's ~90s could prove the transport
 * alive.
 *
 * The #982/#984 fix DEFERS TO THE KEEPALIVE'S OWN DEATH SIGNAL, with NO time
 * ceiling while the keepalive proves the transport alive. On [failureThreshold]
 * consecutive `-CC` misses the probe asks the single transport-death authority,
 * [ProbeIo.transportProvenAliveRecently]:
 *  - if it returns TRUE the probe DEFERS UNCONDITIONALLY — no deferral COUNT, no
 *    time ceiling — and keeps probing. The keepalive's own ~90s ride-through
 *    ([com.pocketshell.core.ssh.TransportKeepAlive.RIDE_THROUGH_BUDGET_MS]) is the
 *    single coherent transport-death budget; while it says "alive" the link is
 *    reachable and the `-CC` reply is just parked behind a slow/congested channel.
 *    The fixed `maxKeepAliveDeferrals` time ceiling that escalated at ~96s WAS the
 *    #982/#984 false positive on stable-but-jittery wifi (#974), and is deleted.
 *  - if it returns FALSE the probe escalates [onProbeFailed] immediately — either a
 *    genuine transport death (the keepalive ALSO stopped proving liveness — the
 *    dominant real #822, where a half-open wifi drop kills both channels) OR no
 *    keepalive signal exists (test fake / pre-attach), where the raw budget is the
 *    bound exactly as before.
 *
 * A single high ABSOLUTE wedge backstop ([absoluteWedgeBudgetMs], default 180s)
 * remains as the ONLY time ceiling — independent of the keepalive — for the rare
 * #822 sub-case where the `-CC` channel is genuinely WEDGED but the transport
 * keepalive stays HEALTHY FOREVER (transport fine, only the control channel stuck).
 * If `-CC` has failed continuously for [absoluteWedgeBudgetMs] the probe escalates
 * even though the keepalive still claims alive, so a truly-stuck control channel
 * still recovers — just on a sane 180s bound that cannot fire on realistic wifi
 * jitter. A blanket "keepalive alive ⇒ never redial" would re-open #822; this
 * backstop closes that hole without the #974-causing 96s false positive.
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
 *     well under the two 60s transport-liveness windows (lease idle TTL,
 *     passive grace), and below the controller's 90s foreground grace.
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
    /**
     * Issue #982/#984 — the single high ABSOLUTE wedge backstop. The ONLY time
     * ceiling that remains: if the `-CC` channel has failed continuously for this
     * long the probe escalates even while [ProbeIo.transportProvenAliveRecently]
     * still claims the transport alive (the rare "transport-keepalive-healthy-forever
     * but `-CC`-wedged" #822 sub-case). Defaults to [DEFAULT_ABSOLUTE_WEDGE_BUDGET_MS]
     * (180s) — comfortably past any realistic wifi jitter and ~2× the keepalive's
     * ~90s ride-through — so it cannot reproduce the #974/#982 96s false positive but
     * still recovers a truly-stuck control channel. A virtual-clock test shortens it.
     */
    private val absoluteWedgeBudgetMs: Long = DEFAULT_ABSOLUTE_WEDGE_BUDGET_MS,
    /**
     * Issue #1683 — the monotonic clock the per-tick latency INPUT is measured
     * against. Production uses [System.nanoTime]; a test injects a controllable
     * clock so the recorded `latencyMs` is deterministic. Diagnostics-only: the
     * measured latency is RECORDED, never used in any detection decision, so this
     * cannot change probe behavior.
     */
    private val nowNanos: () -> Long = { System.nanoTime() },
    private val log: (String) -> Unit = {},
) {
    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
        require(perProbeTimeoutMs > 0) { "perProbeTimeoutMs must be > 0" }
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
        require(absoluteWedgeBudgetMs > 0) { "absoluteWedgeBudgetMs must be > 0" }
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
         * `true` the probe DEFERS UNCONDITIONALLY (no deferral count, no time
         * ceiling): it does NOT declare a drop, because the keepalive is still
         * successfully riding through and the link is fine — it is only the tmux
         * control channel that is momentarily slow. This is the #982/#984 fix: the
         * probe's old fixed `maxKeepAliveDeferrals` time ceiling (~96s) force-redialed
         * a slow-but-live link on stable-but-jittery wifi (#974). The probe now
         * defers to the single transport-liveness authority for as long as it says
         * alive; only when the keepalive ITSELF stops seeing activity (a genuinely
         * dead transport, so this returns `false`) does the probe declare the drop.
         * The keepalive's own ~90s budget detects a real transport death
         * independently (it closes the dead transport), so deferring here can never
         * cause an infinite hang. The rare "keepalive healthy forever but `-CC`
         * wedged" #822 sub-case is bounded separately by [absoluteWedgeBudgetMs].
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
     * Issue #982/#984 — how many probe ticks the `-CC` channel has been failing
     * CONTINUOUSLY (across deferrals), or 0 when the run is clear (the last probe
     * answered, or the gate was closed). Tracks how long the `-CC` channel has been
     * failing so the [absoluteWedgeBudgetMs] backstop can escalate a genuinely-wedged
     * control channel even while the transport keepalive claims alive forever.
     *
     * Counted in TICKS (× [intervalMs] = elapsed wedge time) rather than a
     * [System.nanoTime] wall-clock delta DELIBERATELY: the loop's cadence is pure
     * [delay], so a `TestScope` virtual clock advances the wedge accounting with zero
     * wall-clock waiting — the same determinism seam the rest of the probe relies on.
     * A wall-clock delta would not advance under `advanceTimeBy`, making the backstop
     * untestable on the virtual clock. Reset to 0 on the next successful `-CC` probe
     * (or a gate-closed skip), so a link that un-congests never trips it.
     */
    private var wedgedFailureTicks: Int = 0

    /**
     * Start the probe loop in [scope]. Idempotent: a second [start] while a loop
     * is active is a no-op. The loop sleeps [intervalMs], then — only if
     * [ProbeIo.shouldProbe] — runs one probe under [perProbeTimeoutMs], counting
     * consecutive failures; on [failureThreshold] it fires [ProbeIo.onProbeFailed]
     * ONCE and stops counting (the recovery path now owns the channel) until a
     * later success resets it.
     *
     * ## Issue #1543 / #1517 — virtual-clock test contract (must-run-forever loop)
     *
     * This is an intentionally INFINITE loop with NO idle-tick bound — its ONLY
     * terminal condition is job cancellation ([stop] / [scope] cancel). An idle
     * bound would weaken the on-device drop detector (it must keep probing a quiet
     * foreground link forever), so it is deliberately absent. The consequence for
     * tests: a `TestScope` that STARTs this loop and then calls `advanceUntilIdle()`
     * HANGS FOREVER (the loop always has a next scheduled `delay`, so the scheduler
     * never idles) — the #1517 / #882 CI-hang signature. Drive it with a BOUNDED
     * `advanceTimeBy(...)` + a teardown [stop] / `scope.cancel()` (see
     * `LivenessProbeTest`), never `advanceUntilIdle()`. In the app the VM's
     * auto-start of this probe defaults OFF in the unit-test runtime for the same
     * reason (`LivenessProbeTestOverride.autoStartEnabled`, #1543 finding L1), so a
     * naive `TmuxSessionViewModel` test can't inherit the hang.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        consecutiveFailures = 0
        wedgedFailureTicks = 0
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!io.shouldProbe()) {
                    // Not foregrounded+Live (or not the current client): reset
                    // the counter so a prior partial run never leaks into the
                    // next live window, and skip this tick entirely.
                    consecutiveFailures = 0
                    wedgedFailureTicks = 0
                    continue
                }
                val probeStartNanos = nowNanos()
                val alive = runProbe()
                val latencyMs = (nowNanos() - probeStartNanos) / 1_000_000L
                if (alive) {
                    if (consecutiveFailures != 0) {
                        log("liveness-probe recovered after $consecutiveFailures failure(s)")
                        // Issue #1683 — record the first success AFTER a miss run as an
                        // INPUT: it is the evidence that the run was a slow-but-live
                        // blip rather than a real death (a false-dead would NOT recover).
                        ConnectionDiagnostics.record(
                            "liveness_probe_tick",
                            "result" to "recovered",
                            "latencyMs" to latencyMs,
                            "afterConsecutiveMisses" to consecutiveFailures,
                        )
                    }
                    // A successful `-CC` probe clears the failure run: the control
                    // channel is answering again, so the slow-but-live blip is over
                    // and the absolute-wedge clock resets.
                    consecutiveFailures = 0
                    wedgedFailureTicks = 0
                } else {
                    consecutiveFailures += 1
                    wedgedFailureTicks += 1
                    log(
                        "liveness-probe failed consecutive=$consecutiveFailures " +
                            "threshold=$failureThreshold",
                    )
                    // Issue #1683 — record every MISS tick as an INPUT (miss count +
                    // latency), rate-limited by construction to misses only (a healthy
                    // link records nothing). This is the per-tick series behind the
                    // `liveness_probe_silent_drop` VERDICT, so a false-dead is provable.
                    ConnectionDiagnostics.record(
                        "liveness_probe_tick",
                        "result" to "miss",
                        "latencyMs" to latencyMs,
                        "consecutiveMisses" to consecutiveFailures,
                        "failureThreshold" to failureThreshold,
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
                            wedgedFailureTicks = 0
                        } else {
                            val keepAliveProvesAlive = io.transportProvenAliveRecently()
                            // Elapsed continuous-wedge time, measured in probe ticks ×
                            // interval (virtual-clock friendly — see wedgedFailureTicks).
                            val wedgedForMs = wedgedFailureTicks.toLong() * intervalMs
                            val absoluteWedgeTripped = wedgedForMs >= absoluteWedgeBudgetMs
                            // Issue #1683 — record EVERY `transportProvenAliveRecently`
                            // consultation and its answer. This is THE input to the
                            // defer-vs-escalate branch below: whether the following
                            // `liveness_probe_silent_drop` verdict was an over-eager
                            // false-dead (keepalive still proving alive, escalated only by
                            // the wedge backstop) or a real death (keepalive stopped
                            // proving alive) is decidable from the log alone only if this
                            // input is on the same timeline as the verdict.
                            ConnectionDiagnostics.record(
                                "liveness_probe_keepalive_consult",
                                "keepAliveProvesAlive" to keepAliveProvesAlive,
                                "consecutiveMisses" to consecutiveFailures,
                                "wedgedForMs" to wedgedForMs,
                                "absoluteWedgeBudgetMs" to absoluteWedgeBudgetMs,
                                "absoluteWedgeTripped" to absoluteWedgeTripped,
                                "willDeclareDrop" to (!keepAliveProvesAlive || absoluteWedgeTripped),
                            )
                            if (keepAliveProvesAlive && !absoluteWedgeTripped) {
                                // Issue #982/#984 — DEFER to the transport keepalive
                                // UNCONDITIONALLY (no deferral count, no time ceiling).
                                // The `-CC` probe failed N times, but the always-on
                                // keepalive has seen inbound TRANSPORT activity within
                                // its ride-through window, so the LINK is provably
                                // reachable — the `-CC` reply is just parked behind a
                                // momentarily slow / congested channel. Forcing a redial
                                // here is the exact stable-wifi spurious reconnect the
                                // keepalive exists to ride through (#974). Reset the
                                // failure RUN (re-probe next interval) but KEEP
                                // wedgedFailureTicks so the absolute-wedge backstop can
                                // still escalate a genuinely-stuck channel at
                                // absoluteWedgeBudgetMs.
                                log(
                                    "liveness-probe DEFERRED to keepalive (transport proven " +
                                        "alive) consecutive=$consecutiveFailures " +
                                        "wedgedForMs=$wedgedForMs " +
                                        "absoluteWedgeMs=$absoluteWedgeBudgetMs",
                                )
                                consecutiveFailures = 0
                            } else {
                                // Declare the drop. Either the keepalive stopped proving
                                // the transport alive (a genuine transport death OR the
                                // dominant #822 where a half-open drop killed BOTH
                                // channels — escalate within the keepalive budget), OR
                                // the keepalive is still healthy but the `-CC` channel
                                // has been wedged continuously for absoluteWedgeBudgetMs
                                // — the rare #822 sub-case (transport fine, control
                                // channel permanently stuck) the absolute backstop
                                // catches. A blanket keepalive veto would re-open #822.
                                log(
                                    "liveness-probe DECLARED DROP consecutive=$consecutiveFailures " +
                                        "keepAliveProvesAlive=$keepAliveProvesAlive " +
                                        "wedgedForMs=$wedgedForMs " +
                                        "absoluteWedgeTripped=$absoluteWedgeTripped",
                                )
                                io.onProbeFailed(consecutiveFailures)
                                // The recovery path now owns the channel; reset so the
                                // probe does not re-fire every interval against the
                                // already-reconnecting session.
                                consecutiveFailures = 0
                                wedgedFailureTicks = 0
                            }
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
        wedgedFailureTicks = 0
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
         * transport-liveness windows it would otherwise race when it IS the acting
         * detector:
         *   - lease idle TTL (`SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS = 60_000`),
         *   - passive disconnect grace (`PASSIVE_DISCONNECT_GRACE_MS = 60_000`).
         * (The controller grace `ConnectionController.DEFAULT_GRACE_MS` is 90 s
         * after the #1159 bounded-grace update, so it is no longer one of the
         * 60 s windows the probe races; the probe still sits well below all three
         * current grace/lease budgets.)
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
         * ~12s margin below the two 60s transport-liveness windows (#822 not regressed),
         * while no longer false-positiving on a flaky-but-alive link. The tolerance
         * is raised WITHOUT inflating the raw budget toward the floor because the
         * busy-vs-dead reader-activity guard handles `%output`-burst parking.
         */
        const val DEFAULT_FAILURE_THRESHOLD: Int = 4

        /**
         * Issue #982/#984 — the single high ABSOLUTE wedge backstop, the ONLY time
         * ceiling that remains after the fixed `maxKeepAliveDeferrals` mechanism was
         * deleted (it was the #974/#982 stable-wifi false-positive source: it
         * force-redialed a slow-but-live link at ~96s).
         *
         * While [ProbeIo.transportProvenAliveRecently] says the transport is alive
         * the probe DEFERS UNCONDITIONALLY (the keepalive's own ~90s ride-through is
         * the death authority). This backstop fires ONLY in the rare #822 sub-case
         * where the `-CC` channel is genuinely WEDGED but the transport keepalive
         * stays HEALTHY FOREVER (transport fine, control channel permanently stuck):
         * if `-CC` has failed continuously for 180s the probe escalates anyway so the
         * stuck channel still recovers.
         *
         * 180s ≈ 2× the keepalive's ~90s ride-through
         * ([com.pocketshell.core.ssh.TransportKeepAlive.RIDE_THROUGH_BUDGET_MS]) — far
         * past any realistic wifi jitter (so it cannot reproduce the #974 false
         * positive) yet still a bounded, sane recovery for a truly-wedged control
         * channel. The dominant real #822 (a half-open drop that kills BOTH channels)
         * is recovered much faster: the keepalive flips false within its ~90s budget,
         * which escalates immediately via the keepalive-death branch — this backstop
         * is only for the rarer transport-healthy-forever wedge.
         */
        const val DEFAULT_ABSOLUTE_WEDGE_BUDGET_MS: Long = 180_000L
    }
}
