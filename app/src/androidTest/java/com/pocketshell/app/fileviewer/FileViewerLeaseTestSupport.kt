package com.pocketshell.app.fileviewer

import com.pocketshell.app.testaccess.AuthoritativeSshLeaseConnector
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.dao.HostDao
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared connected-test helpers for the file viewer's warm-lease path
 * (issue #697). The viewer borrows a session from an app-wide
 * [SshLeaseManager] instead of cold-connecting per open, so these tests build
 * a manager (optionally one that counts real handshakes) to feed the view-model.
 *
 * Issue #2458 (host-key-trust fixture gap): [FileViewerViewModel] has no
 * `hostDao` of its own — every consumer of these helpers relies ENTIRELY on
 * the lease manager's connector to resolve host-key trust at `acquire()`
 * time. Both helpers wrap the real connector in [AuthoritativeSshLeaseConnector]
 * (the same class production wires via Hilt in `app/di/SshLeaseModule.kt`) so
 * a caller that supplies [hostDao] gets the production trust-resolution path;
 * the default `hostDao = null` reproduces the exact prior (no-op passthrough)
 * behaviour, so existing callers that never verified a host key are
 * byte-for-byte unaffected.
 */

/** A plain lease manager that dials real SSH handshakes via the default connector. */
internal fun realLeaseManager(hostDao: HostDao? = null): SshLeaseManager =
    SshLeaseManager(
        connector = AuthoritativeSshLeaseConnector(
            delegate = DefaultSshLeaseConnector(),
            hostDao = hostDao,
        ),
    )

/**
 * A lease manager whose [handshakeCount] increments on every real cold SSH
 * handshake. A warm lease already held for the same key means the viewer reuses
 * it and the counter does not advance — proving zero per-open handshakes.
 */
internal class CountingLeaseManager(hostDao: HostDao? = null) {
    val handshakeCount = AtomicInteger(0)
    val manager: SshLeaseManager = SshLeaseManager(
        connector = AuthoritativeSshLeaseConnector(
            delegate = SshLeaseConnector { target ->
                handshakeCount.incrementAndGet()
                DefaultSshLeaseConnector().connect(target)
            },
            hostDao = hostDao,
        ),
    )
}
