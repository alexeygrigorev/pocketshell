package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LeaseHandle
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPIC #687 Phase-2, slice 1c-iv-b-A — characterization pin for the INERT,
 * observe-only [ConnectionEffectDriver].
 *
 * These tests are the lever that makes the whole effect-driver hard-cut
 * PR-testable: they drive a real [ConnectionController] through the representative
 * lifecycle scenarios (enter / switch / background / foreground / transport-drop)
 * and assert the driver OBSERVES the expected transition sequence through the real
 * port flows — while proving it calls ZERO port IO. As later sub-slices (B–F) let
 * the driver ACT, these observations flip into "the driver produced the effect"
 * assertions; for slice A they pin "the driver SEES the right transitions."
 *
 * The driver collects on an [UnconfinedTestDispatcher] scope so the collector runs
 * EAGERLY and synchronously on each `controller.state` write — capturing every
 * intermediate transition (a conflating StateFlow would otherwise collapse
 * back-to-back submits into only the latest value).
 *
 * The fake ports below FAIL the test if any IO method is ever called — that is the
 * inert contract made executable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectDriverTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")
    private val sessionB = SessionId("7/build")

    /**
     * A [TmuxPort] whose IO methods all FAIL the test if invoked (the inert
     * contract). Only [disconnected] is a real flow the driver collects.
     */
    private class InertTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        override val disconnected: Flow<Boolean> = disconnectedFlow

        override suspend fun attach(targetId: SessionId) = fail("attach")
        override suspend fun selectWindow(targetId: SessionId) = fail("selectWindow")
        override suspend fun seedActivePane(targetId: SessionId): Seed = fail("seedActivePane")
        override suspend fun detachCleanly() = fail("detachCleanly")

        private fun fail(method: String): Nothing =
            throw AssertionError("inert driver must NOT call TmuxPort.$method")
    }

    /**
     * A [TransportPort] whose IO methods all FAIL the test if invoked. [isWarm] is
     * a pure synchronous snapshot the controller's reducer consults (NOT driver IO),
     * so it is allowed. Only [transportEvents] is a real flow the driver collects.
     */
    private class InertTransportPort(private val warm: Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow

        // Consulted synchronously by the controller's reducer (NOT by the driver).
        override fun isWarm(host: HostKey): Boolean = warm

        override suspend fun ensureLease(host: HostKey): LeaseHandle = fail("ensureLease")
        override suspend fun evictStale(host: HostKey) = fail("evictStale")

        private fun fail(method: String): Nothing =
            throw AssertionError("inert driver must NOT call TransportPort.$method")
    }

    /** One harness: a controller + inert ports + a started inert driver. */
    private class Harness(
        scope: CoroutineScope,
        clock: TestClock,
        warm: Boolean,
    ) {
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        val recorded = mutableListOf<String>()
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            sink = { recorded += it },
        ).also { it.start() }

        fun stateNames(): List<String> =
            driver.observations.value
                .filterIsInstance<ConnectionEffectDriver.Observation.StateTransition>()
                .map { ConnectionEffectDriver.Observation.nameOf(it.to) }
    }

    @Test
    fun observesEnterColdConnectThroughControllerState() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, TestClock(), warm = false)

        // Cold enter: not warm -> Connecting; lease live -> Attaching; seed -> Live.
        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.TransportLive)
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))

        assertEquals(listOf("Idle", "Connecting", "Attaching", "Live"), h.stateNames())
        // The driver logged every transition through its sink.
        assertTrue(h.recorded.any { it.contains("Idle -> Connecting") })
        assertTrue(h.recorded.any { it.contains("Attaching -> Live") })
        scope.cancel()
    }

    @Test
    fun observesSameHostSwitch() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, TestClock(), warm = true)

        // Warm enter goes straight to Attaching -> Live, then a same-host switch.
        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        h.controller.submit(ConnectionEvent.Switch(sessionB))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionB, paneId = "%0"))

        assertEquals(listOf("Idle", "Attaching", "Live", "Attaching", "Live"), h.stateNames())
        // The switch landed on the new target.
        val last = h.driver.observations.value
            .filterIsInstance<ConnectionEffectDriver.Observation.StateTransition>()
            .last()
        assertEquals(sessionB, (last.to as ConnectionState.Live).targetId)
        scope.cancel()
    }

    @Test
    fun observesBackgroundThenWithinGraceForegroundReattach() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        val h = Harness(scope, clock, warm = true)

        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        h.controller.submit(ConnectionEvent.Background)
        // Foreground within grace (clock barely advanced, warm) -> Reattaching.
        clock.now = 1_000L
        h.controller.submit(ConnectionEvent.Foreground)

        assertEquals(
            listOf("Idle", "Attaching", "Live", "Backgrounded", "Reattaching"),
            h.stateNames(),
        )
        scope.cancel()
    }

    @Test
    fun observesBackgroundThenBeyondGraceForegroundReconnect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        // Beyond grace: warm-snapshot false (lease no longer warm) forces Reconnecting.
        val h = Harness(scope, clock, warm = false)

        // Drive to Live (cold path: Connecting -> Attaching -> Live).
        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.TransportLive)
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        h.controller.submit(ConnectionEvent.Background)
        clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        h.controller.submit(ConnectionEvent.Foreground)

        assertEquals(
            listOf("Idle", "Connecting", "Attaching", "Live", "Backgrounded", "Reconnecting(1)"),
            h.stateNames(),
        )
        scope.cancel()
    }

    @Test
    fun observesTransportDropHealThroughReattaching() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, TestClock(), warm = true)

        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        h.controller.submit(ConnectionEvent.TransportDropped("keepalive")) // -> Reattaching

        assertEquals(listOf("Idle", "Attaching", "Live", "Reattaching"), h.stateNames())
        scope.cancel()
    }

    @Test
    fun observesTransportDropOracleAndLeaseEdgesFromPortFlows() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, TestClock(), warm = true)

        // The driver must SEE both port flows, independent of controller state.
        h.tmuxPort.disconnectedFlow.emit(true)
        h.transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed"))
        h.transportPort.transportEventsFlow.emit(TransportUpDown.Up(host))

        val obs = h.driver.observations.value
        assertTrue(
            "must observe the tmux disconnect oracle",
            obs.any { it is ConnectionEffectDriver.Observation.Disconnected && it.isDisconnected },
        )
        assertTrue(
            "must observe the lease-down edge",
            obs.any {
                it is ConnectionEffectDriver.Observation.TransportEdge &&
                    it.edge is TransportUpDown.Down
            },
        )
        assertTrue(
            "must observe the lease-up edge",
            obs.any {
                it is ConnectionEffectDriver.Observation.TransportEdge &&
                    it.edge is TransportUpDown.Up
            },
        )
        scope.cancel()
    }

    @Test
    fun callsZeroPortIo_acrossFullLifecycle() = runTest {
        // The InertTmuxPort / InertTransportPort throw AssertionError on ANY IO
        // method; driving a full lifecycle through them with the driver collecting
        // proves the inert contract: no ensureLease/attach/selectWindow/seed/detach/
        // evictStale is ever called by the driver.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = Harness(scope, TestClock(), warm = true)

        h.controller.submit(ConnectionEvent.Enter(host, sessionA))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        h.controller.submit(ConnectionEvent.Switch(sessionB))
        h.controller.submit(ConnectionEvent.SeedLanded(sessionB, paneId = "%0"))
        h.controller.submit(ConnectionEvent.Background)
        h.controller.submit(ConnectionEvent.Foreground)
        h.controller.submit(ConnectionEvent.TransportDropped("drop"))
        h.tmuxPort.disconnectedFlow.emit(true)
        h.transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, "closed"))

        // Reaching here without an AssertionError from a fake port IS the proof:
        // the driver collected/logged every signal and called zero port IO.
        assertTrue(h.driver.observations.value.isNotEmpty())
        scope.cancel()
    }
}
