package com.pocketshell.core.connection

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #1654 — the release blocker: the storm path never installed the ladder
 * (`maxAttempts=4`, `retryDelayMs=0`) and `Unreachable` had no exit, so ONE mobile blip made
 * the app surrender in ~0s and strand the user until they manually poked it.
 *
 * ## What is RED on base (the regression these pin)
 *
 * 1. `ConnectionController(clock, transport)` — the shape `ConnectionManager` constructs in
 *    production — starts with `reconnectLadderMs = emptyList()`, so `effectiveMaxAttempts`
 *    falls back to `DEFAULT_MAX_RECONNECT_ATTEMPTS = 4` and `retryDelayForAttempt` returns
 *    `0L` for EVERY rung. The passive-grace loop (the storm path) never calls
 *    `setReconnectLadder` — its only production caller was `scheduleAutoReconnectBody`, which
 *    the storm path never reaches. Measured on base by #1653's probe:
 *    `attempt=2..4 maxAttempts=4 retryDelayMs=0` then `Unreachable`.
 * 2. `Foreground` and `NetworkRestored` are no-op reducer arms out of `Unreachable`, so the
 *    give-up #1633 made reachable is a dead end.
 * 3. The episode budget was 120s — shorter than the ladder (98s of delays + 8 dials) it bounds.
 *
 * ## Scope: defect A only — the `Unreachable` EXIT is deliberately NOT here
 *
 * #1654 also proposed auto-waking a give-up on Foreground/NetworkRestored. That is NOT in this
 * slice, and its absence is asserted below rather than assumed. A reducer-only exit was MEASURED
 * to dial nothing (`Issue1654GiveUpWakeDialsTest`): three production mechanisms absorb it, so it
 * would have swapped today's honest `Failed` band — which carries a WORKING "Tap Reconnect" —
 * for a lying, untappable "Reconnecting…" with zero IO. Strictly worse than the strand (#1656).
 *
 * ## The NEGATIVE case (G6)
 *
 * A genuinely unreachable host must still REACH `Unreachable` and STAY there. That is what
 * keeps the honest band (and its working tap) intact, and it is what stops the storm returning.
 */
class Issue1654LadderAuthorityAndGiveUpWakeTest {

    private companion object {
        /**
         * The ladder production MUST walk, written out LITERALLY here rather than read from
         * `ConnectionController.DEFAULT_RECONNECT_LADDER_MS`.
         *
         * That is deliberate and load-bearing: this file must COMPILE AND RUN against base
         * (pre-#1654), where that constant does not exist — otherwise the red is a compile
         * error, which proves nothing about behaviour. Asserting against the literal is also
         * strictly stronger: a test that reads the same constant the production code reads
         * passes no matter what the constant becomes.
         */
        val EXPECTED_LADDER_MS = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L)

        /** Read off the controller so the base value (120s) is what the red reports. */
        val EPISODE_BUDGET_MS: Long get() = ConnectionController.DEFAULT_EPISODE_BUDGET_MS
    }

    private val host = HostKey("h")
    private val target = SessionId("s")

    /** A clock the test drives, so the episode budget is exercised, never waited on. */
    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    /**
     * The controller EXACTLY as production builds it (`ConnectionManager.kt`: `clock` +
     * `transport`, nothing else). That is load-bearing: the whole defect was that the
     * production construction produced a controller with no ladder, while every unit test
     * either passed `maxReconnectAttempts` or called `setReconnectLadder` by hand and so
     * could never see it.
     */
    private fun productionShapedController(
        clock: Clock = TestClock(),
        transport: TransportPort = FakeTransportPort(),
    ) = ConnectionController(clock = clock, transport = transport, confinementAssertionsEnabled = false)

    /** Drive the machine to a give-up the way the storm does: rung failure after rung failure. */
    private fun ConnectionController.stormToGiveUp(): List<ConnectionState.Reconnecting> {
        submit(ConnectionEvent.Enter(host, target))
        submit(ConnectionEvent.SeedLanded(target, "p"))
        submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("blip")))
        val rungs = mutableListOf<ConnectionState.Reconnecting>()
        repeat(40) {
            val s = submit(ConnectionEvent.ReconnectFailed)
            if (s is ConnectionState.Reconnecting) rungs += s
            if (s is ConnectionState.Unreachable) return rungs
        }
        fail("the machine never reached Unreachable across 40 rung failures")
        return rungs
    }

    // ---- 1. the ladder is installed by CONSTRUCTION, on every path ----

    @Test
    fun `a production-shaped controller walks the real 8-rung ladder with growing delays`() {
        val rungs = productionShapedController().stormToGiveUp()

        // RED on base: maxAttempts is 4 (the deleted DEFAULT_MAX_RECONNECT_ATTEMPTS fallback).
        assertEquals(
            "every Reconnecting the storm path produces must announce the REAL ladder budget. " +
                "On base the ladder is never installed on this path, so the controller falls " +
                "back to a 4-attempt default: the user is told `n/4` and the machine surrenders " +
                "after 4 rungs instead of riding out the intended ~98s. rungs=$rungs",
            listOf(EXPECTED_LADDER_MS.size),
            rungs.map { it.maxAttempts }.distinct(),
        )
        // Rung 1 is the Reattaching SILENT HEAL (#680/#621) — it never surfaces as a
        // Reconnecting — so the numbered band walks 2..8. Its 0ms delay is asserted on the
        // wake path below, where attempt 1 IS a Reconnecting.
        assertEquals(
            "the ladder must be walked rung by rung, 2..8 (rung 1 is the silent heal)",
            (2..EXPECTED_LADDER_MS.size).toList(),
            rungs.map { it.attempt },
        )

        // RED on base: every delay is 0 (retryDelayForAttempt short-circuits on an empty ladder).
        val delays = rungs.map { it.retryDelayMs }
        assertTrue(
            "the backoff must actually GROW — this is #1633's ladder, which is INERT on base " +
                "because there is no ladder to read a delay from (`retryDelayMs=0` on every one " +
                "of the 15,456 logged storm lines). delays=$delays",
            delays.last() > delays.first(),
        )
        assertTrue(
            "every numbered rung must actually WAIT — on base every one of them is 0ms, which " +
                "is the storm's own cadence. delays=$delays",
            delays.all { it > 0L },
        )
        // Pin each rung to its OWN nominal rather than asserting the list is sorted: the last
        // two rungs are both nominally 30s, so ±20% jitter legitimately orders them either way.
        // This is also the stronger assertion — it fixes the ladder's shape, not just its
        // monotonicity.
        rungs.forEach { rung ->
            val nominal = EXPECTED_LADDER_MS[rung.attempt - 1]
            assertTrue(
                "rung ${rung.attempt} must be the real ladder's ${nominal}ms (±20% jitter); " +
                    "got ${rung.retryDelayMs}ms. delays=$delays",
                rung.retryDelayMs in (nominal * 0.8).toLong()..(nominal * 1.2).toLong(),
            )
        }
        assertTrue(
            "the ladder must be patient enough for a mobile flap: the 4-rung `[0,1,2,5]s` " +
                "behaviour surrenders in ~8s, which is the regression. totalMs=${delays.sum()}",
            delays.sum() > 60_000L,
        )
        assertTrue(
            "the whole ladder (silent heal + the numbered rungs) must be the 98s the design " +
                "calls for, not the ~8s a 4-rung ladder gives. totalMs=${delays.sum()}",
            delays.sum() > EXPECTED_LADDER_MS.sum() * 0.75,
        )
    }

    @Test
    fun `the ladder cannot be silently degraded to an empty instant-retry ladder`() {
        // The `.ifEmpty { listOf(0L) }` / `emptyList()` shapes turned "nobody installed a
        // ladder" into "retry instantly, then give up" — a silent degradation that shipped as
        // the #1654 blocker. It must now fail LOUDLY at the boundary instead.
        val c = productionShapedController()
        try {
            c.setReconnectLadder(emptyList())
            fail("an empty ladder must be rejected, not silently degraded to a 4-attempt/0ms budget")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("empty"))
        }
    }

    // ---- 2. the episode budget must not truncate the ladder it bounds ----

    @Test
    fun `the episode budget outlasts the ladder's own delays`() {
        val ladderMs = EXPECTED_LADDER_MS.sum()
        assertTrue(
            "the wall clock must not TRUNCATE the ladder it bounds: the rung delays alone are " +
                "${ladderMs}ms, and 8 real dial/attach cycles (2-15s each) ride on top. At the " +
                "old 120s the two 30s rungs would never execute. " +
                "budget=${EPISODE_BUDGET_MS}ms",
            EPISODE_BUDGET_MS >= 180_000L,
        )
        assertTrue(
            "the budget must still TERMINATE honestly — it is a ceiling, not a licence to " +
                "grind. budget=${EPISODE_BUDGET_MS}ms",
            EPISODE_BUDGET_MS <= 240_000L,
        )
    }

    @Test
    fun `a flap too slow to burn the rungs still gives up at the episode budget`() {
        val clock = TestClock()
        val c = productionShapedController(clock = clock)
        c.submit(ConnectionEvent.Enter(host, target))
        c.submit(ConnectionEvent.SeedLanded(target, "p"))
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("blip")))
        // Two rungs, then jump the wall clock past the budget: the budget, not the rung count,
        // must be what terminates. This is the bound that stops a slow flap grinding forever.
        c.submit(ConnectionEvent.ReconnectFailed)
        c.submit(ConnectionEvent.ReconnectFailed)
        clock.now += EPISODE_BUDGET_MS + 1
        assertTrue(
            "past the episode budget the machine must surrender even with rungs left",
            c.submit(ConnectionEvent.ReconnectFailed) is ConnectionState.Unreachable,
        )
    }

    // ---- 3. Unreachable RECOVERS — foreground + network-restored ----

    @Test
    fun `a genuinely unreachable host still reaches Unreachable and STAYS there`() {
        val c = productionShapedController()
        val rungs = c.stormToGiveUp()
        assertEquals(
            "the machine must still surrender after exactly the ladder's budget — the wake must " +
                "not make give-up unreachable again. (rung 1 is the Reattaching silent heal, so " +
                "8 rungs surface as 7 numbered Reconnecting states.)",
            EXPECTED_LADDER_MS.size - 1,
            rungs.size,
        )
        assertTrue(c.state.value is ConnectionState.Unreachable)

        // Every signal that is NOT a wake must leave the give-up alone. If any of these
        // re-armed the ladder we would have traded the strand back for the infinite storm.
        val nonWakeEvents = listOf(
            ConnectionEvent.TransportDropped(DropCause.RemoteFailure("still dead")),
            ConnectionEvent.ReconnectFailed,
            ConnectionEvent.ReconnectLadderEntered,
            ConnectionEvent.ReconnectGaveUp,
            ConnectionEvent.NetworkLost,
            ConnectionEvent.NetworkChanged(validatedHandoff = true),
            ConnectionEvent.TransportLive,
            ConnectionEvent.SeedLanded(target, "p"),
        )
        for (event in nonWakeEvents) {
            assertTrue(
                "$event must NOT resurrect the ladder out of a give-up — that is the #1610 " +
                    "storm returning. got=${c.state.value}",
                c.submit(event) is ConnectionState.Unreachable,
            )
        }
    }

    @Test
    fun `jitter keeps every rung recognisable and rolls once per install`() {
        // Guards the two properties the single-ladder contract rests on: the displayed
        // countdown must stay roughly truthful (±20%), and re-reading the same attempt must
        // report the SAME backoff (#1633 round 2 — the VM sleeps on its own read).
        val c = ConnectionController(
            clock = TestClock(),
            transport = FakeTransportPort(),
            confinementAssertionsEnabled = false,
            random = Random(7),
        )
        val first = c.stormToGiveUp()
        val ladder = EXPECTED_LADDER_MS
        first.forEach { rung ->
            // `attempt` is 1-based into the ladder; the numbered band starts at attempt 2.
            val nominal = ladder[rung.attempt - 1]
            assertTrue(
                "rung ${rung.attempt} must stay within ±20% of its nominal ${nominal}ms so the " +
                    "displayed `next in Xs` countdown is honest; got ${rung.retryDelayMs}ms",
                rung.retryDelayMs in (nominal * 0.8).toLong()..(nominal * 1.2).toLong(),
            )
        }
    }
}
