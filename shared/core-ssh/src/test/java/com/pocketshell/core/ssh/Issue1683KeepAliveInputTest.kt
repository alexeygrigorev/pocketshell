package com.pocketshell.core.ssh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1683 (A3) — the transport keepalive records its INPUTS through the new
 * minimal core-ssh diagnostics seam ([SshDiagnostics]). core-ssh previously
 * emitted NOTHING to the exported trace, so a `KeepaliveDead` VERDICT arrived
 * with none of the per-tick miss inputs that tell an over-eager false-dead from
 * a real silent-peer death.
 *
 * On base [SshDiagnostics] does not exist and the loop records nothing, so these
 * assertions fail; with the fix every miss and the death-budget crossing are on
 * the timeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1683KeepAliveInputTest {

    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun installSink() {
        events.clear()
        SshDiagnostics.install { event, fields ->
            synchronized(events) { events += event to fields }
        }
    }

    @After
    fun removeSink() {
        SshDiagnostics.reset()
    }

    private class FakeIo : TransportKeepAlive.KeepAliveIo {
        override fun isAlive(): Boolean = true
        override fun lastInboundActivityNanos(): Long = Long.MIN_VALUE
        override fun lastOutboundActivityNanos(): Long = Long.MIN_VALUE
        override suspend fun sendKeepAlive(): Boolean = false // dead peer: every ping misses
        override fun onKeepAliveDead(consecutiveMisses: Int) = Unit
    }

    @Test
    fun `every keepalive miss and the death-budget crossing are recorded as inputs`() = runTest {
        val keepAlive = TransportKeepAlive(io = FakeIo(), intervalMs = 1_000L, countMax = 3)
        keepAlive.start(this)

        advanceTimeBy(1_000L); runCurrent() // miss 1
        advanceTimeBy(1_000L); runCurrent() // miss 2
        advanceTimeBy(1_000L); runCurrent() // miss 3 -> death budget crossed

        val misses = synchronized(events) { events.filter { it.first == "keepalive_miss" } }
        assertEquals("every miss tick is an INPUT", 3, misses.size)
        assertEquals("first miss carries the running count", 1L, (misses[0].second["consecutiveMisses"] as Number).toLong())
        assertEquals("third miss carries the running count", 3L, (misses[2].second["consecutiveMisses"] as Number).toLong())
        assertEquals("miss carries the death budget it climbs toward", 3L, (misses[0].second["countMax"] as Number).toLong())

        val crossings = synchronized(events) {
            events.filter { it.first == "keepalive_death_budget_crossed" }
        }
        assertTrue("the death-budget crossing must be recorded", crossings.isNotEmpty())
        assertEquals(
            "a sustained-silence crossing declares dead (no inbound advance)",
            "declared_dead",
            crossings.last().second["outcome"],
        )
        assertEquals(
            "and records that inbound activity did NOT advance during the streak",
            false,
            crossings.last().second["inboundActivityAdvanced"],
        )

        keepAlive.stop()
    }
}
