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

    // --- slice 1c-iv-c (#754): the driver OWNS the within-grace FOREGROUND reattach ----
    //
    // On the controller's Backgrounded -> Reattaching edge (within grace + warm lease)
    // the driver fires the VM-supplied RESEED-ONLY effect — the hard-cut replacement for
    // the deleted inline `probeCurrentRuntimeOnForegroundIfNeeded -> connect(...)` path
    // that raised the "Attaching…" overlay. These pin that the driver is the trigger,
    // fires only on the foreground (Backgrounded -> Reattaching) edge, and NEVER on a
    // Reattaching reached from a transport DROP (the silent heal ladder).

    @Test
    fun firesForegroundReattachEffectOnWithinGraceForegroundEdge() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        var reseedTriggers = 0
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReattachEffect = { reseedTriggers += 1 },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.Background) // -> Backgrounded
        assertEquals("no reseed before foreground", 0, reseedTriggers)

        // Foreground within grace (clock barely advanced, warm) -> Reattaching.
        clock.now = 1_000L
        controller.submit(ConnectionEvent.Foreground) // -> Reattaching
        assertEquals(
            "driver fires the RESEED-ONLY effect on the within-grace foreground edge",
            1,
            reseedTriggers,
        )

        // The reseed's SeedLanded promotes Reattaching -> Live; no extra reseed fires.
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // -> Live
        assertEquals("no extra reseed once promoted to Live", 1, reseedTriggers)
        scope.cancel()
    }

    @Test
    fun doesNotFireForegroundReattachEffectOnTransportDropHeal() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var reseedTriggers = 0
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReattachEffect = { reseedTriggers += 1 },
        ).also { it.start() }

        // A transport DROP also reaches Reattaching (the silent heal ladder) — but it is
        // NOT a foreground return, so the reseed-only foreground effect must NOT fire.
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.TransportDropped("keepalive")) // Live -> Reattaching

        assertEquals(
            "the within-grace foreground reseed must only fire on Backgrounded -> Reattaching",
            0,
            reseedTriggers,
        )
        scope.cancel()
    }

    @Test
    fun beyondGraceForegroundDoesNotFireForegroundReattachEffect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        var reseedTriggers = 0
        val tmuxPort = InertTmuxPort()
        // Beyond grace: warm-snapshot false forces Backgrounded -> Reconnecting, NOT
        // Reattaching — the reseed-only foreground effect must NOT fire.
        val transportPort = InertTransportPort(warm = false)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReattachEffect = { reseedTriggers += 1 },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.TransportLive)
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.Background)
        clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        controller.submit(ConnectionEvent.Foreground) // -> Reconnecting(1)

        assertEquals(
            "a beyond-grace foreground is a Reconnecting, not a reseed-only Reattaching",
            0,
            reseedTriggers,
        )
        scope.cancel()
    }

    // --- EPIC #766 slice 2a: the BEYOND-grace foreground arm effect -------------
    //
    // The new `foregroundReconnectEffect` seam re-homes the inline
    // `reduceConnection(Foreground)` replay/resume arm onto the controller's
    // Backgrounded -> Reconnecting EDGE. These pin: (1) it fires exactly on the
    // beyond-grace foreground edge, (2) it does NOT fire on the within-grace
    // (Reattaching) foreground edge, and (3) it does NOT fire on a Reconnecting
    // reached from the reconnect LADDER (a transport drop, not a foreground return).
    // Without the new edge wiring in the driver, assertion (1) is RED (the effect
    // never fires); with it, GREEN.

    @Test
    fun firesForegroundReconnectEffectOnBeyondGraceForegroundEdge() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        var reconnectArmTriggers = 0
        var reseedTriggers = 0
        val tmuxPort = InertTmuxPort()
        // Beyond grace: warm-snapshot false forces Backgrounded -> Reconnecting.
        val transportPort = InertTransportPort(warm = false)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReattachEffect = { reseedTriggers += 1 },
            foregroundReconnectEffect = { reconnectArmTriggers += 1 },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.TransportLive)
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.Background) // -> Backgrounded
        assertEquals("no foreground arm before foreground", 0, reconnectArmTriggers)

        clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
        controller.submit(ConnectionEvent.Foreground) // Backgrounded -> Reconnecting(1)

        assertEquals(
            "driver fires the beyond-grace foreground arm on Backgrounded -> Reconnecting",
            1,
            reconnectArmTriggers,
        )
        assertEquals(
            "the within-grace reseed effect must NOT fire on the beyond-grace edge",
            0,
            reseedTriggers,
        )
        scope.cancel()
    }

    @Test
    fun doesNotFireForegroundReconnectEffectOnWithinGraceForegroundEdge() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val clock = TestClock()
        var reconnectArmTriggers = 0
        val tmuxPort = InertTmuxPort()
        // Within grace: warm-snapshot true forces Backgrounded -> Reattaching.
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = clock, transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReconnectEffect = { reconnectArmTriggers += 1 },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.Background) // -> Backgrounded
        clock.now = 1_000L
        controller.submit(ConnectionEvent.Foreground) // Backgrounded -> Reattaching

        assertEquals(
            "the beyond-grace foreground arm must NOT fire on the within-grace edge",
            0,
            reconnectArmTriggers,
        )
        scope.cancel()
    }

    @Test
    fun doesNotFireForegroundReconnectEffectOnReconnectLadderDrop() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var reconnectArmTriggers = 0
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            foregroundReconnectEffect = { reconnectArmTriggers += 1 },
        ).also { it.start() }

        // A transport-drop reconnect ladder reaches Reconnecting (Live -> Reattaching ->
        // Reconnecting), but it is NOT a foreground return, so the foreground arm effect
        // must NOT fire — only the Backgrounded -> Reconnecting edge does.
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0")) // Live
        controller.submit(ConnectionEvent.TransportDropped("keepalive")) // -> Reattaching
        controller.submit(ConnectionEvent.TransportDropped("keepalive")) // -> Reconnecting(1)

        assertEquals(
            "the foreground arm must only fire on Backgrounded -> Reconnecting",
            0,
            reconnectArmTriggers,
        )
        scope.cancel()
    }

    // --- EPIC #687 P2 (J1/#635): the SINGLE-GRACE-OWNER drop-suppression gate ----
    //
    // `suppressTransportDrops` is the no-double-drive invariant: when it returns
    // true (VM supplies a process-backgrounded predicate), a control-channel
    // drop is NOT submitted as `TransportDropped` — the App-level background-grace
    // window is the SOLE grace authority, so the driver must NOT start a second
    // competing grace clock that walks the controller Live → … → Unreachable while
    // backgrounded (the literal cause of the #635 spurious band on the next
    // within-grace foreground). The default `{ false }` keeps the always-submit
    // OLD-path behavior. These pin BOTH sides of the gate at gradle-test speed; the
    // only prior guard was the on-device J1 e2e journey.

    private fun stateNamesOf(driver: ConnectionEffectDriver): List<String> =
        driver.observations.value
            .filterIsInstance<ConnectionEffectDriver.Observation.StateTransition>()
            .map { ConnectionEffectDriver.Observation.nameOf(it.to) }

    @Test
    fun suppressesTransportDropSubmissionWhenGateIsTrue() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            suppressTransportDrops = { true }, // backgrounded under the NEW path
        ).also { it.start() }

        // Drive to Live (warm enter: Attaching -> Live).
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        // A control-channel drop arrives while the gate is true: the driver records
        // DropSuppressed and does NOT submit TransportDropped, so the controller is
        // NEVER walked off Live (no Reattaching) — recovery is left to the single
        // grace owner (the within-grace foreground heal).
        tmuxPort.disconnectedFlow.emit(true)

        assertTrue(
            "the suppressed drop must be recorded as DropSuppressed",
            driver.observations.value.any {
                it is ConnectionEffectDriver.Observation.DropSuppressed
            },
        )
        // The controller stays Live: NO TransportDropped was submitted, so there is
        // no Live -> Reattaching transition.
        assertEquals(
            "a suppressed drop must NOT walk the controller off Live",
            listOf("Idle", "Attaching", "Live"),
            stateNamesOf(driver),
        )
        assertTrue(
            "controller must remain Live (the single grace owner handles recovery)",
            controller.state.value is ConnectionState.Live,
        )
        scope.cancel()
    }

    @Test
    fun submitsTransportDropWhenGateIsFalse_theDefaultOldPathContract() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        // Default gate `{ false }` (omit the param) — the always-submit OLD-path
        // behavior the toggle must not silently regress.
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        // With the gate false the same drop IS submitted: the controller heals
        // Live -> Reattaching (the silent heal ladder).
        tmuxPort.disconnectedFlow.emit(true)

        assertTrue(
            "the drop must NOT be recorded as suppressed when the gate is false",
            driver.observations.value.none {
                it is ConnectionEffectDriver.Observation.DropSuppressed
            },
        )
        assertEquals(
            "an unsuppressed drop walks the controller Live -> Reattaching",
            listOf("Idle", "Attaching", "Live", "Reattaching"),
            stateNamesOf(driver),
        )
        scope.cancel()
    }

    @Test
    fun typedControlChannelDropFiresEffectAfterControllerSubmit() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val typedDrops = MutableSharedFlow<FakeTmuxClient>(extraBufferCapacity = 16)
        val accepted = mutableListOf<FakeTmuxClient>()
        val effects = mutableListOf<Pair<FakeTmuxClient, ConnectionState>>()
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            controlChannelDrops = typedDrops,
            shouldSubmitControlChannelDrop = { client ->
                accepted += client as FakeTmuxClient
                true
            },
            controlChannelDroppedEffect = { client ->
                effects += Pair(client as FakeTmuxClient, controller.state.value)
            },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        val client = FakeTmuxClient()
        typedDrops.emit(client)

        assertEquals(listOf(client), accepted)
        assertEquals(
            "accepted current-client drop walks the controller before the VM recovery effect",
            listOf("Idle", "Attaching", "Live", "Reattaching"),
            stateNamesOf(driver),
        )
        assertEquals(listOf(client to controller.state.value), effects)
        assertTrue(controller.state.value is ConnectionState.Reattaching)
        scope.cancel()
    }

    @Test
    fun typedControlChannelDropRejectedAsStaleDoesNotMoveControllerOrFireEffect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val typedDrops = MutableSharedFlow<FakeTmuxClient>(extraBufferCapacity = 16)
        val effects = mutableListOf<FakeTmuxClient>()
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            controlChannelDrops = typedDrops,
            shouldSubmitControlChannelDrop = { false },
            controlChannelDroppedEffect = { client -> effects += client as FakeTmuxClient },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        typedDrops.emit(FakeTmuxClient())

        assertEquals(
            "a stale-client rejection must leave the controller untouched",
            listOf("Idle", "Attaching", "Live"),
            stateNamesOf(driver),
        )
        assertTrue("stale rejection must not run passive recovery", effects.isEmpty())
        assertTrue(controller.state.value is ConnectionState.Live)
        scope.cancel()
    }

    // --- Slice 3 (#766): controller-authoritative RECONNECT LADDER -----------------
    //
    // The lease `Down` edge — deferred since B2 ("its consumer the reconnect loop is a
    // later slice") — is now SUBMITTED as `TransportDropped` for the current host, under
    // the SAME single-grace-owner gate. This is the input that makes the
    // ConnectionController authoritative for the ladder STATE: a real lease close walks
    // it Live -> Reattaching -> Reconnecting(n) -> Unreachable. These pin both the submit
    // and the gate-suppression at gradle-test speed.

    @Test
    fun submitsLeaseDownAsTransportDrop_walkingTheControllerReconnectLadder() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        // Default gate `{ false }` (foregrounded): the lease Down must drive the ladder.
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }

        // Drive to Live (warm enter: Attaching -> Live).
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        // A real lease `Down` for the CURRENT host is now submitted as TransportDropped:
        // the controller heals Live -> Reattaching (the first ladder rung). Slice 3
        // makes the controller authoritative for that ladder STATE.
        transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed"))
        assertEquals(
            "a foreground lease Down walks the controller Live -> Reattaching",
            listOf("Idle", "Attaching", "Live", "Reattaching"),
            stateNamesOf(driver),
        )
        assertTrue(
            "controller must be Reattaching after the first lease Down",
            controller.state.value is ConnectionState.Reattaching,
        )

        // A second lease Down escalates the ladder: Reattaching -> Reconnecting(1).
        transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed"))
        assertTrue(
            "a second lease Down escalates the ladder to Reconnecting",
            controller.state.value is ConnectionState.Reconnecting,
        )
        scope.cancel()
    }

    @Test
    fun suppressesLeaseDownSubmissionWhenGateIsTrue_singleGraceOwner() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            suppressTransportDrops = { true }, // backgrounded: single grace owner governs
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        // A lease Down while backgrounded must be recorded as DropSuppressed and NOT
        // submitted — submitting it would collapse the controller toward Unreachable
        // while backgrounded (the #635 band on the next within-grace foreground).
        transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed"))
        assertTrue(
            "a suppressed lease Down must be recorded as DropSuppressed",
            driver.observations.value.any {
                it is ConnectionEffectDriver.Observation.DropSuppressed
            },
        )
        assertEquals(
            "a suppressed lease Down must NOT walk the controller off Live",
            listOf("Idle", "Attaching", "Live"),
            stateNamesOf(driver),
        )
        assertTrue(
            "controller stays Live (single grace owner handles recovery)",
            controller.state.value is ConnectionState.Live,
        )
        scope.cancel()
    }

    @Test
    fun ignoresLeaseDownForUnrelatedHost() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        val transportPort = InertTransportPort(warm = true)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))
        controller.submit(ConnectionEvent.SeedLanded(sessionA, paneId = "%0"))
        assertEquals(listOf("Idle", "Attaching", "Live"), stateNamesOf(driver))

        // A lease Down for a DIFFERENT host must be ignored by the per-target host
        // filter — exactly as the lease Up edge already gates — so it never disturbs
        // the current target's live channel.
        transportPort.transportEventsFlow.emit(
            TransportUpDown.Down(HostKey("bob@elsewhere.example:22/9"), reason = "closed"),
        )
        assertEquals(
            "a lease Down for an unrelated host must not move the controller",
            listOf("Idle", "Attaching", "Live"),
            stateNamesOf(driver),
        )
        assertTrue(controller.state.value is ConnectionState.Live)
        scope.cancel()
    }

    @Test
    fun gateTrueStillNeverSuppressesTheLeaseUpTransportLiveFeed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        // Cold (not warm) so the cold enter parks at Connecting and a real lease
        // Up edge is what promotes it — proving the Up feed is not gated.
        val transportPort = InertTransportPort(warm = false)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            suppressTransportDrops = { true }, // gate ON — but it must only gate DROPS
        ).also { it.start() }

        // Cold enter: not warm -> Connecting.
        controller.submit(ConnectionEvent.Enter(host, sessionA))
        assertEquals(listOf("Idle", "Connecting"), stateNamesOf(driver))

        // A healthy lease Up for the current host is the TransportLive feed; even
        // with the drop gate ON it is NEVER suppressed — it must still promote the
        // controller Connecting -> Attaching (a re-Connected lease always un-bands).
        transportPort.transportEventsFlow.emit(TransportUpDown.Up(host))

        assertEquals(
            "the lease Up / TransportLive feed is never gated by suppressTransportDrops",
            listOf("Idle", "Connecting", "Attaching"),
            stateNamesOf(driver),
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

    // --- slice 1c-iv-b-A2 (#739): the driver observes over the REAL adapters -----
    //
    // The tests above prove the inert contract through hand-rolled inert ports. The
    // tests below prove the WIRING this slice ships: the driver is constructed over
    // the SAME real adapters production uses — the real [SshLeaseTransportPort] over
    // a real [SshLeaseManager], and the real [CurrentClientTmuxPort] over a real
    // [com.pocketshell.core.tmux.TmuxClient] — and the controller it observes is the
    // manager's real [ConnectionController] fed by those real signals (no stub
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
     * lease-`Up` edge, wired EXACTLY as production wires it: over the manager's real
     * [ConnectionController], the real [SshLeaseTransportPort], and the real
     * [CurrentClientTmuxPort]. A genuine [SshLeaseManager.acquire] emits a real
     * `Connected` lease state → a real [TransportUpDown.Up] for the lease's host →
     * the driver submits [ConnectionEvent.TransportLive], promoting the controller.
     * No manager mirror feed is used — the transport input ORIGINATES from the driver.
     */
    @Test
    fun submitsTransportLive_fromRealLeaseUpEdge_overRealAdapters() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val leaseManager = realLeaseManager()
        val transportPort = SshLeaseTransportPort(leaseManager, leaseKeyFor = { leaseTarget() })
        transportPort.warmSnapshot = { false } // cold open → Connecting (so Up promotes it)
        val tmuxPort = CurrentClientTmuxPort(activePaneIdFor = { it.value }, scrollbackLines = 100)
        val manager = ConnectionManager(transport = transportPort)
        val recorded = mutableListOf<String>()
        val driver = ConnectionEffectDriver(
            controller = manager.connectionController,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            sink = { recorded += it },
        ).also { it.start() }

        // The controller's host MUST be the lease's hostKeyFor (the production
        // alignment) so the driver's host filter accepts the real Up edge.
        val leaseHost = hostKeyFor(leaseKey)
        // Epic #792 Slice A: the string mirror is deleted; drive the OPEN intent through
        // the manager's typed `enter` entrypoint (the `"Connecting"` branch's replacement).
        manager.enter(leaseHost, sessionA)

        // A real dial → a real `Connected` state event → a real Up edge → the driver
        // SUBMITS TransportLive, promoting Connecting → Attaching.
        leaseManager.acquire(leaseTarget()).getOrThrow()
        // The active-pane seed landing (still manager-fed intent) promotes to Live.
        manager.observeSeedLanded(leaseHost, sessionA, paneId = "%0")

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
        val manager = ConnectionManager(transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = manager.connectionController,
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
        val manager = ConnectionManager(transport = transportPort)
        val driver = ConnectionEffectDriver(
            controller = manager.connectionController,
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
