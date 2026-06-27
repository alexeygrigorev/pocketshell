package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshExecTimeoutException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    val leasePurpose: String? = null,
) {
    internal fun toSshLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = credentialId(),
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
            keyPath == other.keyPath &&
            leasePurpose == other.leasePurpose
    }

    override fun hashCode(): Int {
        var result = hostId.hashCode()
        result = 31 * result + hostname.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + keyPath.hashCode()
        result = 31 * result + (leasePurpose?.hashCode() ?: 0)
        return result
    }

    private fun credentialId(): String {
        val base = "$hostId:$keyPath"
        val purpose = leasePurpose?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return "$base|purpose=$purpose"
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
    ): Result<T> = withSession(leaseManager, target, BLOCK_TIMEOUT_MS, block)

    /**
     * Overload that lets the caller (and tests) override the wall-clock ceiling
     * on [block] (#935 S4-2). Production callers use the default
     * [BLOCK_TIMEOUT_MS]; tests inject a short value so the wedged-block bound
     * can be exercised without a real multi-second wait.
     */
    suspend fun <T> withSession(
        leaseManager: SshLeaseManager,
        target: LeaseSessionTarget,
        blockTimeoutMs: Long?,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val leaseTarget = target.toSshLeaseTarget()
        val firstAttempt = runAttempt(leaseManager, leaseTarget, blockTimeoutMs, block)
        val firstError = firstAttempt.exceptionOrNull()
        if (firstError == null || !isStaleChannelSymptom(firstError)) {
            return firstAttempt
        }
        // The eviction inside runAttempt already discarded the poisoned
        // transport, so this second acquire dials a FRESH connection.
        return runAttempt(leaseManager, leaseTarget, blockTimeoutMs, block)
    }

    private suspend fun <T> runAttempt(
        leaseManager: SshLeaseManager,
        leaseTarget: SshLeaseTarget,
        blockTimeoutMs: Long?,
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
            // #935 S4-2: the lease bounded only the ACQUIRE — `block` (the exec /
            // upload / download work on the warm transport) ran with NO ceiling.
            // A half-open / wedged transport hung the borrow indefinitely while a
            // ref kept the lease warm (the 60s idle-TTL could never close a
            // still-referenced corpse). Bound the block itself: on expiry the
            // wedged transport is a corpse, so treat it as a stale-channel symptom
            // — evict it (below) + surface a clear, retryable error — exactly like
            // a dead-channel exception. `withTimeoutOrNull` cancels the block; the
            // session-level read bound (`RealSshSession.exec` #935 S4-2) is what
            // actually unparks the in-flight blocking JDK read, but this outer
            // bound also covers a block that does several ops or a non-exec
            // (upload/download) hang and keeps the lease from being pinned open.
            //
            // `block` itself may legitimately return `null`, so we wrap its
            // result in a one-element holder: a `null` from `withTimeoutOrNull`
            // means the TIMEOUT fired, never a null block result. The block's own
            // exceptions propagate OUT of `withTimeoutOrNull` to the `catch`
            // below (we do NOT `runCatching` inside, which would swallow the
            // timeout's own CancellationException and defeat the bound).
            val holder = if (blockTimeoutMs == null) {
                Holder(block(lease.session))
            } else {
                withTimeoutOrNull(blockTimeoutMs) { Holder(block(lease.session)) }
            }
            if (holder == null) {
                poisonedTransport = true
                Result.failure(LeaseSessionBlockTimeoutException(blockTimeoutMs ?: BLOCK_TIMEOUT_MS))
            } else {
                Result.success(holder.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A dead-transport / channel-open / "not connected" /
            // wedged-read-timeout failure must EVICT the pooled lease, not just
            // release it, so the retry / next action opens a fresh transport
            // instead of re-grabbing the corpse.
            poisonedTransport = isStaleChannelSymptom(t)
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                lease.release()
                if (poisonedTransport) {
                    runCatching { leaseManager.evictIdle(leaseTarget.leaseKey) }
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
        isWedgedReadTimeout(cause) ||
            isChannelOpenFailure(cause) ||
            isTransportDisconnected(cause) ||
            isSessionNotConnected(cause)

    /**
     * A wedged-read / wedged-block timeout (#935 S4-2) is a stale-channel
     * symptom: the transport stopped making progress, so the borrow must heal +
     * retry on a FRESH lease rather than re-grab the corpse. Covers both the
     * session-level [SshExecTimeoutException] (the `exec` read blew its ceiling)
     * and the lease-level [LeaseSessionBlockTimeoutException] (the whole block
     * blew its ceiling).
     */
    private fun isWedgedReadTimeout(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is SshExecTimeoutException || current is LeaseSessionBlockTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

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

    /**
     * Wall-clock ceiling for the borrowed-session [block] (#935 S4-2). The lease
     * previously bounded only the acquire; a wedged transport could hang the
     * borrow indefinitely while a ref kept the lease warm. 45s is generous for
     * the heaviest in-scope block (a file-viewer save = `mkdir` + a multi-MB
     * `uploadStream`) yet bounds an indefinite hang. It sits ABOVE the
     * session-level [com.pocketshell.core.ssh.RealSshSession] exec read bound
     * (30s) so a single wedged exec surfaces its own clear
     * [SshExecTimeoutException] FIRST; this outer bound is the net for a block
     * that strings several ops or wedges on a non-exec (upload/download) hang.
     */
    internal const val BLOCK_TIMEOUT_MS: Long = 45_000L

    /**
     * One-element holder so a `null` from `withTimeoutOrNull` unambiguously means
     * the timeout fired (vs a [block] that legitimately returned `null`).
     */
    private class Holder<T>(val value: T)
}

/**
 * Thrown (wrapped in `Result.failure`) by [LeaseSessionExec.withSession] when
 * the borrowed-session block does not complete within
 * [LeaseSessionExec.BLOCK_TIMEOUT_MS] (#935 S4-2). The wedged transport is
 * evicted so the next borrow re-dials a fresh connection; the action surfaces a
 * clear, retryable error instead of hanging forever.
 */
class LeaseSessionBlockTimeoutException(
    val timeoutMs: Long,
) : Exception("SSH lease-session block wedged >${timeoutMs}ms (no progress)")
