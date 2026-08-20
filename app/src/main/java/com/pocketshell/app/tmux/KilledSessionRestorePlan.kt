package com.pocketshell.app.tmux

import android.util.Log
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope

/**
 * The restore-related targets which can outlive the visible session screen.
 * Keeping the identity calculation outside the VM makes the kill invalidation
 * path reviewable without adding more weight to the connection god object.
 */
internal data class KilledSessionRestoreSnapshot(
    val activeTarget: ConnectionTarget?,
    val connectingTarget: ConnectionTarget?,
    val pendingTarget: ConnectionTarget?,
    val pausedTarget: ConnectionTarget?,
    val intentTarget: ConnectionTarget?,
)

internal data class KilledSessionRestorePlan(
    val activeWasKilled: Boolean,
    val connectingWasKilled: Boolean,
    val pendingWasKilled: Boolean,
    val pausedWasKilled: Boolean,
    val intentWasKilled: Boolean,
    val connectingToPreserve: ConnectionTarget?,
    val killedTarget: ConnectionTarget?,
)

/**
 * Match the confirmed kill against every in-memory restore lane. Matching is
 * deliberately host-plus-name only: that is the identity carried by the
 * lifecycle signal, and same-name recreation is allowed to clear the old
 * invalidation when its open signal arrives.
 */
internal fun planKilledSessionRestore(
    killed: KilledSession,
    state: KilledSessionRestoreSnapshot,
): KilledSessionRestorePlan? {
    fun isKilled(target: ConnectionTarget?): Boolean =
        target?.hostId == killed.hostId && target.sessionName == killed.sessionName

    val activeWasKilled = isKilled(state.activeTarget)
    val connectingWasKilled = isKilled(state.connectingTarget)
    val pendingWasKilled = isKilled(state.pendingTarget)
    val pausedWasKilled = isKilled(state.pausedTarget)
    val intentWasKilled = isKilled(state.intentTarget)
    if (!activeWasKilled && !connectingWasKilled && !pendingWasKilled &&
        !pausedWasKilled && !intentWasKilled
    ) {
        return null
    }

    return KilledSessionRestorePlan(
        activeWasKilled = activeWasKilled,
        connectingWasKilled = connectingWasKilled,
        pendingWasKilled = pendingWasKilled,
        pausedWasKilled = pausedWasKilled,
        intentWasKilled = intentWasKilled,
        connectingToPreserve = state.connectingTarget?.takeUnless(::isKilled),
        killedTarget = state.activeTarget,
    )
}

/** Observe kills for the VM lifetime; the visible screen can leave first. */
internal fun TmuxSessionViewModel.observeKilledSessionsForRestore() {
    val signals = sessionLifecycleSignals ?: return
    viewModelScope.launch {
        signals.killedSessions.collect { killed -> invalidateKilledRestoreState(killed) }
    }
}

/**
 * Apply a confirmed kill to the VM's restore state. This is deliberately a
 * lifetime-scoped observer path: Stop navigates away before the verified
 * gateway result arrives, so the background detach must not stash the
 * just-killed target for a later foreground replay. The VM members touched
 * here are package-internal solely for this narrow invalidation helper; they
 * remain outside the public app API.
 */
internal fun TmuxSessionViewModel.invalidateKilledRestoreState(killed: KilledSession) {
    val plan = planKilledSessionRestore(
        killed,
        KilledSessionRestoreSnapshot(
            activeTarget = activeTarget,
            connectingTarget = connectingTarget,
            pendingTarget = pendingReattach?.target,
            pausedTarget = pausedAutoReconnect?.target,
            intentTarget = latestConnectIntent?.target,
        ),
    ) ?: return
    if (plan.pendingWasKilled) pendingReattach = null
    if (plan.pausedWasKilled) pausedAutoReconnect = null
    if (plan.intentWasKilled) latestConnectIntent = null
    if (plan.connectingWasKilled) connectingTarget = null

    if (plan.activeWasKilled) {
        // Clear restore-producing fields before asynchronous teardown. A
        // racing lifecycle edge therefore cannot re-arm the killed target.
        activeTarget = null
        pendingBackgroundDetachPreserveTarget = plan.connectingToPreserve
        if (backgroundDetachJob?.isActive != true) {
            launchContainedTeardown {
                withContext(NonCancellable) {
                    closeCurrentConnectionAndJoin(
                        preserveConnectingTarget = plan.connectingToPreserve,
                        cacheEviction = RuntimeCacheEviction.TargetRuntime(
                            checkNotNull(plan.killedTarget).toRuntimeKey(),
                        ),
                    )
                }
            }
        }
    }

    if (plan.connectingWasKilled || (plan.activeWasKilled && plan.intentWasKilled)) {
        connectJob?.cancel()
        connectJob = null
    }
    if (plan.activeWasKilled || plan.pausedWasKilled) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }
    refreshReconnectAvailability()
    Log.i(
        ISSUE_464_KILL_TAG,
        "stop-session-restore-invalidated host=${killed.hostId} " +
            "session=${killed.sessionName} active=${plan.activeWasKilled} " +
            "pending=${plan.pendingWasKilled} paused=${plan.pausedWasKilled}",
    )
}
