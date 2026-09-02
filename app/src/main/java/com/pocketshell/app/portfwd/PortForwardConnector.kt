package com.pocketshell.app.portfwd

import com.pocketshell.app.ssh.hostKeyTrustBinding
import com.pocketshell.app.ssh.HostKeyTrustPromptRouter
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import java.io.File
import javax.inject.Inject

interface PortForwardConnector {
    suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession>
}

internal class DefaultPortForwardConnector @Inject constructor(
    private val trustPromptRouter: HostKeyTrustPromptRouter,
) : PortForwardConnector {
    override suspend fun connect(host: HostEntity, keyPath: String, passphrase: CharArray?): Result<SshSession> {
        val result = host.hostKeyTrustBinding().let { trust -> SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = trust.policy,
        ) }
        result.exceptionOrNull()?.let { failure -> trustPromptRouter.report(host.id, failure) }
        // Issue #2463: same as the lease connector — a success clears the card
        // annotation. The forwarding RESUME scheduler and the reconnect ladder
        // both land here without a user watching, so the report above may only
        // annotate; the port-forward PANEL brackets its user-initiated start
        // with `withUserInitiatedConnect` to keep reaching the trust screen.
        if (result.isSuccess) trustPromptRouter.clearTrustAttention(host.id)
        return result
    }
}
