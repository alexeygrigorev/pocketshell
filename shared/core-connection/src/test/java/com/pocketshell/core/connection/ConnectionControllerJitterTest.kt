package com.pocketshell.core.connection

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1633 — ±20% ladder jitter (the gRPC backoff model) and the tuning constants.
 *
 * Mobile RAT handovers and NAT/keepalive reapers are PERIODIC. An unjittered ladder can
 * resonate with that cadence (and, across sessions/devices, synchronize retries into a
 * thundering herd). gRPC's standard remedy is full jitter on every non-zero rung; this
 * suite pins it as a DISTRIBUTION property rather than a fixed value, deterministically
 * (each sample uses an injected seed, so there is no flake and nothing is skipped).
 *
 * It also pins the constants that `ConnectionControllerEpisodeStabilityTest` mirrors
 * locally — that file deliberately compiles against the UNFIXED reducer so it can be run
 * red on base, so its window constants are copies. These assertions are the anti-drift
 * guard for those copies.
 */
class ConnectionControllerJitterTest {

    private val host = HostKey("alexey@135.181.114.209:22")
    private val a = SessionId("A")

    /** The #1331 design ladder: 8 rungs, `[0,1,2,5,10,20,30,30]s`. */
    private val ladder = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L)

    private fun ConnectionController.bringLive(transport: FakeTransportPort) {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, a))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(a, "%0"))
    }

    /** Drive a seeded controller to the numbered ladder and read [rungsToBurn]+1's backoff. */
    private fun delayForRung(seed: Int, rungsToBurn: Int): Long {
        val transport = FakeTransportPort()
        val c = ConnectionController(FakeClock(), transport, random = Random(seed))
        c.setReconnectLadder(ladder)
        c.bringLive(transport)
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d"))) // Live -> Reattaching
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d"))) // -> Reconnecting(1)
        repeat(rungsToBurn) { c.submit(ConnectionEvent.ReconnectFailed) }
        return (c.state.value as ConnectionState.Reconnecting).retryDelayMs
    }

    private fun assertWithinJitterBand(baseMs: Long, actualMs: Long, label: String) {
        val low = (baseMs * 0.8).toLong()
        val high = (baseMs * 1.2).toLong()
        assertTrue(
            "$label: ${actualMs}ms outside the +/-20% jitter band [$low..$high] of ${baseMs}ms",
            actualMs in low..high,
        )
    }

    /**
     * Jitter as a DISTRIBUTION: every sample inside the ±20% band, genuinely spread (not a
     * constant), unbiased around the base rung, and reaching both sides of it.
     */
    @Test
    fun `ladder backoff is jittered plus or minus 20 percent as a distribution`() {
        val base = 1_000L // ladder rung 2
        val samples = (0 until JITTER_SAMPLES).map { delayForRung(seed = it, rungsToBurn = 1) }

        samples.forEach { assertWithinJitterBand(base, it, "jitter sample") }
        assertTrue(
            "jitter produced a near-constant — retries would still synchronize " +
                "(${samples.distinct().size} distinct of $JITTER_SAMPLES)",
            samples.distinct().size > JITTER_SAMPLES / 4,
        )
        val mean = samples.average()
        assertTrue("jitter is biased: mean=$mean, expected ~$base", mean in 940.0..1_060.0)
        assertTrue("jitter must reach below the base: ${samples.min()}", samples.any { it < base })
        assertTrue("jitter must reach above the base: ${samples.max()}", samples.any { it > base })
    }

    /** Jitter applies to EVERY non-zero rung, not just the one sampled above. */
    @Test
    fun `every non-zero rung is jittered around its own base`() {
        // rungsToBurn = n reaches attempt n+1, i.e. ladder[n].
        (1..ladder.lastIndex).forEach { rungIndex ->
            val base = ladder[rungIndex]
            val samples = (0 until 40).map { delayForRung(seed = it, rungsToBurn = rungIndex) }
            samples.forEach { assertWithinJitterBand(base, it, "rung ${rungIndex + 1}") }
            assertTrue(
                "rung ${rungIndex + 1} (base ${base}ms) is not jittered — it returned a constant",
                samples.distinct().size > 1,
            )
        }
    }

    /** The instant first rung must NEVER be jittered — the silent heal stays immediate. */
    @Test
    fun `the zero-delay first rung is never jittered`() {
        repeat(50) { seed ->
            assertEquals(
                "the silent first heal must stay instant",
                0L,
                delayForRung(seed = seed, rungsToBurn = 0),
            )
        }
    }

    /** Two independently-seeded controllers on the same rung do not resonate. */
    @Test
    fun `two controllers on the same rung do not synchronize`() {
        assertNotEquals(delayForRung(seed = 1, rungsToBurn = 1), delayForRung(seed = 2, rungsToBurn = 1))
    }

    /** A jittered delay never collapses to zero — a non-zero rung always waits. */
    @Test
    fun `a jittered rung never collapses to zero`() {
        (1..ladder.lastIndex).forEach { rungIndex ->
            (0 until 40).forEach { seed ->
                assertTrue(
                    "rung ${rungIndex + 1} jittered down to 0ms — the backoff would vanish",
                    delayForRung(seed = seed, rungsToBurn = rungIndex) > 0L,
                )
            }
        }
    }

    /**
     * Issue #1633 (round 2) — jitter is rolled ONCE PER LADDER INSTALL, so a given attempt's
     * backoff is STABLE however many times its state is rebuilt.
     *
     * RED on the round-1 reducer (jitter rolled inside `reconnectingAt`): `reconnectingAt` is
     * re-invoked for the SAME attempt on every idempotent ladder re-entry, so each rebuild
     * re-rolled that attempt's backoff. That is not academic — the VM's ladder body mirrors
     * `retryDelayMs` into the displayed Reconnecting band and then sleeps on its own earlier
     * read, so the band could advertise one backoff while the dial waited a different one,
     * and the reducer's output stopped being a function of its input sequence. On a
     * connect-then-die link (this issue's environment) that re-entry happens on EVERY cycle.
     */
    @Test
    fun `a given attempt's jittered backoff is stable across repeated state rebuilds`() {
        val transport = FakeTransportPort()
        val c = ConnectionController(FakeClock(), transport, random = Random(7))
        c.setReconnectLadder(ladder)
        c.bringLive(transport)
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d")))
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d"))) // -> Reconnecting(1)
        c.submit(ConnectionEvent.ReconnectFailed) // -> Reconnecting(2), a jittered rung

        val attempt = (c.state.value as ConnectionState.Reconnecting).attempt
        val first = (c.state.value as ConnectionState.Reconnecting).retryDelayMs
        assertTrue("precondition: rung $attempt must actually be jittered (non-zero)", first > 0L)

        // The VM's ladder body re-enters idempotently on every connect-then-die cycle.
        repeat(25) {
            c.submit(ConnectionEvent.ReconnectLadderEntered)
            val now = c.state.value as ConnectionState.Reconnecting
            assertEquals("the re-entry must not advance the attempt", attempt, now.attempt)
            assertEquals(
                "attempt $attempt re-rolled its backoff on rebuild (${now.retryDelayMs}ms vs " +
                    "${first}ms) — the displayed band and the actual dial wait can disagree",
                first,
                now.retryDelayMs,
            )
        }
    }

    /**
     * The stability above must NOT come from jitter having silently died: a fresh ladder
     * install is still an independent roll, which is what actually desynchronises retries.
     * (G6: pins the load-bearing property, not just the convenient one.)
     */
    @Test
    fun `each ladder install is an independent jitter roll`() {
        val transport = FakeTransportPort()
        val c = ConnectionController(FakeClock(), transport, random = Random(11))
        val rungPerInstall = (0 until 30).map {
            c.setReconnectLadder(ladder)
            c.bringLive(transport)
            c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d")))
            c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d")))
            c.submit(ConnectionEvent.ReconnectFailed)
            val d = (c.state.value as ConnectionState.Reconnecting).retryDelayMs
            assertWithinJitterBand(1_000L, d, "per-install rung 2")
            d
        }
        assertTrue(
            "every ladder install produced the SAME backoff — jitter is not re-rolling, so " +
                "retries would still synchronize (${rungPerInstall.distinct().size} distinct of 30)",
            rungPerInstall.distinct().size > 1,
        )
    }

    /**
     * Anti-drift guard: `ConnectionControllerEpisodeStabilityTest` mirrors these constants
     * locally so it can compile against the unfixed reducer for the red run. Pin the mirrors
     * to the real values here.
     */
    @Test
    fun `the mirrored tuning constants match production`() {
        assertEquals(
            "ConnectionControllerEpisodeStabilityTest.STABILITY_WINDOW_MS mirror has drifted",
            30_000L,
            ConnectionController.DEFAULT_STABILITY_WINDOW_MS,
        )
        assertEquals(
            "ConnectionControllerEpisodeStabilityTest.EPISODE_BUDGET_MS mirror has drifted",
            180_000L,
            ConnectionController.DEFAULT_EPISODE_BUDGET_MS,
        )
        assertEquals(0.2, ConnectionController.RETRY_JITTER_FRACTION, 0.0001)
    }

    private companion object {
        private const val JITTER_SAMPLES = 200
    }
}
