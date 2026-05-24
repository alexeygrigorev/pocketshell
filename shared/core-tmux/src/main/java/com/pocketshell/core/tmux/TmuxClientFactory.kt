package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CoroutineScope

/**
 * Constructs [TmuxClient] instances bound to a caller-supplied
 * [CoroutineScope].
 *
 * Single-method, no-state — kept as a class (not a top-level function) so
 * Hilt can inject it, and so the construction details ([RealTmuxClient]
 * being package-internal, scope-passing conventions) stay out of caller
 * code. The factory itself takes no SSH-specific state; callers pass an
 * already-connected [SshSession] per call so the same factory can spin up
 * clients against multiple hosts.
 *
 * Typical Hilt wiring:
 *
 * ```kotlin
 * @Module
 * @InstallIn(SingletonComponent::class)
 * object TmuxModule {
 *   @Provides @Singleton
 *   fun tmuxClientFactory(
 *     @ApplicationScope scope: CoroutineScope,
 *   ): TmuxClientFactory = TmuxClientFactory(scope)
 * }
 * ```
 *
 * @param scope the coroutine scope that backs each created client's
 *   reader loop. Typically an application- or session-scoped supervisor;
 *   when it cancels, all clients created from this factory tear down.
 */
public class TmuxClientFactory(
    private val scope: CoroutineScope,
) {

    /**
     * Build a new [TmuxClient] for [session]. The returned client is NOT
     * connected — call [TmuxClient.connect] before issuing commands. We
     * separate construction from connection so callers can wire up
     * collectors on [TmuxClient.events] before the first event arrives.
     *
     * @param session SSH transport, must already be connected. The
     *   returned client takes a logical reference but does NOT own the
     *   session — [TmuxClient.close] tears down only the tmux shell
     *   channel, leaving the session usable for further work.
     * @param sessionName tmux session name to attach to or create. Pass a
     *   distinct value when running multiple PocketShell clients against
     *   the same host. Defaults to `"pocketshell"`.
     * @param startDirectory optional tmux `-c` start directory for newly
     *   created sessions.
     */
    @JvmOverloads
    public fun create(
        session: SshSession,
        sessionName: String = DEFAULT_SESSION_NAME,
        startDirectory: String? = null,
    ): TmuxClient = RealTmuxClient(
        session = session,
        scope = scope,
        sessionName = sessionName,
        startDirectory = startDirectory,
    )

    private companion object {
        // Mirror RealTmuxClient's default so both sides agree without
        // either side reaching across the visibility boundary.
        private const val DEFAULT_SESSION_NAME = "pocketshell"
    }
}
