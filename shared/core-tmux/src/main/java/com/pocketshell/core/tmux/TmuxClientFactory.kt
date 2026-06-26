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
     * @param createIfMissing Issue #666 — attach-OR-create (`true`, default) vs
     *   attach-only (`false`). The default `true` keeps the explicit user
     *   "new/create session" intent and reconnect-to-live behaviour, which use
     *   `new-session -A` so a missing session is created. Pass `false` on the
     *   foreground cold-restore path so [TmuxClient.connect] runs a
     *   `tmux has-session` preflight and throws [TmuxSessionNotFoundException]
     *   for a session killed elsewhere instead of resurrecting it.
     */
    @JvmOverloads
    public fun create(
        session: SshSession,
        sessionName: String = DEFAULT_SESSION_NAME,
        startDirectory: String? = null,
        createIfMissing: Boolean = true,
        // Issue #998: when this is a reattach to an expected-existing session (a
        // reconnect / lifecycle / network-reconnect), pass `true` so
        // [TmuxClient.connect] probes whether the tmux SERVER is still alive and
        // throws [TmuxServerDeadException] for `no server running` instead of
        // silently resurrecting an empty server via `new-session -A`. The
        // default `false` preserves the explicit user "new session" intent.
        probeServerLiveness: Boolean = false,
    ): TmuxClient = RealTmuxClient(
        session = session,
        scope = scope,
        sessionName = sessionName,
        startDirectory = startDirectory,
        createIfMissing = createIfMissing,
        probeServerLiveness = probeServerLiveness,
    )

    private companion object {
        // Mirror RealTmuxClient's default so both sides agree without
        // either side reaching across the visibility boundary.
        private const val DEFAULT_SESSION_NAME = "pocketshell"
    }
}
