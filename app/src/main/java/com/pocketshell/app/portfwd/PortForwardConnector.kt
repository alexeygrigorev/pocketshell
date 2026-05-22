package com.pocketshell.app.portfwd

import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import java.io.File
import javax.inject.Inject

interface PortForwardConnector {
    suspend fun connect(host: HostEntity, keyPath: String): Result<SshSession>
}

class DefaultPortForwardConnector @Inject constructor() : PortForwardConnector {
    override suspend fun connect(host: HostEntity, keyPath: String): Result<SshSession> =
        SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
        )
}
