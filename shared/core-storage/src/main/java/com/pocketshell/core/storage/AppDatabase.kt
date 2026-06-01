package com.pocketshell.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

const val APP_DATABASE_SCHEMA_VERSION = 12

/**
 * Issue #261 left a deliberately unsupported pre-migration v1 shape in the
 * release gate: it only contains Room metadata and a stale marker table, not
 * PocketShell user-data tables. Production may destructively rebuild only
 * from these start versions; supported real schemas must be represented in
 * [APP_DATABASE_MIGRATIONS] instead.
 */
val APP_DATABASE_UNSUPPORTED_STALE_SCHEMA_VERSIONS: IntArray = intArrayOf(1)

/**
 * The PocketShell Room database.
 *
 * Normal APK updates must preserve user data. Any entity-schema change MUST
 * bump this number and add a matching [Migration] to [APP_DATABASE_MIGRATIONS]
 * before it ships; otherwise upgraded installs fail Room validation instead of
 * silently deleting hosts, keys, snippets, costs, or pending transcription
 * metadata. Bumped to 12 because issue #328 persists the remote pocketshell
 * daemon running/enabled result so the host setup cache cannot route on CLI
 * readiness alone.
 *
 * `exportSchema = false` is historical. Issue #386 starts the preservation
 * path with checked-in migration code; a follow-up should enable exported
 * schemas so every future migration has a generated schema artifact too.
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

val MIGRATION_8_10: Migration = object : Migration(8, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_keys ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")

        db.execSQL(
            """
            CREATE TABLE hosts_migration_8_10 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                hostname TEXT NOT NULL,
                port INTEGER NOT NULL,
                username TEXT NOT NULL,
                keyId INTEGER NOT NULL,
                maxAutoPort INTEGER NOT NULL,
                skipPortsBelow INTEGER NOT NULL,
                scanIntervalSec INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                lastConnectedAt INTEGER,
                tmuxInstalled INTEGER,
                lastBootstrapAt INTEGER,
                pocketshellInstalled INTEGER,
                pocketshellLastDetectedAt INTEGER,
                usageCommandOverride TEXT,
                FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO hosts_migration_8_10 (
                id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                usageCommandOverride
            )
            SELECT
                id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                usageCommandOverride
            FROM hosts
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE hosts")
        db.execSQL("ALTER TABLE hosts_migration_8_10 RENAME TO hosts")
        db.execSQL("CREATE INDEX index_hosts_keyId ON hosts(keyId)")
    }
}

val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN pocketshellCliVersion TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN pocketshellExpectedCliVersion TEXT")
        db.execSQL("ALTER TABLE hosts ADD COLUMN pocketshellVersionCompatible INTEGER")
    }
}

val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN pocketshellDaemonRunning INTEGER")
        db.execSQL("ALTER TABLE hosts ADD COLUMN pocketshellDaemonEnabled INTEGER")
    }
}

val APP_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_8_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
)
