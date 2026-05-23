package com.pocketshell.app.sessions

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class HostTmuxSessionsModule {
    @Binds
    abstract fun bindHostTmuxSessionsGateway(
        gateway: SshHostTmuxSessionsGateway,
    ): HostTmuxSessionsGateway
}
