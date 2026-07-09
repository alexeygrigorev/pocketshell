package com.pocketshell.app.projects

import com.pocketshell.app.portfwd.ForwardingHostSnapshot

internal fun folderListLoadingState(
    hostId: Long?,
    forwardingSnapshots: Map<Long, ForwardingHostSnapshot>,
): FolderListUiState.Loading {
    val snapshot = hostId?.let { forwardingSnapshots[it] }
    return FolderListUiState.Loading(
        portForwarding = HostPortForwardingSummary(
            active = snapshot?.active == true,
            activeTunnelCount = snapshot?.tunnelCount ?: 0,
            entryAvailable = hostId != null,
            discoveryLoading = true,
        ),
    )
}

internal fun folderListReadyState(
    projection: HostTreeModel.Projection,
    refreshing: Boolean,
    creatingSession: Boolean,
    portForwarding: HostPortForwardingSummary,
): FolderListUiState.Ready =
    FolderListUiState.Ready(
        folders = projection.folders,
        treeRoots = projection.treeRoots,
        flatSessions = projection.flatSessions,
        expandedProjectPaths = projection.expandedProjectPaths,
        isRefreshing = refreshing,
        isCreatingSession = creatingSession,
        portForwarding = portForwarding,
    )

internal fun folderListForwardingSummary(
    hostId: Long?,
    forwardingSnapshots: Map<Long, ForwardingHostSnapshot>,
    discoveredPorts: List<HostDiscoveredPort>,
    treeHasSnapshot: Boolean,
): HostPortForwardingSummary {
    if (hostId == null) return HostPortForwardingSummary(discoveredPorts = discoveredPorts)
    val snapshot = forwardingSnapshots[hostId]
    return HostPortForwardingSummary(
        discoveredPorts = FolderTreeProjection.mergeForwardingPortRows(
            discoveredPorts = discoveredPorts,
            activeRemotePorts = snapshot?.activeRemotePorts.orEmpty(),
        ),
        active = snapshot?.active == true,
        activeTunnelCount = snapshot?.tunnelCount ?: 0,
        entryAvailable = true,
        discoveryLoading = !treeHasSnapshot,
    )
}
