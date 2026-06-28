package com.pocketshell.app.portfwd

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkObserver
import com.pocketshell.app.diagnostics.DiagnosticEvents
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

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
    /**
     * Validated-default-network change signal (issue #329 / #439 / #1058).
     * The SAME [com.pocketshell.app.connectivity.TerminalNetworkObserver.changes]
     * stream the terminal transport consumes — a kind-aware
     * [TerminalNetworkChange] (handoff / bare loss / restore), NOT a bare
     * marker. The controller applies the SAME liveness-first ride-through the
     * terminal does (see [onValidatedNetworkChange]).
     *
     * Defaults to [emptyFlow] for the test constructor so unit tests that
     * don't exercise the network path stay light.
     */
    validatedNetworkChanges: Flow<TerminalNetworkChange> = emptyFlow(),
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        connector: PortForwardConnector,
        portRemappingDao: PortRemappingDao,
        terminalNetworkObserver: TerminalNetworkObserver,
    ) : this(
        appContext = appContext,
        connector = connector,
        portRemappingDao = portRemappingDao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        validatedNetworkChanges = terminalNetworkObserver.changes,
    )

    @VisibleForTesting
    constructor(appContext: Context) : this(
        appContext = appContext,
        connector = UnavailablePortForwardConnector,
        portRemappingDao = EmptyPortRemappingDao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        validatedNetworkChanges = emptyFlow(),
    )

    @VisibleForTesting
    constructor(
        appContext: Context,
        connector: PortForwardConnector,
        portRemappingDao: PortRemappingDao,
        validatedNetworkChanges: Flow<TerminalNetworkChange>,
    ) : this(
        appContext = appContext,
        connector = connector,
        portRemappingDao = portRemappingDao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        validatedNetworkChanges = validatedNetworkChanges,
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

    /**
     * Issue #1058 test seam (the #780/#964 hard-inject model): pin the
     * keepalive-proven liveness verdict the network-change policy reads, so a
     * connected journey can drive the ride-through (`true`) and genuinely-dead
     * (`false`) arms deterministically WITHOUT reproducing the real ~90s
     * keepalive ride-through window on the AVD. `null` (production default)
     * reads the real per-host live [SshSession] keepalive oracle. `@Volatile`
     * so the collector coroutine sees a flip from the test thread.
     */
    @Volatile
    @VisibleForTesting
    var forceTransportProvenAliveForTest: Boolean? = null

    init {
        // Subscribe to the validated-default-network change signal from the
        // port-forward side (issue #329 / #1058). Mirrors the terminal
        // transport's kind-aware, liveness-first policy — see
        // [onValidatedNetworkChange].
        scope.launch {
            validatedNetworkChanges.collect { change ->
                onValidatedNetworkChange(change)
            }
        }
    }

    /**
     * Issue #1058 (#843 R1, trigger T11 / coverage gap C1): liveness-first
     * network-change policy for the port-forward tunnels, identical in shape to
     * the terminal transport's (#981/#997/#1042/#1045).
     *
     * Before this, the controller called [forceReconnectNow] on EVERY emitted
     * change — so on cellular, where the device dips into no-validated-network
     * constantly (tunnel, elevator, RAT handover, congestion re-validation),
     * the user's forwards churned "restoring…" on every blip even though the
     * tunnel transport never died, while the terminal stayed Live.
     *
     * The policy now mirrors the terminal, reusing the SAME keepalive-proven
     * liveness oracle ([SshSession.isTransportProvenAliveWithinKeepAliveWindow],
     * #964 — the exact oracle the terminal's
     * `isTransportKeepAliveProvenAliveRecently` also reads), with NO second
     * liveness notion:
     *
     *  - [TerminalNetworkChangeKind.NetworkLost] → HOLD. A bare loss is
     *    frequently transient; do NOT redial. Mirrors the terminal's
     *    `holdNetworkLost` — the tunnels are held, not torn down.
     *  - [TerminalNetworkChangeKind.NetworkRestored] /
     *    [TerminalNetworkChangeKind.ValidatedIdentityChange] → liveness-first:
     *    a host whose tunnel transport is keepalive-PROVEN alive RIDES THROUGH
     *    with no redial and no "restoring…" churn; a host whose transport is
     *    genuinely dead (or has no live session) is REDIALLED via the existing
     *    force hook. A real cross-transport handoff on a dead/quiet tunnel
     *    therefore still re-establishes (the #329/#439/#980 contract).
     */
    @VisibleForTesting
    fun onValidatedNetworkChange(change: TerminalNetworkChange) {
        when (change.kind) {
            TerminalNetworkChangeKind.NetworkLost -> holdNetworkLoss(change)
            TerminalNetworkChangeKind.NetworkRestored,
            TerminalNetworkChangeKind.ValidatedIdentityChange,
            -> rideThroughOrRedial(change)
        }
    }

    /**
     * Issue #1058: a bare network LOSS is HELD — the tunnels are NOT torn down
     * (a loss is frequently transient: pocket, elevator, RAT handover).
     * Recovery is driven by the matching [TerminalNetworkChangeKind.NetworkRestored].
     */
    private fun holdNetworkLoss(change: TerminalNetworkChange) {
        Log.i(
            TAG,
            "network-loss-hold reason=${change.reason} active=${activeHosts.size} " +
                "(bare loss — tunnels held, no redial)",
        )
        DiagnosticEvents.record(
            "portforward",
            "network_loss_hold",
            "reason" to change.reason,
            "kind" to change.kind.name,
            "activeHosts" to activeHosts.size,
        )
    }

    /**
     * Issue #1058: a handoff / restore — ride through the hosts whose tunnel
     * transport is keepalive-proven alive (no redial, no churn), redial the
     * hosts whose transport is genuinely dead.
     */
    private fun rideThroughOrRedial(change: TerminalNetworkChange) {
        activeHosts.forEach { host ->
            if (isTunnelTransportProvenAlive(host)) {
                Log.i(
                    TAG,
                    "network-change ride-through host=${host.hostId} reason=${change.reason} " +
                        "kind=${change.kind} (transport proven alive)",
                )
                DiagnosticEvents.record(
                    "portforward",
                    "network_ride_through",
                    "reason" to change.reason,
                    "kind" to change.kind.name,
                    "hostId" to host.hostId,
                    "cause" to "transport_proven_alive",
                )
            } else {
                Log.i(
                    TAG,
                    "network-change redial host=${host.hostId} reason=${change.reason} " +
                        "kind=${change.kind} (transport not proven alive)",
                )
                DiagnosticEvents.record(
                    "portforward",
                    "network_redial",
                    "reason" to change.reason,
                    "kind" to change.kind.name,
                    "hostId" to host.hostId,
                    "cause" to "transport_dead",
                )
                runCatching { (host.forceReconnectHook ?: host.reconnectHook)?.invoke() }
            }
        }
    }

    /**
     * Issue #1058: reuse the terminal's keepalive-proven liveness oracle
     * ([SshSession.isTransportProvenAliveWithinKeepAliveWindow], #964) on this
     * host's CURRENT live tunnel session. No second liveness notion — the
     * always-on transport keepalive (#945) runs on every [SshSession]
     * (including the port-forward sessions), so a brief sub-window dip keeps
     * the oracle TRUE (ride through) while a genuinely dead post-outage socket
     * ages out to FALSE (redial). A host with no live session reads FALSE.
     */
    private fun isTunnelTransportProvenAlive(host: ActiveHost): Boolean {
        forceTransportProvenAliveForTest?.let { return it }
        val session = host.liveSession?.get() ?: return false
        return runCatching { session.isTransportProvenAliveWithinKeepAliveWindow() }
            .getOrDefault(false)
    }

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
                liveSession = existing.liveSession,
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
     * Stronger explicit redial-all hint: force-rebuilds every active host's
     * transport even if it still reports "connected". Used for an explicit
     * user/manual recovery action.
     *
     * Issue #1058: this is NOT the network-change path any more. A
     * validated-network change goes through [onValidatedNetworkChange], which
     * is liveness-first (it holds on a bare loss and rides a handoff/restore
     * through when the tunnel transport is keepalive-proven alive) — it does
     * NOT redial unconditionally, which was the cellular-churn defect (#843
     * trigger T11).
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
        // Issue #1058: track the CURRENT live tunnel session so the
        // network-change policy can read its keepalive-proven liveness oracle.
        // The supervisor owns the session lifecycle (it swaps/closes on every
        // drop+reconnect), so we capture the freshest session at its single
        // creation point — the factory. A stale (closed) reference reads
        // not-proven-alive, which is the correct "redial" direction.
        val liveSession = AtomicReference<SshSession?>(firstSession)
        val supervisor = AutoForwarderSupervisor(
            sessionFactory = {
                val session = first?.also { first = null }
                    ?: connector.connect(host, keyPath, reconnectPassphrase?.copyOf())
                        .getOrElse { throw it }
                liveSession.set(session)
                session
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
                liveSession = liveSession,
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
        // Issue #1058: the CURRENT live tunnel session (swapped by the
        // supervisor on every reconnect), read by the network-change policy
        // for the keepalive-proven liveness oracle. Null for a panel-only
        // registration with no live supervisor yet (reads not-proven-alive →
        // redial on handoff, the safe direction).
        val liveSession: AtomicReference<SshSession?>? = null,
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

    private companion object {
        private const val TAG = "PsForwardingController"
    }
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
