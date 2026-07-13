package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1063 (R3, from the #843 round-2 mobile audit / gap C2) + issue #928
 * Slice 1a — the carrier-NAT idle-mapping SURVIVAL regression guard for the
 * always-on transport keepalive CADENCE. D31/D32 (G2/G6) / D33 (reproduce →
 * fix → verify) / G9 (a test per acceptance criterion).
 *
 * --------------------------------------------------------------------------
 * THE REAL-WORLD TRIGGER IT PINS
 *
 * A carrier-grade NAT (CGNAT) on a cellular link drops an IDLE TCP mapping after a
 * per-carrier idle window W (observed in the wild as low as tens of seconds — far
 * below RFC 5382's 2h4m floor, which mobile carriers routinely violate). Once the
 * mapping is gone the path is HALF-OPEN: both ends still believe the socket is up,
 * but packets are silently dropped. PocketShell's always-on
 * [TransportKeepAlive] (#945) prevents this: on an idle link it keeps the wire-idle
 * gap below one `intervalMs`, so the mapping never sits idle long enough to be
 * reaped. This is *by design*, but two distinct regressions can break it:
 *
 *  1. an interval RETUNE past W ("save battery") — the original #1063 guard; and
 *  2. the #928 T1a TICK-GRID ALIASING: the loop skipped the ping when real
 *     activity landed within the last interval, then re-slept a FULL interval
 *     from the tick — so the wire-idle gap after a mid-sleep server byte could
 *     reach ~2×interval, and any NAT window in the (interval, 2×interval) band
 *     was reaped BETWEEN pings even though `interval < W` held on paper.
 *
 * --------------------------------------------------------------------------
 * WHY THE PRE-#928 SHAPE OF THIS TEST MASKED THE ALIASING (the corrected model)
 *
 * The old fake reported an always-stale inbound watermark (`now − 100×interval`),
 * so the loop's reset-on-inbound skip NEVER fired — the model pinged on every
 * tick by construction and could not exhibit the aliasing. And its parameters
 * (1s interval vs 3s NAT window) meant even a genuinely aliased 2s effective
 * cadence still fit inside the window. This corrected model is FAITHFUL to
 * production [RealSshSession] semantics instead:
 *
 *  - the loop's `now` clock is the VIRTUAL scheduler clock;
 *  - the inbound watermark starts at attach (t=0) and every successful keepalive
 *    REPLY bumps it (issue #974 — the reply IS decoded inbound bytes);
 *  - sporadic REAL server bytes (`-CC` status lines on a mostly-idle agent host)
 *    bump the watermark AND refresh the NAT idle timer, exactly as on the wire;
 *  - the NAT window sits BETWEEN 1× and 2× the interval, the band the aliasing
 *    defect exposes and a correct anchored cadence protects.
 *
 * --------------------------------------------------------------------------
 * THE MODEL (faithful, deterministic, virtual-time)
 *
 * - [CarrierNatIdleMapping] is the carrier NAT's idle timer: it records every
 *   inbound refresh (keepalive reply or real server byte) at the VIRTUAL clock
 *   time and is "reaped" the instant the gap since the last refresh exceeds the
 *   modelled idle window W. The session attach itself is the first refresh (t=0).
 * - [KeepAliveDrivenNatIo] is the [TransportKeepAlive.KeepAliveIo] the real loop
 *   ticks, with the faithful watermark semantics above.
 *
 * The mapping survives iff the keepalive keeps every wire-idle gap below W.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NatIdleMappingSurvivalKeepAliveTest {

    private companion object {
        /** The keepalive interval driving every arm below. */
        const val INTERVAL_MS = 1_000L

        /**
         * Issue #928 (T1a): the modelled carrier-NAT idle window W sits BETWEEN
         * 1× and 2× the keepalive interval — the exact band the tick-grid
         * aliasing exposed (effective idle gap up to ~2×interval) and that the
         * anchored true-cadence loop protects (gap ≤ interval).
         */
        const val NAT_WINDOW_MS = 1_500L

        /**
         * OVER-LONG keepalive (RED CONTROL / the original #1063 regression): a
         * refresh lands only every 5s, longer than the NAT window — so the idle
         * mapping ages past W between refreshes and the carrier NAT reaps it.
         */
        const val OVER_LONG_INTERVAL_MS = 5_000L

        const val COUNT_MAX = 3

        /** Idle hold: several NAT windows so the survival/reap outcome is stable. */
        const val IDLE_HOLD_MS = 12_000L

        /** Virtual-time step: fine enough to place chatter/samples precisely. */
        const val STEP_MS = 50L

        /**
         * The minimum realistic AGGRESSIVE carrier-NAT TCP idle window. Real CGNAT
         * idle timeouts have been observed this low; OpenSSH ships
         * `ServerAliveInterval 30` precisely to beat it with margin. The production
         * keepalive interval MUST stay below this (with headroom) or long-idle
         * cellular survival breaks.
         */
        const val MIN_REALISTIC_CGNAT_IDLE_WINDOW_MS = 60_000L
    }

    /** The carrier NAT's idle timer, in the test's virtual-clock domain. */
    private class CarrierNatIdleMapping(private val windowMs: Long) {
        // The mapping is created (and first refreshed) at attach, t=0.
        var lastRefreshMs: Long = 0L
            private set
        var everReaped: Boolean = false
            private set

        fun refresh(nowMs: Long) {
            lastRefreshMs = nowMs
        }

        /** Sample the mapping at [nowMs]; reaped if idle longer than the window. */
        fun sample(nowMs: Long) {
            if (nowMs - lastRefreshMs > windowMs) everReaped = true
        }
    }

    /**
     * The FAITHFUL [TransportKeepAlive.KeepAliveIo]: the inbound watermark starts
     * at attach and every successful keepalive reply bumps it (production #974
     * semantics — this is what the pre-#928 fake masked); every reply and every
     * real server byte refreshes the carrier-NAT idle timer at virtual time.
     */
    private class KeepAliveDrivenNatIo(
        private val nat: CarrierNatIdleMapping,
        private val virtualNowNanos: () -> Long,
    ) : TransportKeepAlive.KeepAliveIo {
        var pingsSent: Int = 0
        var deadDeclaredWith: Int? = null
        private var inboundWatermarkNanos: Long = virtualNowNanos()

        override fun isAlive(): Boolean = true

        override fun lastInboundActivityNanos(): Long = inboundWatermarkNanos

        override suspend fun sendKeepAlive(): Boolean {
            pingsSent += 1
            // The server answered — decoded inbound bytes that reset BOTH the
            // carrier-NAT idle timer and the session's inbound watermark (#974).
            nat.refresh(virtualNowNanos() / 1_000_000L)
            inboundWatermarkNanos = virtualNowNanos()
            return true
        }

        override fun onKeepAliveDead(consecutiveMisses: Int) {
            deadDeclaredWith = consecutiveMisses
        }

        /** A real server byte (a `-CC` status line) lands NOW: watermark + NAT. */
        fun chatter() {
            nat.refresh(virtualNowNanos() / 1_000_000L)
            inboundWatermarkNanos = virtualNowNanos()
        }
    }

    private class IdleHoldResult(
        val io: KeepAliveDrivenNatIo,
        val nat: CarrierNatIdleMapping,
    )

    /**
     * Drives the real [TransportKeepAlive] loop at [intervalMs] across the idle
     * hold, sampling the modelled carrier NAT each [STEP_MS] of virtual time and
     * injecting a real server byte at each of [chatterAtMs] (multiples of
     * [STEP_MS]).
     *
     * Virtual time is driven by a STANDALONE [TestCoroutineScheduler] +
     * [StandardTestDispatcher], NOT `runTest`. The keepalive loop is `while (isActive)`
     * and — by design for this model — the IO always reports alive, so it never ends on
     * its own; it only ends when its job is cancelled. Driving it on a plain
     * [CoroutineScope] over the test dispatcher (and cancelling that scope at the end)
     * keeps this a pure deterministic virtual-clock pin WITHOUT entering a `runTest`
     * body. That matters for the whole-module gate: `runTest` registers a callback with
     * kotlinx-coroutines-test's process-wide `ExceptionCollector`, and once enabled the
     * collector intercepts EVERY later uncaught coroutine exception in the JVM worker —
     * including a PRE-EXISTING latent one in an unrelated sibling
     * (`RealSshSessionTailRecoverableFailureTest`, whose #239 test intentionally lets a
     * genuine-bug tail-launch exception reach the crash-reporter / default handler) —
     * and reports it as `UncaughtExceptionsBeforeTest` against whichever test runs next,
     * reddening the module suite. Using a standalone scheduler here adds NO such
     * collector callback, so this pin no longer surfaces that sibling flake. (The
     * sibling's latent leak is tracked separately; the durable fix is a
     * `CoroutineExceptionHandler` on `RealSshSession`'s background scope.)
     */
    private fun runIdleHold(
        intervalMs: Long,
        chatterAtMs: Set<Long> = emptySet(),
    ): IdleHoldResult {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val nat = CarrierNatIdleMapping(NAT_WINDOW_MS)
        val io = KeepAliveDrivenNatIo(
            nat = nat,
            virtualNowNanos = { scheduler.currentTime * 1_000_000L },
        )
        val keepAlive = TransportKeepAlive(
            io = io,
            intervalMs = intervalMs,
            countMax = COUNT_MAX,
            now = { scheduler.currentTime * 1_000_000L },
        )
        keepAlive.start(scope)

        var elapsed = 0L
        while (elapsed < IDLE_HOLD_MS) {
            scheduler.advanceTimeBy(STEP_MS)
            scheduler.runCurrent()
            elapsed += STEP_MS
            if (scheduler.currentTime in chatterAtMs) io.chatter()
            nat.sample(scheduler.currentTime)
        }
        keepAlive.stop()
        // Cancel the driving scope so the (already stopped) keepalive job and the
        // dispatcher are torn down deterministically — no coroutine outlives the test.
        scope.cancel()
        return IdleHoldResult(io, nat)
    }

    // ---- ARM 1: idle-mapping survival on the TRUE cadence (the #928 red→green pin) ----

    @Test
    fun liveKeepAliveKeepsIdleNatMappingWarmAcrossLongIdle() {
        // GREEN: interval (1s) < NAT window (1.5s) and the loop holds the TRUE
        // cadence — every wire-idle gap stays ≤ interval, so the carrier NAT never
        // reaps the fully idle mapping.
        val result = runIdleHold(INTERVAL_MS)

        assertTrue(
            "the keepalive must have actually pinged the idle link (one per interval)",
            result.io.pingsSent > 0,
        )
        assertNull(
            "a live link is never declared dead while its keepalive replies keep arriving",
            result.io.deadDeclaredWith,
        )
        assertFalse(
            "the carrier NAT must NEVER reap the mapping while the keepalive interval " +
                "(${INTERVAL_MS}ms) is shorter than the ${NAT_WINDOW_MS}ms idle " +
                "window — every reply resets the idle timer. If this fails the keepalive " +
                "cadence regressed past the NAT window.",
            result.nat.everReaped,
        )
    }

    @Test
    fun sporadicChatterOnIdleLinkMustNotOpenANatWindowSizedPingGap() {
        // Issue #928 (T1a) — THE ALIASING PIN (RED on the pre-#928 loop, GREEN with
        // the anchored cadence). A real server byte lands just AFTER a tick
        // (t=2100, ticks on the 1000ms grid). The aliased loop then skipped the
        // t=3000 ping and re-slept a FULL interval — no wire packet until t=4000,
        // a 1900ms idle gap that BLOWS the 1500ms NAT window even though
        // `interval < W` holds. The anchored loop pings at t≈3100 (one interval
        // after the byte), keeping every gap ≤ interval and the mapping warm.
        val result = runIdleHold(INTERVAL_MS, chatterAtMs = setOf(2_100L))

        assertTrue(
            "the keepalive must still be pinging the mostly-idle link",
            result.io.pingsSent > 0,
        )
        assertNull(
            "a live link is never declared dead while its keepalive replies keep arriving",
            result.io.deadDeclaredWith,
        )
        assertFalse(
            "issue #928 (T1a): a sporadic real server byte landing mid-sleep must NOT " +
                "open a wire-idle gap of ~2×interval before the next keep-warm ping. The " +
                "carrier NAT (window ${NAT_WINDOW_MS}ms, between 1× and 2× the " +
                "${INTERVAL_MS}ms interval) reaped the idle mapping — the tick-grid " +
                "aliasing defect that flapped exactly the maintainer's idle host.",
            result.nat.everReaped,
        )
    }

    @Test
    fun overLongKeepAliveLetsIdleNatMappingAgeOut() {
        // RED CONTROL (G6): interval (5s) > NAT window (1.5s) — between keepalive
        // refreshes the mapping sits idle longer than W, so the carrier NAT reaps it.
        // This proves the GREEN assertions above are LOAD-BEARING: with the keepalive
        // cadence past the NAT window the mapping is genuinely lost.
        val result = runIdleHold(OVER_LONG_INTERVAL_MS)

        assertTrue(
            "the over-long keepalive must still have pinged the idle link",
            result.io.pingsSent > 0,
        )
        assertTrue(
            "with the keepalive interval (${OVER_LONG_INTERVAL_MS}ms) retuned PAST the " +
                "${NAT_WINDOW_MS}ms NAT idle window, the idle mapping MUST age out between " +
                "refreshes (the carrier NAT reaps it). If this does not happen the survival " +
                "assertions in the sibling GREEN tests would be vacuous.",
            result.nat.everReaped,
        )
    }

    // ---- ARM 1 (constant guard): the production interval must beat a real CGNAT window ----

    @Test
    fun productionKeepAliveIntervalStaysBelowRealisticCgnatIdleWindow() {
        // The concrete regression catcher: a future "battery-saving" retune of the
        // production keepalive interval past a realistic aggressive-CGNAT idle window
        // would silently break long-idle cellular survival. Pin the interval below it
        // WITH ride-through headroom (at least one reply still lands within the window
        // even if a single keepalive is missed), mirroring OpenSSH's 30s default.
        assertTrue(
            "the production keepalive interval (${TransportKeepAlive.DEFAULT_INTERVAL_MS}ms) " +
                "must stay below the minimum realistic aggressive-CGNAT idle window " +
                "(${MIN_REALISTIC_CGNAT_IDLE_WINDOW_MS}ms) or idle cellular sessions are " +
                "reaped — a retune past it is the #1063 regression.",
            TransportKeepAlive.DEFAULT_INTERVAL_MS < MIN_REALISTIC_CGNAT_IDLE_WINDOW_MS,
        )
        assertTrue(
            "the production keepalive interval must leave ride-through headroom: even if " +
                "ONE keepalive is missed, the next reply must still land within the " +
                "${MIN_REALISTIC_CGNAT_IDLE_WINDOW_MS}ms NAT window (2 × interval ≤ window).",
            TransportKeepAlive.DEFAULT_INTERVAL_MS * 2 <= MIN_REALISTIC_CGNAT_IDLE_WINDOW_MS,
        )
    }
}
