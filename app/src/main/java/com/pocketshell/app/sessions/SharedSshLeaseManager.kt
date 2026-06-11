package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager

/**
 * Process-wide accessor for the app's `@Singleton` [SshLeaseManager] — issue
 * #699.
 *
 * The Hilt-injected singleton (provided by
 * [com.pocketshell.app.di.SshLeaseModule]) is the ONE warm SSH transport pool
 * per host that the session screens, folder discovery, and host-sessions
 * gateway all share. Some seams that need to borrow from that pool are NOT
 * Hilt-constructed — notably [com.pocketshell.app.assistant.RealAssistantSshExecutor],
 * which is built field-side inside view models with no DI graph access. This
 * holder lets those seams reach the SAME singleton so an assistant tool call
 * reuses the host's warm transport instead of dialing a fresh handshake.
 *
 * [register] is called once when the singleton is provided. If a seam runs
 * before registration (e.g. a unit test constructing the executor directly),
 * [get] falls back to a lazily-created standalone manager so behavior degrades
 * to "still warm-leased, just not pool-shared" rather than crashing.
 */
object SharedSshLeaseManager {
    @Volatile
    private var registered: SshLeaseManager? = null

    @Volatile
    private var fallback: SshLeaseManager? = null

    /** Record the app-wide singleton so non-DI seams can borrow from it. */
    fun register(manager: SshLeaseManager) {
        registered = manager
    }

    /**
     * The app-wide singleton when registered, else a process-stable standalone
     * manager (created once, reused) so non-DI callers never crash.
     */
    fun get(): SshLeaseManager {
        registered?.let { return it }
        return fallback ?: synchronized(this) {
            fallback ?: SshLeaseManager(
                connector = SshLeaseConnector { target -> DefaultSshLeaseConnector().connect(target) },
            ).also { fallback = it }
        }
    }
}
