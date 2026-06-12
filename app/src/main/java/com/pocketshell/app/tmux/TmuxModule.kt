package com.pocketshell.app.tmux

import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.tmux.TmuxClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt bindings for `:shared:core-tmux` consumed by [TmuxSessionViewModel].
 *
 * The factory takes a [CoroutineScope] that backs the reader-loop coroutine
 * of every client it creates — see [TmuxClientFactory]'s KDoc. A
 * Singleton-scoped application-wide [SupervisorJob] + [Dispatchers.IO]
 * scope is the right fit: the reader loop is bounded by the SSH transport
 * (closing the [com.pocketshell.core.tmux.TmuxClient] cancels its internal
 * child scope), and we don't want a per-host scope here because the factory
 * is reused across hosts.
 *
 * Issue #45 is the first consumer; future tmux-aware features (the workspace
 * dashboard's all-sessions stream, the jobs panel, the agent-aware
 * conversation view) all consume the same factory.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object TmuxModule {

    /**
     * Marks the application-wide [CoroutineScope] used to back the
     * `tmux -CC` reader loops in [TmuxClientFactory]. Kept private to this
     * module so other DI bindings (which might want an unrelated scope)
     * cannot pull this one in by accident.
     */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    internal annotation class TmuxApplicationScope

    @Provides
    @Singleton
    @TmuxApplicationScope
    fun provideTmuxApplicationScope(): CoroutineScope =
        // SupervisorJob so a failure in one client's reader does not
        // cascade into other live clients' readers. Dispatchers.IO matches
        // the blocking-stream nature of the underlying SSH input — see
        // RealTmuxClient.clientScope for the upstream rationale.
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideTmuxClientFactory(
        @TmuxApplicationScope scope: CoroutineScope,
    ): TmuxClientFactory = TmuxClientFactory(scope)

    /**
     * Issue #576: the agent-conversation tail/ingest repository consumed by
     * [TmuxSessionViewModel]. Its constructor is `internal` (only this module
     * builds it) and unscoped here so each ViewModel gets a fresh instance —
     * preserving the prior `private val agentRepository = AgentConversationRepository()`
     * field semantics now that it is a constructor-injected parameter.
     */
    @Provides
    fun provideAgentConversationRepository(): AgentConversationRepository =
        AgentConversationRepository()

    // Issue #46 / cross-host session dashboard: the
    // [com.pocketshell.app.sessions.ActiveTmuxClients] registry is
    // `@Inject constructor()`-able + `@Singleton`, so Hilt discovers
    // and shares the instance app-wide without an explicit
    // `@Provides` binding here. Kept as a comment so the next reader
    // wiring something into the tmux graph knows where to look for it.
}
