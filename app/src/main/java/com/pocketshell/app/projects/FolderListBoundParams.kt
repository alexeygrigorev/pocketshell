package com.pocketshell.app.projects

import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
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
        result = 31 * result + (passphrase?.contentHashCode() ?: 0)
        return result
    }

    fun toSshLeaseTarget(): SshLeaseTarget =
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
}
