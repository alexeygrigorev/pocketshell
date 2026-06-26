package com.pocketshell.app.portfwd

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Qualifies the `Dispatchers.Default` [CoroutineDispatcher] so port-forward
 * components (notably
 * [com.pocketshell.app.portfwd.service.ForwardingService]) can inject — and a
 * test can override — the dispatcher backing their background coroutines
 * instead of hard-wiring a bare `Dispatchers.Default` that no test can cancel.
 *
 * Issue #994: the un-injectable `Dispatchers.Default` scope let the service's
 * notification-observe coroutine outlive a Robolectric unit test and crash the
 * NEXT test with an `UncaughtExceptionsBeforeTest` NPE.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class PortForwardModule {
    @Binds
    abstract fun bindPortForwardConnector(
        connector: DefaultPortForwardConnector,
    ): PortForwardConnector

    companion object {
        @Provides
        @DefaultDispatcher
        fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    }
}
