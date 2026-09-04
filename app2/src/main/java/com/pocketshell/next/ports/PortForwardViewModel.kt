package com.pocketshell.next.ports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the port-forward screen renders.
 *
 * [rows] is already filtered and ordered — the screen paints what it is given and
 * makes no visibility decisions of its own, so "which ports are interesting" has
 * exactly one implementation ([InterestingPortFilter]) and one test.
 */
data class PortForwardUiState(
    val hostId: Long = 0,
    val hostName: String = "",
    val hostSubtitle: String = "",
    /** The durable `hosts.enabled` intent, as last read/written. */
    val enabled: Boolean = false,
    val connection: ConnectionState = ConnectionState.Idle,
    /**
     * What the user has to DO when [connection] is terminal
     * ([ConnectionState.Lost]) — an unconfirmed host key, a deleted host row.
     * Null when the controller has no better explanation than "could not
     * connect", and null whenever [connection] is NOT terminal: this is
     * [ForwardingController.HostForwarding.terminalAttention], the same gated
     * reason the notification renders (#2491).
     */
    val attention: String? = null,
    val rows: List<TunnelInfo> = emptyList(),
    val showAllPorts: Boolean = false,
    /** Rows the default filter is hiding right now. */
    val hiddenCount: Int = 0,
    /** True until the host row and the persisted checkbox have been read. */
    val loading: Boolean = true,
) {
    /** Forwarding is on, but nothing has been discovered yet. */
    val scanning: Boolean
        get() = enabled && rows.isEmpty() && hiddenCount == 0 && connection != ConnectionState.Lost
}

/**
 * The port-forward screen for one host (rewrite task P-4).
 *
 * It owns no forwarding state: [ForwardingController] does, because a forward has
 * to outlive this ViewModel — leaving the screen must not kill a tunnel the user
 * just opened. So this class is a projection: it reads the controller snapshot and
 * turns user intent into controller calls.
 *
 * The durable `hosts.enabled` intent is written by the controller BEFORE the
 * supervisor mounts, so a process death between the two still leaves the next
 * resume knowing what should be running.
 */
@HiltViewModel
class PortForwardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val hostDao: HostDao,
    private val controller: ForwardingController,
    private val showAllPortsStore: ShowAllPortsStore,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "PortForwardViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(PortForwardUiState(hostId = hostId))
    val state: StateFlow<PortForwardUiState> = _state.asStateFlow()

    /**
     * The latest UNFILTERED snapshot for this host. Kept off the UI state so the
     * screen cannot accidentally paint an unfiltered list, while the checkbox can
     * still re-filter immediately instead of waiting for the next emission.
     */
    private var allTunnels: List<TunnelInfo> = emptyList()

    init {
        viewModelScope.launch {
            val host = hostDao.getById(hostId)
            val showAll = showAllPortsStore.isShowAll()
            _state.value = _state.value.copy(
                hostName = host?.name ?: "Port forwarding",
                hostSubtitle = host?.let { "${it.username}@${it.hostname}:${it.port}" }.orEmpty(),
                enabled = host?.enabled == true,
                showAllPorts = showAll,
                loading = false,
            ).reFiltered()
        }
        viewModelScope.launch {
            controller.snapshot.collect { snapshot ->
                val host = snapshot.firstOrNull { it.hostId == hostId }
                allTunnels = host?.tunnels.orEmpty()
                _state.value = _state.value
                    .copy(
                        connection = host?.connection ?: ConnectionState.Idle,
                        // The gated reason, so the screen cannot paint a reason
                        // that belongs to a state the host has already left
                        // (#2491) — and cannot disagree with the notification,
                        // which reads the very same accessor.
                        attention = host?.terminalAttention,
                    )
                    .reFiltered()
            }
        }
    }

    /** The Off/On control: records the durable intent and mounts/unmounts forwarding. */
    fun setEnabled(enabled: Boolean) {
        // Reflected optimistically so the toggle never looks stuck while the dial
        // is in flight; the controller snapshot fills in the rows.
        _state.value = _state.value.copy(enabled = enabled)
        viewModelScope.launch {
            if (enabled) controller.start(hostId) else controller.stop(hostId)
        }
    }

    /** A row's Start/Stop: a per-port opt-in that survives reconnects. */
    fun togglePort(remotePort: Int) {
        viewModelScope.launch { controller.togglePort(hostId, remotePort) }
    }

    /** The "Show hidden/noisy ports" checkbox. Persisted globally. */
    fun setShowAllPorts(showAll: Boolean) {
        _state.value = _state.value.copy(showAllPorts = showAll).reFiltered()
        viewModelScope.launch { showAllPortsStore.setShowAll(showAll) }
    }

    /**
     * Re-derives [PortForwardUiState.rows] and [PortForwardUiState.hiddenCount]
     * from [allTunnels]. One helper so a checkbox change and a fresh snapshot can
     * never disagree about what is visible.
     */
    private fun PortForwardUiState.reFiltered(): PortForwardUiState = copy(
        rows = InterestingPortFilter.filter(allTunnels, showAllPorts),
        hiddenCount = InterestingPortFilter.hiddenCount(allTunnels),
    )
}
