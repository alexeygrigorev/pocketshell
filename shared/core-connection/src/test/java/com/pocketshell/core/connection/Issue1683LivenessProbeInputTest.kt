package com.pocketshell.core.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1683 (A3) — [LivenessProbe] records the dead-detection INPUTS, not just
 * the eventual `liveness_probe_silent_drop` VERDICT:
 *  - every MISS tick (miss count + latency),
 *  - every `transportProvenAliveRecently` consultation and its RESULT — the
 *    input to the defer-vs-escalate branch that decides whether the drop is an
 *    over-eager false-dead or a real death.
 *
 * On base the probe emits nothing to the diagnostics timeline (only `log()`
 * lines), so both assertions fail; with the fix the inputs are on the same
 * correlated timeline as the verdict.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1683LivenessProbeInputTest {

    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun installSink() {
        events.clear()
        ConnectionDiagnostics.install { event, fields ->
            synchronized(events) { events += event to fields }
        }
    }

    @After
    fun removeSink() {
        ConnectionDiagnostics.reset()
    }

    private class ScriptedIo(
        private val results: ArrayDeque<Boolean>,
        val keepAliveProvesAlive: Boolean,
    ) : LivenessProbe.ProbeIo {
        var onProbeFailedCount = 0
            private set

        override fun shouldProbe(): Boolean = true
        override suspend fun probe(): Boolean = results.removeFirstOrNull() ?: true
        override fun onProbeFailed(consecutiveFailures: Int) {
            onProbeFailedCount++
        }
        override fun transportProvenAliveRecently(): Boolean = keepAliveProvesAlive
    }

    @Test
    fun `miss ticks and the keepalive consultation are recorded before the drop`() =
        runTest(StandardTestDispatcher()) {
            // A monotonic clock advancing 12ms per read so latencyMs is deterministic.
            var t = 0L
            val io = ScriptedIo(ArrayDeque(listOf(false, false)), keepAliveProvesAlive = false)
            val probe = LivenessProbe(
                io = io,
                intervalMs = 100,
                perProbeTimeoutMs = 1_000,
                failureThreshold = 2,
                nowNanos = { (t++) * 12_000_000L },
            )
            probe.start(this)

            advanceTimeBy(110) // first miss
            runCurrent()
            advanceTimeBy(110) // second miss -> threshold -> consult -> drop
            runCurrent()

            assertEquals("the drop must fire once", 1, io.onProbeFailedCount)

            val ticks = synchronized(events) { events.filter { it.first == "liveness_probe_tick" } }
            val misses = ticks.filter { it.second["result"] == "miss" }
            assertTrue("both miss ticks must be recorded as INPUTS", misses.size >= 2)
            val firstMiss = misses.first().second
            assertEquals("miss tick carries the running miss count", 1L, (firstMiss["consecutiveMisses"] as Number).toLong())
            assertNotNull("miss tick carries per-tick latency", firstMiss["latencyMs"])
            assertEquals(
                "miss tick carries the threshold it is climbing toward",
                2L,
                (firstMiss["failureThreshold"] as Number).toLong(),
            )

            val consults = synchronized(events) {
                events.filter { it.first == "liveness_probe_keepalive_consult" }
            }
            assertEquals("exactly one keepalive consultation preceded the drop", 1, consults.size)
            val consult = consults.single().second
            assertEquals(
                "the consultation records the keepalive answer (the INPUT to defer-vs-escalate)",
                false,
                consult["keepAliveProvesAlive"],
            )
            assertEquals(
                "with the keepalive NOT proving alive, the probe declares the drop",
                true,
                consult["willDeclareDrop"],
            )

            probe.stop()
        }

    @Test
    fun `a deferred-to-keepalive consultation records that no drop will fire`() =
        runTest(StandardTestDispatcher()) {
            // Keepalive proves the transport alive: the probe DEFERS (no drop), and
            // the consultation must record that decision — a false-dead avoided is
            // exactly what an over-eager-vs-real audit needs to see.
            val io = ScriptedIo(ArrayDeque(listOf(false, false)), keepAliveProvesAlive = true)
            val probe = LivenessProbe(
                io = io,
                intervalMs = 100,
                perProbeTimeoutMs = 1_000,
                failureThreshold = 2,
                absoluteWedgeBudgetMs = 1_000_000,
            )
            probe.start(this)

            advanceTimeBy(220)
            runCurrent()

            assertEquals("keepalive proving alive must suppress the drop", 0, io.onProbeFailedCount)
            val consults = synchronized(events) {
                events.filter { it.first == "liveness_probe_keepalive_consult" }
            }
            assertTrue("the consultation must be recorded even when it DEFERS", consults.isNotEmpty())
            assertEquals(
                "the deferred consultation records keepalive proven alive",
                true,
                consults.first().second["keepAliveProvesAlive"],
            )
            assertEquals(
                "and records that no drop will fire",
                false,
                consults.first().second["willDeclareDrop"],
            )

            probe.stop()
        }
}
