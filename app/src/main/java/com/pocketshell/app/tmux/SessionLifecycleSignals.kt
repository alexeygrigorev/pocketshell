package com.pocketshell.app.tmux

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One tmux-session lifecycle event broadcast across view models — issue #464.
 *
 * The folder/session tree (`FolderListViewModel`) and the per-session screen
 * (`TmuxSessionViewModel`) live on different navigation back-stack entries,
 * so they own separate view-model instances and cannot see each other's
 * state directly. When the user kills the session they are attached to
 * (session dropdown → Kill session → confirm), the tree must learn about it
 * so the dead session drops out of the list promptly instead of lingering
 * until the next foreground re-probe happens to land.
 *
 * @property hostId the host the killed session belonged to. The tree filters
 *   on this so a kill on host A never disturbs host B's rows.
 * @property sessionName the tmux session name that was killed.
 */
data class KilledSession(
    val hostId: Long,
    val sessionName: String,
)

/**
 * Issue #883: one tmux WINDOW was closed by an in-session "Stop session" on a
 * `[wN]` window row, while sibling window(s) and the parent session stayed
 * alive. The folder/session tree models each window as its own row, so a
 * window kill that leaves the session running must drop ONLY that window row
 * (via [com.pocketshell.app.projects.HostTreeModel.removeWindow]), not the
 * whole session ([KilledSession]).
 *
 * @property hostId the host the window belonged to (the tree filters on it).
 * @property windowId the stable tmux window id (`@N`) of the closed window —
 *   the same id the tree's enumeration tags windows with and `removeWindow`
 *   keys on. When the session's LAST window is closed the kill surfaces as a
 *   [KilledSession] instead, so this only ever carries a surviving-session
 *   window close.
 */
data class ClosedWindow(
    val hostId: Long,
    val windowId: String,
)

/**
 * Process-scoped fan-out of tmux-session lifecycle signals — issue #464.
 *
 * Today it carries exactly one event: a confirmed session kill. The kill
 * side ([TmuxSessionViewModel.killCurrentSession]) emits only after tmux
 * acknowledges the `kill-session` (transport OK + no `%error`), so a
 * failed kill never broadcasts and the tree keeps the still-live row.
 *
 * The flow is hot with no replay: a subscriber that is not collecting at
 * emit time simply misses the event, which is correct — the tree's own
 * foreground re-probe is the authoritative source when it next binds, and
 * a stale replayed kill could otherwise wrongly drop a freshly-recreated
 * session that reused the same name. `extraBufferCapacity` keeps `tryEmit`
 * non-suspending from the emitting view model's scope.
 */
@Singleton
class SessionLifecycleSignals @Inject constructor(
    private val runtimeCache: TmuxSessionRuntimeCache?,
    private val agentSessionMemory: AgentSessionMemory?,
) {
    constructor() : this(runtimeCache = null, agentSessionMemory = null)

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _killedSessions = MutableSharedFlow<KilledSession>(
        replay = 0,
        extraBufferCapacity = 8,
    )

    /** Hot stream of confirmed session kills. No replay (see class doc). */
    val killedSessions: SharedFlow<KilledSession> = _killedSessions.asSharedFlow()

    // Issue #883: confirmed single-window closes where the session survived.
    private val _closedWindows = MutableSharedFlow<ClosedWindow>(
        replay = 0,
        extraBufferCapacity = 8,
    )

    /**
     * Issue #883: hot stream of confirmed window closes (the session survived).
     * Same no-replay semantics as [killedSessions] — the tree's re-probe is the
     * authoritative source if a subscriber missed the event.
     */
    val closedWindows: SharedFlow<ClosedWindow> = _closedWindows.asSharedFlow()

    /** Broadcast a confirmed kill of [sessionName] on [hostId]. */
    fun emitKilled(hostId: Long, sessionName: String) {
        val trimmed = sessionName.trim()
        if (trimmed.isEmpty()) return
        agentSessionMemory?.forgetSession(hostId = hostId, sessionName = trimmed)
        runtimeCache?.removeSession(hostId = hostId, sessionName = trimmed)?.forEach { runtime ->
            cleanupScope.launch { runtime.closeCachedRuntime() }
        }
        _killedSessions.tryEmit(KilledSession(hostId = hostId, sessionName = trimmed))
    }

    /**
     * Issue #883: broadcast a confirmed close of the window with tmux id
     * [windowId] on [hostId], the parent session having survived. A blank
     * [windowId] is ignored (the tree keys [removeWindow] on the stable id).
     */
    fun emitWindowClosed(hostId: Long, windowId: String) {
        val trimmed = windowId.trim()
        if (trimmed.isEmpty()) return
        _closedWindows.tryEmit(ClosedWindow(hostId = hostId, windowId = trimmed))
    }
}
