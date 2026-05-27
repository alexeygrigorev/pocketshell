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

    fun flowOfActiveHostCount(): StateFlow<Int> = activeHostCount.asStateFlow()
    fun flowOfTotalTunnelCount(): StateFlow<Int> = totalTunnelCount.asStateFlow()
    fun flowOfPrimaryHostName(): StateFlow<String> = primaryHostName.asStateFlow()

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
            activeHosts += ActiveHost(hostId, hostName, reconnectHook, existing.tunnelCount)
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
    )
}
