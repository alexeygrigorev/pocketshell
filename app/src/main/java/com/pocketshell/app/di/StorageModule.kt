package com.pocketshell.app.di

import android.content.Context
import androidx.room.Room
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.dao.SshKeyDao
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
            // No migrations exist yet — Phase 1 ships with version 1. When the
            // schema evolves, replace this with explicit migrations and switch
            // `exportSchema = true` in :shared:core-storage so they are
            // reviewable. `dropAllTables = false` is the safer default; if a
            // schema mismatch surfaces, Room throws rather than silently
            // wiping user data.
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
