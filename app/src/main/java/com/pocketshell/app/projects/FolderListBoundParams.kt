package com.pocketshell.app.projects

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import java.io.File

internal data class BoundParams(
    val hostId: Long,
    val hostName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val passphrase: CharArray?,
    /**
     * Issue #2455: the host's confirmed SSH server-key fingerprint
     * ([com.pocketshell.core.storage.entity.HostEntity.trustedHostKeySha256]).
     * `null` only while genuinely unresolved (never for a host that has been
     * connected to before, since first-connect trust confirmation writes this
     * column). [toSshLeaseTarget] falls back to the "unconfirmed" lease
     * identity in that case, which never matches a real fingerprint's lease
     * key, so an unresolved bind cannot silently ride a stale trusted lease.
     */
    val trustedHostKeySha256: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BoundParams) return false
        if (hostId != other.hostId) return false
        if (hostName != other.hostName) return false
        if (hostname != other.hostname) return false
        if (port != other.port) return false
        if (username != other.username) return false
        if (keyPath != other.keyPath) return false
        if (trustedHostKeySha256 != other.trustedHostKeySha256) return false
        if (passphrase != null) {
            if (other.passphrase == null) return false
            if (!passphrase.contentEquals(other.passphrase)) return false
        } else if (other.passphrase != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = hostId.hashCode()
        result = 31 * result + hostName.hashCode()
        result = 31 * result + hostname.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + keyPath.hashCode()
        result = 31 * result + (trustedHostKeySha256?.hashCode() ?: 0)
        result = 31 * result + (passphrase?.contentHashCode() ?: 0)
        return result
    }

    /**
     * Issue #2455: build the lease target from the RESOLVED fingerprint, not
     * an always-unconfirmed one (the original bug — see [FolderListTreeSyncRemote],
     * the only production caller, which resolves [trustedHostKeySha256] fresh
     * from [com.pocketshell.core.storage.dao.HostDao] before calling this and
     * passes it through via [withTrustedHostKeySha256]). Mirrors
     * `LeaseSessionTarget.toSshLeaseTarget()` in
     * [com.pocketshell.app.sessions.LeaseSessionExec].
     */
    fun toSshLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$hostId:$keyPath",
                knownHostsId = trustedHostKeySha256?.let { "host-key:$it" }
                    ?: SshLeaseManager.UNCONFIRMED_HOST_KEY_ID,
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.VerifiedFingerprint(trustedHostKeySha256),
        )

    /**
     * Issue #2455: return a copy carrying the freshly-resolved fingerprint,
     * without going through the full [copy] constructor call sites would
     * otherwise need to spell out (keeps the immutable-passphrase [CharArray]
     * shared, not re-cloned, since it is never mutated).
     */
    fun withTrustedHostKeySha256(value: String?): BoundParams =
        if (value == trustedHostKeySha256) this else copy(trustedHostKeySha256 = value)
}
