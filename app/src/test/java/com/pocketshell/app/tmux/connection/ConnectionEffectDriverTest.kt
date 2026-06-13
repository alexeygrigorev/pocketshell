package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.FakeTmuxClient
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
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

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

    // --- slice 1c-iv-b-B1 (#738): the driver OWNS the clean background detach ------
    //
    // The driver fires the VM-supplied `backgroundedEffect` SYNCHRONOUSLY on the
    // controller's transition INTO Backgrounded — the SOLE detach trigger now the
    // inline `backgroundDetachJob` launch is deleted. These pin that the driver is
    // the trigger, fires exactly once per background entry, and never on any other
    // transition.

    @Test
    fun firesBackgroundedEffectExactlyOnBackgroundedEntry() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        var detachTriggers = 0
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        val recorded = mutableListOf<String>()
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            backgroundedEffect = { detachTriggers += 1 },
            sink = { recorded += it },
        ).also { it.start() }

        // Drive to Live — NO detach trigger yet (no Backgrounded entry).
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals("no detach before background", 0, detachTriggers)

        // Background: the driver fires the teardown effect exactly once.
        controller.submit(ConnectionEvent.Background)
        assertEquals("driver triggers the clean detach on Backgrounded entry", 1, detachTriggers)

        // Foreground within grace then re-background: a SECOND background entry fires
        // the effect again (once per entry), never on the Reattaching/Live in between.
        clock.now = 1_000L
        controller.submit(ConnectionEvent.Foreground) // -> Reattaching
        controller.submit(ConnectionEvent.TransportLive) // -> Live
        assertEquals("no extra trigger across fg/reattach/live", 1, detachTriggers)
        controller.submit(ConnectionEvent.Background)
        assertEquals("a fresh background entry re-fires the detach trigger", 2, detachTriggers)

        scope.cancel()
    }

    @Test
    fun doesNotFireBackgroundedEffectOnNonBackgroundTransitions() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var detachTriggers = 0
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            backgroundedEffect = { detachTriggers += 1 },
        ).also { it.start() }

        // A full open/switch/drop lifecycle WITHOUT a Background never triggers detach.
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.Switch(sessionB))
        controller.submit(ConnectionEvent.SeedLanded(sessionB, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.TransportDropped("drop")) // -> Reattaching

        assertEquals("detach trigger only on Backgrounded entry", 0, detachTriggers)
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

    // --- slice 1c-iv-b-A2 (#739): the driver observes over the REAL adapters -----
    //
    // The tests above prove the inert contract through hand-rolled inert ports. The
    // tests below prove the WIRING this slice ships: the driver is constructed over
    // the SAME real adapters production uses — the real [SshLeaseTransportPort] over
    // a real [SshLeaseManager], and the real [CurrentClientTmuxPort] over a real
    // [com.pocketshell.core.tmux.TmuxClient] — and the controller it observes is the
    // bridge's real [ConnectionController] fed by those real signals (no stub
    // `emptyFlow`). The driver still calls ZERO IO; it only OBSERVES.

    private val leaseKey = SshLeaseKey(host = "example.com", port = 22, user = "alice", credentialId = "7:key")

    /** A minimal connected [SshSession] so a real [SshLeaseManager.acquire] emits a
     *  real `Connected` lease state — the genuine signal `SshLeaseTransportPort`
     *  maps to a [TransportUpDown.Up] edge for the driver to observe. */
    private class FakeConnectedSession : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")
        override fun close() = Unit
    }

    private fun realLeaseManager() =
        SshLeaseManager(
            connector = SshLeaseConnector { Result.success<SshSession>(FakeConnectedSession()) },
            idleTtlMillis = 0L,
        )

    private fun leaseTarget() =
        SshLeaseTarget(leaseKey = leaseKey, key = SshKey.Pem("unused"))

    /**
     * Slice 1c-iv-b-B2 (#742): the driver now SUBMITS TransportLive from the REAL
     * lease-`Up` edge, wired EXACTLY as production wires it: over the bridge's real
     * [ConnectionController], the real [SshLeaseTransportPort], and the real
     * [CurrentClientTmuxPort]. A genuine [SshLeaseManager.acquire] emits a real
     * `Connected` lease state → a real [TransportUpDown.Up] for the lease's host →
     * the driver submits [ConnectionEvent.TransportLive], promoting the controller.
     * No bridge mirror feed is used — the transport input ORIGINATES from the driver.
     */
    @Test
    fun submitsTransportLive_fromRealLeaseUpEdge_overRealAdapters() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val leaseManager = realLeaseManager()
        val transportPort = SshLeaseTransportPort(leaseManager, leaseKeyFor = { leaseTarget() })
        transportPort.warmSnapshot = { false } // cold open → Connecting (so Up promotes it)
        val tmuxPort = CurrentClientTmuxPort(activePaneIdFor = { it.value }, scrollbackLines = 100)
        val bridge = ConnectionControllerShadowBridge(transport = transportPort)
        val recorded = mutableListOf<String>()
        val driver = ConnectionEffectDriver(
            controller = bridge.connectionController,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            sink = { recorded += it },
        ).also { it.start() }

        // The controller's host MUST be the lease's hostKeyFor (the production
        // alignment) so the driver's host filter accepts the real Up edge.
        val leaseHost = hostKeyFor(leaseKey)
        bridge.observeInlineTransition("Connecting", leaseHost, sessionA)

        // A real dial → a real `Connected` state event → a real Up edge → the driver
        // SUBMITS TransportLive, promoting Connecting → Attaching.
        leaseManager.acquire(leaseTarget()).getOrThrow()
        // The active-pane seed landing (still bridge-fed intent) promotes to Live.
        bridge.observeSeedLanded(leaseHost, sessionA, paneId = "%0")

        val states = driver.observations.value
            .filterIsInstance<ConnectionEffectDriver.Observation.StateTransition>()
            .map { ConnectionEffectDriver.Observation.nameOf(it.to) }
        assertEquals(listOf("Idle", "Connecting", "Attaching", "Live"), states)
        assertTrue(recorded.any { it.contains("Attaching -> Live") })
        scope.cancel()
    }

    /**
     * The driver SEES the REAL lease-up edge: a genuine [SshLeaseManager.acquire]
     * emits a real `Connected` lease state, which [SshLeaseTransportPort.transportEvents]
     * maps to [TransportUpDown.Up] — and the driver records it. NOT a stub `emptyFlow`.
     */
    @Test
    fun observesRealLeaseUpEdgeFromRealTransportPort() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val leaseManager = realLeaseManager()
        val transportPort = SshLeaseTransportPort(leaseManager, leaseKeyFor = { leaseTarget() })
        val tmuxPort = CurrentClientTmuxPort(activePaneIdFor = { it.value }, scrollbackLines = 100)
        val bridge = ConnectionControllerShadowBridge(transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = bridge.connectionController,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }

        // A real dial → a real `Connected` state event → a real Up edge.
        leaseManager.acquire(leaseTarget()).getOrThrow()

        val edges = driver.observations.value
            .filterIsInstance<ConnectionEffectDriver.Observation.TransportEdge>()
        assertTrue(
            "driver must observe the REAL lease-up edge from the real transport port",
            edges.any { it.edge is TransportUpDown.Up },
        )
        scope.cancel()
    }

    /**
     * The driver SEES the REAL tmux disconnect oracle through the real
     * [CurrentClientTmuxPort]: pointing the port at a real client and flipping its
     * `disconnected` StateFlow surfaces the transport-drop signal the driver records.
     * A client swap re-points the oracle (the `flatMapLatest` seam) — the driver
     * never resubscribes, and never calls any IO method.
     */
    @Test
    fun observesRealTmuxDisconnectOracleAndClientSwapOverRealPort() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transportPort = SshLeaseTransportPort(realLeaseManager(), leaseKeyFor = { leaseTarget() })
        val tmuxPort = CurrentClientTmuxPort(activePaneIdFor = { it.value }, scrollbackLines = 100)
        val bridge = ConnectionControllerShadowBridge(transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = bridge.connectionController,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }

        val clientA = FakeTmuxClient()
        tmuxPort.setClient(clientA)
        clientA.disconnectedSignal.value = true // real transport drop on client A

        assertTrue(
            "driver must observe the REAL disconnect oracle for the attached client",
            driver.observations.value.any {
                it is ConnectionEffectDriver.Observation.Disconnected && it.isDisconnected
            },
        )

        // Swap to a fresh connected client B (a reconnect/switch): the flatMapLatest
        // seam re-points the oracle to B's `disconnected` without the driver
        // resubscribing.
        val clientB = FakeTmuxClient()
        tmuxPort.setClient(clientB)
        clientB.disconnectedSignal.value = true

        val disconnects = driver.observations.value
            .filterIsInstance<ConnectionEffectDriver.Observation.Disconnected>()
            .count { it.isDisconnected }
        assertTrue("driver must keep observing after a client swap", disconnects >= 2)
        scope.cancel()
    }
}
