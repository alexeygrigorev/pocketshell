package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1666 (D28 Layer 3) — the pure-reducer REFUSAL of a self-inflicted drop.
 *
 * The reconnect storm (#1610/#1680) is self-inflicted teardown-churn: an app-owned close
 * site tears down the shared per-host `-CC` lease, the reader hits EOF, and that
 * self-inflicted EOF is re-ingested as a fresh remote failure that re-arms the loud
 * auto-reconnect ladder against ourselves. The #1643 driver filter suppresses that echo at
 * the effect layer; this issue pushes the TYPED [DropCause] into the event so
 * [ConnectionController.onTransportDropped] refuses a [DropCause.SelfInflicted] drop in the
 * PURE reducer — defense in depth BENEATH the driver filter, where the storm becomes
 * impossible by construction.
 *
 * These are the reproduce-first tests (D33/G10 at the right level — the reducer contract):
 *  - RED on the pre-#1666 untyped reducer (`TransportDropped(reason)` had no cause to key
 *    on, so EVERY drop advanced the machine); GREEN once the typed refusal lands.
 *  - The load-bearing NEGATIVE (D31): a genuine death — [DropCause.RemoteFailure] /
 *    [DropCause.KeepaliveDead] / [DropCause.Unknown] — STILL walks the honest ladder
 *    Live → Reattaching → Reconnecting(n) → … → Unreachable. Over-suppression must never
 *    silence a real drop (stranding the app is the one outcome worse than the storm).
 */
class Issue1666DropCauseRefusalTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")

    /** A single-rung ladder makes exhaustion deterministic: attempt 2 > maxAttempts 1. */
    private fun controller(ladder: List<Long> = listOf(0L)): Pair<ConnectionController, FakeTransportPort> {
        val transport = FakeTransportPort()
        val c = ConnectionController(FakeClock(), transport, reconnectLadderMs = ladder)
        return c to transport
    }

    private fun ConnectionController.enterConnecting(transport: FakeTransportPort) {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, a)) // cold -> Connecting
    }

    private fun ConnectionController.bringToAttaching(transport: FakeTransportPort) {
        enterConnecting(transport)
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive) // Connecting -> Attaching
    }

    private fun ConnectionController.bringToLive(transport: FakeTransportPort) {
        bringToAttaching(transport)
        submit(ConnectionEvent.SeedLanded(a, "%0")) // Attaching -> Live
    }

    private fun ConnectionController.bringToReattaching(transport: FakeTransportPort) {
        bringToLive(transport)
        submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("real"))) // Live -> Reattaching
    }

    private fun ConnectionController.bringToReconnecting(transport: FakeTransportPort) {
        bringToReattaching(transport)
        submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("real"))) // Reattaching -> Reconnecting(1)
    }

    private fun selfInflicted() = ConnectionEvent.TransportDropped(DropCause.SelfInflicted("ExplicitDisconnect"))

    // ---- Class coverage: a self-inflicted drop from EACH live-ish state is inert. ----

    @Test
    fun `self-inflicted drop from Live leaves Live untouched`() {
        val (c, t) = controller()
        c.bringToLive(t)
        val before = c.state.value
        c.submit(selfInflicted())
        assertEquals("a self-close is not remote death — Live must not move", before, c.state.value)
        assertTrue(c.state.value is ConnectionState.Live)
    }

    @Test
    fun `self-inflicted drop from Reconnecting does NOT advance the ladder`() {
        val (c, t) = controller(ladder = listOf(0L, 1_000L, 2_000L))
        c.bringToReconnecting(t)
        val rung = c.state.value as ConnectionState.Reconnecting
        assertEquals(1, rung.attempt)

        c.submit(selfInflicted())

        val after = c.state.value as ConnectionState.Reconnecting
        assertEquals("self-inflicted drop must not advance the attempt counter", 1, after.attempt)
        assertEquals(rung, after)
    }

    @Test
    fun `self-inflicted drop from Reattaching leaves Reattaching untouched`() {
        val (c, t) = controller()
        c.bringToReattaching(t)
        val before = c.state.value
        assertTrue(before is ConnectionState.Reattaching)
        c.submit(selfInflicted())
        assertEquals(before, c.state.value)
    }

    @Test
    fun `self-inflicted drop from Attaching leaves Attaching untouched`() {
        val (c, t) = controller()
        c.bringToAttaching(t)
        val before = c.state.value
        assertTrue(before is ConnectionState.Attaching)
        c.submit(selfInflicted())
        assertEquals(before, c.state.value)
    }

    @Test
    fun `self-inflicted drop from Connecting leaves Connecting untouched`() {
        val (c, t) = controller()
        c.enterConnecting(t)
        val before = c.state.value
        assertTrue(before is ConnectionState.Connecting)
        c.submit(selfInflicted())
        assertEquals(before, c.state.value)
    }

    @Test
    fun `a self-inflicted drop starts NO episode — the next real drop heals fresh at attempt 1`() {
        // Proves the refusal does not secretly mutate the hidden counter: if the
        // self-inflicted drop had opened an episode, the following real drop out of Live
        // would resume at attempt 2 instead of healing fresh.
        val (c, t) = controller()
        c.bringToLive(t)
        c.submit(selfInflicted()) // inert
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("real"))) // Live -> Reattaching
        assertTrue("a real drop after a refused self-close still heals silently", c.state.value is ConnectionState.Reattaching)
        c.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("real"))) // Reattaching -> Reconnecting(1)
        assertEquals(1, (c.state.value as ConnectionState.Reconnecting).attempt)
    }

    // ---- The load-bearing NEGATIVE (D31): real deaths STILL walk to Unreachable. ----

    private fun assertWalksToUnreachable(cause: DropCause) {
        val (c, t) = controller(ladder = listOf(0L)) // 1 rung -> maxAttempts 1
        c.bringToLive(t)

        c.submit(ConnectionEvent.TransportDropped(cause)) // Live -> Reattaching
        assertTrue("$cause must heal through Reattaching", c.state.value is ConnectionState.Reattaching)

        c.submit(ConnectionEvent.TransportDropped(cause)) // Reattaching -> Reconnecting(1)
        assertEquals("$cause must arm the ladder", 1, (c.state.value as ConnectionState.Reconnecting).attempt)

        c.submit(ConnectionEvent.ReconnectFailed) // attempt 2 > budget 1 -> Unreachable
        assertTrue(
            "$cause must reach the honest Unreachable when the ladder exhausts",
            c.state.value is ConnectionState.Unreachable,
        )
    }

    @Test
    fun `remote-failure drop still walks Live to Reattaching to Reconnecting to Unreachable`() {
        assertWalksToUnreachable(DropCause.RemoteFailure("Disconnected"))
    }

    @Test
    fun `keepalive-dead drop still walks the honest ladder to Unreachable`() {
        assertWalksToUnreachable(DropCause.KeepaliveDead)
    }

    @Test
    fun `unknown drop is treated as genuine and still walks the ladder`() {
        assertWalksToUnreachable(DropCause.Unknown)
    }

    @Test
    fun `a real drop out of Live opens an episode where a self-inflicted one did not`() {
        // Direct contrast at the same state: RemoteFailure from Live -> Reattaching (moves),
        // SelfInflicted from Live -> Live (inert). The typed cause is the ONLY difference.
        val (c1, t1) = controller()
        c1.bringToLive(t1)
        c1.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("real")))
        assertTrue(c1.state.value is ConnectionState.Reattaching)

        val (c2, t2) = controller()
        c2.bringToLive(t2)
        c2.submit(ConnectionEvent.TransportDropped(DropCause.SelfInflicted("ForceRefresh")))
        assertTrue(c2.state.value is ConnectionState.Live)
    }
}
