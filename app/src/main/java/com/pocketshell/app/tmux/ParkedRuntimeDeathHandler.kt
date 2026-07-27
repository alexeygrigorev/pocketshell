package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.tmux.connection.ParkedRuntimeDeathSignal
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
    signal: ParkedRuntimeDeathSignal,
    runtimeCache: TmuxSessionRuntimeCache,
    // Lease keys the foreground/connecting session currently holds — a shared one
    // must NOT be force-disconnected.
    foregroundLeaseKeys: Set<SshLeaseKey>,
    disconnectLease: suspend (SshLeaseKey) -> Unit,
    launchContained: (suspend () -> Unit) -> Unit,
): Boolean {
    // Atomic exact compare-and-remove. If this binding has already been
    // replaced, the callback is stale and must not touch the new runtime,
    // release its lease, or disconnect its transport.
    val removed = runtimeCache.removeExact(signal.binding)
    if (removed == null) {
        DiagnosticEvents.record(
            "connection",
            "parked_runtime_death_ignored",
            "source" to "parked_health_subscriber",
            "outcome" to "stale_callback",
            "cause" to signal.cause.name,
            "hostId" to signal.binding.key.hostId,
            "session" to signal.binding.key.sessionName,
            "boundRuntimeToken" to signal.binding.token.toString(),
            "boundClientHash" to signal.boundClientIdentity,
            *signal.typedCauseDiagnosticFields(),
        )
        return false
    }
    DiagnosticEvents.record(
        "connection",
        "parked_runtime_death",
        "source" to "parked_health_subscriber",
        "cause" to signal.cause.name,
        "hostId" to signal.binding.key.hostId,
        "session" to signal.binding.key.sessionName,
        "evictedRuntimes" to 1,
        "boundRuntimeToken" to signal.binding.token.toString(),
        "removedRuntimeToken" to removed.healthBinding.token.toString(),
        "boundClientHash" to signal.boundClientIdentity,
        "removedClientHash" to System.identityHashCode(removed.client),
        *signal.typedCauseDiagnosticFields(),
    )
    ReconnectCauseTrail.record(
        stage = "parked_runtime_health",
        outcome = "proactive_evict",
        cause = signal.cause.name,
        "hostId" to signal.binding.key.hostId,
        "runtimeToken" to signal.binding.token.toString(),
    )
    val evictedLeaseKey = signal.leaseKey ?: removed.lease?.key
    launchContained {
        // closeCachedRuntime releases the lease REF (decrement refcount) — never a
        // raw pool disconnect that would nuke a shared transport.
        runCatching { removed.closeCachedRuntime() }
        val stillShared = evictedLeaseKey != null && (
            evictedLeaseKey in foregroundLeaseKeys ||
                runtimeCache.cachedRuntimesForHost(signal.binding.key.hostId)
                    .any { it.lease?.key == evictedLeaseKey }
            )
        if (evictedLeaseKey != null && !stillShared) {
            disconnectLease(evictedLeaseKey)
        }
    }
    return true
}

private fun ParkedRuntimeDeathSignal.typedCauseDiagnosticFields(): Array<Pair<String, Any?>> =
    arrayOf(
        "disconnectReason" to disconnectEvent?.reason?.logValue,
        "disconnectSource" to disconnectEvent?.source,
        "disconnectIntent" to disconnectEvent?.intent,
        "commandKind" to disconnectEvent?.commandKind,
        "timeoutMode" to disconnectEvent?.timeoutMode,
        "exceptionClass" to disconnectEvent?.exceptionClass,
        "message" to disconnectEvent?.message,
        "leaseCloseReason" to leaseCloseReason?.name,
    )
