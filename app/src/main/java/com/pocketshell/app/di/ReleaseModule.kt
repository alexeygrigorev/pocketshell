package com.pocketshell.app.di

import com.pocketshell.app.release.ReleaseChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the auto-update check (issue #40).
 *
 * Kept in its own module rather than landing in `StorageModule` because
 * `ReleaseChecker` has no relation to Room — it's a thin wrapper around
 * `HttpURLConnection`. Mixing the two would obscure the wiring story for
 * anyone reading the `:di` package.
 *
 * The checker holds no state and is `Singleton`-scoped so the
 * `HostListViewModel` and any future call sites share one instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReleaseModule {

    @Provides
    @Singleton
    fun provideReleaseChecker(): ReleaseChecker = ReleaseChecker()
}
