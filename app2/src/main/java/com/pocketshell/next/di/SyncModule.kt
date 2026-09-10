package com.pocketshell.next.di

import android.content.Context
import com.pocketshell.next.sync.AndroidKeystoreSyncTokenStore
import com.pocketshell.next.sync.AuthorizationLauncher
import com.pocketshell.next.sync.CustomTabsAuthorizationLauncher
import com.pocketshell.next.sync.GoogleAuth
import com.pocketshell.next.sync.HttpUrlConnectionSyncClient
import com.pocketshell.next.sync.SyncApiClient
import com.pocketshell.next.sync.SyncHttpClient
import com.pocketshell.next.sync.SyncRepository
import com.pocketshell.next.sync.SyncSelectionStore
import com.pocketshell.next.sync.SyncSignInCoordinator
import com.pocketshell.next.sync.SyncTokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The optional Google-login settings sync (issue #2633).
 *
 * Its own module rather than more bindings in [AppModule], for the same reason
 * `VoiceModule` is separate: an instrumented journey can `@UninstallModules`
 * the sync graph and script a signed-in account without also uninstalling the
 * database and the connection stack.
 *
 * Everything here is a `@Singleton` and that matters twice. [GoogleAuth] holds
 * the in-flight PKCE verifier, and [SyncSignInCoordinator] is reached from an
 * activity the settings screen does not own — a second instance of either
 * would be a sign-in whose redirect arrives at a stranger.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideSyncTokenStore(@ApplicationContext context: Context): SyncTokenStore =
        AndroidKeystoreSyncTokenStore(context)

    @Provides
    @Singleton
    fun provideSyncHttpClient(): SyncHttpClient = HttpUrlConnectionSyncClient()

    @Provides
    @Singleton
    fun provideGoogleAuth(
        tokens: SyncTokenStore,
        http: SyncHttpClient,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): GoogleAuth = GoogleAuth(tokens = tokens, http = http, dispatcher = dispatcher)

    @Provides
    @Singleton
    fun provideAuthorizationLauncher(): AuthorizationLauncher = CustomTabsAuthorizationLauncher()

    @Provides
    @Singleton
    fun provideSyncSignInCoordinator(
        auth: GoogleAuth,
        launcher: AuthorizationLauncher,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): SyncSignInCoordinator = SyncSignInCoordinator(
        auth = auth,
        launcher = launcher,
        // Process-scoped on purpose: the token exchange must outlive both the
        // redirect activity that triggers it and the settings screen that
        // started it. A SupervisorJob so one failed exchange cannot cancel a
        // later retry's scope.
        scope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    @Provides
    @Singleton
    fun provideSyncApiClient(
        auth: GoogleAuth,
        http: SyncHttpClient,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): SyncApiClient = SyncApiClient(auth = auth, http = http, dispatcher = dispatcher)

    @Provides
    @Singleton
    fun provideSyncSelectionStore(@ApplicationContext context: Context): SyncSelectionStore =
        SyncSelectionStore(context)

    @Provides
    @Singleton
    fun provideSyncRepository(
        api: SyncApiClient,
        selection: SyncSelectionStore,
    ): SyncRepository = SyncRepository(api, selection)
}
