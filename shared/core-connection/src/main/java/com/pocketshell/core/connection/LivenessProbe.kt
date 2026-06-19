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
 * silent half-open Wi-Fi drop had happened: sshj's `isConnected` lies for up to
 * ~60s until the keep-alive miss-counter trips, and `TmuxClient.disconnected`
 * only flips on a reader EOF or a watchdog-covered DISPATCHED command. With the
 * user reading or recording a voice note (the exact #822 scenario), no command
 * is in flight, so the dead channel is invisible — the "detection void".
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
 *  2. **Generous per-probe timeout** ([perProbeTimeoutMs], default 8s): a single
 *     probe round-trip is given far more than a healthy RTT. On a BUSY channel
 *     (a heavy `%output` burst) the probe waits behind the control-mode FIFO but
 *     still replies once the burst drains; the timeout only bites a channel that
 *     is genuinely not answering.
 *  3. **N consecutive failures** ([failureThreshold], default 2): a SINGLE slow
 *     probe (a momentary stall, a long burst that outlasts one timeout) does NOT
 *     declare the channel dead — the counter resets on the next success. Only
 *     [failureThreshold] probes in a row, each timing out across
 *     ~[failureThreshold] × [perProbeTimeoutMs] of total silence, trips the drop.
 *     With the defaults that is ~16s of an unresponsive channel before a drop is
 *     declared — still FAR below the ~60s keep-alive worst case (so the product
 *     detection budget is met) yet generous enough that no momentary stall on a
 *     healthy session false-positives.
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
         * How often the probe fires while foregrounded + `Live`. ~10s is below
         * the 60s sshj keep-alive (`SshConnection` 15s × 4) so detection is
         * proactive, yet sparse enough that a single `refresh-client` round-trip
         * per 10s is negligible control-channel traffic.
         */
        const val DEFAULT_INTERVAL_MS: Long = 10_000L

        /**
         * Per-probe response budget. Generous (8s) so a busy / momentarily-slow
         * but HEALTHY channel answers within it rather than false-positiving; a
         * genuinely dead half-open channel never answers and the probe times out.
         */
        const val DEFAULT_PER_PROBE_TIMEOUT_MS: Long = 8_000L

        /**
         * Consecutive probe failures before a drop is declared. 2 means a single
         * slow probe never trips it (the counter resets on the next success);
         * only ~2 × the timeout of sustained silence does. Net worst-case
         * detection ≈ interval + threshold × timeout, still well under the ~60s
         * keep-alive lag.
         */
        const val DEFAULT_FAILURE_THRESHOLD: Int = 2
    }
}
