package com.pocketshell.core.connection

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
 * Issue #1676 — the deterministic MEASUREMENT that classifies the nightly phase-2
 * disconnect-band cohort (`RideThroughInterruptionE2eTest.sustained`,
 * `DisconnectBlackholeE2eTest`, `NatIdleMappingSurvivalE2eTest`,
 * `ColdDialUnderBandwidthLimitE2eTest`) as a HARNESS wall-clock FLAKE, not a
 * slow-detection `core-connection` regression (D33 / G10 reproduce-first, G2 class
 * coverage, G6 load-bearing).
 *
 * ## What the cohort observed vs what this measures
 *
 * On ~6/8 nights the four E2E proofs failed together at `waitForDisconnectBand`'s
 * old flat **35s** wall-clock ceiling; on the 2 "good" nights they passed. That
 * all-or-nothing correlation across independent toxics points at ONE per-night
 * variable — a runner-speed collapse on the CI swiftshader emulator — NOT four
 * independent code faults. The decisive question the brief demands (measure, don't
 * guess): does the production disconnect DETECTION genuinely exceed the budget (a
 * real bug), or is it the test's wall-clock ceiling racing a starved runner (a
 * flake)?
 *
 * The disconnect band is surfaced by the app-level half-open / `-CC`-wedged
 * detector [LivenessProbe]. Its loop cadence is pure [delay], so a virtual clock
 * advances the WHOLE detection window with ZERO wall-clock. This test drives the
 * REAL [LivenessProbe] at its PRODUCTION default budget against a genuinely dead
 * half-open channel (every probe hangs past its per-probe timeout, transport not
 * proven-alive) and MEASURES the exact virtual time the drop is declared.
 *
 * ## The result (the number, deterministic)
 *
 *  - Detection fires at EXACTLY the documented worst case
 *    `failureThreshold × (intervalMs + perProbeTimeoutMs)` = `4 × (7s + 5s)` = **48s**
 *    of VIRTUAL time — every run, regardless of hardware — while the test consumes
 *    only milliseconds of WALL-CLOCK. The detection latency is a function of the
 *    scheduler cadence, not the runner's speed.
 *  - The pre-#1676 E2E budget (**35s**) was SMALLER than this 48s production worst
 *    case: a perfectly-functioning half-open detection could hit its own documented
 *    budget and STILL time out the test. On the slower swiftshader nights the
 *    `delay()`-driven ticks stretched further, blowing 35s outright — the cohort.
 *  - The #1676 fix raises the E2E budget to **90s** (> 48s + swiftshader
 *    tick-stretch headroom), so the load-bearing "band surfaces within budget"
 *    assertion covers the real detector worst case without ever being satisfied by
 *    a non-surfacing regression (a never-firing detector still blows the 120s
 *    measure-past-budget capture ceiling).
 *
 * Because the detector is deterministic on a virtual clock, this test is
 * inherently reproducible: the N≥20 determinism bar is met by construction (no
 * randomness, no wall-clock, no I/O) and re-runnable in the per-push Unit gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1676DisconnectDetectionLatencyTest {

    /**
     * A genuinely DEAD half-open channel: `shouldProbe` stays open, the transport
     * is NOT proven-alive by the keepalive, and every probe HANGS past its
     * per-probe timeout (so the loop's `withTimeoutOrNull(perProbeTimeoutMs)`
     * elapses the full timeout each iteration — the WORST-CASE detection path the
     * 48s budget is derived for). Records the virtual time the drop is declared.
     */
    private class DeadHalfOpenProbeIo(
        private val probeHangMs: Long,
    ) : LivenessProbe.ProbeIo {
        var probeCount = 0
            private set
        var onProbeFailedCount = 0
            private set
        var lastConsecutiveFailures = 0
            private set

        override fun shouldProbe(): Boolean = true

        override suspend fun probe(): Boolean {
            probeCount++
            // Hang past the per-probe timeout so withTimeoutOrNull elapses the full
            // budget — the half-open blackhole (bytes silently dropped, no RST/FIN).
            delay(probeHangMs)
            return true // never reached before the timeout cancels this coroutine
        }

        override fun onProbeFailed(consecutiveFailures: Int) {
            onProbeFailedCount++
            lastConsecutiveFailures = consecutiveFailures
        }

        override fun transportProvenAliveRecently(): Boolean = false
    }

    @Test
    fun `half-open detection fires at its documented worst-case budget in virtual time only`() =
        runTest(StandardTestDispatcher()) {
            // PRODUCTION default budget — the exact numbers behind the 48s worst case.
            val io = DeadHalfOpenProbeIo(probeHangMs = LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS * 2)
            val probe = LivenessProbe(
                io = io,
                intervalMs = LivenessProbe.DEFAULT_INTERVAL_MS,
                perProbeTimeoutMs = LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS,
                failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
            )

            val wallStartNs = System.nanoTime()
            probe.start(this)

            var fireTimeMs = -1L
            var elapsed = 0L
            while (elapsed < CAPTURE_CEILING_MS && fireTimeMs < 0L) {
                advanceTimeBy(STEP_MS)
                runCurrent()
                elapsed += STEP_MS
                if (io.onProbeFailedCount >= 1) fireTimeMs = testScheduler.currentTime
            }
            val wallElapsedMs = (System.nanoTime() - wallStartNs) / 1_000_000L
            probe.stop()

            assertTrue(
                "the probe must have actually run against the dead channel " +
                    "(probeCount=${io.probeCount})",
                io.probeCount >= LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
            )
            assertEquals(
                "a sustained half-open drop must declare EXACTLY once at threshold",
                LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                io.lastConsecutiveFailures,
            )

            // THE MEASURED NUMBER: detection fires at exactly the documented worst
            // case, in VIRTUAL time. This is deterministic across every run — the
            // detection latency is a function of the scheduler cadence, not hardware.
            assertEquals(
                "half-open disconnect detection must fire at its documented worst case " +
                    "failureThreshold × (intervalMs + perProbeTimeoutMs)",
                PRODUCTION_HALF_OPEN_WORST_CASE_MS,
                fireTimeMs,
            )

            // DECOUPLED FROM WALL-CLOCK: 48s of virtual detection consumed only a
            // few ms of real time — so the E2E cohort's failures (a 35s WALL-CLOCK
            // ceiling blown on a starved swiftshader runner) are a HARNESS
            // under-budgeting flake, not slow detection. A real slow-detection
            // regression would move fireTimeMs (a VIRTUAL-time number) above the
            // worst case here, in the per-push Unit gate — long before any runner.
            assertTrue(
                "the 48s detection window is VIRTUAL: this deterministic test must " +
                    "complete in a fraction of the wall time it models (wall=${wallElapsedMs}ms)",
                wallElapsedMs < WALL_CLOCK_DECOUPLING_CEILING_MS,
            )
        }

    @Test
    fun `the E2E disconnect-band budget must exceed the production detection worst case`() {
        // The root cause + the fix, pinned. The pre-#1676 waitForDisconnectBand
        // budget (35s) was BELOW the production half-open detection worst case (48s)
        // — so a functioning detection could hit its documented budget and still
        // time out the test (the structural under-budgeting the cohort exposed on
        // slow nights). The #1676 fix raises NetworkFaultProofBase.DISCONNECT_BAND_
        // BUDGET_MS to 90s. These literals mirror the androidTest constants (a
        // separate sourceset, so referenced by value); if the production detector's
        // worst case is ever retuned above the E2E budget, THIS guard fails in the
        // per-push Unit gate rather than as a mysterious nightly flake.
        assertEquals(
            "the LivenessProbe half-open worst case must stay the documented 48s the " +
                "E2E budget is derived against",
            PRODUCTION_HALF_OPEN_WORST_CASE_MS,
            LivenessProbe.DEFAULT_FAILURE_THRESHOLD *
                (LivenessProbe.DEFAULT_INTERVAL_MS + LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS),
        )
        assertTrue(
            "root cause: the pre-#1676 35s E2E budget was BELOW the 48s production " +
                "detection worst case — a functioning detection could time out the test",
            PRODUCTION_HALF_OPEN_WORST_CASE_MS > OLD_E2E_DISCONNECT_BAND_BUDGET_MS,
        )
        assertTrue(
            "the fix: the #1676 90s E2E budget must exceed the 48s production worst " +
                "case with slow-swiftshader headroom (NetworkFaultProofBase." +
                "DISCONNECT_BAND_BUDGET_MS). If this fails the cohort will re-red.",
            NEW_E2E_DISCONNECT_BAND_BUDGET_MS > PRODUCTION_HALF_OPEN_WORST_CASE_MS,
        )
    }

    private companion object {
        /** `failureThreshold × (intervalMs + perProbeTimeoutMs)` = `4 × (7s + 5s)`. */
        const val PRODUCTION_HALF_OPEN_WORST_CASE_MS: Long = 48_000L

        /** The pre-#1676 flat `waitForDisconnectBand` budget (the root cause). */
        const val OLD_E2E_DISCONNECT_BAND_BUDGET_MS: Long = 35_000L

        /** The #1676 `NetworkFaultProofBase.DISCONNECT_BAND_BUDGET_MS` (the fix). */
        const val NEW_E2E_DISCONNECT_BAND_BUDGET_MS: Long = 90_000L

        /** Fine virtual-time step for precise fire-time capture (48000 % 250 == 0). */
        const val STEP_MS: Long = 250L

        /** Poll ceiling for the measurement (> the 48s worst case). */
        const val CAPTURE_CEILING_MS: Long = 120_000L

        /**
         * The 48s detection window is VIRTUAL; the deterministic test itself must
         * finish in a tiny fraction of that wall time. Generous so a loaded CI JVM
         * worker never flakes this, yet ≪ the 48s it models — proving the decoupling.
         */
        const val WALL_CLOCK_DECOUPLING_CEILING_MS: Long = 20_000L
    }
}
