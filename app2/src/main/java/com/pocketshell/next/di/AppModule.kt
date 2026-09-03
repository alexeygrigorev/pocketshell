package com.pocketshell.next.di

import android.content.Context
import androidx.room.Room
import com.pocketshell.core.storage.APP_DATABASE_MIGRATIONS
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.ForwardingIntentDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.transport.AuthSecretResolver
import com.pocketshell.core.transport.HostConnectionFactory
import com.pocketshell.core.transport.RealHostConnectionFactory
import com.pocketshell.core.transport.TrustStore
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.RoomAuthSecretResolver
import com.pocketshell.next.connect.RoomTrustStore
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Marks the shared IO dispatcher. Screens/ViewModels take it as a constructor
 * parameter instead of touching [Dispatchers.IO] directly, so a unit test can
 * substitute a deterministic scheduler.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * app2's only DI module so far (plan §U-1): the Room database and the DAOs the
 * screens that exist actually consume, plus the IO dispatcher.
 *
 * The database file name matches the shipping client's (`pocketshell.db`) on
 * purpose — app2 reads the very same schema and, once X-4 renames the
 * `applicationId` to `com.pocketshell.app`, the very same file, so cutover is a
 * rename rather than a data migration. Until then app2 runs under its own
 * `applicationId` and therefore its own (initially empty) copy in its own
 * sandbox; that is a property of Android app sandboxing, not of this module.
 *
 * The migration array is wired even though app2 only reads: an install that
 * later becomes the primary app must open an existing v-N file rather than
 * fail Room's schema validation.
 *
 * A DAO is added here when a screen consumes it, not preemptively — the old
 * client's module provided nine and the rewrite's premise is that most of them
 * are never needed again.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val DATABASE_NAME: String = "pocketshell.db"

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .build()

    @Provides
    fun provideHostDao(db: AppDatabase): HostDao = db.hostDao()

    @Provides
    fun provideSshKeyDao(db: AppDatabase): SshKeyDao = db.sshKeyDao()

    // Task P-4. `forwarding_intent` is the aggregate "stop everything" statement
    // behind the notification's Stop action; `port_remappings` carries the user's
    // remote→local overrides into each forwarder.
    @Provides
    fun provideForwardingIntentDao(db: AppDatabase): ForwardingIntentDao = db.forwardingIntentDao()

    @Provides
    fun providePortRemappingDao(db: AppDatabase): PortRemappingDao = db.portRemappingDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    // -------------------------------------------------------------------------
    // The connection stack (task U-2). Four bindings, each one an interface
    // core-transport declares wired to the single implementation app2 has:
    // trust lives on the host row, key material lives in `ssh_keys` + the file
    // it points at, and the dial is sshj's.
    //
    // Every one of them is a @Singleton, and that is load-bearing for the
    // registry: a second ConnectionsRegistry instance would be a second
    // one-connection-per-host table, which is two connections per host — the
    // exact failure the registry exists to make impossible.

    @Provides
    @Singleton
    fun provideTrustStore(
        hostDao: HostDao,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): TrustStore = RoomTrustStore(hostDao, dispatcher)

    @Provides
    @Singleton
    fun provideAuthSecretResolver(
        sshKeyDao: SshKeyDao,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): AuthSecretResolver = RoomAuthSecretResolver(sshKeyDao, dispatcher)

    @Provides
    @Singleton
    fun provideHostConnectionFactory(
        secrets: AuthSecretResolver,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): HostConnectionFactory = RealHostConnectionFactory(secrets, dispatcher)

    @Provides
    @Singleton
    fun provideConnectionsRegistry(
        factory: HostConnectionFactory,
        trustStore: TrustStore,
        hostDao: HostDao,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): ConnectionsRegistry = ConnectionsRegistry(factory, trustStore, hostDao, dispatcher)

    /**
     * The host-CLI seam (task U-3). Deliberately NOT a `@Singleton` client: a
     * `HostCliClient` is bound to one live [com.pocketshell.core.transport.HostConnection]
     * and a connection is spent once its transport dies, so what is shared is
     * the *recipe*, and each screen builds a client over whatever connection
     * [ConnectionsRegistry] hands it at that moment.
     *
     * The binding itself is stateless, hence the singleton on the factory.
     */
    @Provides
    @Singleton
    fun provideHostCliClientFactory(): HostCliClientFactory =
        HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) }
}
