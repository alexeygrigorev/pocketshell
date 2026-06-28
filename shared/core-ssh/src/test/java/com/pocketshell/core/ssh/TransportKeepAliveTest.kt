package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic (Docker-free) JVM contract for [TransportKeepAlive] — the
 * Terminus-parity transport keepalive loop (issue #945).
 *
 * These pin the load-bearing behaviour as a pure virtual-clock loop, the same
 * shape as `LivenessProbeTest`: a transient gap shorter than the budget is
 * RIDDEN THROUGH (no dead-peer reaction); a sustained dead peer IS declared
 * within `countMax × interval`; reset-on-inbound suppresses the ping on a busy
 * link. The connected/real-sshd round-trip + the >100s no-KEX-corruption soak
 * live in the integration test; these are the always-runnable per-push gate that
 * keeps the loop's contract from silently regressing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportKeepAliveTest {

    private class FakeIo(
        var alive: Boolean = true,
    ) : TransportKeepAlive.KeepAliveIo {
        var pingResult: Boolean = true
        var pingsSent: Int = 0
        var deadDeclaredWith: Int? = null
        var lastActivityNanos: Long = Long.MIN_VALUE // "no recent activity"
        // Issue #1072 — the most recent OUTBOUND upload-progress timestamp. Default
        // MIN_VALUE ("no recent outbound") so existing tests behave exactly as before
        // (inbound-only); the #1072 tests advance it to model a streaming upload.
        var lastOutboundActivityNanos: Long = Long.MIN_VALUE

        override fun isAlive(): Boolean = alive
        override fun lastInboundActivityNanos(): Long = lastActivityNanos
        override fun lastOutboundActivityNanos(): Long = lastOutboundActivityNanos
        override suspend fun sendKeepAlive(): Boolean {
            pingsSent += 1
            return pingResult
        }
        override fun onKeepAliveDead(consecutiveMisses: Int) {
            deadDeclaredWith = consecutiveMisses
        }
    }

    @Test
    fun `transient gap shorter than the budget is ridden through - no dead-peer reaction`() = runTest {
        // The Terminus behaviour: a brief gap (here TWO consecutive missed pings,
        // budget is 3) recovers before countMax, so the peer is NEVER declared
        // dead — exactly where PocketShell used to redial.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(
            io = io,
            intervalMs = 1_000L,
            countMax = 3,
        )
        keepAlive.start(this)

        // Two ticks miss (link starved)...
        io.pingResult = false
        advanceTimeBy(1_000L); runCurrent() // miss 1
        advanceTimeBy(1_000L); runCurrent() // miss 2
        assertEquals("no dead-peer reaction yet (under countMax)", null, io.deadDeclaredWith)

        // ...then the link recovers BEFORE the 3rd consecutive miss.
        io.pingResult = true
        advanceTimeBy(1_000L); runCurrent() // recover

        // A long quiet healthy run after recovery: still no dead-peer reaction.
        repeat(10) { advanceTimeBy(1_000L); runCurrent() }
        assertEquals(
            "a transient gap shorter than the keepalive budget must be ridden through " +
                "(no dead-peer declaration)",
            null,
            io.deadDeclaredWith,
        )

        keepAlive.stop()
    }

    @Test
    fun `a genuinely dead peer is declared within countMax consecutive misses`() = runTest {
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(
            io = io,
            intervalMs = 1_000L,
            countMax = 3,
        )
        keepAlive.start(this)

        io.pingResult = false // dead peer: every ping misses
        advanceTimeBy(1_000L); runCurrent() // miss 1
        assertEquals(null, io.deadDeclaredWith)
        advanceTimeBy(1_000L); runCurrent() // miss 2
        assertEquals(null, io.deadDeclaredWith)
        advanceTimeBy(1_000L); runCurrent() // miss 3 -> declare dead

        assertEquals(
            "a dead peer must be declared at exactly countMax consecutive misses",
            3,
            io.deadDeclaredWith,
        )
        keepAlive.stop()
    }

    @Test
    fun `recent inbound activity skips the ping - a busy link is self-evidently alive`() = runTest {
        // OpenSSH reset-on-server-traffic: a link that produced bytes within the
        // last interval is NOT pinged, and a prior miss streak is reset.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(
            io = io,
            intervalMs = 1_000L,
            countMax = 3,
        )
        keepAlive.start(this)

        // Mark inbound activity "just now" for every tick: the loop must skip the
        // ping entirely across a long run, so a busy link sends ZERO keepalives.
        io.pingResult = false // would miss IF it pinged
        repeat(10) {
            io.lastActivityNanos = System.nanoTime()
            advanceTimeBy(1_000L)
            runCurrent()
        }
        assertEquals("a busy link must never be pinged", 0, io.pingsSent)
        assertEquals("a busy link must never be declared dead", null, io.deadDeclaredWith)
        keepAlive.stop()
    }

    @Test
    fun `inbound data flowing while keepalive replies are starved never declares dead`() = runTest {
        // Issue #974 — the MISSING #970 signature: a stable-but-jittery link where
        // every keepalive REPLY is starved (delayed/dropped past the ride-through
        // window) but the `-CC` reader is STILL delivering bytes. The reset-on-inbound
        // shortcut must keep the link alive off the live data, NOT tear it down on
        // the missed replies. The #970 gate only injected sub-budget stalls and never
        // this case.
        //
        // RED on base (production): RealSshSession bumped lastInboundActivityNanos
        // ONLY on a keepalive reply, so streaming `-CC` data never reset the miss
        // counter — exactly the spurious stable-Wi-Fi teardown. GREEN with the fix:
        // the live reader bumps the timestamp every interval, so even though every
        // ping misses, the loop SKIPS the ping (inbound activity) and never declares
        // dead. Here we model the fix by keeping lastActivity fresh each tick, which
        // is precisely what the production `-CC` reader now does.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        // Replies are ALWAYS starved — if the loop ever pinged it would miss.
        io.pingResult = false
        // Hold the link for FOUR budgets (12 ticks, budget = 3) with inbound DATA
        // arriving fresh every tick (the live `-CC` reader). The loop must ride
        // through indefinitely on the data alone.
        repeat(12) {
            io.lastActivityNanos = System.nanoTime() // live data just arrived
            advanceTimeBy(1_000L)
            runCurrent()
        }

        assertEquals(
            "a link with live inbound data must never be declared dead even when every " +
                "keepalive reply is starved — the streaming data proves it alive (#974)",
            null,
            io.deadDeclaredWith,
        )
        assertEquals(
            "the loop must SKIP every ping while inbound data is fresh (reset-on-inbound), " +
                "so a busy link with starved replies sends ZERO pings",
            0,
            io.pingsSent,
        )
        keepAlive.stop()
    }

    @Test
    fun `data stops AND replies starved - a genuinely dead link is still declared dead`() = runTest {
        // Issue #974 class-coverage (the dual): once the inbound data ALSO stops
        // (genuine half-open death — no bytes, no reply), the data-bump no longer
        // fires, the reset-on-inbound shortcut goes silent, and the loop MUST still
        // declare the peer dead within countMax. The fix must not make a truly-dead
        // link look alive forever.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        // Phase 1: data flowing + replies starved -> ridden through (no death).
        io.pingResult = false
        repeat(5) {
            io.lastActivityNanos = System.nanoTime()
            advanceTimeBy(1_000L); runCurrent()
        }
        assertEquals("ridden through while data flowed", null, io.deadDeclaredWith)

        // Phase 2: data STOPS (no more bumps) and replies stay starved -> dead.
        io.lastActivityNanos = Long.MIN_VALUE // no inbound activity any more
        advanceTimeBy(1_000L); runCurrent() // miss 1
        advanceTimeBy(1_000L); runCurrent() // miss 2
        advanceTimeBy(1_000L); runCurrent() // miss 3 -> declare dead
        assertEquals(
            "once inbound data ALSO stops, a genuinely dead link must still be declared " +
                "dead within countMax — the #974 data-bump must not mask a real death",
            3,
            io.deadDeclaredWith,
        )
        keepAlive.stop()
    }

    @Test
    fun `outbound upload progress alone rides through starved replies - never declares dead (#1072)`() = runTest {
        // Issue #1072 (the maintainer's "attaching breaks the connection"): a large
        // attachment upload over a QUIET `-CC` session is almost pure OUTBOUND —
        // `cat > tmp` emits nothing until EOF, so ZERO inbound bytes arrive for the
        // whole upload, and on a slow/high-RTT link not even a keepalive reply lands.
        // Before #1072 the loop keyed liveness off inbound ONLY, so it declared the
        // peer dead and tore the LIVE transport down mid-upload. With outbound folded
        // into the reset-on-activity / ride-through decision, a steadily-progressing
        // upload (outbound advancing every tick) proves the peer is consuming our
        // window, so the loop SKIPS the ping and NEVER declares dead.
        //
        // RED on base: revert the loop's `lastActivityNanos()` helper back to
        // `lastInboundActivityNanos()` and this fails — inbound stays MIN_VALUE
        // (no server bytes), every ping misses, and it declares dead within countMax.
        // GREEN with the fix: outbound advancing rides it through.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        // Inbound is FROZEN stale the whole time (quiet `-CC`, no server bytes), and
        // any ping that DID fire would miss (starved replies on a saturated uplink).
        io.lastActivityNanos = Long.MIN_VALUE
        io.pingResult = false
        // Hold for FOUR budgets (12 ticks, budget = 3) with OUTBOUND progress fresh
        // every tick — the streaming upload. The loop must ride through indefinitely.
        repeat(12) {
            io.lastOutboundActivityNanos = System.nanoTime() // a chunk just went out
            advanceTimeBy(1_000L)
            runCurrent()
        }

        assertEquals(
            "a steadily-progressing upload (outbound advancing) must NEVER be declared " +
                "dead even with zero inbound and starved keepalive replies — the outbound " +
                "progress proves the transport alive (#1072). RED on base: outbound was " +
                "ignored, so the loop pinged, every ping missed, and it tore the live " +
                "transport down mid-upload.",
            null,
            io.deadDeclaredWith,
        )
        assertEquals(
            "the loop must SKIP every ping while outbound is fresh (reset-on-activity), so " +
                "a saturated uplink with an in-flight upload sends ZERO pings",
            0,
            io.pingsSent,
        )
        keepAlive.stop()
    }

    @Test
    fun `upload finishes then transport genuinely dies - still declared dead within budget (#1072)`() = runTest {
        // Issue #1072 class-coverage (the genuine-death contract the fix must preserve):
        // once the upload FINISHES (outbound stops advancing) AND the link is genuinely
        // dead (no inbound, no reply), the outbound bump no longer fires, the
        // reset-on-activity shortcut goes silent, and the loop MUST still declare the
        // peer dead within countMax. The outbound proof must NOT make a truly-dead link
        // look alive forever.
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        // Phase 1: upload streaming (outbound fresh) + replies starved -> ridden through.
        io.lastActivityNanos = Long.MIN_VALUE
        io.pingResult = false
        repeat(5) {
            io.lastOutboundActivityNanos = System.nanoTime()
            advanceTimeBy(1_000L); runCurrent()
        }
        assertEquals("ridden through while the upload streamed", null, io.deadDeclaredWith)

        // Phase 2: upload DONE (outbound stops) and the link is dead (no inbound) -> dead.
        io.lastOutboundActivityNanos = Long.MIN_VALUE // no more outbound progress
        advanceTimeBy(1_000L); runCurrent() // miss 1
        advanceTimeBy(1_000L); runCurrent() // miss 2
        advanceTimeBy(1_000L); runCurrent() // miss 3 -> declare dead
        assertEquals(
            "once the upload ends AND the link is genuinely dead, the peer must STILL be " +
                "declared dead within countMax — the #1072 outbound bump must not mask a " +
                "real death once outbound progress stops",
            3,
            io.deadDeclaredWith,
        )
        keepAlive.stop()
    }

    @Test
    fun `a single miss between successes never declares dead - counter resets`() = runTest {
        val io = FakeIo()
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        // miss, success, miss, success, ... never reaches 3 in a row.
        repeat(20) { i ->
            io.pingResult = (i % 2 == 1)
            advanceTimeBy(1_000L)
            runCurrent()
        }
        assertEquals(
            "an isolated miss between successes must never declare dead (counter resets)",
            null,
            io.deadDeclaredWith,
        )
        keepAlive.stop()
    }

    @Test
    fun `the loop ends when the transport is no longer alive - no ping after`() = runTest {
        val io = FakeIo(alive = true)
        val keepAlive = TransportKeepAlive(io = io, intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        advanceTimeBy(1_000L); runCurrent()
        val pingsWhileAlive = io.pingsSent
        assertTrue("should have pinged while alive", pingsWhileAlive >= 1)

        io.alive = false // transport closed
        repeat(5) { advanceTimeBy(1_000L); runCurrent() }
        assertEquals(
            "no keepalive should be sent once the transport is no longer alive",
            pingsWhileAlive,
            io.pingsSent,
        )
        keepAlive.stop()
    }

    @Test
    fun `default budget matches a conservative OpenSSH client - 30s x 3 = 90s`() {
        // Pin the documented budget so a future retune that brushes the floor is
        // a visible diff (mirrors LivenessProbeBudgetUnder55sTest's intent).
        assertEquals(30_000L, TransportKeepAlive.DEFAULT_INTERVAL_MS)
        assertEquals(3, TransportKeepAlive.DEFAULT_COUNT_MAX)
        val worstCaseMs = TransportKeepAlive.DEFAULT_INTERVAL_MS * TransportKeepAlive.DEFAULT_COUNT_MAX
        assertEquals(
            "worst-case dead-peer detection must be ~90s (Terminus-parity ServerAlive 30x3)",
            90_000L,
            worstCaseMs,
        )
    }

    /**
     * Issue #964 — the ride-through budget the app-level `LivenessProbe` defers to
     * MUST equal the keepalive's own worst-case window and MUST stay STRICTLY
     * LONGER than the probe's raw ~48s detection budget. If the keepalive's
     * ride-through were ever shorter than (or equal to) the probe's budget, the
     * probe's deferral guard would expire BEFORE the keepalive finished proving the
     * link alive — re-opening the exact spurious-reconnect-on-slow-wifi bug #964
     * fixed. This pins the single coherent liveness budget so the two mechanisms
     * can never re-diverge.
     *
     * (The probe's raw budget — 4 × (7s + 5s) = 48s — lives in `core-connection`,
     * which `core-ssh` does not depend on, so it is referenced here as the
     * documented constant; the matching guard on the probe side asserts the
     * deferral behaviour itself.)
     */
    @Test
    fun `issue 964 ride-through budget is the keepalive worst-case and exceeds the probe budget`() {
        assertEquals(
            "the ride-through budget the probe defers to must be derived from the " +
                "keepalive's own interval x countMax (one coherent number, not a " +
                "second hard-coded one)",
            TransportKeepAlive.DEFAULT_INTERVAL_MS * TransportKeepAlive.DEFAULT_COUNT_MAX,
            TransportKeepAlive.RIDE_THROUGH_BUDGET_MS,
        )
        assertEquals(
            "the documented ride-through window is 90s",
            90_000L,
            TransportKeepAlive.RIDE_THROUGH_BUDGET_MS,
        )
        // The probe's raw worst-case budget (core-connection LivenessProbe:
        // 4 × (7s + 5s) = 48s). The keepalive ride-through MUST strictly exceed it
        // so the probe's deferral never expires before the keepalive proves the
        // link alive (the #964 coordination guarantee).
        val probeRawBudgetMs = 48_000L
        assertTrue(
            "the keepalive ride-through (${TransportKeepAlive.RIDE_THROUGH_BUDGET_MS}ms) must " +
                "stay STRICTLY longer than the probe's raw detection budget (${probeRawBudgetMs}ms) " +
                "so the probe defers to the keepalive on a slow-but-live link (#964)",
            TransportKeepAlive.RIDE_THROUGH_BUDGET_MS > probeRawBudgetMs,
        )
    }
}
