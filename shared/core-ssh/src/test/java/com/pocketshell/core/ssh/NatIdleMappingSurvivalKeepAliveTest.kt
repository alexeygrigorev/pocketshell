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
 * Issue #1063 (R3, from the #843 round-2 mobile audit / gap C2) — the carrier-NAT
 * idle-mapping SURVIVAL regression guard for the always-on transport keepalive
 * INTERVAL. D31/D32 (G2/G6) / D33 (reproduce → fix → verify) / G9 (a test per
 * acceptance criterion).
 *
 * --------------------------------------------------------------------------
 * THE REAL-WORLD TRIGGER IT PINS
 *
 * A carrier-grade NAT (CGNAT) on a cellular link drops an IDLE TCP mapping after a
 * per-carrier idle window W (observed in the wild as low as tens of seconds — far
 * below RFC 5382's 2h4m floor, which mobile carriers routinely violate). Once the
 * mapping is gone the path is HALF-OPEN: both ends still believe the socket is up,
 * but packets are silently dropped. PocketShell's always-on
 * [TransportKeepAlive] (#945) prevents this: on an idle link it sends one
 * `keepalive@openssh.com` per [TransportKeepAlive] `intervalMs`, and EVERY reply is
 * inbound traffic that resets the carrier-NAT idle timer — so the mapping never
 * sits idle long enough to be reaped. This is *by design*, but nothing pinned it.
 * A future interval retune (e.g. bumping it past W to "save battery") would
 * silently break long-idle survival on cellular and the maintainer would only find
 * out by losing a session after reading/recording a voice note for a minute.
 *
 * --------------------------------------------------------------------------
 * WHY THIS LIVES AT THE KEEPALIVE LAYER, NOT THE CONNECTED `-CC` ORACLE (round 2)
 *
 * Round 1 tried to prove this on the live connected session by disabling the
 * foreground `-CC` LivenessProbe and reading the production transport-liveness
 * oracle [RealSshSession.isTransportProvenAliveWithinKeepAliveWindow] across a long
 * idle, claiming the keepalive was then the SOLE inbound source. The reviewer RAN
 * it on emulator + Docker and proved that premise FALSE: with the keepalive retuned
 * to 600s (effectively OFF) the oracle still stayed proven-alive for the whole 130s
 * — so something OTHER than the keepalive refreshes inbound activity inside the
 * window. That something is the tmux `-CC` control channel itself: every byte its
 * reader decodes calls `recordInboundActivity()`
 * (`RealSshSession.startInteractiveShell`, `onInboundActivity = ::recordInbound-`
 * `Activity`), and an attached `-CC` session is never fully silent (status / layout
 * notifications). On a live attached session the keepalive can therefore NEVER be
 * isolated as the sole inbound source, so the connected oracle CANNOT give a true
 * red→green for "interval < NAT window" — the GREEN passes vacuously (it would stay
 * green through the exact interval regression it claims to guard).
 *
 * So the load-bearing red→green pivots HERE, to the layer where the keepalive IS
 * the deciding factor (the #1059 / [TransportKeepAliveIdleHighRttTest] model): the
 * real [TransportKeepAlive] loop drives the inbound-refresh cadence, a faithful
 * carrier-NAT idle-timer model reaps the mapping when an idle gap exceeds W, and
 * the keepalive INTERVAL is the sole variable. The connected, real-wire RECOVERY
 * proof (the carrier NAT actually reaps the mapping mid-idle and the keepalive
 * drives recovery) stays in `app/.../proof/NatIdleMappingSurvivalE2eTest.kt` over
 * the toxiproxy half-open harness (Arm 2). This JVM pair is Arm 1 (survival) and
 * runs in the per-push Unit gate (`:shared:core-ssh:test`).
 *
 * --------------------------------------------------------------------------
 * THE MODEL (faithful, deterministic, virtual-time)
 *
 * - [CarrierNatIdleMapping] is the carrier NAT's idle timer: it records every
 *   inbound refresh (the keepalive reply) at the VIRTUAL clock time and is "reaped"
 *   the instant the gap since the last refresh exceeds the modelled idle window W.
 *   The session attach itself is the first refresh (t=0), as on the real wire.
 * - [KeepAliveDrivenNatIo] is the [TransportKeepAlive.KeepAliveIo] the real loop
 *   ticks. Its [TransportKeepAlive.KeepAliveIo.lastInboundActivityNanos] is reported
 *   far-older-than-one-interval (the #1059 trick) so reset-on-inbound never skips the
 *   ping on this idle link — i.e. the loop genuinely sends one ping per interval,
 *   exactly as on a quiet cellular link. Each
 *   [TransportKeepAlive.KeepAliveIo.sendKeepAlive] models the server answering: it
 *   refreshes the NAT mapping at the current virtual time and returns alive=true.
 *
 * The mapping survives iff the keepalive refreshes it within W — i.e. iff the
 * keepalive INTERVAL < W. That is the whole contract, and the interval is the only
 * thing that differs between the GREEN and the RED-control arms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NatIdleMappingSurvivalKeepAliveTest {

    private companion object {
        /**
         * The modelled carrier-NAT idle window W. The keepalive must refresh the
         * mapping more often than this or the NAT reaps it.
         */
        const val NAT_WINDOW_MS = 3_000L

        /**
         * LIVE keepalive (Arm 1, GREEN): a reply lands every 1s, comfortably inside
         * the 3s NAT window — the NAT idle timer is reset before it can fire.
         */
        const val LIVE_INTERVAL_MS = 1_000L

        /**
         * OVER-LONG keepalive (Arm 1, RED CONTROL / the regression): a reply lands
         * only every 5s, longer than the 3s NAT window — so the idle mapping ages
         * past W between refreshes and the carrier NAT reaps it.
         */
        const val OVER_LONG_INTERVAL_MS = 5_000L

        const val COUNT_MAX = 3

        /** Idle hold: several NAT windows so the survival/reap outcome is stable. */
        const val IDLE_HOLD_MS = 24_000L

        /** Sample the NAT idle timer this often (well below W) to catch any reap. */
        const val SAMPLE_MS = 250L

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
     * The [TransportKeepAlive.KeepAliveIo] the real loop ticks. Each keepalive
     * reply refreshes the carrier-NAT mapping at the current virtual time.
     */
    private class KeepAliveDrivenNatIo(
        private val intervalMs: Long,
        private val nat: CarrierNatIdleMapping,
        private val virtualNowMs: () -> Long,
    ) : TransportKeepAlive.KeepAliveIo {
        var pingsSent: Int = 0
        var deadDeclaredWith: Int? = null

        override fun isAlive(): Boolean = true

        // Far older than one interval (the #1059 trick) so reset-on-inbound never
        // skips the ping on this idle link: the loop sends exactly one ping per
        // interval, as on a quiet cellular link.
        override fun lastInboundActivityNanos(): Long =
            System.nanoTime() - 100L * intervalMs * 1_000_000L

        override suspend fun sendKeepAlive(): Boolean {
            pingsSent += 1
            // The server answered — an inbound byte that resets the carrier-NAT
            // idle timer at the current virtual time.
            nat.refresh(virtualNowMs())
            return true
        }

        override fun onKeepAliveDead(consecutiveMisses: Int) {
            deadDeclaredWith = consecutiveMisses
        }
    }

    private class IdleHoldResult(
        val io: KeepAliveDrivenNatIo,
        val nat: CarrierNatIdleMapping,
    )

    /**
     * Drives the real [TransportKeepAlive] loop at [intervalMs] across the idle
     * hold, sampling the modelled carrier NAT each [SAMPLE_MS] of virtual time.
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
    private fun runIdleHold(intervalMs: Long): IdleHoldResult {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val nat = CarrierNatIdleMapping(NAT_WINDOW_MS)
        val io = KeepAliveDrivenNatIo(
            intervalMs = intervalMs,
            nat = nat,
            virtualNowMs = { scheduler.currentTime },
        )
        val keepAlive = TransportKeepAlive(io = io, intervalMs = intervalMs, countMax = COUNT_MAX)
        keepAlive.start(scope)

        var elapsed = 0L
        while (elapsed < IDLE_HOLD_MS) {
            scheduler.advanceTimeBy(SAMPLE_MS)
            scheduler.runCurrent()
            elapsed += SAMPLE_MS
            nat.sample(scheduler.currentTime)
        }
        keepAlive.stop()
        // Cancel the driving scope so the (already stopped) keepalive job and the
        // dispatcher are torn down deterministically — no coroutine outlives the test.
        scope.cancel()
        return IdleHoldResult(io, nat)
    }

    // ---- ARM 1: idle-mapping survival, the deterministic red→green pin ----

    @Test
    fun liveKeepAliveKeepsIdleNatMappingWarmAcrossLongIdle() {
        // GREEN: interval (1s) < NAT window (3s) — every reply resets the idle timer
        // before it fires, so the carrier NAT never reaps the idle mapping.
        val result = runIdleHold(LIVE_INTERVAL_MS)

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
                "(${LIVE_INTERVAL_MS}ms) is shorter than the ${NAT_WINDOW_MS}ms idle " +
                "window — every reply resets the idle timer. If this fails the keepalive " +
                "interval was retuned past the NAT window (the regression this guards).",
            result.nat.everReaped,
        )
    }

    @Test
    fun overLongKeepAliveLetsIdleNatMappingAgeOut() {
        // RED CONTROL (G6): interval (5s) > NAT window (3s) — between keepalive
        // refreshes the mapping sits idle longer than W, so the carrier NAT reaps it.
        // This proves the GREEN assertion above is LOAD-BEARING: with the keepalive
        // retuned past the NAT window the mapping is genuinely lost.
        val result = runIdleHold(OVER_LONG_INTERVAL_MS)

        assertTrue(
            "the over-long keepalive must still have pinged the idle link",
            result.io.pingsSent > 0,
        )
        assertTrue(
            "with the keepalive interval (${OVER_LONG_INTERVAL_MS}ms) retuned PAST the " +
                "${NAT_WINDOW_MS}ms NAT idle window, the idle mapping MUST age out between " +
                "refreshes (the carrier NAT reaps it). If this does not happen the survival " +
                "assertion in the sibling GREEN test would be vacuous.",
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
