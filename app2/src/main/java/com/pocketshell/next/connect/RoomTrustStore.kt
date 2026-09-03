package com.pocketshell.next.connect

import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.transport.HostTarget
import com.pocketshell.core.transport.TrustDecision
import com.pocketshell.core.transport.TrustStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TrustStore] backed by the existing `hosts` row (rewrite task M-3).
 *
 * Trust is stored INLINE on the host row — `trustedHostKeyAlgorithm` /
 * `trustedHostKeySha256` — not in a separate known-hosts table, so a host's
 * trusted key is deleted with the host and can never outlive it or be
 * mis-joined to another row. [HostTarget.hostId] IS the `hosts` row id, so the
 * lookup is by primary key; the hostname string is deliberately not consulted
 * (editing a host's address must not silently inherit the old server's trust —
 * it changes the presented key, which surfaces as a [TrustDecision.Mismatch]
 * the user has to answer).
 *
 * Fail-closed everywhere: a missing row, a null column, or a blank column all
 * mean "not trusted yet" ([TrustDecision.Unknown]), never "trusted".
 */
class RoomTrustStore(
    private val hostDao: HostDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TrustStore {

    override suspend fun evaluate(
        target: HostTarget,
        presentedSha256: String,
    ): TrustDecision = withContext(dispatcher) {
        val stored = hostDao.getById(target.hostId)?.trustedHostKeySha256
        when {
            stored.isNullOrBlank() -> TrustDecision.Unknown(presentedSha256)
            stored == presentedSha256 -> TrustDecision.Trusted
            else -> TrustDecision.Mismatch(storedSha256 = stored, presentedSha256 = presentedSha256)
        }
    }

    override suspend fun recordTrusted(target: HostTarget, sha256: String) {
        withContext(dispatcher) {
            val host = hostDao.getById(target.hostId) ?: return@withContext
            hostDao.update(
                host.copy(
                    trustedHostKeyAlgorithm = FINGERPRINT_DIGEST,
                    trustedHostKeySha256 = sha256,
                ),
            )
        }
    }

    companion object {
        /**
         * Value written to `hosts.trustedHostKeyAlgorithm`.
         *
         * The [TrustStore] contract only ever hands us a SHA-256 fingerprint
         * string (`SHA256:<base64>`, produced by
         * `RealHostConnectionFactory.sha256HostKeyFingerprint`) — the host
         * key's own algorithm name (`ssh-ed25519`, `rsa-sha2-512`, ...) is not
         * part of the contract and is not available at
         * [recordTrusted] time. So this column records the DIGEST algorithm of
         * the stored fingerprint, which is what the stored value actually is.
         * Nothing branches on it: [evaluate] compares `trustedHostKeySha256`
         * only. It exists so the persisted row is self-describing (and so a
         * future format change is detectable) rather than carrying a bare
         * opaque string.
         */
        const val FINGERPRINT_DIGEST = "SHA256"
    }
}
