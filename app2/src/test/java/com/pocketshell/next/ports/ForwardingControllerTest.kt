package com.pocketshell.next.ports

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.RoomTrustStore
import com.pocketshell.core.transport.ConnectResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ForwardingController] over the real `core-portfwd` engine, a real in-memory
 * Room database and a scripted transport (see [TestForwardingStack]).
 *
 * The scan loop is intentionally unbounded, so these tests drive it with
 * [runCurrent] / [advanceTimeBy] rather than `advanceUntilIdle`, which would
 * advance virtual time through `delay(scanIntervalSec)` forever.
 *
 * Ports 7431/7432 are used instead of the obvious 3000/8080 because the
 * forwarder's local-port allocator really does bind a loopback socket to test
 * availability; an unremarkable high port keeps the run independent of whatever
 * the dev box happens to have listening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ForwardingControllerTest {

    private var stack: TestForwardingStack? = null

    @After
    fun tearDown() {
        stack?.close()
    }

    @Test
    fun `enabling a host records the durable intent and publishes a row`() = forwardingTest { stack ->
        val hostId = stack.seedHost(name = "rmthz")

        stack.controller.start(hostId)
        runCurrent()

        assertTrue("hosts.enabled is the durable intent", stack.isEnabled(hostId))
        assertTrue(stack.controller.isRunning(hostId))
        val row = stack.controller.snapshot.value.single()
        assertEquals(hostId, row.hostId)
        assertEquals("rmthz", row.hostName)
    }

    @Test
    fun `enabling twice mounts one supervisor and dials once`() = forwardingTest { stack ->
        val hostId = stack.seedHost()

        stack.controller.start(hostId)
        runCurrent()
        stack.controller.start(hostId)
        runCurrent()

        assertEquals(1, stack.controller.snapshot.value.size)
        assertEquals("a second start must not dial again", 1, stack.factory.dialCount)
    }

    @Test
    fun `discovered in-window ports are forwarded and reach the snapshot`() = forwardingTest { stack ->
        stack.listenOn(7_431 to "vite", 7_432 to "api")
        val hostId = stack.seedHost()

        stack.controller.start(hostId)
        runCurrent()

        val tunnels = stack.controller.tunnels(hostId)
        assertEquals(listOf(7_431, 7_432), tunnels.map { it.remotePort }.sorted())
        assertTrue(
            "in-window ports auto-forward, got $tunnels",
            tunnels.all { it.status == TunnelInfo.Status.FORWARDING },
        )
        assertEquals("vite", tunnels.first { it.remotePort == 7_431 }.process)
    }

    @Test
    fun `an out-of-window port is discovered as available and forwarded only on toggle`() = forwardingTest { stack ->
        // 22 is below skipPortsBelow, so it is discovered but never auto-forwarded.
        stack.listenOn(22 to "sshd")
        val hostId = stack.seedHost()

        stack.controller.start(hostId)
        runCurrent()
        assertEquals(
            TunnelInfo.Status.AVAILABLE,
            stack.controller.tunnels(hostId).single().status,
        )

        stack.controller.togglePort(hostId, 22)
        runCurrent()

        assertEquals(
            TunnelInfo.Status.FORWARDING,
            stack.controller.tunnels(hostId).single { it.remotePort == 22 }.status,
        )
    }

    @Test
    fun `a persisted remapping moves the local end of the forward`() = forwardingTest { stack ->
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        // Without the remapping the forwarder mirrors remote 7431 onto local 7431.
        stack.seedRemapping(hostId, remotePort = 7_431, localPort = 7_500)

        stack.controller.start(hostId)
        runCurrent()

        val tunnel = stack.controller.tunnels(hostId).single()
        assertTrue(
            "the controller must feed port_remappings into the forwarder, got ${tunnel.localPort}",
            tunnel.localPort >= 7_500,
        )
    }

    @Test
    fun `disabling clears the intent, drops the row and stops forwarding`() = forwardingTest { stack ->
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        stack.controller.start(hostId)
        runCurrent()
        assertTrue(stack.controller.tunnels(hostId).isNotEmpty())

        stack.controller.stop(hostId)
        runCurrent()

        assertFalse("hosts.enabled must be cleared", stack.isEnabled(hostId))
        assertFalse(stack.controller.isRunning(hostId))
        assertEquals(emptyList<ForwardingController.HostForwarding>(), stack.controller.snapshot.value)
        assertEquals(emptyList<TunnelInfo>(), stack.controller.tunnels(hostId))
    }

    @Test
    fun `stopAll disables every enabled host and empties the snapshot`() = forwardingTest { stack ->
        val first = stack.seedHost(name = "alpha")
        val second = stack.seedHost(name = "beta")
        stack.controller.start(first)
        stack.controller.start(second)
        runCurrent()
        assertEquals(2, stack.controller.snapshot.value.size)

        stack.controller.stopAll()
        runCurrent()

        assertFalse(stack.isEnabled(first))
        assertFalse(stack.isEnabled(second))
        assertEquals(emptyList<ForwardingController.HostForwarding>(), stack.controller.snapshot.value)
    }

    @Test
    fun `resumeEnabled mounts exactly the hosts whose enabled column is set`() = forwardingTest { stack ->
        val enabled = stack.seedHost(name = "alpha", enabled = true)
        stack.seedHost(name = "beta", enabled = false)

        val mounted = stack.controller.resumeEnabled()
        runCurrent()

        assertEquals(1, mounted)
        assertEquals(listOf(enabled), stack.controller.snapshot.value.map { it.hostId })
        assertEquals("only the enabled host is dialled", 1, stack.factory.dialCount)
    }

    @Test
    fun `resumeEnabled with nothing enabled mounts nothing`() = forwardingTest { stack ->
        stack.seedHost(enabled = false)

        assertEquals(0, stack.controller.resumeEnabled())
        runCurrent()

        assertEquals(0, stack.factory.dialCount)
        assertEquals(emptyList<ForwardingController.HostForwarding>(), stack.controller.snapshot.value)
    }

    @Test
    fun `rapid enable-disable toggling ends in the requested state with no stale row`() =
        forwardingTest { stack ->
        // The old race class: a burst of toggles left a row (and a notification)
        // describing a host that had already been torn down. One mutex around the
        // table plus a filter-and-map publish makes a stale row impossible.
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()

        repeat(12) { index ->
            if (index % 2 == 0) stack.controller.start(hostId) else stack.controller.stop(hostId)
            runCurrent()
        }
        // Ends on stop() (index 11 is odd).
        assertFalse(stack.isEnabled(hostId))
        assertEquals(emptyList<ForwardingController.HostForwarding>(), stack.controller.snapshot.value)

        // And a final enable still produces exactly one healthy row.
        stack.controller.start(hostId)
        runCurrent()
        val row = stack.controller.snapshot.value.single()
        assertEquals(hostId, row.hostId)
        assertTrue(row.tunnels.isNotEmpty())
    }

    @Test
    fun `an unreachable host keeps its row and reports a non-connected state`() = forwardingTest { stack ->
        stack.factory.failWith = "connection refused"
        val hostId = stack.seedHost()

        stack.controller.start(hostId)
        runCurrent()

        val row = stack.controller.snapshot.value.single()
        assertTrue("a failed dial must not silently vanish", row.tunnels.isEmpty())
        assertTrue(
            "a failed dial must not read as Connected, got ${row.connection}",
            row.connection != ConnectionState.Connected,
        )
    }

    @Test
    fun `forwarding dials its own connection instead of reusing the interactive one`() =
        forwardingTest { stack ->
        // D21's forwarding carve-out, asserted rather than assumed: if forwards
        // shared the registry's one-connection-per-host instance, backgrounding
        // the terminal and letting its grace close fire would kill every tunnel.
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        val registry = ConnectionsRegistry(
            factory = stack.factory,
            trustStore = RoomTrustStore(stack.db.hostDao(), Dispatchers.Unconfined),
            hostDao = stack.db.hostDao(),
            dispatcher = Dispatchers.Unconfined,
        )

        val interactive = (registry.getOrConnect(hostId) as ConnectResult.Connected).connection
        stack.controller.start(hostId)
        runCurrent()

        assertEquals("forwarding must dial its own transport", 2, stack.factory.dialCount)
        val forwarding = stack.factory.connections.last()
        assertNotSame(interactive, forwarding)

        // And proving it is not the registry's: closing the interactive
        // connection (what a grace close does) leaves the forwards up.
        interactive.close()
        runCurrent()
        assertTrue(
            "forwards must survive the interactive connection closing",
            stack.controller.tunnels(hostId).any { it.status == TunnelInfo.Status.FORWARDING },
        )
    }

    /**
     * A `runTest` body with a live forwarding stack, torn down before the test
     * ends.
     *
     * The teardown is not hygiene, it is required: the forwarder's scan loop is
     * intentionally unbounded, and `runTest` runs `advanceUntilIdle` after the
     * body — which would advance virtual time through `delay(scanIntervalSec)`
     * forever and hang the whole suite. [ForwardingController.stopAll] is the
     * production teardown and cancels every supervisor, so the scheduler really
     * does go idle.
     */
    private fun forwardingTest(
        body: suspend TestScope.(TestForwardingStack) -> Unit,
    ) = runTest {
        val stack = TestForwardingStack(StandardTestDispatcher(testScheduler))
        this@ForwardingControllerTest.stack = stack
        try {
            body(stack)
        } finally {
            stack.controller.stopAll()
            runCurrent()
        }
    }
}
