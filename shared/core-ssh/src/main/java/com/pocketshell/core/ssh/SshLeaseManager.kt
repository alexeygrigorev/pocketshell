package com.pocketshell.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-scoped, DI-ready SSH connection lease pool keyed by host and credential
 * identity.
 *
 * A live [SshSession] may be shared only while callers hold leases for the
 * exact same [SshLeaseKey]. Releasing the final lease keeps the connection
 * warm for [idleTtlMillis], then closes it if no caller reacquires it. The
 * idle retention cap bounds how many unused transports can sit open at once.
 */
public class SshLeaseManager(
    private val connector: SshLeaseConnector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val idleTtlMillis: Long = DEFAULT_IDLE_TTL_MILLIS,
    private val maxIdleLeases: Int = DEFAULT_MAX_IDLE_LEASES,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {
    private val mutex = Mutex()
    private val entries: MutableMap<SshLeaseKey, Entry> = linkedMapOf()
    private var closed: Boolean = false
    private var nextEntryId: Long = 1L

    init {
        require(idleTtlMillis >= 0) { "idleTtlMillis must be >= 0" }
        require(maxIdleLeases >= 0) { "maxIdleLeases must be >= 0" }
    }

    public suspend fun acquire(target: SshLeaseTarget): Result<SshLease> {
        val key = target.leaseKey
        val existing = mutex.withLock {
            if (closed) return Result.failure(SshLeaseManagerClosedException())
            entries[key]?.takeIf { it.session.isConnected }?.also { entry ->
                entry.closeJob?.cancel()
                entry.closeJob = null
                entry.idleSinceMillis = null
                entry.refCount += 1
            }
        }
        if (existing != null) {
            return Result.success(
                SshLease(
                    key = key,
                    session = existing.session,
                    entryId = existing.id,
                    releaseAction = ::release,
                ),
            )
        }

        mutex.withLock {
            entries.remove(key)?.close()
        }

        val session = connector.connect(target).getOrElse { return Result.failure(it) }
        return mutex.withLock {
            if (closed) {
                runCatching { session.close() }
                return@withLock Result.failure(SshLeaseManagerClosedException())
            }
            val raced = entries[key]
            if (raced != null && raced.session.isConnected) {
                runCatching { session.close() }
                raced.closeJob?.cancel()
                raced.closeJob = null
                raced.idleSinceMillis = null
                raced.refCount += 1
                Result.success(
                    SshLease(
                        key = key,
                        session = raced.session,
                        entryId = raced.id,
                        releaseAction = ::release,
                    ),
                )
            } else {
                raced?.close()
                val entry = Entry(id = nextEntryId++, key = key, session = session, refCount = 1)
                entries[key] = entry
                Result.success(
                    SshLease(
                        key = key,
                        session = session,
                        entryId = entry.id,
                        releaseAction = ::release,
                    ),
                )
            }
        }
    }

    public suspend fun closeIdle() {
        val idleEntries = mutex.withLock {
            entries.values
                .filter { it.refCount == 0 }
                .also { idle -> idle.forEach { entries.remove(it.key) } }
        }
        idleEntries.forEach { it.close() }
    }

    override fun close() {
        val toClose = mutableListOf<Entry>()
        runCatching {
            kotlinx.coroutines.runBlocking {
                mutex.withLock {
                    closed = true
                    toClose += entries.values
                    entries.clear()
                }
            }
        }
        toClose.forEach { it.close() }
    }

    private suspend fun release(key: SshLeaseKey, entryId: Long) {
        val closeNow = mutex.withLock {
            val entry = entries[key] ?: return
            if (entry.id != entryId) return
            if (entry.refCount <= 0) return
            entry.refCount -= 1
            if (entry.refCount > 0) return
            if (!entry.session.isConnected || idleTtlMillis == 0L || maxIdleLeases == 0) {
                entries.remove(key)
                return@withLock entry
            }

            entry.idleSinceMillis = nowMillis()
            entry.closeJob?.cancel()
            entry.closeJob = scope.launch {
                delay(idleTtlMillis)
                closeIfStillIdle(key, entryId)
            }
            trimIdleLocked()
        }
        closeNow?.close()
    }

    private suspend fun closeIfStillIdle(key: SshLeaseKey, entryId: Long) {
        val expired = mutex.withLock {
            val entry = entries[key] ?: return
            if (entry.id != entryId) return
            if (entry.refCount != 0) return
            entries.remove(key)
        }
        expired?.close()
    }

    private fun trimIdleLocked(): Entry? {
        val idle = entries.values
            .filter { it.refCount == 0 }
            .sortedBy { it.idleSinceMillis ?: Long.MAX_VALUE }
        if (idle.size <= maxIdleLeases) return null
        val oldest = idle.first()
        entries.remove(oldest.key)
        return oldest
    }

    private class Entry(
        val id: Long,
        val key: SshLeaseKey,
        val session: SshSession,
        var refCount: Int,
        var idleSinceMillis: Long? = null,
        var closeJob: Job? = null,
    ) {
        fun close() {
            closeJob?.cancel()
            closeJob = null
            runCatching { session.close() }
        }
    }

    public companion object {
        public const val DEFAULT_IDLE_TTL_MILLIS: Long = 60_000L
        public const val DEFAULT_MAX_IDLE_LEASES: Int = 2
    }
}

public data class SshLeaseKey(
    val host: String,
    val port: Int,
    val user: String,
    val credentialId: String,
    val knownHostsId: String = "accept-all",
)

public data class SshLeaseTarget(
    val leaseKey: SshLeaseKey,
    val key: SshKey,
    val passphrase: CharArray? = null,
    val knownHosts: KnownHostsPolicy = KnownHostsPolicy.AcceptAll,
    val timeoutMs: Int = SshConnection.DEFAULT_TIMEOUT_MS,
    val keepAliveSeconds: Int = SshConnection.DEFAULT_KEEP_ALIVE_SECONDS,
)

public fun interface SshLeaseConnector {
    public suspend fun connect(target: SshLeaseTarget): Result<SshSession>
}

public class DefaultSshLeaseConnector : SshLeaseConnector {
    override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
        SshConnection.connect(
            host = target.leaseKey.host,
            port = target.leaseKey.port,
            user = target.leaseKey.user,
            key = target.key,
            passphrase = target.passphrase?.copyOf(),
            knownHosts = target.knownHosts,
            timeoutMs = target.timeoutMs,
            keepAliveSeconds = target.keepAliveSeconds,
        )
}

public class SshLease internal constructor(
    public val key: SshLeaseKey,
    public val session: SshSession,
    private val entryId: Long,
    private val releaseAction: suspend (SshLeaseKey, Long) -> Unit,
) {
    private val releaseMutex = Mutex()
    private var released: Boolean = false

    public suspend fun release() {
        releaseMutex.withLock {
            if (released) return
            withContext(NonCancellable) {
                releaseAction(key, entryId)
            }
            released = true
        }
    }
}

public class SshLeaseManagerClosedException : IllegalStateException("SSH lease manager is closed")
