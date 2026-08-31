package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #766 S7 class coverage for the one connection lifecycle authority.
 *
 * These tests traverse the facade/controller reducer and only then project the result. They
 * deliberately do not construct a VM-private lifecycle value, so restoring the removed mirror
 * cannot satisfy them.
 */
class Issue766ControllerAuthorityTest {
    private val host = HostKey("host")
    private val sessionA = SessionId("session-a")
    private val sessionB = SessionId("session-b")
    private val endpointA = ConnectionStatusProjection.Endpoint("host.example", 2222, "alex")

    @Test
    fun coldAndWarmOpenWaitForExactRevealBeforeLive() {
        val cold = ConnectionManager(warmSnapshot = { false })
        cold.enter(host, sessionA)
        assertEquals(ConnectionState.Connecting(host, sessionA), cold.state)

        // #178: transport-up is not pane-ready. Only the target-tagged reveal may claim Live.
        cold.submit(ConnectionEvent.TransportLive)
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = false), cold.state)
        cold.observeSeedLanded(host, SessionId("stale"), paneId = "%stale")
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = false), cold.state)
        cold.observeSeedLanded(host, sessionA, paneId = "%0")
        assertEquals(ConnectionState.Live(host, sessionA), cold.state)

        val warm = ConnectionManager(warmSnapshot = { true })
        warm.enter(host, sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionA), warm.state)
        warm.observeSeedLanded(host, sessionA, paneId = "%0")
        assertEquals(ConnectionState.Live(host, sessionA), warm.state)
    }

    @Test
    fun replacementTransportLiveNeedsCurrentTargetSeedToBecomeLive() {
        val manager = ConnectionManager(warmSnapshot = { true })
        manager.enter(host, sessionA)
        manager.submit(ConnectionEvent.TransportLive)

        // A replacement transport is usable, but its pane has not been revealed yet.
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = true), manager.state)
        assertEquals(
            ConnectionStatus.Switching("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )

        // This is the production-facing seed-landed edge used by passive replacement attach.
        manager.observeSeedLanded(host, sessionA, paneId = "%replacement")
        assertEquals(ConnectionState.Live(host, sessionA), manager.state)
        assertEquals(
            ConnectionStatus.Connected("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )
    }

    @Test
    fun attachReadyIsTypedControllerEvidenceButRevealIntentAloneIsNot() {
        val manager = ConnectionManager(warmSnapshot = { true })
        manager.enter(host, sessionA)

        // Load-bearing fence: reveal is not evidence that a pane capture landed. The
        // controller must remain Attaching until the real current-target seed arrives.
        // Mutation that must redden this test: re-add
        // `ConnectionEvent.SeedLanded(targetId, paneId = "inline-reveal")` inside
        // ConnectionManager.submitTransportLiveForReveal().
        manager.revealLive(host, sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionA, warm = true), manager.state)
        assertEquals(
            ConnectionStatus.Switching("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )

        manager.observeAttachReady(host, sessionA, paneId = "%blank")
        assertEquals(ConnectionState.Live(host, sessionA), manager.state)
        assertEquals(
            ConnectionStatus.Connected("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )

        manager.observeSeedLanded(host, sessionA, paneId = "%real-seed")
        assertEquals(ConnectionState.Live(host, sessionA), manager.state)
        assertEquals(
            ConnectionStatus.Connected("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )
    }

    @Test
    fun switchAndGoneDropStaleOrSupersededTargetEvents() {
        val manager = liveManager()
        manager.switchTo(host, sessionB)
        assertEquals(ConnectionState.Attaching(host, sessionB), manager.state)

        manager.observeSeedLanded(host, sessionA, paneId = "%late-a")
        manager.markGone(sessionA)
        assertEquals(ConnectionState.Attaching(host, sessionB), manager.state)

        manager.observeSeedLanded(host, sessionB, paneId = "%b")
        assertEquals(ConnectionState.Live(host, sessionB), manager.state)
        manager.markGone(sessionB)
        assertEquals(ConnectionState.Gone(host, sessionB), manager.state)
        assertEquals(
            ConnectionStatus.Failed("This session ended. Tap Reconnect."),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )
    }

    @Test
    fun backgroundGraceRecoveryAndBeyondGraceUseOneClockAndOneState() {
        val clock = TestClock()
        var warm = true
        val withinGrace = ConnectionManager(clock = clock, warmSnapshot = { warm })
        withinGrace.enter(host, sessionA)
        withinGrace.revealLive(host, sessionA)
        withinGrace.observeSeedLanded(host, sessionA, paneId = "%initial")
        withinGrace.observeBackground()
        assertTrue(withinGrace.state is ConnectionState.Backgrounded)
        assertEquals(
            ConnectionStatus.Connected("host.example", 2222, "alex"),
            ConnectionStatusProjection.project(withinGrace.state, endpointA),
        )

        clock.now = 30_000L
        withinGrace.observeForeground()
        assertEquals(ConnectionState.Reattaching(host, sessionA), withinGrace.state)
        withinGrace.observeForegroundReattachLive()
        assertEquals(ConnectionState.Live(host, sessionA), withinGrace.state)

        withinGrace.observeBackground()
        warm = false
        clock.now = 91_001L
        withinGrace.observeForeground()
        val reconnecting = withinGrace.state as ConnectionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "host.example",
                port = 2222,
                user = "alex",
                attempt = reconnecting.attempt,
                maxAttempts = reconnecting.maxAttempts,
                retryDelayMs = reconnecting.retryDelayMs,
                reason = "Reconnecting…",
            ),
            ConnectionStatusProjection.project(reconnecting, endpointA),
        )
    }

    @Test
    fun remoteDropReconnectAndHonestFailureComeOnlyFromControllerTransitions() {
        val manager = liveManager()
        manager.reportRemoteDrop("wire lost")
        assertEquals(ConnectionState.Reattaching(host, sessionA), manager.state)

        manager.enterReconnectLadder(host, sessionA)
        val reconnecting = manager.state as ConnectionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        manager.escalateUnreachable()
        assertEquals(ConnectionState.Unreachable(host, sessionA), manager.state)
        assertEquals(
            ConnectionStatus.Failed("Disconnected. Tap Reconnect to retry."),
            ConnectionStatusProjection.project(manager.state, endpointA),
        )
    }

    @Test
    fun networkHoldShapeIsRepresentableOnlyAsTypedControllerState() {
        val state = ConnectionState.NetworkLossSuspended(host, sessionA, sinceMs = 9L)
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "host.example",
                port = 2222,
                user = "alex",
                attempt = 1,
                maxAttempts = com.pocketshell.core.connection.ConnectionController
                    .DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Network unavailable. Waiting to reconnect…",
            ),
            ConnectionStatusProjection.project(state, endpointA),
        )
    }

    private fun liveManager(): ConnectionManager =
        ConnectionManager(warmSnapshot = { true }).also { manager ->
            manager.enter(host, sessionA)
            manager.revealLive(host, sessionA)
            manager.observeSeedLanded(host, sessionA, paneId = "%setup")
            assertEquals(ConnectionState.Live(host, sessionA), manager.state)
        }

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }
}
