package com.pocketshell.core.ssh

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1080 — reproduce-first JVM proof for the Doze clock-domain bug (D33/G10,
 * the #780 synthetic-clock model, NO `assumeTrue` self-skip on the load-bearing
 * assertion).
 *
 * ## The bug
 *
 * Android **deep Doze** suspends network access and ignores wake locks, so the
 * always-on keepalive ([TransportKeepAlive]) cannot keep the carrier NAT mapping
 * alive — the socket is silently half-open by the time the device wakes. That
 * part is ACCEPTED (we can't keep the mapping alive in deep Doze). The KEYSTONE
 * defect is the WAKE-side liveness gate
 * [RealSshSession.isTransportProvenAliveWithinKeepAliveWindow]: it used
 * `System.nanoTime()` (`CLOCK_MONOTONIC`), which **STOPS during deep sleep**. So
 * after a Doze gap the monotonic delta `now - lastActivity` UNDER-counts the real
 * elapsed time, the oracle FALSELY reports the (now-dead) socket "alive within the
 * keepalive window," and the #1042 restore ride-through / #1078 handoff arm sail
 * through a DEAD transport instead of redialing.
 *
 * The fix injects the wall-elapsed boot clock
 * (`SystemClock.elapsedRealtimeNanos()`, `CLOCK_BOOTTIME`, which COUNTS deep
 * sleep) for both the activity watermarks and the oracle's comparison, so after a
 * real deep-sleep gap the oracle correctly reports STALE → the existing reconnect
 * machinery redials (no new mechanism — D28).
 *
 * ## Why a synthetic clock
 *
 * `SystemClock` is an `android.os` API; in these pure host-JVM unit tests it runs
 * against the android.jar stub and throws "not mocked." So [RealSshSession] takes
 * an injectable `nowNanos` clock (default `System.nanoTime` for tests; production
 * injects the boot clock at `SshConnection.toSession`). These tests inject a
 * [FakeNanoClock] so they can model the EXACT failing state — "the device slept
 * for 95s; the boot clock counted it" vs "the monotonic clock froze across it" —
 * deterministically, with zero wall-clock waiting and no self-skip.
 *
 * ## Reviewer red→green procedure (the load-bearing assertion is the GREEN one)
 *
 * To reproduce RED on the unfixed code, revert the #1080 migration in
 * [RealSshSession] (keep the `nowNanos` ctor seam, but change the watermark field
 * initializers + `recordInboundActivity`/`recordOutboundActivity` + the oracle's
 * `nowNanos()` calls back to `System.nanoTime()`):
 *  - [oracle reports STALE after a deep-sleep gap the boot clock counted] FAILS —
 *    the real monotonic clock barely moved and ignores the injected clock's
 *    advance, so the dead socket reads "alive" (assertFalse fails). RED.
 *  - [activity watermark is stamped from the injected clock - no mixed domain]
 *    FAILS — the watermark is the real `System.nanoTime` value, not the injected
 *    clock's. RED.
 * Re-applying the fix turns both GREEN.
 */
class RealSshSessionDozeClockTest {

    /** Production ride-through budget = 30s × 3 = 90s (the window the oracle uses). */
    private val rideThroughMs = TransportKeepAlive.RIDE_THROUGH_BUDGET_MS

    @Test
    fun `oracle reports STALE after a deep-sleep gap the boot clock counted`() {
        // The boot clock (CLOCK_BOOTTIME) COUNTS deep sleep: a 95s Doze gap shows up
        // as +95s of elapsed time. The NAT mapping is long dead, so the oracle MUST
        // report STALE → the #1042 restore / #1078 handoff redials instead of riding
        // through a dead socket.
        val clock = FakeNanoClock(startNanos = 1_000_000_000L)
        val session = RealSshSession(connectedClient(), nowNanos = clock::now)
        try {
            assertTrue(
                "precondition: a freshly-stamped session is alive within the window (gap = 0)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )

            // Deep sleep: the boot clock counts the whole 95s gap (> the 90s budget).
            clock.advanceMs(rideThroughMs + 5_000L)

            assertFalse(
                "after a ${rideThroughMs + 5_000L}ms deep-sleep gap that the boot clock " +
                    "COUNTED, the transport-liveness oracle MUST report STALE so the dead " +
                    "socket is redialed, not ridden through (#1080 keystone fix)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `oracle still reports ALIVE when activity is recent - no false-stale regression`() {
        // Class-coverage (G2): the fix must NOT make a genuinely-recent link look
        // stale. A short 10s gap (well inside the 90s budget) is still ALIVE — a
        // backgrounded-but-not-deep-asleep app, or a quick screen-off, must ride
        // through exactly as before.
        val clock = FakeNanoClock(startNanos = 1_000_000_000L)
        val session = RealSshSession(connectedClient(), nowNanos = clock::now)
        try {
            clock.advanceMs(10_000L)
            assertTrue(
                "a recent (10s < 90s budget) gap must still report ALIVE — no false-stale " +
                    "regression from the boot-clock migration (#1080)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `a clock frozen across the sleep under-counts the gap and falsely reports ALIVE`() {
        // The BUG MECHANISM, made explicit: `System.nanoTime()` (CLOCK_MONOTONIC)
        // FREEZES during deep sleep, so a 95s Doze gap advances it by ~0. With such a
        // frozen clock the oracle's `now - lastActivity` delta stays tiny and the
        // DEAD socket FALSELY reads "alive within the window." This is exactly the
        // #1080 defect — and exactly why production must inject the boot clock
        // (which counts the sleep, asserted by the STALE test above). A frozen clock
        // is modelled here by simply NOT advancing it across the gap.
        val clock = FakeNanoClock(startNanos = 0L)
        val session = RealSshSession(connectedClient(), nowNanos = clock::now)
        try {
            // 95s of deep sleep elapses in the real world, but the frozen monotonic
            // clock does not advance — the gap is invisible to it.
            assertTrue(
                "a clock that FREEZES across deep sleep under-counts the elapsed time and " +
                    "FALSELY reports a dead socket alive — the System.nanoTime bug the boot " +
                    "clock fixes (#1080)",
                session.isTransportProvenAliveWithinKeepAliveWindow(),
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `activity watermark is stamped from the injected clock - no mixed domain`() {
        // The oracle subtracts the activity watermark from `nowNanos()`; if the
        // watermark were stamped from a DIFFERENT clock domain (e.g. System.nanoTime
        // while the oracle reads the boot clock) the elapsed math would be garbage.
        // Pin that BOTH the inbound and outbound watermarks are stamped from the SAME
        // injected clock the oracle reads — no mixed clock domains (#1080).
        val stamp = 424_242_000_000L
        val clock = FakeNanoClock(startNanos = stamp)
        val session = RealSshSession(connectedClient(), nowNanos = clock::now)
        try {
            assertEquals(
                "the inbound-activity watermark must be stamped from the injected wall-elapsed " +
                    "clock (no System.nanoTime mixed-domain) — #1080",
                stamp,
                session.lastInboundActivityNanosForTest(),
            )
            assertEquals(
                "the outbound-activity watermark must be stamped from the same injected clock",
                stamp,
                session.lastOutboundActivityNanosForTest(),
            )
        } finally {
            session.close()
        }
    }

    /**
     * A controllable wall-elapsed clock seam. Models the boot clock by [advanceMs]
     * (counts the sleep) or a frozen monotonic clock by simply not advancing it.
     * Single-threaded use in these tests, but volatile for safety against the
     * keepalive loop coroutine that [RealSshSession] starts in its `init`.
     */
    private class FakeNanoClock(startNanos: Long) {
        @Volatile
        private var nanos: Long = startNanos
        fun now(): Long = nanos
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000L
        }
    }

    /**
     * Minimal connected sshj client: the oracle only consults
     * `isConnected`/`isAuthenticated` (and never the connection itself in these
     * sub-second tests — the 30s keepalive loop never ticks). `disconnect()` is a
     * no-op since no real socket is opened.
     */
    private fun connectedClient(): SSHClient = object : SSHClient() {
        override fun isConnected(): Boolean = true
        override fun isAuthenticated(): Boolean = true
        override fun disconnect() = Unit
    }
}
