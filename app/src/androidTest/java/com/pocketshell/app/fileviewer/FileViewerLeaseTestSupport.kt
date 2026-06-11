package com.pocketshell.app.fileviewer

import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared connected-test helpers for the file viewer's warm-lease path
 * (issue #697). The viewer borrows a session from an app-wide
 * [SshLeaseManager] instead of cold-connecting per open, so these tests build
 * a manager (optionally one that counts real handshakes) to feed the view-model.
 */

/** A plain lease manager that dials real SSH handshakes via the default connector. */
internal fun realLeaseManager(): SshLeaseManager =
    SshLeaseManager(connector = DefaultSshLeaseConnector())

/**
 * A lease manager whose [handshakeCount] increments on every real cold SSH
 * handshake. A warm lease already held for the same key means the viewer reuses
 * it and the counter does not advance — proving zero per-open handshakes.
 */
internal class CountingLeaseManager {
    val handshakeCount = AtomicInteger(0)
    val manager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            handshakeCount.incrementAndGet()
            DefaultSshLeaseConnector().connect(target)
        },
    )
}
