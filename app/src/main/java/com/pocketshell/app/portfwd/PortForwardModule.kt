package com.pocketshell.app.portfwd

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PortForwardModule {
    @Binds
    abstract fun bindPortForwardConnector(
        connector: DefaultPortForwardConnector,
    ): PortForwardConnector
}
