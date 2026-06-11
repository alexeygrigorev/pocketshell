package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Identity of the host + credential a per-action SSH exec runs against —
 * issue #699. Carries exactly the fields needed to build a [SshLeaseKey] that
 * is BYTE-IDENTICAL to the one the session screens, folder discovery, and the
 * host-sessions gateway already use
 * ([com.pocketshell.app.sessions.SshHostTmuxSessionsGateway] /
 * [com.pocketshell.app.projects.SshFolderListGateway]).
 *
 * The lease key MUST match those by construction so a borrow here reuses the
 * app-wide `@Singleton` [SshLeaseManager]'s WARM transport for the host instead
 * of dialing a fresh ~3-4s SSH handshake per action. The credential id is
 * `"$hostId:$keyPath"` and the known-hosts id is the fixed `"accept-all"`,
 * exactly as the existing gateways encode them.
 */
data class LeaseSessionTarget(
    val hostId: Long,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val passphrase: CharArray?,
) {
    internal fun toSshLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$hostId:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    // CharArray breaks data-class structural equality; the lease identity is
    // fully captured by the non-secret fields, so compare on those.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LeaseSessionTarget) return false
        return hostId == other.hostId &&
            hostname == other.hostname &&
            port == other.port &&
            username == other.username &&
            keyPath == other.keyPath
    }

    override fun hashCode(): Int {
        var result = hostId.hashCode()
        result = 31 * result + hostname.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + keyPath.hashCode()
        return result
    }
}

/**
 * "Borrow a session from the lease" — issue #699.
 *
 * The shared acquire → exec → release helper the three per-call-open-close
 * offenders (`StartDirectoryAutocompleteRemoteSource`, `RealAssistantSshExecutor`,
 * `SshEnvGateway`) route through instead of doing a raw
 * [com.pocketshell.core.ssh.SshConnection.connect] / `close` per action.
 *
 * Each of those surfaces used to dial a brand-new SSH connection (the full
 * 3-4s handshake) for EVERY action — the worst being the autocomplete probe,
 * which handshaked on every keystroke. This helper instead acquires a
 * reference-counted lease on the app-wide `@Singleton` [SshLeaseManager],
 * runs the caller's exec on a channel of the already-warm transport, and
 * RELEASES the refcount when done. It deliberately NEVER closes the transport
 * — releasing only decrements the refcount so the pooled connection stays warm
 * for the next action / session open (the lease manager owns idle-expiry).
 *
 * Lifecycle / robustness mirrors the [com.pocketshell.app.projects.SshFolderListGateway]
 * template:
 *
 *  - The release runs under [NonCancellable] so a cancelled coroutine still
 *    returns the lease (a leaked refcount would pin the transport open forever).
 *  - On a stale-channel symptom (the pooled transport silently died / refuses a
 *    channel / `isConnected` lied), the poisoned lease is EVICTED — not just
 *    released — and the borrow is RETRIED ONCE on a fresh transport, so a
 *    transient corpse heals instead of failing the action.
 */
object LeaseSessionExec {

    /**
     * Acquire a refcounted lease for [target], run [block] on the warm
     * transport's session, then release the refcount (without closing the
     * transport). On a connect failure returns `Result.failure`; on a
     * stale-channel symptom evicts the corpse and retries ONCE on a fresh
     * lease.
     */
    suspend fun <T> withSession(
        leaseManager: SshLeaseManager,
        target: LeaseSessionTarget,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val leaseTarget = target.toSshLeaseTarget()
        val firstAttempt = runAttempt(leaseManager, leaseTarget, block)
        val firstError = firstAttempt.exceptionOrNull()
        if (firstError == null || !isStaleChannelSymptom(firstError)) {
            return firstAttempt
        }
        // The eviction inside runAttempt already discarded the poisoned
        // transport, so this second acquire dials a FRESH connection.
        return runAttempt(leaseManager, leaseTarget, block)
    }

    private suspend fun <T> runAttempt(
        leaseManager: SshLeaseManager,
        leaseTarget: SshLeaseTarget,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val lease = try {
            leaseManager.acquire(leaseTarget).getOrElse { return Result.failure(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        var poisonedTransport = false
        return try {
            Result.success(block(lease.session))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A dead-transport / channel-open / "not connected" failure must
            // EVICT the pooled lease, not just release it, so the retry / next
            // action opens a fresh transport instead of re-grabbing the corpse.
            poisonedTransport = isStaleChannelSymptom(t)
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                lease.release()
                if (poisonedTransport) {
                    runCatching { leaseManager.disconnect(leaseTarget.leaseKey) }
                }
            }
        }
    }

    /**
     * The family of transient probe failures that must HEAL + RETRY on a fresh
     * lease rather than surface a persistent error. Mirrors
     * [com.pocketshell.app.projects.SshFolderListGateway] /
     * [com.pocketshell.app.tmux.TmuxSessionViewModel] stale-channel detection.
     */
    internal fun isStaleChannelSymptom(cause: Throwable?): Boolean =
        isChannelOpenFailure(cause) ||
            isTransportDisconnected(cause) ||
            isSessionNotConnected(cause)

    private fun isSessionNotConnected(cause: Throwable?): Boolean =
        anyInChain(cause) { message ->
            message.contains("SSH session is not connected", ignoreCase = true) ||
                message.contains("transport endpoint is not connected", ignoreCase = true)
        }

    private fun isChannelOpenFailure(cause: Throwable?): Boolean =
        anyInChain(cause) { message ->
            message.contains("open failed", ignoreCase = true) ||
                message.contains("failed to open SSH shell", ignoreCase = true)
        }

    private fun isTransportDisconnected(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current.javaClass.simpleName == "TransportException") {
                val reasonName = runCatching {
                    current!!.javaClass.getMethod("getDisconnectReason").invoke(current)?.toString()
                }.getOrNull()
                if (reasonName != null && reasonName.contains("BY_APPLICATION", ignoreCase = true)) {
                    return true
                }
                val message = current.message
                if (message != null &&
                    (
                        message.contains("BY_APPLICATION", ignoreCase = true) ||
                            message.contains("Disconnected", ignoreCase = true)
                        )
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private inline fun anyInChain(cause: Throwable?, predicate: (String) -> Boolean): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null && predicate(message)) return true
            current = current.cause
        }
        return false
    }
}
