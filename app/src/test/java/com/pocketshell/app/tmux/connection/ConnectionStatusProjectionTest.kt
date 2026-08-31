package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Class-covering #766 S7 truth table for the one-authority projection. */
class ConnectionStatusProjectionTest {
    private val host = HostKey("host-key")
    private val target = SessionId("target")
    private val endpoint = ConnectionStatusProjection.Endpoint("example.com", 2222, "alex")

    private fun project(state: ConnectionState): ConnectionStatus =
        ConnectionStatusProjection.project(state, endpoint)

    @Test
    fun controllerLifecycleShapeIsExhaustivelyProjected() {
        assertEquals(ConnectionStatus.Idle, project(ConnectionState.Idle))
        assertEquals(
            ConnectionStatus.Connecting("example.com", 2222, "alex"),
            project(ConnectionState.Connecting(host, target)),
        )
        assertEquals(
            ConnectionStatus.Switching("example.com", 2222, "alex"),
            project(ConnectionState.Attaching(host, target)),
        )
        assertEquals(
            ConnectionStatus.Connecting("example.com", 2222, "alex"),
            project(ConnectionState.Attaching(host, target, warm = false)),
        )
        assertEquals(
            ConnectionStatus.Connected("example.com", 2222, "alex"),
            project(ConnectionState.Live(host, target)),
        )
        assertEquals(
            ConnectionStatus.Connected("example.com", 2222, "alex"),
            project(ConnectionState.Backgrounded(host, target, sinceMs = 10L)),
        )
        assertEquals(
            ConnectionStatus.Failed("This session ended. Tap Reconnect."),
            project(ConnectionState.Gone(host, target)),
        )
        assertEquals(
            ConnectionStatus.Failed("Disconnected. Tap Reconnect to retry."),
            project(ConnectionState.Unreachable(host, target)),
        )
    }

    @Test
    fun recoveryPayloadComesOnlyFromTheController() {
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alex",
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Reconnecting…",
            ),
            project(ConnectionState.Reattaching(host, target)),
        )
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alex",
                attempt = 4,
                maxAttempts = 8,
                retryDelayMs = 20_000L,
                reason = "Reconnecting…",
            ),
            project(
                ConnectionState.Reconnecting(
                    host = host,
                    targetId = target,
                    attempt = 4,
                    maxAttempts = 8,
                    retryDelayMs = 20_000L,
                ),
            ),
        )
    }

    @Test
    fun networkHoldIsControllerOwnedAndVisibleAsWaiting() {
        assertEquals(
            ConnectionStatus.Reconnecting(
                host = "example.com",
                port = 2222,
                user = "alex",
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Network unavailable. Waiting to reconnect…",
            ),
            project(ConnectionState.NetworkLossSuspended(host, target, sinceMs = 20L)),
        )
    }

    @Test
    fun endpointCanNeverOverrideControllerShape() {
        val foreignEndpoint = ConnectionStatusProjection.Endpoint("foreign", 1, "other")
        assertEquals(
            ConnectionStatus.Connected("foreign", 1, "other"),
            ConnectionStatusProjection.project(ConnectionState.Live(host, target), foreignEndpoint),
        )
        assertEquals(
            ConnectionStatus.Failed("Disconnected. Tap Reconnect to retry."),
            ConnectionStatusProjection.project(ConnectionState.Unreachable(host, target), foreignEndpoint),
        )
    }

    @Test
    fun endpointPayloadIsBoundToTheControllersExactTargetIdentity() {
        val stale = SessionId("stale")
        val freshEndpoint = ConnectionStatusProjection.Endpoint("fresh.example", 2200, "fresh")
        val candidates = listOf(
            ConnectionStatusProjection.TargetEndpoint(stale, endpoint),
            ConnectionStatusProjection.TargetEndpoint(target, freshEndpoint),
        )

        assertEquals(
            freshEndpoint,
            ConnectionStatusProjection.endpointFor(ConnectionState.Live(host, target), candidates),
        )
        assertEquals(
            ConnectionStatusProjection.Endpoint.Empty,
            ConnectionStatusProjection.endpointFor(
                ConnectionState.Live(host, SessionId("superseding")),
                candidates,
            ),
        )
        assertEquals(
            ConnectionStatusProjection.Endpoint.Empty,
            ConnectionStatusProjection.endpointFor(ConnectionState.Idle, candidates),
        )
    }
}
