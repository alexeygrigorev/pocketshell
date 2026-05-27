package com.pocketshell.app.projects

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt binding for [FolderListGateway] — issue #171.
 *
 * Mirrors the
 * [com.pocketshell.app.sessions.HostTmuxSessionsModule] pattern so the
 * `:projects` package owns its gateway wiring instead of leaking
 * into the `:sessions` module that already serves the picker-sheet
 * code path.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FolderListGatewayModule {
    @Binds
    abstract fun bindFolderListGateway(
        gateway: SshFolderListGateway,
    ): FolderListGateway
}
