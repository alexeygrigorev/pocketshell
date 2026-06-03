package com.pocketshell.app.portfwd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the compact global "ports are forwarding" indicator surfaced in
 * the host-list app bar (epic #432 slice D, issue #446).
 *
 * Pure read surface over [ForwardingController]: it projects the
 * controller's active-host / tunnel counts into a single
 * [ForwardingIndicatorState] the app bar renders. No SSH, no service
 * control — the indicator is a tap target that routes to the existing
 * port-forward panel entry; the forwarding itself is owned by
 * [PortForwardPanelViewModel] + [ForwardingController] as before.
 */
@HiltViewModel
class ForwardingIndicatorViewModel @Inject constructor(
    controller: ForwardingController,
) : ViewModel() {

    val state: StateFlow<ForwardingIndicatorState> = combine(
        controller.flowOfActiveHostCount(),
        controller.flowOfTotalTunnelCount(),
        controller.flowOfRestoringHostCount(),
    ) { hostCount, tunnelCount, restoringCount ->
        ForwardingIndicatorState(
            activeHostCount = hostCount,
            totalTunnelCount = tunnelCount,
            restoringHostCount = restoringCount,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ForwardingIndicatorState(),
    )
}

/**
 * Snapshot of the global forwarding state for the indicator.
 *
 * [visible] is the single source of truth for "show the indicator": it
 * is true exactly when ≥1 host is actively auto-forwarding. The label is
 * derived from the tunnel count so the pill reads e.g. "3 tunnels".
 */
data class ForwardingIndicatorState(
    val activeHostCount: Int = 0,
    val totalTunnelCount: Int = 0,
    // Issue #439: number of active hosts whose transport is currently
    // down and reconnecting. > 0 surfaces a transient "restoring…" hint
    // so a transport blip never reads as "removed".
    val restoringHostCount: Int = 0,
) {
    val visible: Boolean get() = activeHostCount > 0

    /**
     * True while ≥1 active host's transport is down and its forwards are
     * being restored (issue #439). The indicator stays visible (the host
     * is still registered) but reads as "restoring" rather than dropping
     * to a removed-looking zero state.
     */
    val restoring: Boolean get() = restoringHostCount > 0

    /**
     * Compact pill label. When tunnels are still spinning up the host is
     * registered (count > 0) but the tunnel count can momentarily be 0;
     * fall back to the host count so the pill never reads "0".
     */
    val label: String
        get() = when {
            totalTunnelCount > 0 -> "$totalTunnelCount"
            else -> "$activeHostCount"
        }

    val contentDescription: String
        get() = when {
            restoring -> "Port forwarding restoring"
            totalTunnelCount == 1 -> "1 port forwarding active"
            totalTunnelCount > 1 -> "$totalTunnelCount ports forwarding active"
            else -> "Port forwarding active"
        }
}
