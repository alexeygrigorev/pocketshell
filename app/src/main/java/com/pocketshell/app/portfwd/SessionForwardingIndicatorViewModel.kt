package com.pocketshell.app.portfwd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Per-host "port forwarding is active for THIS host" indicator state
 * (issue #487). Distinct from [ForwardingIndicatorViewModel], which backs
 * the global host-list pill summing every host. This one scopes the signal
 * to a single host id so the in-session chrome of
 * [com.pocketshell.app.tmux.TmuxSessionScreen] can tell the user "you have a
 * tunnel open on the server you are looking at right now", so they don't
 * forget it's running.
 *
 * Pure read surface over [ForwardingController.flowOfHostSnapshots]; no SSH,
 * no service control. The chip is a tap target that routes to the existing
 * per-host port-forward panel.
 */
@HiltViewModel
class SessionForwardingIndicatorViewModel @Inject constructor(
    private val controller: ForwardingController,
) : ViewModel() {

    /**
     * Project the controller's per-host snapshot map into a state for
     * [hostId]. A host that is not registered (no active forwarding) maps to
     * the hidden default. Re-collecting for a different [hostId] is cheap —
     * callers pass the id of the host whose session is on screen.
     */
    fun stateFor(hostId: Long): StateFlow<SessionForwardingIndicatorState> =
        controller.flowOfHostSnapshots()
            .map { snapshots -> snapshots[hostId].toIndicatorState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SessionForwardingIndicatorState(),
            )

    /**
     * Issue #488: if [remotePort] is currently forwarding for [hostId], return
     * the local (phone-loopback) port it maps to; otherwise `null`. Read
     * synchronously off the controller's latest snapshot so the terminal
     * URL-tap path can decide "already forwarded → open the local URL" vs
     * "not forwarded → offer to forward" without subscribing to a flow.
     */
    fun forwardedLocalPortFor(hostId: Long, remotePort: Int): Int? =
        controller.flowOfHostSnapshots().value[hostId]
            ?.takeIf { it.active }
            ?.forwardedPortMap
            ?.get(remotePort)
}

private fun ForwardingHostSnapshot?.toIndicatorState(): SessionForwardingIndicatorState {
    if (this == null || !active) return SessionForwardingIndicatorState()
    return SessionForwardingIndicatorState(
        active = true,
        tunnelCount = tunnelCount,
        restoring = restoring,
    )
}

/**
 * Snapshot of the active-forwarding state for one host, rendered by the
 * in-session chip.
 *
 * [visible] is true exactly while this host is registered as actively
 * forwarding. [restoring] (issue #439 carry-over) means the host's transport
 * is briefly down and its forwards are being re-established, so the chip reads
 * "restoring" rather than dropping to a removed-looking state.
 */
data class SessionForwardingIndicatorState(
    val active: Boolean = false,
    val tunnelCount: Int = 0,
    val restoring: Boolean = false,
) {
    val visible: Boolean get() = active

    /** Compact label e.g. "2" tunnels; falls back to a dot-free blank when
     *  the count is still spinning up so the chip never reads "0". */
    val label: String
        get() = when {
            tunnelCount > 0 -> "$tunnelCount"
            else -> ""
        }

    val contentDescription: String
        get() = when {
            restoring -> "Port forwarding restoring for this host"
            tunnelCount == 1 -> "1 port forwarding active for this host"
            tunnelCount > 1 -> "$tunnelCount ports forwarding active for this host"
            else -> "Port forwarding active for this host"
        }
}
