package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1059 (R2) — bound the idle high-RTT / bufferbloat-cellular keepalive
 * false positive WITHOUT weakening the #945 truly-silent-peer detection.
 *
 * ## The reported defect (trigger T6b / coverage gap C3 from the #843 audit)
 *
 * A keepalive "miss" is a reply not seen within the 5s per-reply budget
 * (`KEEPALIVE_REPLY_TIMEOUT_MS`). On an IDLE `-CC` link (no other inbound bytes),
 * bufferbloat/congestion can push the keepalive round-trip past that 5s budget, so
 * EACH tick's send reports a miss even though the reply DOES land (late). The
 * reset-on-inbound shortcut only credits inbound seen within the LAST interval, so
 * in the worst-case phase — where the late replies land just OUTSIDE that narrow
 * 1-interval window each tick — the raw `countMax` consecutive-miss count reaches
 * the death budget and declares a LIVE link dead, redialing it (~90s on
 * production timings). Reset-on-inbound only saves a *busy* link.
 *
 * ## The fix (Option B)
 *
 * The death decision keys off whether the inbound-activity watermark
 * (`KeepAliveIo.lastInboundActivityNanos`) ADVANCED across the miss streak, not the
 * raw miss count. A half-open DEAD peer produces zero inbound and can never advance
 * that watermark, so a genuinely silent peer is STILL declared dead within
 * `countMax × interval` — but a slow-but-answering link, whose late replies keep
 * advancing the watermark, rides through.
 *
 * ## Test model (deterministic, Docker-free)
 *
 * `reset-on-inbound` compares `io.lastInboundActivityNanos()` against the real
 * `System.nanoTime()`, while the loop's cadence runs on the virtual test clock. To
 * reproduce the worst-case phase deterministically, the fake reports inbound
 * timestamps that are far OLDER than the 1-interval reset window (so reset-on-inbound
 * never credits them — exactly the idle-link "late reply lands outside the reset
 * window" case) yet genuinely ADVANCE each tick the link answers (the late replies
 * ARE arriving — the link is alive). The slow-but-alive arm asserts the link is never
 * declared dead; the truly-dead arm (the watermark frozen — a half-open peer that
 * answers nothing) asserts it is still declared dead within `countMax`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportKeepAliveIdleHighRttTest {

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val COUNT_MAX = 3
        val INTERVAL_NANOS = INTERVAL_MS * 1_000_000L
    }

    /**
     * Models an idle link whose keepalive replies land LATE.
     *
     * @param answersForTicks the number of ticks for which a (late) reply still
     *   lands and advances the inbound watermark. After that the link is truly
     *   silent (no inbound at all) — a half-open death. `Int.MAX_VALUE` models a
     *   slow-but-perpetually-answering link.
     */
    private class IdleHighRttIo(
        private val answersForTicks: Int,
    ) : TransportKeepAlive.KeepAliveIo {
        var alive: Boolean = true
        var deadDeclaredWith: Int? = null
        var pingsSent: Int = 0

        // Far OLDER than the 1-interval reset-on-inbound window, so reset-on-inbound
        // never credits these timestamps (the idle "late reply lands outside the
        // reset window" phase) — yet they advance whenever the link answers.
        private val oldBaseNanos: Long = System.nanoTime() - 100L * INTERVAL_NANOS
        private var inboundAdvances: Long = 0L
        private var answersLeft: Int = answersForTicks

        override fun isAlive(): Boolean = alive

        override fun lastInboundActivityNanos(): Long =
            oldBaseNanos + inboundAdvances * INTERVAL_NANOS

        override suspend fun sendKeepAlive(): Boolean {
            pingsSent += 1
            // RTT > the 5s per-reply budget: the per-tick send NEVER sees the reply
            // in time, so every tick reports a miss. But while the link is alive the
            // late reply still lands and advances the inbound watermark.
            if (answersLeft > 0) {
                inboundAdvances += 1
                answersLeft -= 1
            }
            return false
        }

        override fun onKeepAliveDead(consecutiveMisses: Int) {
            deadDeclaredWith = consecutiveMisses
        }
    }

    @Test
    fun `slow-but-alive idle high-RTT link is never declared dead while late replies keep arriving`() = runTest {
        // ARM 1 (#1059) — RED on base: every per-reply send misses (RTT > 5s budget)
        // and the late replies land outside the 1-interval reset window, so the base
        // count-only death budget declares dead at countMax. GREEN with the fix: the
        // inbound watermark advanced across the streak, so the slow-but-alive link
        // rides through.
        val io = IdleHighRttIo(answersForTicks = Int.MAX_VALUE)
        val keepAlive = TransportKeepAlive(io = io, intervalMs = INTERVAL_MS, countMax = COUNT_MAX)
        keepAlive.start(this)

        // Hold the link across several death budgets (12 ticks, budget = 3).
        repeat(12) {
            advanceTimeBy(INTERVAL_MS)
            runCurrent()
        }

        assertTrue(
            "the loop must keep probing an idle link (the late replies are outside the " +
                "reset-on-inbound window, so the ping is NOT skipped)",
            io.pingsSent > 0,
        )
        assertNull(
            "a slow-but-alive idle high-RTT/bufferbloat link must NEVER be declared dead " +
                "while its (late) keepalive replies keep advancing inbound activity (#1059)",
            io.deadDeclaredWith,
        )

        keepAlive.stop()
    }

    @Test
    fun `a truly silent peer is still declared dead within countMax - the #945 contract holds`() = runTest {
        // ARM 2 (#945 / G2 class coverage) — the inverse: a half-open peer that
        // answers NOTHING (the inbound watermark never advances). The fix must not
        // make a genuinely dead link look alive forever.
        val io = IdleHighRttIo(answersForTicks = 0)
        val keepAlive = TransportKeepAlive(io = io, intervalMs = INTERVAL_MS, countMax = COUNT_MAX)
        keepAlive.start(this)

        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 1
        assertNull("not dead before countMax", io.deadDeclaredWith)
        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 2
        assertNull("not dead before countMax", io.deadDeclaredWith)
        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 3 -> declare dead

        assertEquals(
            "a genuinely silent (half-open) peer that never advances inbound activity must " +
                "STILL be declared dead within countMax consecutive misses (#945)",
            COUNT_MAX,
            io.deadDeclaredWith,
        )

        keepAlive.stop()
    }

    @Test
    fun `a link that answers only at the streak start then goes silent is still declared dead`() = runTest {
        // ARM 3 (class coverage) — a link that produces ONE late reply at the start of
        // the streak and then goes truly silent must still be declared dead within the
        // budget: the streak-start watermark is baked into the snapshot, so only a
        // reply landing AFTER the streak began (proving ongoing life) rides through.
        // This pins that the fix credits ONGOING inbound, not a single stale byte.
        val io = IdleHighRttIo(answersForTicks = 1)
        val keepAlive = TransportKeepAlive(io = io, intervalMs = INTERVAL_MS, countMax = COUNT_MAX)
        keepAlive.start(this)

        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 1 (the single late reply lands here)
        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 2 (silent)
        advanceTimeBy(INTERVAL_MS); runCurrent() // miss 3 -> declare dead

        assertEquals(
            "a link that answers only at the streak start and then goes silent must still be " +
                "declared dead within countMax — the fix must credit ONGOING inbound, not a " +
                "single stale byte",
            COUNT_MAX,
            io.deadDeclaredWith,
        )

        keepAlive.stop()
    }
}
