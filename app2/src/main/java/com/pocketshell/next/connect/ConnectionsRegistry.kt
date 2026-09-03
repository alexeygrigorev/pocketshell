package com.pocketshell.next.connect

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
     * Returns the live connection for [hostId], dialing one if there is none
     * (or the stored one is dead).
     *
     * Never throws for an unreachable host or an unknown host row — those come
     * back as [ConnectResult.Failed].
     */
    suspend fun getOrConnect(hostId: Long): ConnectResult = mutex.withLock {
        withContext(dispatcher) {
            val existing = connections[hostId]
            if (existing != null) {
                if (existing.state.value.isLive()) {
                    return@withContext ConnectResult.Connected(existing)
                }
                // Spent instance: drop it before dialing so a failed dial can
                // never leave a dead connection behind for `current()` to hand
                // out.
                connections.remove(hostId)
                runCatching { existing.close() }
            }

            val host = hostDao.getById(hostId)
                ?: return@withContext ConnectResult.Failed("No host row for id $hostId", null)

            when (val result = factory.connect(host.toTarget(), trustStore)) {
                is ConnectResult.Connected -> {
                    connections[hostId] = result.connection
                    result
                }

                is ConnectResult.NeedsTrust -> ConnectResult.NeedsTrust(
                    decision = result.decision,
                    retry = { getOrConnect(hostId) },
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
            true
        }

    /**
     * The live connection for [hostId], or null when there is none (or the
     * stored one is spent). Does not dial.
     */
    fun current(hostId: Long): HostConnection? =
        connections[hostId]?.takeIf { it.state.value.isLive() }

    /** Closes every connection and empties the table. Safe to call twice. */
    suspend fun closeAll() = mutex.withLock {
        withContext(dispatcher) {
            val open = connections.values.toList()
            connections.clear()
            open.forEach { runCatching { it.close() } }
        }
    }

    private companion object {
        fun TransportState.isLive(): Boolean = when (this) {
            TransportState.Connecting, TransportState.Connected -> true
            is TransportState.Lost, TransportState.Closed -> false
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
