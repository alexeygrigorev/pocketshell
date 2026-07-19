package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-ii — the `NetworkChanged(validatedHandoff)`
 * controller event (the 1c-i controller-API gap, flagged in #687 comment
 * 4686955117).
 *
 * The #548 suppression contract: ONLY a real, VALIDATED network-identity handoff
 * on a LIVE channel proactively silent-reconnects; a non-validated change is a
 * no-op so a transient blip / same-network re-validation never tears down a
 * healthy channel. Every recoverable transition stays SILENT (the
 * [ConnectionState.Reconnecting] ladder) — never a scary error band.
 *
 * The 1c-i coverage-first suite already pinned that a transient *transport drop*
 * heals silently; this pins the explicit network-handoff decision the inline
 * `onNetworkChanged` (#548) makes, so the inline branch can be deleted with a
 * controller-level test covering it.
 */
class ConnectionControllerNetworkChangedTest {

    private val host = HostKey("alice@host:22")
    private val a = SessionId("A")

    private fun controller(
        transport: FakeTransportPort = FakeTransportPort(),
    ) = ConnectionController(FakeClock(), transport)

    private fun ConnectionController.bringLive(transport: FakeTransportPort): ConnectionController {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, a))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(a, "%0"))
        return this
    }

    @Test
    fun `a non-validated network change on a live channel is suppressed - stays Live`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)

        // #548: a transient blip / same-network re-validation is NOT a validated
        // handoff — never tear down a healthy channel.
        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff = false))

        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `a validated network handoff on a live channel silently reconnects - no band`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)

        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))

        // Proactive silent reconnect via the Reconnecting ladder — NOT Unreachable.
        assertEquals(ConnectionState.Reconnecting(host, a, attempt = 1), controller.state.value)

        // And it resolves back to Live with no error band ever shown.
        controller.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Live(host, a), controller.state.value)
    }

    @Test
    fun `a validated handoff in Idle is a no-op`() {
        val controller = controller()
        assertEquals(ConnectionState.Idle, controller.state.value)

        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))

        assertEquals(ConnectionState.Idle, controller.state.value)
    }

    @Test
    fun `a validated handoff does not disturb an in-flight Reattaching heal`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)

        // A transient drop puts us mid-heal (silent).
        controller.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("blip")))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)

        // A network change during the heal must NOT restart the ladder or surface
        // a band — the in-flight heal owns recovery.
        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))
        assertEquals(ConnectionState.Reattaching(host, a), controller.state.value)
    }

    @Test
    fun `a validated handoff while Backgrounded is a no-op - grace owns the resume`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)

        controller.submit(ConnectionEvent.Background)
        val backgrounded = controller.state.value
        assertEquals(true, backgrounded is ConnectionState.Backgrounded)

        // No live channel to proactively replace; the grace predicate on the next
        // Foreground owns the decision, not a background network blip.
        controller.submit(ConnectionEvent.NetworkChanged(validatedHandoff = true))
        assertEquals(backgrounded, controller.state.value)
    }
}
