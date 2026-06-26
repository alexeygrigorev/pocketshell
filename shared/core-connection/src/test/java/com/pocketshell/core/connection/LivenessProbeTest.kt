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
        /**
         * Issue #964 — the transport-keepalive liveness the probe defers to. When
         * `true` the transport keepalive is still riding through (the link is
         * provably alive), so the probe must NOT declare a drop even after N
         * consecutive control-channel probe failures. Default `false` preserves the
         * pre-#964 behaviour (no keepalive signal → the probe keeps its own
         * authority), so the existing tests are unaffected.
         */
        var transportProvenAliveRecently: Boolean = false,
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

        override fun transportProvenAliveRecently(): Boolean = transportProvenAliveRecently
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
    //   * the RAW budget must stay strictly under the D3 < 55s ceiling so when the
    //     probe IS the acting detector (no keepalive signal to defer to) it does
    //     not race the three coupled 60s grace/lease windows. (Post-#945/#964 the
    //     probe also DEFERS to the transport keepalive's ~90s ride-through while
    //     the link is provably alive — see the #964 cases below — but the raw
    //     budget still bounds the no-keepalive-signal tmux-control-wedge case.)
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
     * The probe's RAW worst-case budget is its bound when it IS the acting detector
     * (a tmux-control-wedge with no transport-keepalive liveness to defer to), so it
     * MUST stay comfortably below the three coupled 60s windows (lease idle TTL,
     * passive grace, controller grace). The D3 research set a hard ceiling of
     * strictly `< 55s`; this asserts an even tighter `< 50s` (the shipping budget is
     * 48s) so ANY future bump that raises the threshold / interval / timeout toward
     * the 60s floor FAILS at PR time instead of silently regressing #822. (Post-#964
     * the probe additionally DEFERS to the transport keepalive's ~90s ride-through
     * while the link is provably alive — that deferral is asserted by the #964 cases
     * — but this raw ceiling still guards the no-keepalive-signal path.)
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
            "the probe's RAW worst-case budget (its bound when no keepalive signal " +
                "is available to defer to, post-#964) must stay strictly under the " +
                "< 55s D3 ceiling (asserted < 50s for margin below the three coupled " +
                "60s grace/lease windows) so #927's threshold raise cannot regress " +
                "#822. worstCase=${worstCaseMs}ms",
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

    // ---------------------------------------------------------------------
    // Issue #964 / #982 / #984 / #822 — spurious reconnect on slow-but-live wifi,
    // WITHOUT regressing the wedged-`-CC` recovery (reproduce-first, D33/G10).
    //
    // Two foreground liveness mechanisms run together on DIFFERENT channels: the
    // transport keepalive (#945, TransportKeepAlive) pings the SSH TRANSPORT
    // (global-request, ~90s budget); this LivenessProbe (#927) pings the tmux
    // `-CC` CHANNEL (refresh-client/%output, ~48s budget). The #964 bug: on a
    // live-but-slow link the `-CC` probe declared dead at ~48s and force-redialed
    // a FINE link BEFORE the keepalive's ~90s could prove the transport alive.
    //
    // The #982/#984 fix DEFERS TO THE KEEPALIVE'S OWN DEATH SIGNAL with NO time
    // ceiling while it proves the transport alive (the deleted maxKeepAliveDeferrals
    // ~96s ceiling WAS the #974 stable-wifi false positive). Escalation is the
    // keepalive flipping false, OR a single high absolute wedge backstop (180s) for
    // the rare keepalive-healthy-forever wedge. These pin the whole class:
    //   (1) live-slow: `-CC` fails one run then RECOVERS while keepalive alive →
    //       NO redial (the #964 fix). RED on base, GREEN after.
    //   (2a) wedged-`-CC` + keepalive DEAD (#822 dominant): escalate within budget.
    //   (2b) wedged-`-CC` + keepalive HEALTHY FOREVER: defer with NO time ceiling,
    //        then escalate ONLY at the absolute wedge budget. #822 not suppressed,
    //        no #974 false positive.
    //   (2c) defer-forever across MANY budgets while keepalive alive (the #970
    //        gate's pure mirror — the gap the old ceiling could not span).
    //   (3) genuinely-dead transport: `-CC` fails AND keepalive sees nothing →
    //       redial within the raw budget (no infinite hang).
    //   (4) slow→dead transition: defer while keepalive alive, then redial the
    //       instant the keepalive ALSO gives up.
    //   (5) busy-channel (%output-burst parks the probe reply) → defer, no redial.
    // ---------------------------------------------------------------------

    /**
     * #964 headline reproduce-first: a live-but-slow link. The `-CC` probe fails
     * for a full failure run (the reply parked behind a slow / congested channel),
     * but the transport keepalive is still proving the link alive — and then the
     * `-CC` channel RECOVERS (the bufferbloat clears) before the deferral bound.
     * The probe must DEFER and NOT force a redial.
     *
     * RED on base: without the #964 deferral the probe fires `onProbeFailed` the
     * moment the threshold is reached (~48s in production), spuriously redialing a
     * fine link. GREEN with the fix: the keepalive signal holds the redial back and
     * the `-CC` recovery clears the deferral run.
     */
    @Test
    fun `issue 964 live-but-slow link rides through (keepalive proves alive, no redial)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = true)
            // One full failure run (threshold misses) — a slow `-CC` channel —
            // then the channel ANSWERS again (the link un-congests). The keepalive
            // proves the transport alive throughout.
            repeat(LivenessProbe.DEFAULT_FAILURE_THRESHOLD) { io.enqueue(false) }
            io.enqueue(true, true, true)
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            advanceTimeBy(LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 500)
            runCurrent()

            assertEquals(
                "while the transport keepalive proves the link alive, a slow `-CC` " +
                    "channel that recovers within the deferral bound must NOT force a " +
                    "redial (#964 — defer to the keepalive)",
                0,
                io.onProbeFailedCount,
            )
            assertTrue("the probe keeps probing while deferring", io.probeCount >= 4)
            probe.stop()
        }

    /**
     * #822 sub-case (a) — the DOMINANT real wedge: a half-open wifi drop wedges the
     * `-CC` channel AND kills the transport keepalive together. The new #982/#984
     * contract defers to the keepalive's OWN death signal, so the instant
     * `transportProvenAliveRecently` flips FALSE the probe escalates within the
     * keepalive budget. This is the path that recovers the maintainer's real #822.
     */
    @Test
    fun `issue 822 wedged control channel with dead keepalive escalates within the keepalive budget`() =
        runTest(StandardTestDispatcher()) {
            // `-CC` never answers AND the keepalive sees nothing either (a half-open
            // drop killed both channels) — transportProvenAliveRecently = false.
            val io = FakeProbeIo(transportProvenAliveRecently = false)
            repeat(LivenessProbe.DEFAULT_FAILURE_THRESHOLD + 4) { io.enqueue(false) }
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            // ONE failure run is enough — the keepalive death authority is false, so
            // escalation fires at the threshold (no time ceiling needed).
            advanceTimeBy(LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 100)
            runCurrent()
            probe.stop()
            runCurrent()

            assertTrue(
                "a wedged `-CC` channel whose keepalive ALSO died (#822, the dominant " +
                    "real case) must escalate within the keepalive budget once " +
                    "transportProvenAliveRecently flips false (count=${io.onProbeFailedCount})",
                io.onProbeFailedCount >= 1,
            )
        }

    /**
     * #822 sub-case (b) — the RARE wedge the absolute backstop exists for: the `-CC`
     * channel is genuinely WEDGED but the transport keepalive stays HEALTHY FOREVER
     * (`transportProvenAliveRecently = true` the whole time). Under the new
     * #982/#984 contract there is NO time ceiling while the keepalive proves the
     * transport alive — the ONLY escalation is the high [absoluteWedgeBudgetMs]
     * backstop. The probe must DEFER until that absolute budget, then escalate so the
     * truly-stuck channel still recovers (a blanket keepalive veto would re-open
     * #822). This pins BOTH the no-time-ceiling-defer AND the absolute-wedge escalate.
     */
    @Test
    fun `issue 822 wedged control channel with healthy keepalive defers until the absolute wedge budget then escalates`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = true)
            // `-CC` never answers; keepalive proves the transport alive the whole
            // time. Shorten the absolute wedge budget so the virtual clock can reach
            // it deterministically (production is 180s; the contract is identical).
            repeat(200) { io.enqueue(false) }
            val absoluteWedgeMs = 2_000L
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                    absoluteWedgeBudgetMs = absoluteWedgeMs,
                )
            probe.start(this)

            // BEFORE the absolute wedge budget elapses: the keepalive proves the link
            // alive, so the probe must DEFER unconditionally — NO escalation, even
            // though it has failed many full threshold runs. (The deleted
            // maxKeepAliveDeferrals would have escalated at ~96s here — the #974 bug.)
            advanceTimeBy(absoluteWedgeMs - 400)
            runCurrent()
            assertEquals(
                "while the keepalive proves the link alive and BEFORE the absolute wedge " +
                    "budget, a wedged `-CC` must NOT escalate (no fixed time ceiling — " +
                    "the deleted #982/#984 false positive)",
                0,
                io.onProbeFailedCount,
            )

            // AFTER the absolute wedge budget: the `-CC` has been failing continuously
            // past absoluteWedgeBudgetMs, so the backstop escalates even though the
            // keepalive still claims alive — the rare truly-wedged channel recovers.
            advanceTimeBy(
                absoluteWedgeMs + LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 200,
            )
            runCurrent()
            probe.stop()
            runCurrent()
            assertTrue(
                "a `-CC` channel WEDGED continuously past the absolute wedge budget must " +
                    "STILL escalate even with the keepalive healthy forever (#822 not " +
                    "suppressed; count=${io.onProbeFailedCount})",
                io.onProbeFailedCount >= 1,
            )
        }

    /**
     * #982/#984 the headline no-time-ceiling proof (the #970 gate's pure
     * virtual-clock mirror): the `-CC` channel is wedged for FAR longer than two
     * production probe budgets (the gap the old `maxKeepAliveDeferrals=1` ceiling
     * could not span without escalating at ~96s) while the keepalive proves the link
     * alive throughout and the absolute wedge budget is generous. The probe must
     * DEFER FOREVER — ZERO escalations. RED with the old time-ceiling, GREEN now.
     */
    @Test
    fun `issue 982 defers forever across many probe budgets while keepalive proves alive (no time ceiling)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = true)
            repeat(500) { io.enqueue(false) } // `-CC` never answers
            // Absolute wedge budget WELL beyond the window we advance, so only the
            // keepalive-alive defer-forever behaviour is under test here.
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                    absoluteWedgeBudgetMs = 10_000_000L,
                )
            probe.start(this)

            // Span MANY full threshold runs (≫ 2 budgets — the structural gap the
            // #970 deterministic gate now also covers at production scale).
            val budgets = 8
            advanceTimeBy(
                LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * budgets * 100 + 500,
            )
            runCurrent()

            assertEquals(
                "while the keepalive proves the link alive, the probe must DEFER " +
                    "across an UNBOUNDED number of `-CC` failure runs — ZERO escalation, " +
                    "no fixed time ceiling (#982/#984). The deleted maxKeepAliveDeferrals " +
                    "would have escalated after the first ~2 runs (count=${io.onProbeFailedCount})",
                0,
                io.onProbeFailedCount,
            )
            assertTrue("the probe keeps probing while deferring", io.probeCount >= budgets)
            probe.stop()
        }

    /**
     * #964 genuine dead peer (the #822-detection non-regression). The control probe
     * fails AND the keepalive also sees no activity (`transportProvenAliveRecently
     * = false`) — a real transport death. The probe must STILL declare a drop,
     * within its raw worst-case budget. Deferral must never become an infinite hang
     * on a real death.
     */
    @Test
    fun `issue 964 genuinely dead transport still declares a drop (no infinite hang)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = false)
            repeat(LivenessProbe.DEFAULT_FAILURE_THRESHOLD + 2) { io.enqueue(false) }
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            // Advance just one threshold window (threshold ticks at the 100ms
            // interval) then stop, so we observe exactly the FIRST drop and the
            // periodic loop does not re-arm on the still-dead channel.
            advanceTimeBy(LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 50)
            runCurrent()
            probe.stop()
            runCurrent()

            assertEquals(
                "a genuinely dead transport (keepalive sees nothing either) must STILL " +
                    "declare a drop within the raw budget — deferral is not an infinite hang",
                1,
                io.onProbeFailedCount,
            )
            assertEquals(LivenessProbe.DEFAULT_FAILURE_THRESHOLD, io.lastConsecutiveFailures)
        }

    /**
     * #964 the slow→dead transition. The keepalive proves the link alive for the
     * first failure run (defer, no redial), THEN the keepalive itself stops seeing
     * activity (transport genuinely died). The probe must then declare the drop —
     * proving deferral defers but does NOT permanently suppress real detection.
     */
    @Test
    fun `issue 964 defers while keepalive alive then declares once keepalive gives up`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = true)
            repeat(20) { io.enqueue(false) } // control channel never answers
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                    // A high absolute wedge budget keeps phase 1 in the pure DEFER
                    // state (no time-ceiling escalation while the keepalive is alive),
                    // so this test isolates the keepalive-gave-up trigger.
                    absoluteWedgeBudgetMs = 10_000_000L,
                )
            probe.start(this)

            // Phase 1: keepalive proving alive → deferral, no redial despite the
            // failed control probes.
            advanceTimeBy(LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 50)
            runCurrent()
            assertEquals(
                "phase 1: keepalive alive → no redial",
                0,
                io.onProbeFailedCount,
            )

            // Phase 2: the transport genuinely dies — the keepalive stops proving
            // liveness. The probe must now declare the drop within the raw budget.
            io.transportProvenAliveRecently = false
            advanceTimeBy(LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * 100 + 50)
            runCurrent()
            // Stop immediately so the periodic loop does not re-arm and re-fire on
            // the still-failing channel (the recovery path owns it after the drop).
            probe.stop()
            runCurrent()
            assertTrue(
                "phase 2: keepalive gave up → the probe declares the real drop " +
                    "(count=${io.onProbeFailedCount})",
                io.onProbeFailedCount >= 1,
            )
        }

    /**
     * #964 busy channel (%output burst). A heavy `%output` storm parks the
     * control-channel probe reply behind the FIFO, so the probe times out for one
     * failure run — but the keepalive sees the inbound `%output` bytes as transport
     * activity and proves the link alive, and the channel answers again once the
     * burst clears. The probe must DEFER, not redial mid-work.
     */
    @Test
    fun `issue 964 busy channel output burst defers to keepalive (no redial)`() =
        runTest(StandardTestDispatcher()) {
            val io = FakeProbeIo(transportProvenAliveRecently = true)
            // The probe reply is parked behind the %output FIFO for one failure run
            // → those probes time out; then the burst clears and the channel answers
            // within the per-probe timeout again.
            io.probeDelayMs = 1_500 // > the 1_000ms per-probe timeout
            val probe =
                LivenessProbe(
                    io,
                    intervalMs = 100,
                    perProbeTimeoutMs = 1_000,
                    failureThreshold = LivenessProbe.DEFAULT_FAILURE_THRESHOLD,
                )
            probe.start(this)

            // One failure run's worth of parked (timed-out) probes — the keepalive
            // defers it (within the bound) so no redial fires.
            advanceTimeBy(
                LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() * (100 + 1_000) + 500,
            )
            runCurrent()

            assertEquals(
                "a %output-burst-parked probe with a provably-alive keepalive must NOT " +
                    "redial mid-work within the deferral bound (#964)",
                0,
                io.onProbeFailedCount,
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
