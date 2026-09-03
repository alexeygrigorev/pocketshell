package com.pocketshell.next.share

import android.content.Context
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Share-target bindings (rewrite task P-9).
 *
 * Its own module rather than three more `@Provides` in `di.AppModule`: the share
 * feature is self-contained (it consumes the registry and nothing else consumes
 * it), and keeping its wiring next to its code means a future decision to drop
 * or re-home the feature deletes one directory instead of editing the app's
 * central module.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule {

    @Provides
    @Singleton
    fun provideShareContentReader(
        @ApplicationContext context: Context,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): ShareContentReader = ContentResolverShareContentReader(context, dispatcher)

    @Provides
    @Singleton
    fun provideShareUploader(
        registry: ConnectionsRegistry,
        content: ShareContentReader,
    ): ShareUploader = ShareUploader(registry, content)

    @Provides
    @Singleton
    fun provideShareUploadNotifier(
        @ApplicationContext context: Context,
    ): ShareUploadNotifier = ShareUploadNotifier(context)
}
