package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.ssh.SshLeaseKey
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
 * Issue #766 S7 replacement for the deleted 544-line connection equivalence test.
 *
 * These are explicit controller-authority cases: every lifecycle assertion names the
 * expected [ConnectionState] or [ConnectionStatus] value. There is no second reducer,
 * string status oracle, or helper that asks the controller what the expected answer is.
 * The transport cases also wire the real [ConnectionEffectDriver] flow boundary, so a
 * VM-side authority reintroduction cannot make this suite pass by merely calling the
 * same controller APIs through a differently named helper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControllerAuthorityCoverageTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val sessionA = SessionId("7/main")
    private val sessionB = SessionId("7/build")
    private val endpoint = ConnectionStatusProjection.Endpoint("example.com", 2222, "alice")

    private class FakeTransportPort(private val warm: (HostKey) -> Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 32)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow
        override fun isWarm(host: HostKey): Boolean = warm(host)
    }

    private class FakeTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 32)
        override val disconnected: Flow<Boolean> = disconnectedFlow
    }

    /** Production-shaped facade + driver; events enter through typed public boundaries. */
    private class Harness(
        scope: CoroutineScope,
        clock: TestClock,
        warm: (HostKey) -> Boolean,
    ) {
        private val host = HostKey("alice@example.com:22/7")
        private val endpoint = ConnectionStatusProjection.Endpoint("example.com", 2222, "alice")
        val transport = FakeTransportPort(warm)
        val tmux = FakeTmuxPort()
        val manager = ConnectionManager(clock = clock, transport = transport)

        @Suppress("UNUSED_VARIABLE")
        val driver = ConnectionEffectDriver(
            controller = manager.connectionController,
            tmuxPort = tmux,
            transportPort = transport,
            scope = scope,
        ).also { it.start() }

        val state: ConnectionState
            get() = manager.state

        fun projectedStatus(): ConnectionStatus =
            ConnectionStatusProjection.project(
                controllerState = state,
                endpoint = endpoint,
            )

        fun expectStatus(expected: ConnectionStatus) {
            assertEquals(expected, projectedStatus())
        }

        fun seedLanded(target: SessionId, paneId: String = "%0") =
            manager.observeSeedLanded(host, target, paneId)

        suspend fun transportUp(upHost: HostKey = host) {
            transport.transportEventsFlow.emit(TransportUpDown.Up(upHost))
        }

        suspend fun transportDropped() {
            tmux.disconnectedFlow.emit(true)
        }

        suspend fun landLiveFromTransport(target: SessionId, upHost: HostKey = host) {
            transportUp(upHost)
            seedLanded(target)
        }
    }

    private fun runHarness(
        clock: TestClock = TestClock(),
        warm: (HostKey) -> Boolean = { true },
        body: suspend Harness.() -> Unit,
    ) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        Harness(scope, clock, warm).body()
        scope.cancel()
    }

    @Test
    fun coldOpenUsesConnectingThenAttachingThenLiveControllerStates() =
        runHarness(warm = { false }) {
            manager.enter(host, sessionA)
            assertEquals(ConnectionState.Connecting(host, sessionA), state)
            expectStatus(ConnectionStatus.Connecting("example.com", 2222, "alice"))

            transportUp()
            assertEquals(ConnectionState.Attaching(host, sessionA, warm = false), state)
            expectStatus(ConnectionStatus.Connecting("example.com", 2222, "alice"))

            seedLanded(sessionA)
            assertEquals(ConnectionState.Live(host, sessionA), state)
            expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))

            // Repeated carrier/seed feedback cannot manufacture a second lifecycle.
            landLiveFromTransport(sessionA)
            assertEquals(ConnectionState.Live(host, sessionA), state)
            expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))
        }

    @Test
    fun warmOpenUsesAttachingSwitchingUntilTargetSeed() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = true), state)
        expectStatus(ConnectionStatus.Switching("example.com", 2222, "alice"))

        seedLanded(sessionA)
        assertEquals(ConnectionState.Live(host, sessionA), state)
        expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))
        landLiveFromTransport(sessionA)
        assertEquals(ConnectionState.Live(host, sessionA), state)
    }

    @Test
    fun sameHostSwitchRetargetsAttachingThenLivesOnTheNewTarget() =
        runHarness(warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            assertEquals(ConnectionState.Live(host, sessionA), state)

            manager.switchTo(host, sessionB)
            assertEquals(ConnectionState.Attaching(host, sessionB, warm = true), state)
            expectStatus(ConnectionStatus.Switching("example.com", 2222, "alice"))

            seedLanded(sessionB)
            assertEquals(ConnectionState.Live(host, sessionB), state)
            expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))
        }

    @Test
    fun beyondGraceForegroundStartsTheTypedReconnectLadder() {
        val clock = TestClock()
        runHarness(clock = clock, warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.observeBackground()
            clock.now = ConnectionController.DEFAULT_GRACE_MS + 1L
            manager.observeForeground()

            val reconnecting = state as ConnectionState.Reconnecting
            assertEquals(host, reconnecting.host)
            assertEquals(sessionA, reconnecting.targetId)
            assertEquals(1, reconnecting.attempt)
            expectStatus(
                ConnectionStatus.Reconnecting(
                    host = "example.com",
                    port = 2222,
                    user = "alice",
                    attempt = 1,
                    maxAttempts = reconnecting.maxAttempts,
                    retryDelayMs = reconnecting.retryDelayMs,
                    reason = "Reconnecting…",
                ),
            )
        }
    }

    @Test
    fun withinGraceButColdLeaseStartsReconnectInsteadOfReattach() {
        val clock = TestClock()
        runHarness(clock = clock, warm = { false }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.observeBackground()
            clock.now = 10_000L
            manager.observeForeground()

            val reconnecting = state as ConnectionState.Reconnecting
            assertEquals(ConnectionState.Reconnecting(host, sessionA, attempt = 1, maxAttempts = reconnecting.maxAttempts, retryDelayMs = reconnecting.retryDelayMs), state)
            assertEquals(1, reconnecting.attempt)
            assertTrue(projectedStatus() is ConnectionStatus.Reconnecting)
        }
    }

    @Test
    fun nonValidatedNetworkChangeLeavesLiveControllerStateUntouched() =
        runHarness(warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.observeNetworkChanged(validatedHandoff = false)
            assertEquals(ConnectionState.Live(host, sessionA), state)
            expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))
        }

    @Test
    fun validatedNetworkHandoffEntersTypedReconnect() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        landLiveFromTransport(sessionA)
        manager.observeNetworkChanged(validatedHandoff = true)
        assertTrue(state is ConnectionState.Reconnecting)
        assertTrue(projectedStatus() is ConnectionStatus.Reconnecting)
    }

    @Test
    fun targetGoneProjectsTheControllerGoneStateAsTheHonestTerminalStatus() =
        runHarness(warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.markGone(sessionA)
            assertEquals(ConnectionState.Gone(host, sessionA), state)
            expectStatus(ConnectionStatus.Failed("This session ended. Tap Reconnect."))
        }

    @Test
    fun unreachableProjectsTheControllerUnreachableStateAsTheHonestFailure() =
        runHarness(warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.escalateUnreachable()
            assertEquals(ConnectionState.Unreachable(host, sessionA), state)
            expectStatus(ConnectionStatus.Failed("Disconnected. Tap Reconnect to retry."))
        }

    @Test
    fun recoverableDropArrivesFromTheDriverAndProjectsSilentRecovery() =
        runHarness(warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            transportDropped()

            assertEquals(ConnectionState.Reattaching(host, sessionA), state)
            expectStatus(
                ConnectionStatus.Reconnecting(
                    host = "example.com",
                    port = 2222,
                    user = "alice",
                    attempt = 1,
                    maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                    retryDelayMs = 0L,
                    reason = "Reconnecting…",
                ),
            )
            transportUp()
            // Audit mutation M766-LIFECYCLE-001 collapses replacement-transport readiness
            // straight to Live/Reattaching. The controller must retain this typed recovery
            // attach until current-target pane evidence lands.
            assertEquals(
                ConnectionState.Attaching(host, sessionA, warm = false, recovering = true),
                state,
            )
            seedLanded(sessionA, paneId = "%recovered")
            assertEquals(ConnectionState.Live(host, sessionA), state)
        }

    @Test
    fun withinGraceForegroundUsesReattachingUntilTheCurrentPaneIsSeeded() {
        val clock = TestClock()
        runHarness(clock = clock, warm = { true }) {
            manager.enter(host, sessionA)
            landLiveFromTransport(sessionA)
            manager.observeBackground()
            clock.now = 5_000L
            manager.observeForeground()
            assertEquals(ConnectionState.Reattaching(host, sessionA), state)
            expectStatus(
                ConnectionStatus.Reconnecting(
                    host = "example.com",
                    port = 2222,
                    user = "alice",
                    attempt = 1,
                    maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                    retryDelayMs = 0L,
                    reason = "Reconnecting…",
                ),
            )
            transportUp()
            assertEquals(
                ConnectionState.Attaching(host, sessionA, warm = false, recovering = true),
                state,
            )
            seedLanded(sessionA, paneId = "%foreground")
            assertEquals(ConnectionState.Live(host, sessionA), state)
            expectStatus(ConnectionStatus.Connected("example.com", 2222, "alice"))
        }
    }

    @Test
    fun driverTransportUpPromotesColdOpenOnlyToAttachingUntilSeed() =
        runHarness(warm = { false }) {
            manager.enter(host, sessionA)
            assertEquals(ConnectionState.Connecting(host, sessionA), state)
            transportUp()
            assertEquals(ConnectionState.Attaching(host, sessionA, warm = false), state)
            seedLanded(sessionA)
            assertEquals(ConnectionState.Live(host, sessionA), state)
        }

    @Test
    fun driverIgnoresTransportUpForAHostThatIsNotCurrent() = runHarness(warm = { false }) {
        manager.enter(host, sessionA)
        assertEquals(ConnectionState.Connecting(host, sessionA), state)
        transportUp(HostKey("bob@other:22/9"))
        assertEquals(ConnectionState.Connecting(host, sessionA), state)
        transportUp()
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = false), state)
    }

    @Test
    fun driverTransportDropWhileIdleIsAControllerNoOp() = runHarness(warm = { true }) {
        assertEquals(ConnectionState.Idle, state)
        transportDropped()
        assertEquals(ConnectionState.Idle, state)
        expectStatus(ConnectionStatus.Idle)
    }

    @Test
    fun seedForSupersededTargetCannotPromoteTheCurrentAttach() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        landLiveFromTransport(sessionA)
        manager.switchTo(host, sessionB)
        assertEquals(ConnectionState.Attaching(host, sessionB, warm = true), state)

        seedLanded(sessionA, paneId = "%late-a")
        assertEquals(ConnectionState.Attaching(host, sessionB, warm = true), state)
        seedLanded(sessionB, paneId = "%b")
        assertEquals(ConnectionState.Live(host, sessionB), state)
    }

    @Test
    fun typedEnterUsesTheControllerWarmPredicateForWarmOpen() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = true), state)
        assertEquals(sessionA, (state as ConnectionState.Attaching).targetId)
    }

    @Test
    fun typedEnterUsesTheControllerWarmPredicateForColdOpen() = runHarness(warm = { false }) {
        manager.enter(host, sessionA)
        assertEquals(ConnectionState.Connecting(host, sessionA), state)
    }

    @Test
    fun typedSwitchFromLiveRetargetsWithoutASecondHandshake() = runHarness(warm = { true }) {
        manager.enter(host, sessionA)
        landLiveFromTransport(sessionA)
        manager.switchTo(host, sessionB)
        assertEquals(ConnectionState.Attaching(host, sessionB, warm = true), state)
        assertEquals(sessionB, (state as ConnectionState.Attaching).targetId)
    }

    @Test
    fun typedSwitchFromIdleUsesTheWarmOpenDecision() = runHarness(warm = { true }) {
        assertEquals(ConnectionState.Idle, state)
        manager.switchTo(host, sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = true), state)
    }

    @Test
    fun warmPredicateUsesTheSameEncodedHostAsTheLeaseKey() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val leaseKey = SshLeaseKey(
            host = "example.com",
            port = 22,
            user = "alice",
            credentialId = "7:/home/alice/.ssh/id_ed25519",
        )
        val encodedHost = hostKeyFor(leaseKey)
        val target = SessionId("7/main")

        val warmHarness = Harness(scope, TestClock(), warm = { it == encodedHost })
        warmHarness.manager.enter(encodedHost, target)
        assertEquals(ConnectionState.Attaching(encodedHost, target, warm = true), warmHarness.state)

        val coldHarness = Harness(scope, TestClock(), warm = { false })
        coldHarness.manager.enter(encodedHost, target)
        assertEquals(ConnectionState.Connecting(encodedHost, target), coldHarness.state)
        assertEquals(encodedHost, hostKeyFor(leaseKey))
        scope.cancel()
    }

    @Test
    fun projectionIsTheExplicitControllerOnlyLifecycleMapping() {
        fun project(state: ConnectionState): ConnectionStatus =
            ConnectionStatusProjection.project(state, endpoint)

        assertEquals(ConnectionStatus.Idle, project(ConnectionState.Idle))
        assertEquals(
            ConnectionStatus.Connecting("example.com", 2222, "alice"),
            project(ConnectionState.Connecting(host, sessionA)),
        )
        assertEquals(
            ConnectionStatus.Switching("example.com", 2222, "alice"),
            project(ConnectionState.Attaching(host, sessionA, warm = true)),
        )
        assertEquals(
            ConnectionStatus.Connecting("example.com", 2222, "alice"),
            project(ConnectionState.Attaching(host, sessionA, warm = false)),
        )
        assertEquals(
            ConnectionStatus.Connected("example.com", 2222, "alice"),
            project(ConnectionState.Live(host, sessionA)),
        )
        assertEquals(
            ConnectionStatus.Connected("example.com", 2222, "alice"),
            project(ConnectionState.Backgrounded(host, sessionA, sinceMs = 1L)),
        )
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alice",
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Reconnecting…",
            ),
            project(ConnectionState.Reattaching(host, sessionA)),
        )
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alice",
                attempt = 3,
                maxAttempts = 8,
                retryDelayMs = 2_000L,
                reason = "Reconnecting…",
            ),
            project(
                ConnectionState.Reconnecting(
                    host,
                    sessionA,
                    attempt = 3,
                    maxAttempts = 8,
                    retryDelayMs = 2_000L,
                ),
            ),
        )
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alice",
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Network unavailable. Waiting to reconnect…",
            ),
            project(ConnectionState.NetworkLossSuspended(host, sessionA, sinceMs = 4L)),
        )
        assertEquals(
            ConnectionStatus.Failed("This session ended. Tap Reconnect."),
            project(ConnectionState.Gone(host, sessionA)),
        )
        assertEquals(
            ConnectionStatus.Failed("Disconnected. Tap Reconnect to retry."),
            project(ConnectionState.Unreachable(host, sessionA)),
        )
    }
}
