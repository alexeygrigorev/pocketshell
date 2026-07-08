package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive unit tests for the pure-JVM [ConnectionController] state machine
 * (EPIC #687 Phase-2, slice 1). These pin the load-bearing lifecycle decisions —
 * within/beyond-grace, no-resurrect, drop-by-id, transient-drop heal, and the
 * state->indicator mapping — at PR time, with a virtual [FakeClock] and fake
 * ports. This is the whole point of extracting the controller: #685's
 * within-vs-beyond-grace decision becomes a fast deterministic JVM test.
 */
class ConnectionControllerTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")
    private val b = SessionId("B")

    private fun controller(
        clock: FakeClock = FakeClock(),
        transport: FakeTransportPort = FakeTransportPort(),
        maxReconnectAttempts: Int = ConnectionController.DEFAULT_MAX_RECONNECT_ATTEMPTS,
    ) = ConnectionController(clock, transport, maxReconnectAttempts)

    /** Drive a cold open all the way to Live so individual tests can start from
     *  a realistic attached state. */
    private fun ConnectionController.bringLive(
        transport: FakeTransportPort,
        targetId: SessionId = a,
    ): ConnectionController {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, targetId)) // -> Connecting (cold)
        // The VM adapter brings the lease warm during the cold dial; model that
        // so the warm-lease grace predicate has something to AND against.
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive) // Connecting -> Attaching
        submit(ConnectionEvent.SeedLanded(targetId, "%0")) // Attaching -> Live
        return this
    }

    // --- Cold open --------------------------------------------------------

    @Test
    fun `cold enter goes Connecting then Attaching then Live`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport)
        transport.setWarm(host, false)

        assertEquals(ConnectionState.Idle, controller.state.value)

        controller.submit(ConnectionEvent.Enter(host, a))
        assertEquals(ConnectionState.Connecting(host, a), controller.state.value)

        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Attaching(host, a), controller.state.value)

        controller.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `warm enter skips the cold overlay and goes straight to Attaching`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport)
        transport.setWarm(host, true)

        controller.submit(ConnectionEvent.Enter(host, a))
        assertEquals(ConnectionState.Attaching(host, a), controller.state.value)
    }

    // --- Within-grace foreground = NO reconnect (#685 Bug-A) --------------

    @Test
    fun `background then foreground within grace reattaches with no reconnect`() {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        assertTrue(controller.state.value is ConnectionState.Backgrounded)

        // #1159: grace is now 90 s. Just before the deadline (well past the
        // OLD 60 s boundary but within the new window), lease still warm ->
        // within grace, no reconnect.
        clock.advanceBy(ConnectionController.DEFAULT_GRACE_MS - 1L)
        controller.submit(ConnectionEvent.Foreground)

        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)
        // It heals silently to Live, never entering Reconnecting.
        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `within grace but cold lease still reconnects`() {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        // Lease evicted while backgrounded (D21 onProcessStopped closes idle leases).
        transport.setWarm(host, false)
        clock.advanceBy(10_000L) // well within 60s, but lease is cold

        controller.submit(ConnectionEvent.Foreground)
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)
    }

    // --- Beyond-grace foreground = silent Reconnecting -> Live (#685 Bug-B)

    @Test
    fun `background then foreground beyond grace reconnects silently then goes Live`() {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        // #1159: grace is now 90 s. Just past it -> beyond grace even though lease warm.
        clock.advanceBy(ConnectionController.DEFAULT_GRACE_MS + 1L)
        controller.submit(ConnectionEvent.Foreground)

        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)
        // Silent: no error band; resolves to Live on transport-live.
        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `no timer is scheduled while backgrounded - grace is a stored deadline`() {
        // D21: the only thing Background does is stamp a deadline; advancing the
        // clock past it does NOT itself transition state. Only Foreground reads it.
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        val backgrounded = controller.state.value
        clock.advanceBy(120_000L) // long past grace
        // No event submitted -> state is untouched (no wakeup fired).
        assertEquals(backgrounded, controller.state.value)
        assertTrue(controller.state.value is ConnectionState.Backgrounded)
    }

    // --- TargetGone = Gone, no resurrect (#666) ---------------------------

    @Test
    fun `target gone for the current target transitions to Gone`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport)

        controller.submit(ConnectionEvent.TargetGone(a))
        assertEquals(ConnectionState.Gone(host, a), controller.state.value)
    }

    @Test
    fun `gone never resurrects - a later seed or transport-live does not revive it`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport)
        controller.submit(ConnectionEvent.TargetGone(a))

        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Gone(host, a), controller.state.value)
        controller.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertEquals(ConnectionState.Gone(host, a), controller.state.value)
        // Backgrounding a Gone surface keeps it Gone (no grace, nothing to detach).
        controller.submit(ConnectionEvent.Background)
        assertEquals(ConnectionState.Gone(host, a), controller.state.value)
    }

    @Test
    fun `target gone for a non-current target is dropped`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)

        controller.submit(ConnectionEvent.TargetGone(b)) // not current
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    // --- Drop-by-id: a non-current seed is dropped (#686 contract) --------

    @Test
    fun `offerSeed for a non-current target is dropped`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)

        val accepted = controller.offerSeed(Seed(b, "%0", "frame-for-B"))
        assertFalse("seed for non-current target B must be dropped", accepted)
    }

    @Test
    fun `offerSeed for the current target is accepted`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)

        val accepted = controller.offerSeed(Seed(a, "%0", "frame-for-A"))
        assertTrue("seed for current target A must be accepted", accepted)
    }

    @Test
    fun `seedLanded for a non-current target does not promote to Live`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport)
        transport.setWarm(host, true)
        controller.submit(ConnectionEvent.Enter(host, a)) // Attaching(A)

        // A late seed from a superseded target B must not flip A to Live.
        controller.submit(ConnectionEvent.SeedLanded(b, "%0"))
        assertEquals(ConnectionState.Attaching(host, a), controller.state.value)

        // The correct target's seed promotes.
        controller.submit(ConnectionEvent.SeedLanded(a, "%0"))
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    // --- Fast switch: Live -> Attaching, no re-handshake ------------------

    @Test
    fun `same-host switch goes Attaching on the new id without leaving Live-host`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)

        controller.submit(ConnectionEvent.Switch(b))
        assertEquals(ConnectionState.Attaching(host, b), controller.state.value)

        controller.submit(ConnectionEvent.SeedLanded(b, "%0"))
        assertEquals(ConnectionState.Live(host, b), controller.state.value)
    }

    @Test
    fun `switch from idle is a no-op`() {
        val controller = controller()
        controller.submit(ConnectionEvent.Switch(b))
        assertEquals(ConnectionState.Idle, controller.state.value)
    }

    // --- Transient drop while Live -> Reattaching -> Live on heal ---------

    @Test
    fun `transport drop while live heals through Reattaching back to Live`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)

        controller.submit(ConnectionEvent.TransportDropped("eof"))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `repeated drops escalate Reattaching to Reconnecting then Unreachable`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport, maxReconnectAttempts = 2)
            .bringLive(transport, targetId = a)

        controller.submit(ConnectionEvent.TransportDropped("d1"))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        controller.submit(ConnectionEvent.TransportDropped("d2"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)

        controller.submit(ConnectionEvent.TransportDropped("d3"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 2), controller.state.value)

        // Exhausts the budget -> the ONLY honest error.
        controller.submit(ConnectionEvent.TransportDropped("d4"))
        assertEquals(ConnectionState.Unreachable(host, a), controller.state.value)
    }

    // --- Reveal gate ------------------------------------------------------

    @Test
    fun `reveal gate holds until Live then reveals the current target only`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport)
        transport.setWarm(host, false)

        assertEquals(RevealDecision.None, controller.revealGate.value)

        controller.submit(ConnectionEvent.Enter(host, a))
        assertEquals(RevealDecision.Hold(a), controller.revealGate.value)

        controller.submit(ConnectionEvent.TransportLive) // Attaching
        assertEquals(RevealDecision.Hold(a), controller.revealGate.value)

        controller.submit(ConnectionEvent.SeedLanded(a, "%0")) // Live
        assertEquals(RevealDecision.Reveal(a, inputEnabled = true), controller.revealGate.value)
    }

    @Test
    fun `reveal gate re-holds on a switch so a non-target frame is never revealed`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)
        assertEquals(RevealDecision.Reveal(a, inputEnabled = true), controller.revealGate.value)

        controller.submit(ConnectionEvent.Switch(b))
        // Immediately holds on B — the surface can never observe (reveal, old A).
        assertEquals(RevealDecision.Hold(b), controller.revealGate.value)
    }

    @Test
    fun `reveal gate holds during silent reconnect and reveals again on heal`() {
        val clock = FakeClock()
        val transport = FakeTransportPort()
        val controller = controller(clock, transport).bringLive(transport, targetId = a)

        controller.submit(ConnectionEvent.Background)
        clock.advanceBy(ConnectionController.DEFAULT_GRACE_MS + 1L) // #1159: beyond the 90 s grace
        controller.submit(ConnectionEvent.Foreground) // Reconnecting
        assertEquals(RevealDecision.Hold(a), controller.revealGate.value)

        controller.submit(ConnectionEvent.TransportLive) // Live
        assertEquals(RevealDecision.Reveal(a, inputEnabled = true), controller.revealGate.value)
    }

    // --- Drop events for a non-current target ----------------------------

    @Test
    fun `foreground without a prior background is a no-op`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, targetId = a)
        controller.submit(ConnectionEvent.Foreground)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }
}
