package com.pocketshell.next.di

import android.content.Context
import androidx.room.Room
import com.pocketshell.core.storage.APP_DATABASE_MIGRATIONS
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
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
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
