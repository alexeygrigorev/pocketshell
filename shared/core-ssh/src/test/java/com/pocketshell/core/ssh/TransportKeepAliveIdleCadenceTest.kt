package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #928 Slice 1a (T1a from the definitive connection-stability audit) — the
 * keepalive tick-grid ALIASING defect: on a mostly-idle link the gap between the
 * last wire activity and the next keep-warm ping could reach ~2×interval, and
 * dead detection could take ~(countMax+1)×interval — NOT the documented
 * `ServerAliveInterval 30` cadence / `countMax × interval` (90s) budget.
 *
 * ## The defect mechanism (reproduced RED on base by this test)
 *
 * The loop ticked on a fixed `delay(intervalMs)` grid. When a REAL server byte
 * landed mid-sleep (an occasional `-CC` status line on an otherwise idle agent
 * host — exactly the maintainer's flapping idle host), the next tick saw
 * "activity within the last interval" and SKIPPED the ping — then re-slept a
 * FULL interval from the tick, not from the activity. So a byte landing just
 * after a tick was next protected by a ping only ~2×interval−ε later:
 *
 *     tick t=60s → ping ok; real byte at t=60.1s; tick t=90s → skip (29.9s ago);
 *     tick t=120s → ping. NAT keep-warm gap = 59.9s ≈ 2×interval.
 *
 * Any carrier NAT whose idle window sits in the (interval, 2×interval) band —
 * a real 30-60s CGNAT band on cellular — is then reaped BETWEEN pings, killing
 * exactly the idle host while the busy host stays glued (the #1522 per-host
 * divergence). And once the peer dies right after such a byte, the first probe
 * fires up to 2×interval later, so death lands at ~(countMax+1)×interval.
 *
 * ## The fix this pins (GREEN)
 *
 * The skip path anchors the NEXT ping one interval after the ACTIVITY (sleep
 * only the remainder), and the loop's OWN keepalive reply is never counted as
 * "recent inbound" that suppresses the next scheduled ping. An idle link is
 * therefore pinged within intervalMs(+ε) of its last wire activity, and a dead
 * peer is declared within countMax×interval(+ε) of its last proof of life.
 *
 * ## Determinism
 *
 * Same standalone-scheduler shape as [NatIdleMappingSurvivalKeepAliveTest]
 * (no `runTest`, so no process-wide ExceptionCollector callback): the loop's
 * `now` clock is injected from the virtual scheduler, and the IO is FAITHFUL to
 * production [RealSshSession] semantics — a successful keepalive reply bumps the
 * inbound watermark (issue #974/#945), which is precisely what the old fake
 * masked (it reported an always-stale watermark, so the aliasing never showed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportKeepAliveIdleCadenceTest {

    private companion object {
        const val INTERVAL_MS = 30_000L
        const val COUNT_MAX = 3

        /**
         * Generous tolerance for ms-ceil rounding + tick scheduling. The defect
         * being pinned is a full-interval (30s) overshoot, so 1s of slack keeps
         * the assertion load-bearing while never flaking on rounding.
         */
        const val SLACK_MS = 1_000L
    }

    /**
     * A FAITHFUL production-shaped [TransportKeepAlive.KeepAliveIo] on the
     * virtual clock: the inbound watermark starts at attach (t=0, as
     * [RealSshSession]'s init stamps it), a successful keepalive reply bumps it
     * (issue #974 — the reply IS inbound activity), and [chatter] models a real
     * server byte (`-CC` status output) landing on the otherwise idle link.
     */
    private class FaithfulIdleIo(
        private val virtualNowNanos: () -> Long,
    ) : TransportKeepAlive.KeepAliveIo {
        var inboundWatermarkNanos: Long = virtualNowNanos()
        var replyAnswers: Boolean = true
        val pingTimesMs = mutableListOf<Long>()
        var deadDeclaredAtMs: Long? = null
        private var alive = true

        override fun isAlive(): Boolean = alive
        override fun lastInboundActivityNanos(): Long = inboundWatermarkNanos

        override suspend fun sendKeepAlive(): Boolean {
            pingTimesMs += virtualNowNanos() / 1_000_000L
            if (!replyAnswers) return false
            // The server answered: the reply is decoded inbound bytes, so it
            // bumps the SAME watermark real traffic does (production #974).
            inboundWatermarkNanos = virtualNowNanos()
            return true
        }

        override fun onKeepAliveDead(consecutiveMisses: Int) {
            deadDeclaredAtMs = virtualNowNanos() / 1_000_000L
            // Faithful to production: the dead-peer reaction closes the
            // transport, so the loop's isAlive() gate goes false and the loop
            // ends (the recovery machinery owns the transport from here).
            alive = false
        }

        /** A real server byte (e.g. one `-CC` status line) lands NOW. */
        fun chatter() {
            inboundWatermarkNanos = virtualNowNanos()
        }
    }

    private class Harness {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val io = FaithfulIdleIo(virtualNowNanos = { scheduler.currentTime * 1_000_000L })
        val keepAlive = TransportKeepAlive(
            io = io,
            intervalMs = INTERVAL_MS,
            countMax = COUNT_MAX,
            now = { scheduler.currentTime * 1_000_000L },
        )

        fun advanceToMs(targetMs: Long) {
            val step = 100L
            while (scheduler.currentTime < targetMs) {
                scheduler.advanceTimeBy(step)
                scheduler.runCurrent()
            }
        }

        fun finish() {
            keepAlive.stop()
            scope.cancel()
        }
    }

    // ---- 1. The keep-warm gap: RED on base (~2×interval), GREEN with the fix ----

    @Test
    fun idleLinkIsPingedWithinOneIntervalOfItsLastRealActivity() {
        val h = Harness()
        h.keepAlive.start(h.scope)

        // Settle two on-cadence pings on the fully idle link (t=30s, t=60s).
        h.advanceToMs(60_000L)

        // A single real server byte lands just AFTER the t=60s tick — the
        // adversarial phase (an idle agent host's sporadic `-CC` status line).
        h.advanceToMs(60_100L)
        h.io.chatter()
        val chatterAtMs = h.scheduler.currentTime

        // Hold idle long enough for the next keep-warm ping wherever it lands.
        h.advanceToMs(130_000L)

        val nextPingAtMs = h.io.pingTimesMs.firstOrNull { it > chatterAtMs }
        assertNotNull(
            "the idle link must be pinged again after its last real activity",
            nextPingAtMs,
        )
        val gapMs = nextPingAtMs!! - chatterAtMs
        assertTrue(
            "issue #928 (T1a) — the NAT keep-warm gap after the last real activity must be " +
                "~one interval (${INTERVAL_MS}ms), got ${gapMs}ms. A gap near 2×interval is " +
                "the tick-grid aliasing defect: the skip path re-slept a FULL interval from " +
                "the tick instead of anchoring the next ping at lastActivity+interval, so a " +
                "carrier NAT with an idle window in the (interval, 2×interval) band reaps " +
                "the idle mapping between pings — the per-host idle flap.",
            gapMs <= INTERVAL_MS + SLACK_MS,
        )
        assertNull("a live answering link must never be declared dead", h.io.deadDeclaredAtMs)
        h.finish()
    }

    // ---- 2. Dead detection budget: RED on base (~4×interval), GREEN with the fix ----

    @Test
    fun deadPeerAfterMidSleepActivityIsDeclaredWithinCountMaxIntervals() {
        val h = Harness()
        h.keepAlive.start(h.scope)

        // Settle two on-cadence pings, then the adversarial-phase real byte...
        h.advanceToMs(60_000L)
        h.advanceToMs(60_100L)
        h.io.chatter()
        val lastActivityMs = h.scheduler.currentTime
        // ...and the peer dies silently right after it (half-open: no bytes, no
        // replies, watermark frozen from here on).
        h.io.replyAnswers = false

        // Hold well past both the documented and the aliased budgets.
        h.advanceToMs(lastActivityMs + 6 * INTERVAL_MS)

        assertNotNull(
            "a genuinely silent peer must be declared dead",
            h.io.deadDeclaredAtMs,
        )
        val detectionMs = h.io.deadDeclaredAtMs!! - lastActivityMs
        assertTrue(
            "issue #928 (T1a) — dead detection must land within the documented " +
                "countMax×interval (${COUNT_MAX * INTERVAL_MS}ms) of the last proof of " +
                "life, got ${detectionMs}ms. ~(countMax+1)×interval means the first probe " +
                "was aliased a full extra interval out by the tick grid, stretching the " +
                "silent half-open window past the #945 budget.",
            detectionMs <= COUNT_MAX * INTERVAL_MS + SLACK_MS,
        )
        h.finish()
    }

    // ---- 3. Steady idle cadence: the class guard (the reply must not suppress pings) ----

    @Test
    fun fullyIdleLinkIsPingedOnTheTrueIntervalCadenceNotEveryOtherInterval() {
        // The brief's REAL-cadence pin: an idle link is pinged at ~interval
        // (ServerAliveInterval semantics), NOT ~2×interval. This is the guard
        // against the whole aliasing CLASS — in particular against any future
        // change that lets the keepalive's OWN reply count as "recent inbound"
        // and skip (suppress) the next scheduled ping.
        val h = Harness()
        h.keepAlive.start(h.scope)

        val holdIntervals = 10
        h.advanceToMs(holdIntervals * INTERVAL_MS + SLACK_MS)

        assertEquals(
            "a fully idle link must be pinged once per interval across the hold " +
                "(true ${INTERVAL_MS}ms cadence — half the pings means the keepalive's own " +
                "reply is suppressing the next tick's ping, issue #928 T1a)",
            holdIntervals,
            h.io.pingTimesMs.size,
        )
        val maxGapMs = (listOf(0L) + h.io.pingTimesMs).zipWithNext { a, b -> b - a }.max()
        assertTrue(
            "consecutive keep-warm pings on a fully idle link must stay ~interval apart " +
                "(max gap ${maxGapMs}ms, budget ${INTERVAL_MS + SLACK_MS}ms)",
            maxGapMs <= INTERVAL_MS + SLACK_MS,
        )
        assertNull("a live answering link must never be declared dead", h.io.deadDeclaredAtMs)
        h.finish()
    }
}
