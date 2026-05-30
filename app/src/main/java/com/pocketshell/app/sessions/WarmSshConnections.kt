package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one host-level SSH connection PocketShell keeps warm while the
 * user is inside a host flow.
 *
 * The manager deliberately keeps a single entry, not a host map: issue #306's
 * lifecycle constraint is "currently relevant host", so warming another host
 * closes the previous one. Callers can either run short operations through
 * [withSession] while this class retains ownership, or [take] the live
 * transport when a tmux control client becomes the new owner.
 */
@Singleton
class WarmSshConnections @Inject constructor() {
    private var connector: SshConnector = SshConnector { target, passphrase ->
        SshOpenTelemetry.record(
            source = SSH_SOURCE_WARM_HOST_CONNECT,
            host = target.hostname,
            port = target.port,
            user = target.username,
        )
        SshConnection.connect(
            host = target.hostname,
            port = target.port,
            user = target.username,
            key = SshKey.Path(File(target.keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
    }

    internal constructor(connector: SshConnector) : this() {
        this.connector = connector
    }

    private val mutex = Mutex()
    private var entry: Entry? = null

    suspend fun warm(
        target: WarmSshTarget,
        passphrase: CharArray?,
    ): Result<Unit> = mutex.withLock {
        ensureLocked(target, passphrase).map { }
    }

    suspend fun <T> withSession(
        target: WarmSshTarget,
        passphrase: CharArray?,
        block: suspend (SshSession) -> T,
    ): Result<T> = mutex.withLock {
        val session = ensureLocked(target, passphrase).getOrElse { return@withLock Result.failure(it) }
        try {
            Result.success(block(session))
        } catch (e: CancellationException) {
            closeLocked(target)
            throw e
        } catch (t: Throwable) {
            if (!session.isConnected) closeLocked(target)
            throw t
        }
    }

    /**
     * Transfer the warm session to a tmux owner. Returns null when there is
     * no connected matching warm session, leaving the caller to perform its
     * normal cold connect path.
     */
    suspend fun take(target: WarmSshTarget): SshSession? = mutex.withLock {
        val current = entry ?: return@withLock null
        if (!current.target.matches(target)) return@withLock null
        if (!current.session.isConnected) {
            closeLocked(target)
            return@withLock null
        }
        entry = null
        current.session
    }

    suspend fun close(target: WarmSshTarget) {
        mutex.withLock {
            closeLocked(target)
        }
    }

    /**
     * Best-effort synchronous close for ViewModel teardown paths where the
     * caller cannot safely suspend. Returns false if a warm/connect/exec
     * operation is currently holding the session; that operation's coroutine
     * cancellation path will close the connection if it is interrupted.
     */
    fun closeIfIdle(target: WarmSshTarget): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            closeLocked(target)
            true
        } finally {
            mutex.unlock()
        }
    }

    suspend fun closeAll() {
        mutex.withLock {
            entry?.session?.let { runCatching { it.close() } }
            entry = null
        }
    }

    private suspend fun ensureLocked(
        target: WarmSshTarget,
        passphrase: CharArray?,
    ): Result<SshSession> {
        entry?.let { current ->
            if (current.target.matches(target) && current.session.isConnected) {
                return Result.success(current.session)
            }
            runCatching { current.session.close() }
            entry = null
        }

        val result = connector.connect(target, passphrase)
        val session = result.getOrElse { return Result.failure(it) }
        entry = Entry(target = target, session = session)
        return Result.success(session)
    }

    private fun closeLocked(target: WarmSshTarget) {
        val current = entry ?: return
        if (!current.target.matches(target)) return
        runCatching { current.session.close() }
        entry = null
    }

    private data class Entry(
        val target: WarmSshTarget,
        val session: SshSession,
    )
}

data class WarmSshTarget(
    val hostId: Long,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
) {
    fun matches(other: WarmSshTarget): Boolean =
        hostId == other.hostId &&
            hostname == other.hostname &&
            port == other.port &&
            username == other.username &&
            keyPath == other.keyPath
}

fun interface SshConnector {
    suspend fun connect(target: WarmSshTarget, passphrase: CharArray?): Result<SshSession>
}
