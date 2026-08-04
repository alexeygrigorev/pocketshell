package com.pocketshell.app.di

import com.pocketshell.app.sessions.SharedSshLeaseManager
import com.pocketshell.app.testaccess.AuthoritativeSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SshLeaseModule {
    @Provides
    @Singleton
    internal fun provideAuthoritativeSshLeaseConnector(): AuthoritativeSshLeaseConnector =
        AuthoritativeSshLeaseConnector()

    @Provides
    @Singleton
    internal fun provideSshLeaseConnector(connector: AuthoritativeSshLeaseConnector): SshLeaseConnector =
        connector

    @Provides
    @Singleton
    fun provideSshLeaseManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(connector = connector).also {
            // Issue #699: expose the app-wide singleton to non-DI seams (e.g.
            // RealAssistantSshExecutor, built field-side in view models) so an
            // assistant tool call borrows from the SAME warm transport pool the
            // session screens use instead of dialing a fresh handshake.
            SharedSshLeaseManager.register(it)
        }
}
