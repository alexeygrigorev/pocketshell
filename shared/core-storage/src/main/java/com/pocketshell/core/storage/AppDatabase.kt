package com.pocketshell.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pocketshell.core.storage.dao.AgentSessionDao
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.PortUsageDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.dao.SessionDao
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.AgentSessionEntity
import com.pocketshell.core.storage.entity.AiApiCallEntry
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.PortUsageEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SessionEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity

const val APP_DATABASE_SCHEMA_VERSION = 9

/**
 * The PocketShell Room database.
 *
 * PocketShell has no external install base to preserve, so schema changes
 * are handled by destructive rebuild in the app database builder
 * (`fallbackToDestructiveMigration(dropAllTables = true)`) instead of by
 * carrying migration code forward. That fallback only fires on a version
 * delta, so any entity-schema change MUST bump this number above every
 * shipped version — otherwise upgraded installs hit a Room identity-hash
 * mismatch or downgrade path and crash on launch (#261). Bumped to 9 because
 * issue #291 adds SSH key fingerprints and intentionally relies on the
 * destructive rebuild to remove previously accumulated duplicate keys.
 *
 * `exportSchema = false` matches the reference module. When the schema
 * starts evolving in real users' hands, flip this on and check generated
 * schemas into `schemas/` so migrations are reviewable.
 */
@Database(
    entities = [
        HostEntity::class,
        SshKeyEntity::class,
        PortRemappingEntity::class,
        PortUsageEntity::class,
        ProjectRootEntity::class,
        SessionEntity::class,
        SnippetEntity::class,
        AgentSessionEntity::class,
        AiApiCallEntry::class,
        PendingTranscriptionEntity::class,
    ],
    version = APP_DATABASE_SCHEMA_VERSION,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portRemappingDao(): PortRemappingDao
    abstract fun portUsageDao(): PortUsageDao
    abstract fun projectRootDao(): ProjectRootDao
    abstract fun sessionDao(): SessionDao
    abstract fun snippetDao(): SnippetDao
    abstract fun agentSessionDao(): AgentSessionDao
    abstract fun aiApiCallLogDao(): AiApiCallLogDao
    abstract fun pendingTranscriptionDao(): PendingTranscriptionDao
}
