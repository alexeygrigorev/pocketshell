package com.pocketshell.app.portfwd

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.portfwd.service.ForwardingService
import com.pocketshell.core.portfwd.AutoForwardConfig
import com.pocketshell.core.portfwd.AutoForwarderSupervisor
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-singleton owner for active port-forwarding sessions and the
 * foreground [ForwardingService].
 *
 * Issue #329 reopened after dogfood: active forwarding must not be
 * owned by the panel ViewModel. The ViewModel can be disposed when the
 * panel closes or the activity is recreated, while the user's forwards
 * must remain backed by foreground-service machinery. This singleton
 * therefore owns the live [AutoForwarderSupervisor] jobs; the panel is
 * a start/observe/disable surface.
 *
 *  1. Tracks which hosts are currently auto-forwarding from the
 *     user's perspective.
 *  2. Starts the [ForwardingService] when the count of active hosts
 *     transitions 0 → ≥ 1.
 *  3. Stops the service when the count transitions back to 0.
 *  4. Exposes [flowOfTotalTunnelCount] / [flowOfPrimaryHostName] for
 *     the service to render in its persistent notification.
 *  5. Broadcasts [reconnectNow] hints (from the service's network
 *     callback) to every active host's supervisor so the
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
class ForwardingController(
    @ApplicationContext private val appContext: Context,
    private val connector: PortForwardConnector,
    private val portRemappingDao: PortRemappingDao,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        connector: PortForwardConnector,
        portRemappingDao: PortRemappingDao,
    ) : this(
        appContext = appContext,
        connector = connector,
        portRemappingDao = portRemappingDao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    @VisibleForTesting
    constructor(appContext: Context) : this(
        appContext = appContext,
        connector = UnavailablePortForwardConnector,
        portRemappingDao = EmptyPortRemappingDao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

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
    private val hostTunnels = MutableStateFlow<Map<Long, List<TunnelInfo>>>(emptyMap())
    private val hostConnectionStates =
        MutableStateFlow<Map<Long, AutoForwarderSupervisor.ConnectionState>>(emptyMap())
    private val hostErrors = MutableStateFlow<Map<Long, String?>>(emptyMap())

    fun flowOfActiveHostCount(): StateFlow<Int> = activeHostCount.asStateFlow()
    fun flowOfTotalTunnelCount(): StateFlow<Int> = totalTunnelCount.asStateFlow()
    fun flowOfPrimaryHostName(): StateFlow<String> = primaryHostName.asStateFlow()
    fun flowOfHostSnapshots(): StateFlow<Map<Long, ForwardingHostSnapshot>> = hostSnapshots.asStateFlow()
    fun flowOfHostTunnels(hostId: Long) =
        hostTunnels.asStateFlow().map { it[hostId].orEmpty() }.distinctUntilChanged()

    fun flowOfHostConnectionState(hostId: Long) =
        hostConnectionStates.asStateFlow()
            .map { it[hostId] ?: AutoForwarderSupervisor.ConnectionState.Idle }
            .distinctUntilChanged()

    fun flowOfHostError(hostId: Long) =
        hostErrors.asStateFlow().map { it[hostId] }.distinctUntilChanged()

    /**
     * Number of active hosts whose forwarding transport is currently
     * down and reconnecting (issue #439). > 0 means the user's forwards
     * are temporarily down and being restored — surfaced as a transient
     * "restoring…" state in the indicator/notification so a transport
     * blip reads as "restoring" rather than "removed".
     */
    fun flowOfRestoringHostCount(): StateFlow<Int> = restoringHostCount.asStateFlow()

    suspend fun startForwarding(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): Result<Unit> {
        val firstSession = connector.connect(host, keyPath, passphrase).getOrElse { failure ->
            return Result.failure(failure)
        }
        val remappings = runCatching {
            portRemappingDao.getByHostId(host.id).first()
                .associate { it.remotePort to it.localPort }
        }.getOrElse { emptyMap() }
        startForwardingWithSession(
            host = host,
            keyPath = keyPath,
            passphrase = passphrase,
            firstSession = firstSession,
            initialRemappings = remappings,
        )
        return Result.success(Unit)
    }

    fun adoptForwardingSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        firstSession: SshSession,
        initialRemappings: Map<Int, Int>,
    ) {
        startForwardingWithSession(
            host = host,
            keyPath = keyPath,
            passphrase = passphrase,
            firstSession = firstSession,
            initialRemappings = initialRemappings,
        )
    }

    fun isHostActive(hostId: Long): Boolean = activeHosts.any { it.hostId == hostId }

    fun togglePort(hostId: Long, remotePort: Int) {
        val supervisor = activeHosts.firstOrNull { it.hostId == hostId }?.supervisor ?: return
        scope.launch { supervisor.togglePort(remotePort) }
    }

    fun stopForwarding(hostId: Long) {
        unregisterActiveHost(hostId)
    }

    @JvmOverloads
    fun stopAllForwarding(requestServiceStop: Boolean = true) {
        val entries = synchronized(this) {
            val snapshot = activeHosts.toList()
            activeHosts.clear()
            hostTunnels.value = emptyMap()
            hostConnectionStates.value = emptyMap()
            hostErrors.value = emptyMap()
            recomputeSnapshot()
            snapshot
        }
        entries.forEach { it.stopOwnedSupervisor() }
        if (requestServiceStop) {
            ForwardingService.stop(appContext)
        }
    }

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
        forceReconnectHook: (() -> Unit)? = null,
    ) {
        val wasEmpty = activeHosts.isEmpty()
        val existing = activeHosts.firstOrNull { it.hostId == hostId }
        if (existing != null) {
            // Update in place — the reconnect hook may have changed
            // because the panel rebuilt its supervisor.
            activeHosts.remove(existing)
            activeHosts += ActiveHost(
                hostId = hostId,
                hostName = hostName,
                reconnectHook = reconnectHook,
                forceReconnectHook = forceReconnectHook,
                supervisor = existing.supervisor,
                supervisorJob = existing.supervisorJob,
                reconnectPassphrase = existing.reconnectPassphrase,
                tunnelCount = existing.tunnelCount,
                activeRemotePorts = existing.activeRemotePorts,
                forwardedPortMap = existing.forwardedPortMap,
                restoring = existing.restoring,
            )
        } else {
            activeHosts += ActiveHost(
                hostId = hostId,
                hostName = hostName,
                reconnectHook = reconnectHook,
                forceReconnectHook = forceReconnectHook,
                supervisor = null,
                supervisorJob = null,
                reconnectPassphrase = null,
                tunnelCount = 0,
            )
        }
        recomputeSnapshot()
        if (wasEmpty && activeHostCount.value == 1) {
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
        hostTunnels.update { it - hostId }
        hostConnectionStates.update { it - hostId }
        hostErrors.update { it - hostId }
        recomputeSnapshot()
        entry.stopOwnedSupervisor()
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
     * Update the exact set of remote ports currently forwarding for [hostId],
     * together with the local (phone-loopback) port each one maps to. Host-detail
     * surfaces use this to label per-port rows; aggregate-only counts are
     * insufficient because a host can have one active tunnel alongside several
     * discovered-but-idle ports.
     *
     * Issue #488: the `remotePort → localPort` mapping is carried so a tapped
     * `localhost:<remotePort>` terminal link can resolve the working local URL
     * (`http://127.0.0.1:<localPort>`) without re-opening the panel. By default
     * `localPort == remotePort`; a user remapping makes them differ.
     */
    @Synchronized
    fun updateActiveTunnels(hostId: Long, tunnels: Map<Int, Int>) {
        val entry = activeHosts.firstOrNull { it.hostId == hostId } ?: return
        val normalized = tunnels.toSortedMap()
        if (entry.forwardedPortMap == normalized && entry.tunnelCount == normalized.size) return
        activeHosts.remove(entry)
        activeHosts += entry.copy(
            tunnelCount = normalized.size,
            activeRemotePorts = normalized.keys.toSortedSet(),
            forwardedPortMap = normalized,
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

    /**
     * Stronger network-recovery hint used after the foreground service
     * has observed an actual default-network loss. This may rebuild a
     * session that still reports "connected" but whose forwards died
     * under the old network.
     */
    fun forceReconnectNow() {
        activeHosts.forEach {
            runCatching {
                (it.forceReconnectHook ?: it.reconnectHook)?.invoke()
            }
        }
    }

    private fun startForwardingWithSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        firstSession: SshSession,
        initialRemappings: Map<Int, Int>,
    ) {
        val reconnectPassphrase = passphrase?.copyOf()
        var first: SshSession? = firstSession
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = {
                first?.also { first = null }
                    ?: connector.connect(host, keyPath, reconnectPassphrase?.copyOf())
                        .getOrElse { throw it }
            },
            config = host.toAutoForwardConfig(),
            initialRemappings = initialRemappings,
        )
        val collectionJob = scope.launch {
            launch {
                supervisor.flowOfTunnels().collect { tunnels ->
                    hostTunnels.update { it + (host.id to tunnels) }
                    val activeTunnelMap = tunnels
                        .filter { tunnel -> tunnel.status == TunnelInfo.Status.FORWARDING }
                        .associate { tunnel -> tunnel.remotePort to tunnel.localPort }
                    updateActiveTunnels(host.id, activeTunnelMap)
                }
            }
            launch {
                supervisor.flowOfConnectionState().collect { state ->
                    hostConnectionStates.update { it + (host.id to state) }
                    setHostRestoring(
                        hostId = host.id,
                        restoring = state == AutoForwarderSupervisor.ConnectionState.Reconnecting,
                    )
                }
            }
            launch {
                supervisor.flowOfEvents().collect { event ->
                    when (event) {
                        is AutoForwarderSupervisor.Event.Connected -> {
                            hostErrors.update { it + (host.id to null) }
                        }
                        is AutoForwarderSupervisor.Event.Error -> {
                            hostErrors.update {
                                it + (host.id to "Port forwarding reconnect failed: ${event.message}")
                            }
                        }
                        is AutoForwarderSupervisor.Event.ConnectionLost -> {
                            hostErrors.update { it + (host.id to event.lastError) }
                        }
                        is AutoForwarderSupervisor.Event.Disconnected -> Unit
                    }
                }
            }
        }
        val oldEntry = synchronized(this) {
            val wasEmpty = activeHosts.isEmpty()
            val existing = activeHosts.firstOrNull { it.hostId == host.id }
            if (existing != null) activeHosts.remove(existing)
            activeHosts += ActiveHost(
                hostId = host.id,
                hostName = host.name,
                reconnectHook = { supervisor.reconnectNow() },
                forceReconnectHook = { supervisor.reconnectNow(force = true) },
                supervisor = supervisor,
                supervisorJob = collectionJob,
                reconnectPassphrase = reconnectPassphrase,
                tunnelCount = existing?.tunnelCount ?: 0,
                activeRemotePorts = existing?.activeRemotePorts.orEmpty(),
                forwardedPortMap = existing?.forwardedPortMap.orEmpty(),
                restoring = existing?.restoring ?: false,
            )
            recomputeSnapshot()
            if (wasEmpty) ForwardingService.start(appContext)
            existing
        }
        oldEntry?.stopOwnedSupervisor()
        supervisor.start(scope)
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
                    forwardedPortMap = host.forwardedPortMap,
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
        val forceReconnectHook: (() -> Unit)? = null,
        val supervisor: AutoForwarderSupervisor?,
        val supervisorJob: kotlinx.coroutines.Job?,
        val reconnectPassphrase: CharArray?,
        val tunnelCount: Int,
        val activeRemotePorts: Set<Int> = emptySet(),
        // Issue #488: remote → local port mapping for the active forwards.
        val forwardedPortMap: Map<Int, Int> = emptyMap(),
        // Issue #439: the host is registered (user enabled forwarding)
        // but its transport is currently down and reconnecting, so its
        // forwards are being restored rather than removed.
        val restoring: Boolean = false,
    ) {
        fun stopOwnedSupervisor() {
            supervisorJob?.cancel()
            supervisor?.stop()
            reconnectPassphrase?.fill('\u0000')
        }
    }

    private fun HostEntity.toAutoForwardConfig(): AutoForwardConfig =
        AutoForwardConfig(
            scanIntervalSec = scanIntervalSec,
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
        )
}

data class ForwardingHostSnapshot(
    val active: Boolean,
    val tunnelCount: Int,
    val activeRemotePorts: Set<Int> = emptySet(),
    // Issue #488: remote → local port mapping for the active forwards, so a
    // tapped `localhost:<remotePort>` link resolves the working local URL.
    val forwardedPortMap: Map<Int, Int> = emptyMap(),
    // Issue #439: transport down, forwards restoring (transient).
    val restoring: Boolean = false,
)

private object UnavailablePortForwardConnector : PortForwardConnector {
    override suspend fun connect(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): Result<SshSession> = Result.failure(IllegalStateException("PortForwardConnector unavailable"))
}

private object EmptyPortRemappingDao : PortRemappingDao {
    override fun getByHostId(hostId: Long) =
        kotlinx.coroutines.flow.flowOf(emptyList<PortRemappingEntity>())

    override suspend fun getByRemotePort(hostId: Long, remotePort: Int): PortRemappingEntity? = null

    override suspend fun insert(remapping: PortRemappingEntity): Long = remapping.id

    override suspend fun deleteByRemotePort(hostId: Long, remotePort: Int) = Unit

    override suspend fun deleteByHostId(hostId: Long) = Unit
}
