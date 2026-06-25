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

        override fun isAlive(): Boolean = alive
        override fun lastInboundActivityNanos(): Long = lastActivityNanos
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
}
