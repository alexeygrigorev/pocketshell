package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.ssh.SshLeaseKey

/**
 * Issue #1537 (option b): the parked-runtime death effect, extracted from
 * [TmuxSessionViewModel] so the god-object does not grow (the #1047 ratchet).
 *
 * A parked runtime's liveness edge declared it dead while another session is
 * foreground — the fast-switch stale-lease blind spot the single controller was
 * missing a subscriber for. Evict the corpse from the cache and release its
 * lease ref NOW so the switch-back reattaches on a live transport (or dials
 * fresh) instead of discovering the death as an attach EOF.
 *
 * ONE-TRANSPORT safety: only force-disconnect the pooled lease when NO live
 * holder remains ([leaseKeyStillInUse] false — neither the foreground session
 * nor a sibling cached runtime holds this key). A shared transport that is
 * genuinely dead is owned by the foreground session's own recovery; the
 * same-host silent-corpse race is caught by the attach-EOF fallback, never by
 * killing a transport the active session is still using.
 */
internal fun handleParkedRuntimeDeath(
    key: RuntimeHealthKey,
    leaseKey: SshLeaseKey?,
    cause: RuntimeDeathCause,
    runtimeCache: TmuxSessionRuntimeCache,
    // Lease keys the foreground/connecting session currently holds — a shared one
    // must NOT be force-disconnected.
    foregroundLeaseKeys: Set<SshLeaseKey>,
    disconnectLease: suspend (SshLeaseKey) -> Unit,
    launchContained: (suspend () -> Unit) -> Unit,
) {
    val removed = runtimeCache.removeSession(key.hostId, key.sessionName)
    DiagnosticEvents.record(
        "connection",
        "parked_runtime_death",
        "source" to "parked_health_subscriber",
        "cause" to cause.name,
        "hostId" to key.hostId,
        "session" to key.sessionName,
        "evictedRuntimes" to removed.size,
    )
    ReconnectCauseTrail.record(
        stage = "parked_runtime_health",
        outcome = "proactive_evict",
        cause = cause.name,
        "hostId" to key.hostId,
    )
    val evictedLeaseKey = leaseKey ?: removed.firstNotNullOfOrNull { it.lease?.key }
    launchContained {
        // closeCachedRuntime releases the lease REF (decrement refcount) — never a
        // raw pool disconnect that would nuke a shared transport.
        removed.forEach { runCatching { it.closeCachedRuntime() } }
        val stillShared = evictedLeaseKey != null && (
            evictedLeaseKey in foregroundLeaseKeys ||
                runtimeCache.cachedRuntimesForHost(key.hostId).any { it.lease?.key == evictedLeaseKey }
            )
        if (evictedLeaseKey != null && !stillShared) {
            disconnectLease(evictedLeaseKey)
        }
    }
}
