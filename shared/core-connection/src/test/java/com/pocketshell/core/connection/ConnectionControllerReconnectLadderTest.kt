package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1328 (S5) — the SINGLE reconnect ladder now lives entirely in the
 * [ConnectionController] reducer: one attempt counter, one exhaustion point, the
 * per-attempt backoff read straight off the injected ladder. These pure-JVM
 * virtual-clock tests pin that:
 *
 *  1. An injected ladder sets [ConnectionState.Reconnecting.maxAttempts] to the
 *     ladder size and [retryDelayMs] to each ladder step (clamped to the last).
 *  2. Exhaustion is decided ONCE, in the reducer: a [ConnectionEvent.TransportDropped]
 *     that pushes `attempt` past `maxAttempts` transitions to [ConnectionState.Unreachable].
 *  3. The honest [ConnectionEvent.ReconnectGaveUp] abort surfaces [Unreachable] from
 *     any live-ish / recovering state, and is a no-op with no live channel.
 *
 * The VM effect (`TmuxSessionViewModel.scheduleAutoReconnectBody`) reads the attempt /
 * delay off this state and feeds honest drops; it never counts a parallel ladder.
 */
class ConnectionControllerReconnectLadderTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")

    private fun controller(ladder: List<Long> = emptyList()): Pair<ConnectionController, FakeTransportPort> {
        val transport = FakeTransportPort()
        val c = ConnectionController(FakeClock(), transport)
        if (ladder.isNotEmpty()) c.setReconnectLadder(ladder)
        return c to transport
    }

    /** Live -> Reattaching -> Reconnecting(1): enter the numbered ladder. */
    private fun ConnectionController.bringToReconnecting(transport: FakeTransportPort) {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, a))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(a, "%0")) // Live
        submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d"))) // Live -> Reattaching
        submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("d"))) // Reattaching -> Reconnecting(1)
    }

    /**
     * Issue #1633 note: the per-attempt delay is now jittered ±20% (the gRPC model), so
     * this asserts the rung's backoff lands in its ladder step's band rather than on the
     * exact step. The jitter distribution itself is pinned by `ConnectionControllerJitterTest`.
     */
    private fun assertRung(attempt: Int, maxAttempts: Int, baseDelayMs: Long, state: ConnectionState) {
        val recon = state as ConnectionState.Reconnecting
        assertEquals(host, recon.host)
        assertEquals(a, recon.targetId)
        assertEquals(attempt, recon.attempt)
        assertEquals(maxAttempts, recon.maxAttempts)
        if (baseDelayMs == 0L) {
            assertEquals("the instant first rung is never jittered", 0L, recon.retryDelayMs)
        } else {
            val band = (baseDelayMs * 0.8).toLong()..(baseDelayMs * 1.2).toLong()
            assertTrue(
                "attempt $attempt: ${recon.retryDelayMs}ms outside the +/-20% jitter band " +
                    "$band of ${baseDelayMs}ms",
                recon.retryDelayMs in band,
            )
        }
    }

    @Test
    fun `injected ladder drives maxAttempts and per-attempt retry delay`() {
        val (c, transport) = controller(ladder = listOf(0L, 1_000L, 2_000L, 5_000L))
        c.bringToReconnecting(transport)

        assertRung(attempt = 1, maxAttempts = 4, baseDelayMs = 0L, state = c.state.value)
        // Advancement is EXCLUSIVELY ReconnectFailed (one per real rung failure).
        c.submit(ConnectionEvent.ReconnectFailed)
        assertRung(attempt = 2, maxAttempts = 4, baseDelayMs = 1_000L, state = c.state.value)
        c.submit(ConnectionEvent.ReconnectFailed)
        assertRung(attempt = 3, maxAttempts = 4, baseDelayMs = 2_000L, state = c.state.value)
        c.submit(ConnectionEvent.ReconnectFailed)
        assertRung(attempt = 4, maxAttempts = 4, baseDelayMs = 5_000L, state = c.state.value)
    }

    @Test
    fun `a raw transport drop while Reconnecting does NOT advance the ladder`() {
        // Issue #1328: incidental re-dial churn drops must not count — only ReconnectFailed
        // advances. Otherwise a 1-rung ladder would exhaust before its dial resolves.
        val (c, transport) = controller(ladder = listOf(0L, 0L, 0L))
        c.bringToReconnecting(transport)
        assertEquals(1, (c.state.value as ConnectionState.Reconnecting).attempt)
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("incidental churn")))
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("incidental churn")))
        assertEquals(
            "raw drops while already Reconnecting hold the attempt (loop owns advancement)",
            1,
            (c.state.value as ConnectionState.Reconnecting).attempt,
        )
    }

    @Test
    fun `exhaustion is decided once in the reducer at the ladder budget`() {
        val (c, transport) = controller(ladder = listOf(0L, 0L)) // budget 2
        c.bringToReconnecting(transport)

        assertEquals(1, (c.state.value as ConnectionState.Reconnecting).attempt)
        c.submit(ConnectionEvent.ReconnectFailed) // -> attempt 2
        assertEquals(2, (c.state.value as ConnectionState.Reconnecting).attempt)
        c.submit(ConnectionEvent.ReconnectFailed) // 3 > 2 -> exhausted
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)
    }

    @Test
    fun `retry delay clamps to the last ladder step past the budget`() {
        // A 1-step ladder: every attempt reuses the single step's delay.
        val (c, transport) = controller(ladder = listOf(750L))
        c.bringToReconnecting(transport)
        assertRung(attempt = 1, maxAttempts = 1, baseDelayMs = 750L, state = c.state.value)
        // attempt 2 > budget 1 -> exhausted (no over-run of the ladder).
        c.submit(ConnectionEvent.ReconnectFailed)
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)
    }

    @Test
    fun `a controller with no explicit ladder still walks the real production ladder`() {
        // Issue #1654 (D22 hard-cut): this test used to assert the OPPOSITE — that an empty
        // ladder "falls back to the flat default budget with zero delay". That fallback was
        // the release blocker, not a feature: the passive-grace loop never installed a ladder,
        // so the storm path silently ran on a 4-attempt/0ms budget and the app surrendered in
        // under a second. There is no empty-ladder state any more; the controller installs the
        // real ladder at construction, so "nobody called setReconnectLadder" now yields the
        // production ladder rather than a degraded one.
        val (c, transport) = controller(ladder = emptyList())
        c.bringToReconnecting(transport)
        val recon = c.state.value as ConnectionState.Reconnecting
        assertEquals(1, recon.attempt)
        assertEquals(ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size, recon.maxAttempts)
        assertEquals(
            "rung 1 is the instant silent heal (#680/#621)",
            0L,
            recon.retryDelayMs,
        )
    }

    @Test
    fun `ReconnectGaveUp surfaces Unreachable from the reconnect ladder before exhaustion`() {
        val (c, transport) = controller(ladder = listOf(0L, 0L, 0L, 0L))
        c.bringToReconnecting(transport)
        assertTrue(c.state.value is ConnectionState.Reconnecting)
        // A non-retryable failure aborts the ladder early — honest, reducer-owned.
        c.submit(ConnectionEvent.ReconnectGaveUp)
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)
    }

    @Test
    fun `ReconnectGaveUp aborts a live channel too`() {
        val (c, transport) = controller()
        transport.setWarm(host, false)
        c.submit(ConnectionEvent.Enter(host, a))
        transport.setWarm(host, true)
        c.submit(ConnectionEvent.TransportLive)
        c.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertTrue(c.state.value is ConnectionState.Live)
        c.submit(ConnectionEvent.ReconnectGaveUp)
        assertEquals(ConnectionState.Unreachable(host, a), c.state.value)
    }

    @Test
    fun `ReconnectGaveUp is a no-op with no live channel`() {
        val (c, _) = controller()
        assertTrue(c.state.value is ConnectionState.Idle)
        c.submit(ConnectionEvent.ReconnectGaveUp)
        assertEquals(ConnectionState.Idle, c.state.value)
    }
}
