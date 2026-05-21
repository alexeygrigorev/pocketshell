package com.pocketshell.app.di

import android.content.Context
import androidx.room.Room
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for `:shared:core-storage` consumed by the app module.
 *
 * The database file lives under `databases/pocketshell.db` in the app's
 * private storage. Single-instance Singleton scope — Room is expensive to
 * construct and is safe to share across all ViewModels.
 *
 * Issue #18 is the first consumer (host + key DAOs). Future issues that
 * need other DAOs (sessions, snippets, agent sessions, port remappings)
 * add their `@Provides` lines here.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    private const val DATABASE_NAME: String = "pocketshell.db"

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME,
        )
            // Issue #49 introduced schema v2 (host-bootstrap cache columns).
            // The migration is hand-rolled in `core-storage/migrations` so the
            // SQL is explicit and unit-testable. We still keep
            // `fallbackToDestructiveMigration` as a backstop for the
            // pre-release era — if a schema mismatch surfaces from a route
            // we haven't covered, Room would otherwise throw and brick the
            // app. `dropAllTables = false` keeps user data on the safer
            // path; once we ship to real users this fallback should be
            // removed and missing migrations should hard-fail.
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()

    @Provides
    fun provideHostDao(db: AppDatabase): HostDao = db.hostDao()

    @Provides
    fun provideSshKeyDao(db: AppDatabase): SshKeyDao = db.sshKeyDao()

    // Issue #17: snippet library DAO consumed by SnippetsViewModel.
    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()
}
