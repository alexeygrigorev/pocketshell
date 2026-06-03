package com.pocketshell.app.tmux

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
class SessionLifecycleSignals @Inject constructor() {
    private val _killedSessions = MutableSharedFlow<KilledSession>(
        replay = 0,
        extraBufferCapacity = 8,
    )

    /** Hot stream of confirmed session kills. No replay (see class doc). */
    val killedSessions: SharedFlow<KilledSession> = _killedSessions.asSharedFlow()

    /** Broadcast a confirmed kill of [sessionName] on [hostId]. */
    fun emitKilled(hostId: Long, sessionName: String) {
        val trimmed = sessionName.trim()
        if (trimmed.isEmpty()) return
        _killedSessions.tryEmit(KilledSession(hostId = hostId, sessionName = trimmed))
    }
}
