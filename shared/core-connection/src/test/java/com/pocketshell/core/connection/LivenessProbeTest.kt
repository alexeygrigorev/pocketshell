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

    // ---------------------------------------------------------------------
    // Issue #927 — flaky-but-alive tolerance (reproduce-first, D33/G10).
    //
    // The maintainer dogfood: "on the same wifi Terminus stays connected but
    // PocketShell keeps restarting". Root cause (#895 spike): the OLD policy
    // (threshold = 2) declared a drop — and force-redialed the warm lease (the
    // visible "restart") — after just TWO back-to-back missed `refresh-client`
    // probes (~16–26s), which a congested-but-ALIVE link (bufferbloat / a heavy
    // `%output` burst parking the probe behind the control-mode FIFO) hits
    // routinely. These pin BOTH halves with the PRODUCTION defaults so the
    // red→green is demonstrable by reverting the companion constants:
    //   * congested-but-alive: 3 consecutive misses then a recovery must RIDE
    //     THROUGH (no drop). RED on the old threshold=2, GREEN on the new =4.
    //   * real dead peer: sustained silence must STILL declare a drop, within
    //     the documented worst-case budget (#822 not regressed).
    //   * the budget itself must stay strictly under the D3 < 55s ceiling (the
    //     probe is the SOLE dead-peer detector — no keepalive backstop, #847 —
    //     so its budget must not race the three coupled 60s grace/lease windows).
    // ---------------------------------------------------------------------

    /**
     * #927 congested-but-alive (the headline reproduce-first case). With the
     * SHIPPING defaults, a flaky link that misses 3 probes back-to-back and then
     * answers must NOT declare a drop — the warm lease is preserved. This FAILS
     * red on the pre-#927 `DEFAULT_FAILURE_THRESHOLD = 2` (two misses already trip
     * the drop) and passes green on `= 4`.
     */
    @Test
    fun `issue 927 flaky-but-alive link with 3 back-to-back misses rides through (no drop)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo()
            // A congested-but-alive burst: three consecutive missed round-trips
            // (the reply parked behind a %output storm / bufferbloat) then the
            // link answers again. A conservative client rides this through.
            io.enqueue(true, false, false, false, true, true, true)
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            advanceTimeBy(900) // ~8 intervals — covers the whole burst + recovery
            runCurrent()

            assertEquals(
                "a flaky-but-alive link (3 back-to-back misses then recovery) must " +
                    "NOT declare a drop with the shipping threshold (#927)",
                0,
                io.onProbeFailedCount,
            )
            probe.stop()
        }

    /**
     * #927 / D3 — the HARD detection-budget ceiling (the durable safety rail, D31).
     *
     * The probe is the SOLE dead-peer detector (no SSH keepalive backstop — #847),
     * so its worst-case budget IS the entire #822 guarantee, and it MUST stay
     * comfortably below the three coupled 60s windows (lease idle TTL, passive
     * grace, controller grace). The D3 research set a hard ceiling of strictly
     * `< 55s`; this asserts an even tighter `< 50s` (the shipping budget is 48s) so
     * ANY future bump that raises the threshold / interval / timeout toward the 60s
     * floor FAILS at PR time instead of silently regressing #822.
     *
     * This is the test the reviewer required: it FAILS on a #927 candidate that
     * pushes the budget past the ceiling (e.g. the previous 8s/6s/4 = 56s, which is
     * > 50s and > 55s).
     */
    @Test
    fun `issue 927 worst-case detection budget is strictly under the 55s D3 ceiling`() {
        val worstCaseMs =
            LivenessProbe.DEFAULT_FAILURE_THRESHOLD *
                (LivenessProbe.DEFAULT_INTERVAL_MS + LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS)
        // Hard ceiling: < 50s (the D3 < 55s floor with margin). The shipping
        // budget is 48s; the previous 8s/6s/4 = 56s candidate must NOT pass this.
        assertTrue(
            "the probe is the SOLE dead-peer detector (no keepalive backstop, #847); " +
                "its worst-case budget must stay strictly under the < 55s D3 ceiling " +
                "(asserted < 50s for margin below the three coupled 60s grace/lease " +
                "windows) so #927's threshold raise cannot regress #822. " +
                "worstCase=${worstCaseMs}ms",
            worstCaseMs < 50_000L,
        )
        // And it must remain generous enough to absorb a legitimate flaky-but-alive
        // burst (the #927 point): at least threshold-1 = 3 consecutive misses ride
        // through, so the raw budget cannot collapse back toward the aggressive
        // pre-#927 ~16-26s.
        assertTrue(
            "the budget must remain generous enough that the threshold absorbs a " +
                "flaky-but-alive burst (not collapse back to the aggressive pre-#927 " +
                "policy); worstCase=${worstCaseMs}ms",
            worstCaseMs >= 36_000L,
        )
    }

    /**
     * #927 real dead peer (the #822 non-regression). Sustained silence (every
     * probe a miss) must STILL declare a drop with the shipping defaults, WITHIN
     * the documented worst-case budget. Uses the PRODUCTION knobs so the assertion
     * is on the real ladder.
     */
    @Test
    fun `issue 927 genuine dead peer still declares a drop within the worst-case budget (822 not regressed)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo()
            // Never answers — a genuine silent half-open drop.
            repeat(LivenessProbe.DEFAULT_FAILURE_THRESHOLD + 2) { io.enqueue(false) }
            // Each probe times out at the shipping per-probe timeout so the
            // virtual-clock budget matches the real worst case.
            io.probeDelayMs = LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS + 500
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = LivenessProbe.DEFAULT_INTERVAL_MS,
                    perProbeTimeoutMs = LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            val worstCaseMs =
                LivenessProbe.DEFAULT_FAILURE_THRESHOLD *
                    (LivenessProbe.DEFAULT_INTERVAL_MS + LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS)
            advanceTimeBy(worstCaseMs + 1_000)
            runCurrent()

            assertEquals(
                "a genuine silent half-open drop must still declare a drop within " +
                    "the worst-case budget (#822 not regressed)",
                1,
                io.onProbeFailedCount,
            )
            assertEquals(
                LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                io.lastConsecutiveFailures,
            )
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
