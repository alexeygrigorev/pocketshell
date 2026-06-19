package com.pocketshell.core.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, virtual-clock unit tests for [LivenessProbe] (EPIC #792 Slice
 * D). The probe loop's cadence is pure [delay], so a [StandardTestDispatcher]
 * advances the probe window with ZERO wall-clock waiting — the analogue of the
 * `BackgroundGraceTestOverride` seam, and the same lever that lets the connected
 * journey shorten the window without weakening an assertion or self-skipping.
 *
 * These pin BOTH the headline behaviour (a sustained drop fires
 * [LivenessProbe.ProbeIo.onProbeFailed]) AND the cardinal no-false-positive
 * requirements (a healthy channel, a momentary stall, a busy-but-eventually-OK
 * channel, a backgrounded session, and a probe that times out once then
 * recovers all do NOT declare a drop).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivenessProbeTest {

    /**
     * A scriptable [LivenessProbe.ProbeIo] double. [probeResults] is consumed in
     * order; [probeDelayMs] simulates per-probe latency (so a [withTimeoutOrNull]
     * timeout can be exercised). [shouldProbe] is settable so the gate can be
     * flipped (background / not-Live).
     */
    private class FakeProbeIo(
        private val probeResults: ArrayDeque<Boolean> = ArrayDeque(),
        var shouldProbe: Boolean = true,
    ) : LivenessProbe.ProbeIo {
        var probeCount = 0
            private set
        var onProbeFailedCount = 0
            private set
        var lastConsecutiveFailures = 0
            private set

        /** Per-probe simulated latency (defaults to instantaneous). */
        var probeDelayMs: Long = 0

        fun enqueue(vararg results: Boolean) {
            results.forEach { probeResults.addLast(it) }
        }

        override fun shouldProbe(): Boolean = shouldProbe

        override suspend fun probe(): Boolean {
            probeCount++
            if (probeDelayMs > 0) delay(probeDelayMs)
            // Default to alive when the script runs out (healthy steady state).
            return probeResults.removeFirstOrNull() ?: true
        }

        override fun onProbeFailed(consecutiveFailures: Int) {
            onProbeFailedCount++
            lastConsecutiveFailures = consecutiveFailures
        }
    }

    @Test
    fun `healthy channel never declares a drop`() = runTest(StandardTestDispatcher()) {
        val io = FakeProbeIo()
        io.enqueue(true, true, true, true, true)
        val probe = LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
        probe.start(this)

        advanceTimeBy(550) // ~5 intervals
        runCurrent()

        assertTrue("probe should have fired several times", io.probeCount >= 5)
        assertEquals("no drop on a healthy channel", 0, io.onProbeFailedCount)
        probe.stop()
    }

    @Test
    fun `sustained drop fires onProbeFailed after the threshold`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo()
            io.enqueue(true, false, false) // healthy, then two consecutive deaths
            val probe =
                LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
            probe.start(this)

            // Interval 1 (alive): no drop.
            advanceTimeBy(110)
            runCurrent()
            assertEquals(0, io.onProbeFailedCount)

            // Interval 2 (first failure): below threshold, still no drop.
            advanceTimeBy(110)
            runCurrent()
            assertEquals("one failure is below threshold=2", 0, io.onProbeFailedCount)

            // Interval 3 (second consecutive failure): threshold reached → drop.
            advanceTimeBy(110)
            runCurrent()
            assertEquals("two consecutive failures declare the drop", 1, io.onProbeFailedCount)
            assertEquals(2, io.lastConsecutiveFailures)

            probe.stop()
        }

    @Test
    fun `a single failure between successes never declares a drop (no false positive)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo()
            // A momentary stall: fail once, then recover — the counter resets, so
            // a healthy-but-occasionally-slow channel must never trip.
            io.enqueue(true, false, true, false, true, false, true)
            val probe =
                LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
            probe.start(this)

            advanceTimeBy(800) // ~7 intervals
            runCurrent()

            assertEquals(
                "alternating fail/success never reaches 2 consecutive failures",
                0,
                io.onProbeFailedCount,
            )
            probe.stop()
        }

    @Test
    fun `a per-probe timeout counts as a failure`() = runTest(StandardTestDispatcher()) {
        val io = FakeProbeIo(shouldProbe = true)
        // Every probe hangs longer than the per-probe timeout → each is a failure.
        io.probeDelayMs = 5_000
        val probe =
            LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
        probe.start(this)

        // Interval 1: 100ms wait + 1000ms timeout = first failure at ~1100ms.
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals("first timed-out probe is below threshold", 0, io.onProbeFailedCount)

        // Interval 2: another 100ms + 1000ms timeout → second failure → drop.
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals("two consecutive timeouts declare the drop", 1, io.onProbeFailedCount)
        probe.stop()
    }

    @Test
    fun `does not probe or declare a drop while backgrounded (D21)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(shouldProbe = false)
            io.enqueue(false, false, false, false) // would be a drop IF it probed
            val probe =
                LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
            probe.start(this)

            advanceTimeBy(550)
            runCurrent()

            assertEquals("gate closed → no probe IO at all", 0, io.probeCount)
            assertEquals("gate closed → no drop declared", 0, io.onProbeFailedCount)
            probe.stop()
        }

    @Test
    fun `a partial failure run is reset when the gate closes mid-run`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(shouldProbe = true)
            io.enqueue(false) // one failure while live
            val probe =
                LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 2)
            probe.start(this)

            // Interval 1: one failure (below threshold).
            advanceTimeBy(110)
            runCurrent()
            assertEquals(0, io.onProbeFailedCount)

            // App backgrounds: the gate closes. The next tick must RESET the
            // partial failure count so a single pre-background failure does not
            // combine with a later post-foreground failure into a false drop.
            io.shouldProbe = false
            advanceTimeBy(110)
            runCurrent()

            // Foreground again, one fresh failure: that is the FIRST failure of a
            // new run, still below threshold → no drop.
            io.shouldProbe = true
            io.enqueue(false)
            advanceTimeBy(110)
            runCurrent()
            assertEquals(
                "a pre-background failure must not combine with a post-foreground one",
                0,
                io.onProbeFailedCount,
            )
            probe.stop()
        }

    @Test
    fun `the gate is re-checked before firing the drop`() = runTest(StandardTestDispatcher()) {
        // The probe times out (latency > timeout); during that timeout the app
        // backgrounds. When the loop resumes to ACT on the threshold it must
        // re-check shouldProbe and NOT fire onProbeFailed (the single grace owner
        // now governs recovery).
        val gate = CompletableDeferred<Unit>()
        val io = object : LivenessProbe.ProbeIo {
            var probeCount = 0
            var onProbeFailedCount = 0
            var shouldProbe = true
            override fun shouldProbe(): Boolean = shouldProbe
            override suspend fun probe(): Boolean {
                probeCount++
                // Hang forever (until the loop's timeout cancels us).
                delay(Long.MAX_VALUE / 2)
                return true
            }
            override fun onProbeFailed(consecutiveFailures: Int) {
                onProbeFailedCount++
            }
        }
        val probe =
            LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000, failureThreshold = 1)
        probe.start(this)

        // Let the first probe start and time out; flip the gate closed BEFORE the
        // loop resumes to act on the (threshold=1) failure.
        advanceTimeBy(150) // enter the probe
        runCurrent()
        io.shouldProbe = false
        advanceTimeBy(2_000) // blow past the per-probe timeout
        runCurrent()

        assertTrue("the probe did run", io.probeCount >= 1)
        assertEquals(
            "a drop must NOT fire if the gate closed during the probe window",
            0,
            io.onProbeFailedCount,
        )
        // Stop the loop BEFORE the test scope drains so the periodic delay does
        // not leave an uncompleted coroutine.
        probe.stop()
        runCurrent()
    }

    @Test
    fun `start is idempotent`() = runTest(StandardTestDispatcher()) {
        val io = FakeProbeIo()
        val probe = LivenessProbe(io, intervalMs = 100, perProbeTimeoutMs = 1_000)
        probe.start(this)
        probe.start(this) // second start must not double the loop
        advanceTimeBy(550)
        runCurrent()
        // ~5 intervals → ~5 probes, NOT ~10 (no doubled loop).
        assertTrue("idempotent start did not double the loop", io.probeCount in 4..6)
        probe.stop()
    }

    @Test
    fun `constructor rejects invalid parameters`() {
        val io = FakeProbeIo()
        listOf(
            { LivenessProbe(io, intervalMs = 0) },
            { LivenessProbe(io, perProbeTimeoutMs = 0) },
            { LivenessProbe(io, failureThreshold = 0) },
        ).forEach { build ->
            var threw = false
            try {
                build()
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("expected IllegalArgumentException", threw)
        }
    }
}
