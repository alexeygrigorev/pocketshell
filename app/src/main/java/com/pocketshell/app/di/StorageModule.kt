package com.pocketshell.app.di

import android.content.Context
import androidx.room.Room
import com.pocketshell.core.storage.APP_DATABASE_MIGRATIONS
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.PortUsageDao
import com.pocketshell.core.storage.dao.ProjectRootDao
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
        buildDatabase(context)

    private fun buildDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME,
        )
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .build()

    @Provides
    fun provideHostDao(db: AppDatabase): HostDao = db.hostDao()

    @Provides
    fun provideProjectRootDao(db: AppDatabase): ProjectRootDao = db.projectRootDao()

    @Provides
    fun provideSshKeyDao(db: AppDatabase): SshKeyDao = db.sshKeyDao()

    // Issue #17: snippet library DAO consumed by SnippetsViewModel.
    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()

    // Issue #181: per-call cost log consumed by CostsViewModel and the
    // Whisper instrumentation in VoiceModule.
    @Provides
    fun provideAiApiCallLogDao(db: AppDatabase): AiApiCallLogDao = db.aiApiCallLogDao()

    // Issue #180: failed-transcription retry queue consumed by
    // PendingTranscriptionStore in the app layer.
    @Provides
    fun providePendingTranscriptionDao(db: AppDatabase): PendingTranscriptionDao =
        db.pendingTranscriptionDao()

    // Issue #203 expanded scope: ported from `ssh-auto-forward-android`.
    // `provideAppDatabase` already wires the migration that creates the
    // `port_remappings` (v1) and `port_usage` (v10) tables; here we just
    // surface the DAOs so the port-forward panel + AutoForwarder can read
    // and write them via the new bridge interfaces in
    // `com.pocketshell.core.portfwd`.
    @Provides
    fun providePortRemappingDao(db: AppDatabase): PortRemappingDao = db.portRemappingDao()

    @Provides
    fun providePortUsageDao(db: AppDatabase): PortUsageDao = db.portUsageDao()
}
