package com.pocketshell.app.di

import android.content.Context
import com.pocketshell.app.notifications.DefaultUpdateNotifier
import com.pocketshell.app.notifications.UpdateNotifier
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.UpdateCheckStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * Issue #698: also supplies the [UpdateCheckStore] (throttle ledger) and
 * the production [UpdateNotifier] so Hilt can construct the singleton
 * `UpdateCheckScheduler` (its `@Inject` constructor depends on both; Kotlin
 * default values are invisible to Dagger, so explicit bindings are
 * required).
 */
@Module
@InstallIn(SingletonComponent::class)
object ReleaseModule {

    @Provides
    @Singleton
    fun provideReleaseChecker(): ReleaseChecker = ReleaseChecker()

    @Provides
    @Singleton
    fun provideUpdateCheckStore(
        @ApplicationContext context: Context,
    ): UpdateCheckStore = UpdateCheckStore(context)

    @Provides
    @Singleton
    fun provideUpdateNotifier(
        @ApplicationContext context: Context,
    ): UpdateNotifier = DefaultUpdateNotifier(context)
}
