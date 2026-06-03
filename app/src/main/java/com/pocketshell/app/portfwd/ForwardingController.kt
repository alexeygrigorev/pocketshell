package com.pocketshell.app.portfwd

import android.content.Context
import com.pocketshell.app.portfwd.service.ForwardingService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-singleton coordinator between [PortForwardPanelViewModel] and
 * [ForwardingService].
 *
 * Issue #203 expanded scope. The ViewModel owns the in-process
 * forwarder + SSH session (matching the existing architecture); the
 * controller is a thin coordinator that:
 *
 *  1. Tracks which hosts are currently auto-forwarding from the
 *     panel's perspective (the panel registers a host on enable,
 *     unregisters on disable).
 *  2. Starts the [ForwardingService] when the count of active hosts
 *     transitions 0 → ≥ 1.
 *  3. Stops the service when the count transitions back to 0.
 *  4. Exposes [flowOfTotalTunnelCount] / [flowOfPrimaryHostName] for
 *     the service to render in its persistent notification.
 *  5. Broadcasts [reconnectNow] hints (from the service's network
 *     callback) to every registered host's supervisor so the
 *     exponential-backoff sleep in
 *     [com.pocketshell.core.portfwd.AutoForwarderSupervisor] cancels
 *     and an attempt fires immediately.
 *
 * Singleton-scoped because the service and the ViewModels (one per
 * panel mount) need a shared rendezvous. Backed by a
 * [java.util.concurrent.CopyOnWriteArrayList] for the registration
 * set since registration / deregistration is rare and reads (from the
 * service's notification update path) happen on every state change.
 */
@Singleton
class ForwardingController @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    /**
     * One entry per host that has auto-forward enabled. The set lives
     * for the lifetime of the process; entries are added in
     * [registerActiveHost] (idempotent on host id) and removed in
     * [unregisterActiveHost].
     */
    private val activeHosts: CopyOnWriteArrayList<ActiveHost> = CopyOnWriteArrayList()

    private val activeHostCount = MutableStateFlow(0)
    private val totalTunnelCount = MutableStateFlow(0)
    private val primaryHostName = MutableStateFlow("")
    private val hostSnapshots = MutableStateFlow<Map<Long, ForwardingHostSnapshot>>(emptyMap())
    private val restoringHostCount = MutableStateFlow(0)

    fun flowOfActiveHostCount(): StateFlow<Int> = activeHostCount.asStateFlow()
    fun flowOfTotalTunnelCount(): StateFlow<Int> = totalTunnelCount.asStateFlow()
    fun flowOfPrimaryHostName(): StateFlow<String> = primaryHostName.asStateFlow()
    fun flowOfHostSnapshots(): StateFlow<Map<Long, ForwardingHostSnapshot>> = hostSnapshots.asStateFlow()

    /**
     * Number of active hosts whose forwarding transport is currently
     * down and reconnecting (issue #439). > 0 means the user's forwards
     * are temporarily down and being restored — surfaced as a transient
     * "restoring…" state in the indicator/notification so a transport
     * blip reads as "restoring" rather than "removed".
     */
    fun flowOfRestoringHostCount(): StateFlow<Int> = restoringHostCount.asStateFlow()

    /**
     * Tell the controller that [hostId] has just gone active.
     * Idempotent on host id. Starts the [ForwardingService] if this is
     * the first active host. The optional [reconnectHook] is invoked
     * by [reconnectNow] (typically called by the service on network
     * recovery).
     */
    @Synchronized
    fun registerActiveHost(
        hostId: Long,
        hostName: String,
        reconnectHook: (() -> Unit)? = null,
    ) {
        val existing = activeHosts.firstOrNull { it.hostId == hostId }
        if (existing != null) {
            // Update in place — the reconnect hook may have changed
            // because the panel rebuilt its supervisor.
            activeHosts.remove(existing)
            activeHosts += ActiveHost(
                hostId = hostId,
                hostName = hostName,
                reconnectHook = reconnectHook,
                tunnelCount = existing.tunnelCount,
                activeRemotePorts = existing.activeRemotePorts,
                restoring = existing.restoring,
            )
        } else {
            activeHosts += ActiveHost(hostId, hostName, reconnectHook, tunnelCount = 0)
        }
        recomputeSnapshot()
        if (activeHostCount.value == 1) {
            // First active host → start the service.
            ForwardingService.start(appContext)
        }
    }

    /**
     * Tell the controller that [hostId] is no longer auto-forwarding.
     * Stops the [ForwardingService] if this was the last active host.
     */
    @Synchronized
    fun unregisterActiveHost(hostId: Long) {
        val entry = activeHosts.firstOrNull { it.hostId == hostId } ?: return
        activeHosts.remove(entry)
        recomputeSnapshot()
        if (activeHostCount.value == 0) {
            ForwardingService.stop(appContext)
        }
    }

    /**
     * Update the cached tunnel count for [hostId]. Called by the
     * panel's ViewModel each time the forwarder's tunnel snapshot
     * changes. The service uses the sum across hosts in its
     * notification.
     */
    @Synchronized
    fun updateTunnelCount(hostId: Long, count: Int) {
        val entry = activeHosts.firstOrNull { it.hostId == hostId } ?: return
        if (entry.tunnelCount == count) return
        activeHosts.remove(entry)
        activeHosts += entry.copy(tunnelCount = count)
        recomputeSnapshot()
    }

    /**
     * Update the exact set of remote ports currently forwarding for
     * [hostId]. Host-detail surfaces use this to label per-port rows;
     * aggregate-only counts are insufficient because a host can have
     * one active tunnel alongside several discovered-but-idle ports.
     */
    @Synchronized
    fun updateActiveTunnels(hostId: Long, remotePorts: Set<Int>) {
        val entry = activeHosts.firstOrNull { it.hostId == hostId } ?: return
        val normalized = remotePorts.toSortedSet()
        if (entry.activeRemotePorts == normalized && entry.tunnelCount == normalized.size) return
        activeHosts.remove(entry)
        activeHosts += entry.copy(
            tunnelCount = normalized.size,
            activeRemotePorts = normalized,
        )
        recomputeSnapshot()
    }

    /**
     * Mark whether [hostId]'s forwarding transport is currently down and
     * reconnecting (issue #439). Drives the transient "restoring…" state
     * in the indicator/notification. No-op for an unregistered host —
     * restoring only makes sense for a host the user has enabled.
     */
    @Synchronized
    fun setHostRestoring(hostId: Long, restoring: Boolean) {
        val entry = activeHosts.firstOrNull { it.hostId == hostId } ?: return
        if (entry.restoring == restoring) return
        activeHosts.remove(entry)
        activeHosts += entry.copy(restoring = restoring)
        recomputeSnapshot()
    }

    /**
     * Network-recovery / user-action hint. Fans out to every
     * registered host's [ActiveHost.reconnectHook] so each supervisor
     * has a chance to cancel its in-flight backoff sleep. Idempotent
     * inside the supervisor.
     */
    fun reconnectNow() {
        activeHosts.forEach { runCatching { it.reconnectHook?.invoke() } }
    }

    private fun recomputeSnapshot() {
        activeHostCount.update { activeHosts.size }
        totalTunnelCount.update { activeHosts.sumOf { it.tunnelCount } }
        primaryHostName.update { activeHosts.firstOrNull()?.hostName.orEmpty() }
        restoringHostCount.update { activeHosts.count { it.restoring } }
        hostSnapshots.update {
            activeHosts.associate { host ->
                host.hostId to ForwardingHostSnapshot(
                    active = true,
                    tunnelCount = host.tunnelCount,
                    activeRemotePorts = host.activeRemotePorts,
                    restoring = host.restoring,
                )
            }
        }
    }

    /**
     * Exposed for tests so they can snapshot the registration set
     * without going through the StateFlow surfaces.
     */
    internal fun activeHostIdsSnapshot(): List<Long> = activeHosts.map { it.hostId }

    private data class ActiveHost(
        val hostId: Long,
        val hostName: String,
        val reconnectHook: (() -> Unit)?,
        val tunnelCount: Int,
        val activeRemotePorts: Set<Int> = emptySet(),
        // Issue #439: the host is registered (user enabled forwarding)
        // but its transport is currently down and reconnecting, so its
        // forwards are being restored rather than removed.
        val restoring: Boolean = false,
    )
}

data class ForwardingHostSnapshot(
    val active: Boolean,
    val tunnelCount: Int,
    val activeRemotePorts: Set<Int> = emptySet(),
    // Issue #439: transport down, forwards restoring (transient).
    val restoring: Boolean = false,
)
