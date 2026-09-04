package com.pocketshell.next.ports

import com.pocketshell.core.portfwd.AutoForwardConfig
import com.pocketshell.core.portfwd.AutoForwarderSupervisor
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.dao.ForwardingIntentDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.transport.AuthMaterial
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.HostConnectionFactory
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.TrustStore
import com.pocketshell.next.di.IoDispatcher
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's port-forwarding table: `hostId -> one running [AutoForwarderSupervisor]`
 * (rewrite task P-4).
 *
 * ## Why forwarding does NOT go through `ConnectionsRegistry`
 *
 * D21's forwarding carve-out, kept deliberately: a forward must outlive the
 * interactive connection's grace close. If forwards shared the registry's
 * one-connection-per-host instance, backgrounding the terminal (and letting its
 * grace timer fire) would silently kill every tunnel. So this class dials its own
 * connection per forwarding host, straight through [HostConnectionFactory] —
 * exactly what the old client's `PortForwardConnector` did, and the one place in
 * app2 that is allowed a second connection to a host.
 *
 * ## What is persisted, and where
 *
 * `hosts.enabled` is the durable intent: "forwarding is on for this host". It is
 * the only forwarding state that survives process death, which is what makes
 * [resumeEnabled] a complete answer to "what should be running?" — the service
 * re-reads that column instead of keeping its own registry.
 *
 * Per-port opt-ins ([togglePort]) are deliberately session-scoped, held by the
 * supervisor's desired-state set: they survive reconnects (their whole point) but
 * not a process restart, where re-discovery re-derives the useful ones anyway.
 *
 * ## No authority/barrier machinery
 *
 * The old client wrapped this in ~900 lines of notification-mutation authority,
 * stop authority, close barrier and observe-generation fencing. Its replacement
 * is: one mutex around the table, one snapshot [StateFlow] the notification and
 * the screen both read, and a per-host collector job that dies with its host.
 */
@Singleton
class ForwardingController @Inject constructor(
    private val hostDao: HostDao,
    private val forwardingIntentDao: ForwardingIntentDao,
    private val remappingDao: PortRemappingDao,
    private val connectionFactory: HostConnectionFactory,
    private val trustStore: TrustStore,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** One forwarding host, as the notification and the screen see it. */
    data class HostForwarding(
        val hostId: Long,
        val hostName: String,
        val connection: AutoForwarderSupervisor.ConnectionState,
        val tunnels: List<TunnelInfo> = emptyList(),
    ) {
        val forwardingCount: Int
            get() = tunnels.count { it.status == TunnelInfo.Status.FORWARDING }
    }

    private class ActiveHost(
        val supervisor: AutoForwarderSupervisor,
        val jobs: List<Job>,
    )

    /**
     * Owns the supervisors and their collectors. Application-scoped on purpose:
     * a forward has to survive the screen that started it (that is the feature),
     * and the foreground service is what keeps the process around.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mutex = Mutex()
    private val active = mutableMapOf<Long, ActiveHost>()

    private val _snapshot = MutableStateFlow<List<HostForwarding>>(emptyList())

    /** Every forwarding host, ordered by host name. Empty means nothing is running. */
    val snapshot: StateFlow<List<HostForwarding>> = _snapshot.asStateFlow()

    /** Tunnels for one host, or an empty list when it is not forwarding. */
    fun tunnels(hostId: Long): List<TunnelInfo> =
        _snapshot.value.firstOrNull { it.hostId == hostId }?.tunnels.orEmpty()

    /** True while a supervisor is mounted for [hostId]. */
    suspend fun isRunning(hostId: Long): Boolean = mutex.withLock { hostId in active }

    /**
     * Turns forwarding on for [hostId]: records the durable intent and mounts a
     * supervisor. Idempotent — a second call while one is running is a no-op, so
     * the screen and the service's resume can both call it freely.
     */
    suspend fun start(hostId: Long) {
        val host = hostDao.getById(hostId) ?: return
        if (!host.enabled) hostDao.update(host.copy(enabled = true))
        mutex.withLock {
            if (hostId in active) return@withLock
            active[hostId] = mount(host)
        }
    }

    /** Turns forwarding off for [hostId]: clears the intent and tears the supervisor down. */
    suspend fun stop(hostId: Long) {
        hostDao.getById(hostId)?.let { host ->
            if (host.enabled) hostDao.update(host.copy(enabled = false))
        }
        val removed = mutex.withLock { active.remove(hostId) }
        removed?.let { unmount(hostId, it) }
    }

    /**
     * The notification's Stop action: clears every enabled host in ONE Room
     * statement (so the persisted scope matches what the aggregate notification
     * promises) and tears every supervisor down.
     */
    suspend fun stopAll() {
        forwardingIntentDao.disableAll()
        val removed = mutex.withLock {
            val all = active.toMap()
            active.clear()
            all
        }
        removed.forEach { (hostId, host) -> unmount(hostId, host) }
    }

    /**
     * Re-mounts a supervisor for every host whose durable intent is on. This is
     * the whole of "resume on foreground": the enabled column IS the state, so
     * there is nothing to reconcile and nothing that can drift.
     *
     * Returns the number of hosts now forwarding, so the caller (the service) can
     * stop itself when there is no work.
     */
    suspend fun resumeEnabled(): Int {
        hostDao.getEnabled().first().forEach { host ->
            mutex.withLock {
                if (host.id !in active) active[host.id] = mount(host)
            }
        }
        return mutex.withLock { active.size }
    }

    /**
     * Forwards a per-port opt-in/opt-out to the host's supervisor. A no-op when
     * the host is not forwarding: there is no per-port state to record without a
     * running supervisor to hold it.
     */
    suspend fun togglePort(hostId: Long, remotePort: Int) {
        val supervisor = mutex.withLock { active[hostId]?.supervisor }
        supervisor?.togglePort(remotePort)
    }

    /** Hint that the network changed — every mounted supervisor retries now. */
    suspend fun reconnectNow() {
        mutex.withLock { active.values.toList() }.forEach { it.supervisor.reconnectNow() }
    }

    // ----------------------------------------------------------------- internals

    private suspend fun mount(host: HostEntity): ActiveHost {
        val supervisor = AutoForwarderSupervisor(
            connectionFactory = { dial(host.id) },
            config = host.toAutoForwardConfig(),
            initialRemappings = remappingDao.getByHostId(host.id).first()
                .associate { it.remotePort to it.localPort },
        )
        publish(host.id, host.name, AutoForwarderSupervisor.ConnectionState.Connecting, emptyList())
        val jobs = listOf(
            supervisor.start(scope),
            scope.launch {
                supervisor.flowOfTunnels().collect { tunnels ->
                    updateHost(host.id) { it.copy(tunnels = tunnels) }
                }
            },
            scope.launch {
                supervisor.flowOfConnectionState().collect { state ->
                    updateHost(host.id) { it.copy(connection = state) }
                }
            },
        )
        return ActiveHost(supervisor, jobs)
    }

    private fun unmount(hostId: Long, host: ActiveHost) {
        host.supervisor.stop()
        host.jobs.forEach { it.cancel() }
        _snapshot.update { current -> current.filterNot { it.hostId == hostId } }
    }

    /**
     * The forwarding dial. Anything that is not a live connection is thrown, so
     * the supervisor's backoff owns the retry: a host whose key still needs
     * confirming, or one that is simply unreachable, must not be a silent no-op
     * that leaves the row saying "Connecting" forever.
     */
    private suspend fun dial(hostId: Long): HostConnection {
        val host = hostDao.getById(hostId) ?: throw IOException("No host row for id $hostId")
        return when (val result = connectionFactory.connect(host.toTarget(), trustStore)) {
            is ConnectResult.Connected -> result.connection
            // Trust is a decision the user makes once, on the screen that owns it
            // (U-2's trust sheet). Forwarding never answers a host-key question —
            // two writers to the trust store is exactly what that screen prevents.
            is ConnectResult.NeedsTrust -> throw IOException(
                "${host.name}: host key needs confirmation — connect from the host list first",
            )

            is ConnectResult.Failed -> throw IOException(result.message, result.cause)
        }
    }

    private fun publish(
        hostId: Long,
        hostName: String,
        state: AutoForwarderSupervisor.ConnectionState,
        tunnels: List<TunnelInfo>,
    ) {
        _snapshot.update { current ->
            (current.filterNot { it.hostId == hostId } + HostForwarding(hostId, hostName, state, tunnels))
                .sortedBy { it.hostName }
        }
    }

    /**
     * Updates one host's row IF it is still published. A collector emission that
     * lands after [unmount] must not resurrect a row for a host the user just
     * turned off — the filter-and-map here is what makes that impossible without
     * any generation fencing.
     */
    private fun updateHost(hostId: Long, transform: (HostForwarding) -> HostForwarding) {
        _snapshot.update { current ->
            current.map { if (it.hostId == hostId) transform(it) else it }
        }
    }

    private companion object {
        fun HostEntity.toAutoForwardConfig(): AutoForwardConfig = AutoForwardConfig(
            scanIntervalSec = scanIntervalSec.coerceAtLeast(1),
            maxAutoPort = maxAutoPort,
            skipPortsBelow = skipPortsBelow,
        )

        /**
         * Auth is always [AuthMaterial.KeyRef]: `hosts.keyId` is a non-null FK to
         * `ssh_keys` and the schema has no password column, so
         * [AuthMaterial.Password] has no producer in the current data model.
         */
        fun HostEntity.toTarget(): HostTarget = HostTarget(
            hostId = id,
            hostname = hostname,
            port = port,
            username = username,
            auth = AuthMaterial.KeyRef(keyId),
        )
    }
}
