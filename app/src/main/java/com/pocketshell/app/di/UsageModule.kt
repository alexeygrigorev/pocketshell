package com.pocketshell.app.di

import com.pocketshell.app.usage.HostUsageFetcher
import com.pocketshell.app.usage.SshHostUsageFetcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Usage / quota panel (issue #114 Fix A).
 *
 * Binds the production [SshHostUsageFetcher] to the [HostUsageFetcher]
 * surface so [com.pocketshell.app.usage.UsageViewModel] receives the
 * SSH-backed implementation in the running app. Tests construct the view
 * model directly with their own fake fetcher.
 *
 * `SshHostUsageFetcher` is `@Singleton` — it is stateless and reuses the
 * singleton [com.pocketshell.core.storage.dao.SshKeyDao]; no per-call
 * state to dispose.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UsageModule {

    @Binds
    @Singleton
    abstract fun bindHostUsageFetcher(impl: SshHostUsageFetcher): HostUsageFetcher
}
