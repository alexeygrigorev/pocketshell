package com.pocketshell.next.connect

import android.util.Log
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.transport.AuthMaterial
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.HostConnectionFactory
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.TransportState
import com.pocketshell.core.transport.TrustStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's one-connection-per-host table (rewrite task M-3).
 *
 * This is the whole connection manager: a `hostId -> HostConnection` map, one
 * mutex, and the dial. No leases, no refcounts, no shadow state, no reconnect
 * supervisor — the deliberate replacement for the old `SshLeaseManager` /
 * `ConnectionCoordinator` stack (plan §A.3 / D28: prefer a clean model over
 * another shim).
 *
 * ## Liveness
 *
 * A [HostConnection] never self-heals: once its state is
 * [TransportState.Lost] or [TransportState.Closed] the instance is spent. So
 * [getOrConnect] treats a stored-but-dead entry as absent — it closes it
 * best-effort, drops it, and dials a fresh one. Callers therefore never have
 * to check liveness themselves before using what they got back.
 *
 * ## Serialization
 *
 * ONE [Mutex], held across the whole read-check-dial-store sequence, so two
 * concurrent `getOrConnect(sameId)` calls can never both dial: the second
 * parks until the first has stored its connection, then sees it live and
 * returns the SAME instance. That also serializes dials to *different* hosts,
 * which is fine and intentional — a dial happens on a user tap, never in a
 * loop, and one mutex is far easier to reason about than a lock map. If
 * cross-host dial concurrency ever matters, swap in a per-hostId lock map
 * behind this same signature.
 *
 * ## Trust
 *
 * [ConnectResult.NeedsTrust] and [ConnectResult.Failed] are passed straight
 * through to the caller (the U-2 trust sheet decides what to do). The one
 * thing the registry does add is re-wrapping `NeedsTrust.retry` so the retry
 * runs through [getOrConnect] again instead of the factory directly —
 * otherwise a successful post-trust retry would produce a connection the
 * registry does not know about, and the next [getOrConnect] would dial a
 * second one. The wrapped lambda is invoked by the caller *after*
 * [getOrConnect] has returned, so it re-enters the mutex unlocked (no
 * re-entrancy deadlock).
 */
class ConnectionsRegistry(
    private val factory: HostConnectionFactory,
    private val trustStore: TrustStore,
    private val hostDao: HostDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    /**
     * Concurrent map because [current] is a non-suspending read that must not
     * block behind an in-flight dial. All *mutations* still happen under
     * [mutex], so the map only ever gains a connection the mutex holder just
     * created.
     */
    private val connections = ConcurrentHashMap<Long, HostConnection>()

    /**
     * Membership of [connections], as a flow, so [liveHostIds] can react.
     *
     * Deliberately only MEMBERSHIP: whether a member is still live is the
     * member's own `state` flow, which [liveHostIds] folds in. Publishing a
     * pre-computed live set here instead would go stale the moment a
     * connection dropped without anyone calling the registry, which is exactly
     * the "shadow state that disagrees with the transport" shape D28 exists to
     * keep out of this file.
     */
    private val membership = MutableStateFlow<Map<Long, HostConnection>>(emptyMap())

    /**
     * Returns the live connection for [hostId], dialing one if there is none
     * (or the stored one is dead).
     *
     * Never throws for an unreachable host or an unknown host row — those come
     * back as [ConnectResult.Failed].
     */
    suspend fun getOrConnect(hostId: Long): ConnectResult = mutex.withLock {
        withContext(dispatcher) {
            Log.i(TAG, "registry dial start host=$hostId")
            val existing = connections[hostId]
            if (existing != null) {
                if (existing.state.value.isLive()) {
                    Log.i(TAG, "registry reused live connection host=$hostId")
                    return@withContext ConnectResult.Connected(existing)
                }
                // Spent instance: drop it before dialing so a failed dial can
                // never leave a dead connection behind for `current()` to hand
                // out.
                connections.remove(hostId)
                publishMembership()
                runCatching { existing.close() }
            }

            val host = hostDao.getById(hostId)
                ?: return@withContext ConnectResult.Failed("No host row for id $hostId", null)

            val target = host.toTarget()
            val result = factory.connect(target, trustStore)
            Log.i(TAG, "registry factory result host=$hostId ${result.summary()}")
            when (result) {
                is ConnectResult.Connected -> {
                    connections[hostId] = result.connection
                    publishMembership()
                    result
                }

                is ConnectResult.NeedsTrust -> ConnectResult.NeedsTrust(
                    decision = result.decision,
                    retry = {
                        Log.i(TAG, "registry post-trust retry entered host=$hostId")
                        getOrConnect(hostId).also {
                            Log.i(TAG, "registry post-trust retry result host=$hostId ${it.summary()}")
                        }
                    },
                )

                is ConnectResult.Failed -> result
            }
        }
    }

    /**
     * Records [sha256] as the trusted host key for [hostId], returning false
     * when there is no such host row.
     *
     * Lives here (task U-2) because [TrustStore.recordTrusted] is keyed by
     * [HostTarget] and this class already owns the `hostId -> HostTarget`
     * mapping. Without it every caller answering a trust prompt would have to
     * rebuild a target from the host row by hand — three call sites away from
     * the dial, and free to build a *different* one.
     *
     * Deliberately NOT under [mutex]: it touches only the trust store, never
     * the connection table, and it is called immediately before a retry that
     * does take the mutex.
     */
    suspend fun recordTrusted(hostId: Long, sha256: String): Boolean =
        withContext(dispatcher) {
            val host = hostDao.getById(hostId) ?: return@withContext false
            trustStore.recordTrusted(host.toTarget(), sha256)
            Log.i(TAG, "registry recorded trust host=$hostId fingerprint=$sha256")
            true
        }

    /**
     * The live connection for [hostId], or null when there is none (or the
     * stored one is spent). Does not dial.
     */
    fun current(hostId: Long): HostConnection? =
        connections[hostId]?.takeIf { it.state.value.isLive() }

    /**
     * Every connection that is still usable, in no particular order. Does not
     * dial (task U-8: [com.pocketshell.next.terminal.GraceCoordinator] arms one
     * bounded delayed close per entry when the app is backgrounded).
     *
     * "Usable" is [TransportState.Connecting] or [TransportState.Connected] —
     * the same liveness [current] uses — rather than `Connected` alone: a dial
     * still in flight when the user leaves the app would otherwise have no grace
     * armed at all, and would then be held open with no bound once it landed.
     */
    fun liveConnections(): List<HostConnection> =
        connections.values.filter { it.state.value.isLive() }

    /**
     * The ids whose connection is live right now, as a flow (#2635 2a).
     *
     * The host list paints a leading [com.pocketshell.uikit.components.StatusDot]
     * from this so "which host is warm" is answerable BEFORE tapping — the
     * desktop client's host picker has had that dot since day one and the phone
     * shipped a list with no status at all.
     *
     * Reactive on both axes: [membership] re-emits when the registry gains or
     * loses an entry, and each entry's own `state` re-emits when its transport
     * drops, so a connection dying on its own turns the dot off without anyone
     * touching the registry. It NEVER dials — this is a pre-connection screen
     * (D21) and a status dot that opened SSH sessions would be a bug, not a
     * glance.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun liveHostIds(): Flow<Set<Long>> = membership.flatMapLatest { entries ->
        if (entries.isEmpty()) {
            flowOf(emptySet())
        } else {
            val perHost = entries.map { (hostId, connection) ->
                connection.state.map { hostId to it.isLive() }
            }
            combine(perHost) { pairs ->
                pairs.filter { it.second }.map { it.first }.toSet()
            }
        }
    }.distinctUntilChanged()

    private fun publishMembership() {
        membership.value = connections.toMap()
    }

    /** Closes and removes the connection for [hostId], if one is currently held. */
    suspend fun close(hostId: Long) = mutex.withLock {
        withContext(dispatcher) {
            connections.remove(hostId)?.let { runCatching { it.close() } }
            publishMembership()
        }
    }

    /** Closes every connection and empties the table. Safe to call twice. */
    suspend fun closeAll() = mutex.withLock {
        withContext(dispatcher) {
            val open = connections.values.toList()
            connections.clear()
            publishMembership()
            open.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        const val TAG = "PocketShell.Connect"

        fun ConnectResult.summary(): String = when (this) {
            is ConnectResult.Connected -> "connected"
            is ConnectResult.NeedsTrust -> "needs-trust/${decision::class.simpleName}"
            is ConnectResult.Failed -> "failed/${cause?.javaClass?.simpleName ?: "no-cause"}"
        }

        fun TransportState.isLive(): Boolean = when (this) {
            TransportState.Connecting, TransportState.Connected -> true
            // Both terminal states are spent, whatever the close's reason: a
            // grace-expired connection is as unusable as a requested-close one
            // (issue #2487), and the caller gets a fresh dial either way.
            is TransportState.Lost, is TransportState.Closed -> false
        }

        /**
         * Builds the dial target from the stored host row.
         *
         * Auth is always [AuthMaterial.KeyRef]: `hosts.keyId` is a non-null FK
         * to `ssh_keys`, and the schema has NO password-auth column, so
         * [AuthMaterial.Password] has no producer in the current data model.
         * That is a schema gap, not an omission here — adding password auth
         * means adding a column first.
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
