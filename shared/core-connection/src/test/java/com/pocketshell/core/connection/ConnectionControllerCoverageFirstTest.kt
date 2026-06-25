package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c (coverage-first, plan §4 risk #3).
 *
 * Two inline VM behaviors have JVM-pinned effects through `ConnectionStatus` but
 * had NO controller-level test. Per the coverage-first rule, these are added
 * green-on-the-new-controller BEFORE the inline branches are deleted (the
 * deletion itself lands in the atomic 1c-ii swap + slice 3), so the hard cut
 * never drops a real learning the JVM suite only catches indirectly:
 *
 *  - #630 in-app-nav-mismatch skip: when the user has already navigated to a
 *    DIFFERENT session, a late transport drop from the LEAVING session must NOT
 *    pause-reconnect the session the user is now on. The controller encodes this
 *    via drop-by-id: once the state's target is B, a `TransportDropped` is
 *    interpreted against B (the current target), and the superseded A channel's
 *    late drop cannot resurrect or stall A.
 *
 *  - #548 network-handoff suppression: a transport blip on a still-live channel
 *    must heal SILENTLY (no reconnect, no scary band) rather than tearing down
 *    and re-dialing on a non-validated network change. The controller encodes
 *    this as the single transient-drop heal: Live --TransportDropped--> Reattaching
 *    (silent), and only a confirmed-dead transport (heal-failed) escalates the
 *    silent reconnect ladder. There is exactly ONE honest error (Unreachable),
 *    and it appears only after the retry budget truly exhausts.
 *
 * NOTE: a dedicated `NetworkChanged(validatedHandoff)` controller event is a
 * 1c-ii controller-API addition (the controller has no such event today). This
 * test pins the controller behavior the #548 suppression decision RELIES ON —
 * that a transient drop heals silently instead of surfacing a Failed/Unreachable
 * band — which is the load-bearing guarantee the inline suppression preserves.
 */
class ConnectionControllerCoverageFirstTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")
    private val b = SessionId("B")

    private fun controller(
        clock: FakeClock = FakeClock(),
        transport: FakeTransportPort = FakeTransportPort(),
        maxReconnectAttempts: Int = ConnectionController.DEFAULT_MAX_RECONNECT_ATTEMPTS,
    ) = ConnectionController(clock, transport, maxReconnectAttempts)

    private fun ConnectionController.bringLive(
        transport: FakeTransportPort,
        targetId: SessionId = a,
    ): ConnectionController {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, targetId))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(targetId, "%0"))
        return this
    }

    // --- #630 in-app-nav-mismatch skip -----------------------------------

    @Test
    fun `after switching to B a late drop is interpreted against B not the left A`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, a)

        // User navigates in-app to session B (same warm host, fast switch).
        controller.submit(ConnectionEvent.Switch(b))
        assertEquals(ConnectionState.Attaching(host, b), controller.state.value)
        controller.submit(ConnectionEvent.SeedLanded(b, "%0"))
        assertEquals(ConnectionState.Live(host, b), controller.state.value)

        // The LEAVING session A's old control channel now drops (the late
        // passive disconnect the #630 inline skip guards against). The
        // controller interprets the drop against the CURRENT target (B) — it
        // heals B silently and never pauses/stalls/resurrects A.
        controller.submit(ConnectionEvent.TransportDropped("late EOF from left session A"))
        val state = controller.state.value
        assertEquals(ConnectionState.Reattaching(host, b), state)
        // Still on B's identity, never A.
        assertEquals(b, state.targetIdOrNull())
    }

    @Test
    fun `a target-gone for the superseded A after switching to B is dropped`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, a)

        controller.submit(ConnectionEvent.Switch(b))
        controller.submit(ConnectionEvent.SeedLanded(b, "%0"))
        assertEquals(ConnectionState.Live(host, b), controller.state.value)

        // A delete/gone for the SUPERSEDED A must not transition the live B.
        controller.submit(ConnectionEvent.TargetGone(a))
        assertEquals(ConnectionState.Live(host, b), controller.state.value)
    }

    // --- #548 network-handoff suppression (silent heal, never a scary band) --

    @Test
    fun `a transient drop on a live channel heals silently through Reattaching - no band`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport).bringLive(transport, a)

        controller.submit(ConnectionEvent.TransportDropped("network blip / non-validated change"))

        // SILENT recovery — Reattaching, NOT Unreachable. This is the
        // suppress-don't-reconnect-on-a-blip guarantee the #548 inline decision
        // preserves: a non-validated network change never produces the honest
        // error band; the live channel heals.
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        // And it resolves back to Live with no band ever shown.
        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `a transient drop while attaching heals silently and returns Live for the same target`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport)
        transport.setWarm(host, true)
        controller.submit(ConnectionEvent.Enter(host, a))
        assertEquals(ConnectionState.Attaching(host, a), controller.state.value)

        controller.submit(ConnectionEvent.TransportDropped("control channel closed before first seed"))

        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)
        assertEquals(a, controller.state.value.targetIdOrNull())
        assertEquals(RevealDecision.Hold(a), controller.revealGate.value)

        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
        assertEquals(RevealDecision.Reveal(a, inputEnabled = true), controller.revealGate.value)
    }

    @Test
    fun `repeated drops during silent recovery keep the original target and stale events cannot switch it`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport, maxReconnectAttempts = 3)
            .bringLive(transport, a)

        controller.submit(ConnectionEvent.TransportDropped("drop 1"))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        controller.submit(ConnectionEvent.TransportDropped("drop 2"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)
        assertEquals(a, controller.state.value.targetIdOrNull())

        controller.submit(ConnectionEvent.SeedLanded(b, "%1"))
        controller.submit(ConnectionEvent.TargetGone(b))
        assertEquals(
            "late events for B must not corrupt or switch the recovery target",
            ConnectionState.Reconnecting(host, a, attempt = 1),
            controller.state.value,
        )

        controller.submit(ConnectionEvent.TransportDropped("drop 3"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 2), controller.state.value)
        assertEquals(a, controller.state.value.targetIdOrNull())

        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(
            "transport recovery must return to Live on the original target",
            ConnectionState.Live(host, a),
            controller.state.value,
        )
    }

    @Test
    fun `the honest error appears only after the silent retry budget truly exhausts`() {
        val transport = FakeTransportPort()
        val controller = controller(transport = transport, maxReconnectAttempts = 2)
            .bringLive(transport, a)

        // Live -> Reattaching (silent heal).
        controller.submit(ConnectionEvent.TransportDropped("drop 1"))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        // Heal failed -> silent reconnect ladder (attempt 1).
        controller.submit(ConnectionEvent.TransportDropped("drop 2"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)

        // attempt 2 (still silent — no band).
        controller.submit(ConnectionEvent.TransportDropped("drop 3"))
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 2), controller.state.value)

        // Budget exhausted -> the ONLY honest error band.
        controller.submit(ConnectionEvent.TransportDropped("drop 4"))
        assertEquals(ConnectionState.Unreachable(host, a), controller.state.value)
    }
}
