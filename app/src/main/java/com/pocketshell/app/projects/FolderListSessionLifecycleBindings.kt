package com.pocketshell.app.projects

import com.pocketshell.app.tmux.ClosedWindow
import com.pocketshell.app.tmux.KilledSession
import com.pocketshell.app.tmux.SessionIdentityUncertain
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.app.tmux.StaleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Binds the process-scoped session lifecycle signals to the folder tree.
 *
 * The collectors intentionally start in the same order as the tree's previous
 * inline bindings. The callbacks keep the destructive decisions in
 * [FolderListViewModel], where exact tmux generations remain authoritative.
 * A name-only signal is forwarded separately so it can request a reconcile
 * without authorizing removal of a same-name successor.
 */
internal fun bindFolderListSessionLifecycleSignals(
    scope: CoroutineScope,
    signals: SessionLifecycleSignals,
    onKilled: (KilledSession) -> Unit,
    onWindowClosed: (ClosedWindow) -> Unit,
    onStale: (StaleSession) -> Unit,
    onIdentityUncertain: (SessionIdentityUncertain) -> Unit,
) {
    scope.launch {
        signals.killedSessions.collect { killed -> onKilled(killed) }
    }
    scope.launch {
        signals.closedWindows.collect { closed -> onWindowClosed(closed) }
    }
    scope.launch {
        signals.staleSessions.collect { stale -> onStale(stale) }
    }
    scope.launch {
        signals.identityUncertain.collect { uncertain -> onIdentityUncertain(uncertain) }
    }
}

/** Applies lifecycle events to the generation-keyed held tree. */
internal class FolderListSessionLifecycleActions(
    private val boundHostId: () -> Long?,
    private val tree: HostTreeModel,
    private val emitReady: () -> Unit,
    private val isPolling: () -> Boolean,
    private val refresh: () -> Unit,
    private val requestIdentityReconcile: () -> Unit,
) {
    fun onIdentityUncertain(uncertain: SessionIdentityUncertain) {
        val hostId = boundHostId() ?: return
        if (hostId != uncertain.hostId) return
        requestIdentityReconcile()
        if (isPolling()) refresh()
    }

    fun onWindowClosed(closed: ClosedWindow) {
        val hostId = boundHostId() ?: return
        if (hostId != closed.hostId) return
        if (tree.removeWindow(closed.windowId)) emitReady()
        if (isPolling()) refresh()
    }

    fun onStale(stale: StaleSession) {
        val hostId = boundHostId() ?: return
        if (hostId != stale.hostId) return
        if (tree.removeSession(stale.generation)) emitReady()
    }
}
