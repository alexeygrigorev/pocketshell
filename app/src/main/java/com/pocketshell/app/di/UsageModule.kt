package com.pocketshell.app.di

import android.content.Context
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.usage.DefaultUsageNotifier
import com.pocketshell.app.usage.HostUsageFetcher
import com.pocketshell.app.usage.SshHostUsageFetcher
import com.pocketshell.app.usage.SharedPreferencesUsageNotificationStateStore
import com.pocketshell.app.usage.UsageNotifier
import com.pocketshell.app.usage.UsageRemoteSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
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
 *
 * Issue #116 (usage-panel Fix B): the [com.pocketshell.app.usage.UsageScheduler]
 * singleton now needs an explicit [UsageRemoteSource] binding because
 * the source's Kotlin default-arg `@Inject` constructor generates two
 * constructors at the bytecode level, which Hilt refuses. Tests still
 * construct the source directly via its no-arg path.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UsageModule {

    @Binds
    @Singleton
    abstract fun bindHostUsageFetcher(impl: SshHostUsageFetcher): HostUsageFetcher
}

/**
 * Companion `@Provides` module sitting alongside [UsageModule] (Hilt
 * does not accept a `companion object` reference as a module, but two
 * top-level modules in the same package compose cleanly).
 */
@Module
@InstallIn(SingletonComponent::class)
object UsageProvidersModule {
    @Provides
    @Singleton
    fun provideUsageRemoteSource(): UsageRemoteSource = UsageRemoteSource()

    @Provides
    @Singleton
    fun provideUsageNotifier(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ): UsageNotifier = DefaultUsageNotifier(
        context = context,
        settingsRepository = settingsRepository,
        stateStore = SharedPreferencesUsageNotificationStateStore(context),
    )
}
