package com.pocketshell.app.di

import com.pocketshell.core.ssh.DefaultSshLeaseConnector
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
    fun provideSshLeaseConnector(): SshLeaseConnector = DefaultSshLeaseConnector()

    @Provides
    @Singleton
    fun provideSshLeaseManager(connector: SshLeaseConnector): SshLeaseManager =
        SshLeaseManager(connector = connector)
}
