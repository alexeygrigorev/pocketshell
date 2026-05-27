package com.pocketshell.app.share

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.tmux.TmuxClientFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point exposing the singletons that share-target connected
 * tests need to drive the issue #193 paste-into-session journey
 * end-to-end.
 *
 * **Why this lives in `main`** — see the rationale in
 * [com.pocketshell.app.testaccess.TestAccessEntryPoint]: an `@EntryPoint`
 * defined under `androidTest/` is never folded into the production
 * Hilt graph and fails with a `ClassCastException` at runtime. The
 * narrowest reachable seam is a `main`-resident interface that the
 * connected test calls via [dagger.hilt.android.EntryPointAccessors].
 *
 * **Scope** — this is intentionally a `:share`-local entry point. The
 * paste journey needs:
 *
 *  - [ActiveTmuxClients] so the test can register a live tmux client
 *    against the same registry the production [ShareViewModel] observes.
 *    Building a fresh `ActiveTmuxClients()` instance bypasses the
 *    Hilt-shared singleton and the ViewModel never sees the
 *    registration.
 *  - [TmuxClientFactory] so the test can build a real `tmux -CC`
 *    client against the deterministic Docker fixture, mirroring the
 *    pattern in [com.pocketshell.app.proof.TmuxExternalUpdateDockerTest].
 *
 * The interface is `internal` so it is only visible to code inside
 * this `:app` Gradle module — the share connected test is the only
 * consumer.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ShareTestAccessEntryPoint {
    fun activeTmuxClients(): ActiveTmuxClients
    fun tmuxClientFactory(): TmuxClientFactory
}
