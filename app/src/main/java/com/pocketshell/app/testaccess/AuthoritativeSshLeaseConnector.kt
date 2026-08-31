package com.pocketshell.app.testaccess

import androidx.annotation.VisibleForTesting
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshLeaseTargetResolver
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.app.ssh.hostKeyTrustBinding
import com.pocketshell.app.ssh.HostKeyTrustPromptRouter
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The app singleton's physical SSH connector, with a narrowly scoped connected-test fault seam.
 *
 * Production always delegates. A connected journey may hold manager-new acquisitions for the
 * exact last-observed lease key unavailable; that exercises the same connector authority owned
 * by [com.pocketshell.core.ssh.SshLeaseManager], unlike a ViewModel-local reconnect flag.
 */
internal class AuthoritativeSshLeaseConnector(
    private val delegate: SshLeaseConnector = DefaultSshLeaseConnector(),
    private val hostDao: HostDao? = null,
    private val trustPromptRouter: HostKeyTrustPromptRouter? = null,
) : SshLeaseConnector, SshLeaseTargetResolver {
    private val lastObservedKey = AtomicReference<SshLeaseKey?>(null)
    private val activeOutage = AtomicReference<LeaseOutageForTest?>(null)
    private val nextOutageId = AtomicLong(1L)

    override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
        lastObservedKey.set(target.leaseKey)
        activeOutage.get()?.takeIf { it.leaseKey == target.leaseKey }?.let { outage ->
            return blocked(outage)
        }
        val result = delegate.connect(target)
        result.exceptionOrNull()?.let { failure ->
            trustPromptRouter?.report(target.hostIdOrNull(), failure)
        }
        // Fence a dial that entered the physical delegate just before the test armed the
        // outage. It must not become a manager-owned fresh lease during the offline interval.
        activeOutage.get()?.takeIf { it.leaseKey == target.leaseKey }?.let { outage ->
            result.getOrNull()?.let { session -> runCatching { session.close() } }
            return blocked(outage)
        }
        return result
    }

    private fun SshLeaseTarget.hostIdOrNull(): Long? =
        leaseKey.credentialId.substringBefore(':').toLongOrNull()

    override suspend fun resolveTarget(target: SshLeaseTarget): SshLeaseTarget {
        val hostId = target.leaseKey.credentialId.substringBefore(':').toLongOrNull() ?: return target
        val host = hostDao?.getById(hostId) ?: return target
        if (!host.hostname.equals(target.leaseKey.host, ignoreCase = true) ||
            host.port != target.leaseKey.port
        ) return target
        val binding = host.hostKeyTrustBinding()
        return target.copy(
            leaseKey = target.leaseKey.copy(knownHostsId = binding.leaseIdentity),
            knownHosts = binding.policy,
        )
    }

    private fun blocked(outage: LeaseOutageForTest): Result<SshSession> {
        val attempt = outage.blockedAttempts.incrementAndGet()
        return Result.failure(
            IOException("authoritative lease outage ${outage.id} blocked attempt $attempt"),
        )
    }

    @VisibleForTesting
    internal fun beginSustainedOutageForLastLeaseForTest(): LeaseOutageForTest {
        val key = requireNotNull(lastObservedKey.get()) {
            "cannot arm lease outage before the singleton connector observes a target"
        }
        val outage = LeaseOutageForTest(nextOutageId.getAndIncrement(), key)
        check(activeOutage.compareAndSet(null, outage)) { "a lease outage is already active" }
        return outage
    }

    @VisibleForTesting
    internal fun endSustainedOutageForTest(outage: LeaseOutageForTest) {
        check(activeOutage.compareAndSet(outage, null)) {
            "cannot end a stale or inactive lease outage"
        }
    }

    @VisibleForTesting
    internal fun resetOutageForTest() {
        activeOutage.set(null)
    }

    internal class LeaseOutageForTest internal constructor(
        internal val id: Long,
        internal val leaseKey: SshLeaseKey,
    ) {
        internal val blockedAttempts = AtomicInteger(0)
        internal val blockedAttemptCount: Int
            get() = blockedAttempts.get()
    }
}
