package com.pocketshell.app.projects

import com.pocketshell.core.portfwd.RemotePort
import kotlinx.coroutines.Deferred

/** Joins the independent expansion and port-scan halves of one reconcile. */
internal class ReconcileSideProbes(
    private val expansion: Deferred<WatchedRootProjectExpansion>,
    private val ports: Deferred<List<RemotePort>>,
) {
    suspend fun sessions(rows: List<FolderSessionRow>): FolderListResult.Sessions {
        val resolved = expansion.await()
        val discoveredPorts = runCatching { ports.await() }.getOrDefault(emptyList())
        return FolderListResult.Sessions(
            rows = rows,
            projectFoldersByRoot = resolved.projectFoldersByRoot,
            historyProjectFoldersByRoot = resolved.historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = resolved.resolvedWatchedRootPaths,
            discoveredPorts = discoveredPorts,
        )
    }
}
