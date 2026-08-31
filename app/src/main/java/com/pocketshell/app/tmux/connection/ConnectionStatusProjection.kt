package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.targetIdOrNull

/**
 * Issue #766 S7: the controller-only view-status adapter.
 *
 * [controllerState] is the lifecycle authority. [endpoint] supplies display text
 * only; it cannot alter the lifecycle shape selected by the exhaustive `when`.
 * This is deliberately a one-state-input projection: the deleted implementation
 * reconciled a controller state with a second VM state and therefore made
 * contradictory lifecycle decisions representable.
 */
internal object ConnectionStatusProjection {

    data class Endpoint(val host: String, val port: Int, val user: String) {
        companion object {
            val Empty = Endpoint(host = "", port = 0, user = "")
        }
    }

    data class TargetEndpoint(val targetId: SessionId, val endpoint: Endpoint)

    /** Resolve display text by the controller's exact target identity; stale rows never win. */
    fun endpointFor(
        controllerState: ConnectionState,
        candidates: List<TargetEndpoint>,
    ): Endpoint {
        val targetId = controllerState.targetIdOrNull() ?: return Endpoint.Empty
        return candidates.firstOrNull { it.targetId == targetId }?.endpoint ?: Endpoint.Empty
    }

    fun project(
        controllerState: ConnectionState,
        endpoint: Endpoint,
        failureMessage: String? = null,
    ): ConnectionStatus = when (controllerState) {
        is ConnectionState.Idle -> ConnectionStatus.Idle
        is ConnectionState.Connecting ->
            ConnectionStatus.Connecting(endpoint.host, endpoint.port, endpoint.user)
        is ConnectionState.Attaching ->
            if (controllerState.recovering) {
                ConnectionStatus.Reconnecting(
                    host = endpoint.host,
                    port = endpoint.port,
                    user = endpoint.user,
                    attempt = 1,
                    maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                    retryDelayMs = 0L,
                    reason = "Reconnecting…",
                )
            } else if (controllerState.warm) {
                ConnectionStatus.Switching(endpoint.host, endpoint.port, endpoint.user)
            } else {
                ConnectionStatus.Connecting(endpoint.host, endpoint.port, endpoint.user)
            }
        is ConnectionState.Live,
        is ConnectionState.Backgrounded,
        -> ConnectionStatus.Connected(endpoint.host, endpoint.port, endpoint.user)
        is ConnectionState.NetworkLossSuspended ->
            ConnectionStatus.Reconnecting(
                host = endpoint.host,
                port = endpoint.port,
                user = endpoint.user,
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Network unavailable. Waiting to reconnect…",
            )
        is ConnectionState.Reattaching ->
            ConnectionStatus.Reconnecting(
                host = endpoint.host,
                port = endpoint.port,
                user = endpoint.user,
                attempt = 1,
                maxAttempts = ConnectionController.DEFAULT_RECONNECT_LADDER_MS.size,
                retryDelayMs = 0L,
                reason = "Reconnecting…",
            )
        is ConnectionState.Reconnecting ->
            ConnectionStatus.Reconnecting(
                host = endpoint.host,
                port = endpoint.port,
                user = endpoint.user,
                attempt = controllerState.attempt,
                maxAttempts = controllerState.maxAttempts,
                retryDelayMs = controllerState.retryDelayMs,
                reason = "Reconnecting…",
            )
        is ConnectionState.Gone ->
            ConnectionStatus.Failed("This session ended. Tap Reconnect.")
        is ConnectionState.Unreachable ->
            ConnectionStatus.Failed(failureMessage ?: "Disconnected. Tap Reconnect to retry.")
    }

}

/**
 * Stateful display adapter for the controller-only projection.
 *
 * The endpoint and failure maps are display payload caches keyed by the
 * controller's target identity; neither map can choose a lifecycle state.
 */
internal class ControllerStatusProjector(
    private val controllerState: () -> ConnectionState,
    private val currentTargets: () -> List<ConnectionStatusProjection.TargetEndpoint>,
    private val publish: (ConnectionStatus) -> Unit,
) {
    private val endpointsByTarget = LinkedHashMap<SessionId, ConnectionStatusProjection.Endpoint>()
    private val failureMessagesByTarget = LinkedHashMap<SessionId, String>()

    fun project() {
        val state = controllerState()
        currentTargets().forEach { target ->
            endpointsByTarget[target.targetId] = target.endpoint
        }
        val endpoint = ConnectionStatusProjection.endpointFor(
            controllerState = state,
            candidates = endpointsByTarget.map { (targetId, endpoint) ->
                ConnectionStatusProjection.TargetEndpoint(targetId, endpoint)
            },
        )
        val failureMessage = state.targetIdOrNull()?.let(failureMessagesByTarget::get)
        publish(ConnectionStatusProjection.project(state, endpoint, failureMessage))
    }

    fun rememberEndpoint(targetId: SessionId, endpoint: ConnectionStatusProjection.Endpoint) {
        endpointsByTarget[targetId] = endpoint
    }

    fun endpointFor(targetId: SessionId?): ConnectionStatusProjection.Endpoint? =
        targetId?.let(endpointsByTarget::get)

    fun rememberFailure(targetId: SessionId?, message: String) {
        targetId?.let { failureMessagesByTarget[it] = message }
    }

    fun clearFailure(targetId: SessionId?) {
        targetId?.let(failureMessagesByTarget::remove)
    }
}
