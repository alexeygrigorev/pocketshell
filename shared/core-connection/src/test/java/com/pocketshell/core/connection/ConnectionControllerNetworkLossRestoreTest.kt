package com.pocketshell.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControllerNetworkLossRestoreTest {

    private val host = HostKey("alice@host:22")
    private val target = SessionId("A")
    private val other = SessionId("B")
    private val nowMs = 12_345L

    @Test
    fun `NetworkLost on Live suspends - holds lease and does not redial`() {
        val liveness = FakeLivenessPort()

        val next = reduceNetworkLossRestore(
            ConnectionState.Live(host, target),
            ConnectionEvent.NetworkLost,
            liveness,
            nowMs,
        )

        assertEquals(ConnectionState.NetworkLossSuspended(host, target, sinceMs = nowMs), next)
        assertEquals("loss should not query liveness", 0, liveness.queryCount)
    }

    @Test
    fun `NetworkLost stamps sinceMs from injected clock value`() {
        val next = reduceNetworkLossRestore(
            ConnectionState.Live(host, target),
            ConnectionEvent.NetworkLost,
            FakeLivenessPort(),
            nowMs = 77L,
        )

        assertEquals(ConnectionState.NetworkLossSuspended(host, target, sinceMs = 77L), next)
    }

    @Test
    fun `NetworkLost outside Live is a no-op for every existing state class`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting(host, target),
            ConnectionState.Attaching(host, target),
            ConnectionState.Backgrounded(host, target, sinceMs = 1L),
            ConnectionState.NetworkLossSuspended(host, target, sinceMs = 2L),
            ConnectionState.Reattaching(host, target),
            ConnectionState.Reconnecting(host, target, attempt = 2),
            ConnectionState.Gone(host, target),
            ConnectionState.Unreachable(host, target),
        )
        val liveness = FakeLivenessPort()

        states.forEach { state ->
            assertEquals(state, reduceNetworkLossRestore(state, ConnectionEvent.NetworkLost, liveness, nowMs))
        }
        assertEquals("loss no-ops should not query liveness", 0, liveness.queryCount)
    }

    @Test
    fun `NetworkRestored with transport proven alive rides through to Live - no redial`() {
        val liveness = FakeLivenessPort(provenAlive = true)

        val next = reduceNetworkLossRestore(
            ConnectionState.NetworkLossSuspended(host, target, sinceMs = 1L),
            ConnectionEvent.NetworkRestored,
            liveness,
            nowMs,
        )

        assertEquals(ConnectionState.Live(host, target), next)
        assertEquals(1, liveness.queryCount)
    }

    @Test
    fun `NetworkRestored with dead transport redials via the silent Reconnecting ladder`() {
        val liveness = FakeLivenessPort(provenAlive = false)

        val next = reduceNetworkLossRestore(
            ConnectionState.NetworkLossSuspended(host, target, sinceMs = 1L),
            ConnectionEvent.NetworkRestored,
            liveness,
            nowMs,
        )

        assertEquals(ConnectionState.Reconnecting(host, target, attempt = 1), next)
        assertEquals(1, liveness.queryCount)
    }

    @Test
    fun `NetworkRestored outside suspended state is a no-op and does not query liveness`() {
        val states = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting(host, target),
            ConnectionState.Attaching(host, target),
            ConnectionState.Live(host, target),
            ConnectionState.Backgrounded(host, target, sinceMs = 1L),
            ConnectionState.Reattaching(host, target),
            ConnectionState.Reconnecting(host, target, attempt = 2),
            ConnectionState.Gone(host, target),
            ConnectionState.Unreachable(host, target),
        )
        val liveness = FakeLivenessPort(provenAlive = true)

        states.forEach { state ->
            assertEquals(state, reduceNetworkLossRestore(state, ConnectionEvent.NetworkRestored, liveness, nowMs))
        }
        assertEquals(0, liveness.queryCount)
    }

    @Test
    fun `foreign event is a no-op and does not query liveness`() {
        val state = ConnectionState.NetworkLossSuspended(host, target, sinceMs = 1L)
        val liveness = FakeLivenessPort(provenAlive = true)

        val next = reduceNetworkLossRestore(
            state,
            ConnectionEvent.SeedLanded(target, "%0"),
            liveness,
            nowMs,
        )

        assertEquals(state, next)
        assertEquals(0, liveness.queryCount)
    }

    @Test
    fun `new vocabulary carries target and host through helper accessors`() {
        val state = ConnectionState.NetworkLossSuspended(host, target, sinceMs = nowMs)

        assertEquals(target, state.targetIdOrNull())
        assertEquals(host, state.hostOrNull())
    }

    @Test
    fun `submit treats NetworkLost and NetworkRestored as inert vocabulary while Live`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)
        assertEquals(ConnectionState.Live(host, target), controller.state.value)
        val reveal = controller.revealGate.value

        controller.submit(ConnectionEvent.NetworkLost)
        assertEquals(ConnectionState.Live(host, target), controller.state.value)
        assertEquals(reveal, controller.revealGate.value)

        controller.submit(ConnectionEvent.NetworkRestored)
        assertEquals(ConnectionState.Live(host, target), controller.state.value)
        assertEquals(reveal, controller.revealGate.value)
    }

    @Test
    fun `submit treats NetworkLost and NetworkRestored as inert vocabulary while Backgrounded`() {
        val transport = FakeTransportPort()
        val controller = controller(transport).bringLive(transport)
        controller.submit(ConnectionEvent.Background)
        val backgrounded = controller.state.value
        val reveal = controller.revealGate.value

        controller.submit(ConnectionEvent.NetworkLost)
        assertEquals(backgrounded, controller.state.value)
        assertEquals(reveal, controller.revealGate.value)

        controller.submit(ConnectionEvent.NetworkRestored)
        assertEquals(backgrounded, controller.state.value)
        assertEquals(reveal, controller.revealGate.value)
    }

    @Test
    fun `NetworkLossSuspended reveal projection keeps current reveal state`() {
        val machine = RevealStateMachine()
        machine.navigate(target, "A")
        machine.onConnectionState(ConnectionState.Attaching(host, target))
        machine.onSeed(Seed(target, "%0", "content"))
        assertEquals(
            RevealState.Live(target, "A", listOf(Seed(target, "%0", "content"))),
            machine.state.value,
        )

        machine.onConnectionState(ConnectionState.NetworkLossSuspended(host, target, sinceMs = nowMs))

        assertEquals(
            RevealState.Live(target, "A", listOf(Seed(target, "%0", "content"))),
            machine.state.value,
        )
    }

    @Test
    fun `NetworkLossSuspended reveal projection still drops foreign target states`() {
        val machine = RevealStateMachine()
        machine.navigate(target, "A")

        machine.onConnectionState(ConnectionState.NetworkLossSuspended(host, other, sinceMs = nowMs))

        assertEquals(RevealState.Navigating(target, "A"), machine.state.value)
    }

    private fun controller(
        transport: FakeTransportPort = FakeTransportPort(),
    ) = ConnectionController(FakeClock(), transport)

    private fun ConnectionController.bringLive(transport: FakeTransportPort): ConnectionController {
        transport.setWarm(host, false)
        submit(ConnectionEvent.Enter(host, target))
        transport.setWarm(host, true)
        submit(ConnectionEvent.TransportLive)
        submit(ConnectionEvent.SeedLanded(target, "%0"))
        return this
    }
}
