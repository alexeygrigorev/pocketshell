package com.pocketshell.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pocketshell.core.storage.dao.AiApiCallLogDao
import com.pocketshell.core.storage.dao.CommandTemplateDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PendingTranscriptionDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.PortUsageDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.AiApiCallEntry
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.PortUsageEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity

const val APP_DATABASE_SCHEMA_VERSION = 17

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
 * metadata. Bumped to 17 because issue #1447 drops the never-populated
 * `sessions` / `agent_sessions` stub tables (superseded by the host-side
 * daemon session registry, epic #821); see [MIGRATION_16_17].
 *
 * `exportSchema = true` (Room writes the versioned schema JSON to the
 * `room.schemaLocation` dir configured in this module's `build.gradle.kts`).
 * The exported artifact is the durable record of each shipped schema, so a
 * future migration gap (a `version` bump with no matching `MIGRATION_*`) is
 * caught by a schema diff / migration test at build time instead of on the
 * maintainer's device.
 */
@Database(
    entities = [
        HostEntity::class,
        SshKeyEntity::class,
        PortRemappingEntity::class,
        PortUsageEntity::class,
        ProjectRootEntity::class,
        SnippetEntity::class,
        AiApiCallEntry::class,
        PendingTranscriptionEntity::class,
        CommandTemplateEntity::class,
    ],
    version = APP_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portRemappingDao(): PortRemappingDao
    abstract fun portUsageDao(): PortUsageDao
    abstract fun projectRootDao(): ProjectRootDao
    abstract fun snippetDao(): SnippetDao
    abstract fun aiApiCallLogDao(): AiApiCallLogDao
    abstract fun pendingTranscriptionDao(): PendingTranscriptionDao
    abstract fun commandTemplateDao(): CommandTemplateDao
}

val MIGRATION_2_8: Migration = legacyMigrationToVersionEight(2)
val MIGRATION_3_8: Migration = legacyMigrationToVersionEight(3)
val MIGRATION_4_8: Migration = legacyMigrationToVersionEight(4)
val MIGRATION_5_8: Migration = legacyMigrationToVersionEight(5)
val MIGRATION_6_8: Migration = legacyMigrationToVersionEight(6)
val MIGRATION_7_8: Migration = legacyMigrationToVersionEight(7)

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

val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        normalizeLegacyVersionNineToVersionTen(db)
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

val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS command_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT NOT NULL,
                commands TEXT NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_command_templates_hostId ON command_templates(hostId)")
    }
}

val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN claudeProfilesJson TEXT")
    }
}

val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN codexProfilesJson TEXT")
    }
}

/**
 * Issue #718 (slice 2, hard-cut per D22): drop the client-stored
 * `claudeProfilesJson` (#627) and `codexProfilesJson` (#631) columns now
 * that agent profiles are discovered ON THE HOST and fetched at runtime by
 * `ProfilesGateway`. SQLite can't portably `DROP COLUMN` on the engine
 * versions Room targets, so this rebuilds the `hosts` table without the two
 * columns — the proven [MIGRATION_8_10] table-rebuild shape (create a
 * sibling table with the kept columns, copy the rows over, drop the old
 * table, rename, recreate the index). Host rows and every FK-scoped child
 * table (sessions, snippets, port_remappings, project_roots, …) are
 * preserved; only the two profile columns disappear.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hosts_migration_15_16 (
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
                pocketshellCliVersion TEXT,
                pocketshellExpectedCliVersion TEXT,
                pocketshellVersionCompatible INTEGER,
                pocketshellDaemonRunning INTEGER,
                pocketshellDaemonEnabled INTEGER,
                usageCommandOverride TEXT,
                FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO hosts_migration_15_16 (
                id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                pocketshellCliVersion, pocketshellExpectedCliVersion,
                pocketshellVersionCompatible, pocketshellDaemonRunning,
                pocketshellDaemonEnabled, usageCommandOverride
            )
            SELECT
                id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                pocketshellCliVersion, pocketshellExpectedCliVersion,
                pocketshellVersionCompatible, pocketshellDaemonRunning,
                pocketshellDaemonEnabled, usageCommandOverride
            FROM hosts
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE hosts")
        db.execSQL("ALTER TABLE hosts_migration_15_16 RENAME TO hosts")
        db.execSQL("CREATE INDEX index_hosts_keyId ON hosts(keyId)")
    }
}

/**
 * Issue #1447 (#684 code-health, hard-cut per D22): drop the never-populated
 * `sessions` and `agent_sessions` stub tables. Both were forward-compatibility
 * placeholders (`SessionEntity` / `AgentSessionEntity`) that no production code
 * ever read or wrote — the durable session tree and per-pane agent-kind state
 * now live host-side in the `pocketshell` daemon registry (epic #821), not a
 * client Room table. `DROP TABLE IF EXISTS` is safe even though the tables were
 * empty; older installs (v1–v16) legitimately carried both tables, so this
 * migration removes them cleanly on the next update. Every live table
 * (`hosts` + its FK-scoped children, `ai_api_call_log`, `pending_transcriptions`,
 * `command_templates`, …) is untouched.
 */
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS sessions")
        db.execSQL("DROP TABLE IF EXISTS agent_sessions")
    }
}

val APP_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_2_8,
    MIGRATION_3_8,
    MIGRATION_4_8,
    MIGRATION_5_8,
    MIGRATION_6_8,
    MIGRATION_7_8,
    MIGRATION_8_10,
    MIGRATION_9_10,
    MIGRATION_10_11,
    MIGRATION_11_12,
    MIGRATION_12_13,
    MIGRATION_13_14,
    MIGRATION_14_15,
    MIGRATION_15_16,
    MIGRATION_16_17,
)

private fun legacyMigrationToVersionEight(startVersion: Int): Migration =
    object : Migration(startVersion, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            normalizeLegacySchemaToVersionEight(db)
        }
    }

private fun normalizeLegacySchemaToVersionEight(db: SupportSQLiteDatabase) {
    rebuildHostsForVersionEight(db)
    createProjectRootsTableIfMissing(db)
    createAiApiCallLogTableIfMissing(db)
    createPendingTranscriptionsTableIfMissing(db)
    createPortUsageTableIfMissing(db)
    relaxSnippetLabelIfNeeded(db)
}

private fun normalizeLegacyVersionNineToVersionTen(db: SupportSQLiteDatabase) {
    if (!db.hasColumn("ssh_keys", "fingerprint")) {
        db.execSQL("ALTER TABLE ssh_keys ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
    }
    rebuildHostsForVersionTen(db)
    createPortUsageTableIfMissing(db)
}

private fun rebuildHostsForVersionEight(db: SupportSQLiteDatabase) {
    val usageInstalled = db.firstExistingColumnExpression(
        "hosts",
        "pocketshellInstalled",
        "quseInstalled",
        "heruInstalled",
    )
    val usageDetectedAt = db.firstExistingColumnExpression(
        "hosts",
        "pocketshellLastDetectedAt",
        "quseLastDetectedAt",
        "heruLastDetectedAt",
    )
    val usageCommandOverride = db.firstExistingColumnExpression("hosts", "usageCommandOverride")
    val pathOverride = db.firstExistingColumnExpression("hosts", "pathOverride")

    db.execSQL("DROP TABLE IF EXISTS hosts_migration_legacy_to_8")
    db.execSQL(
        """
        CREATE TABLE hosts_migration_legacy_to_8 (
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
            pathOverride TEXT,
            FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        """
        INSERT INTO hosts_migration_legacy_to_8 (
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
            usageCommandOverride, pathOverride
        )
        SELECT
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, $usageInstalled, $usageDetectedAt,
            $usageCommandOverride, $pathOverride
        FROM hosts
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE hosts")
    db.execSQL("ALTER TABLE hosts_migration_legacy_to_8 RENAME TO hosts")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_hosts_keyId ON hosts(keyId)")
}

private fun rebuildHostsForVersionTen(db: SupportSQLiteDatabase) {
    val usageInstalled = db.firstExistingColumnExpression(
        "hosts",
        "pocketshellInstalled",
        "quseInstalled",
        "heruInstalled",
    )
    val usageDetectedAt = db.firstExistingColumnExpression(
        "hosts",
        "pocketshellLastDetectedAt",
        "quseLastDetectedAt",
        "heruLastDetectedAt",
    )
    val usageCommandOverride = db.firstExistingColumnExpression("hosts", "usageCommandOverride")

    db.execSQL("DROP TABLE IF EXISTS hosts_migration_legacy_to_10")
    db.execSQL(
        """
        CREATE TABLE hosts_migration_legacy_to_10 (
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
        INSERT INTO hosts_migration_legacy_to_10 (
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
            usageCommandOverride
        )
        SELECT
            id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
            scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
            lastBootstrapAt, $usageInstalled, $usageDetectedAt,
            $usageCommandOverride
        FROM hosts
        """.trimIndent(),
    )
    db.execSQL("DROP TABLE hosts")
    db.execSQL("ALTER TABLE hosts_migration_legacy_to_10 RENAME TO hosts")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_hosts_keyId ON hosts(keyId)")
}

private fun createProjectRootsTableIfMissing(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS project_roots (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT NOT NULL,
            path TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_project_roots_hostId ON project_roots(hostId)")
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_project_roots_hostId_path " +
            "ON project_roots(hostId, path)",
    )
}

private fun createAiApiCallLogTableIfMissing(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS ai_api_call_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            timestampMillis INTEGER NOT NULL,
            provider TEXT NOT NULL,
            feature TEXT NOT NULL,
            inputUnits INTEGER NOT NULL,
            outputUnits INTEGER NOT NULL,
            unitCostUsdMillicents INTEGER NOT NULL,
            computedCostUsdMillicents INTEGER NOT NULL,
            metadataJson TEXT
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_ai_api_call_log_timestampMillis " +
            "ON ai_api_call_log(timestampMillis)",
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_ai_api_call_log_provider_feature " +
            "ON ai_api_call_log(provider, feature)",
    )
}

private fun createPendingTranscriptionsTableIfMissing(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS pending_transcriptions (
            id TEXT NOT NULL,
            audioPath TEXT NOT NULL,
            recordingTimestampMs INTEGER NOT NULL,
            destinationContext TEXT NOT NULL,
            retryCount INTEGER NOT NULL,
            lastErrorMessage TEXT,
            audioByteSize INTEGER NOT NULL,
            createdAtMs INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_pending_transcriptions_recordingTimestampMs " +
            "ON pending_transcriptions(recordingTimestampMs)",
    )
}

private fun createPortUsageTableIfMissing(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS port_usage (
            hostId INTEGER NOT NULL,
            remotePort INTEGER NOT NULL,
            clickCount INTEGER NOT NULL,
            totalBytes INTEGER NOT NULL,
            lastUsedAt INTEGER NOT NULL,
            PRIMARY KEY(hostId, remotePort),
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_port_usage_hostId ON port_usage(hostId)")
}

private fun relaxSnippetLabelIfNeeded(db: SupportSQLiteDatabase) {
    if (!db.hasTable("snippets") || !db.columnIsNotNull("snippets", "label")) return

    db.execSQL("DROP TABLE IF EXISTS snippets_migration_legacy_to_8")
    db.execSQL(
        """
        CREATE TABLE snippets_migration_legacy_to_8 (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            hostId INTEGER NOT NULL,
            label TEXT,
            body TEXT NOT NULL,
            kind TEXT NOT NULL,
            FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    )
    db.execSQL(
        "INSERT INTO snippets_migration_legacy_to_8 (id, hostId, label, body, kind) " +
            "SELECT id, hostId, label, body, kind FROM snippets",
    )
    db.execSQL("DROP TABLE snippets")
    db.execSQL("ALTER TABLE snippets_migration_legacy_to_8 RENAME TO snippets")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_snippets_hostId ON snippets(hostId)")
}

private fun SupportSQLiteDatabase.firstExistingColumnExpression(
    tableName: String,
    vararg columnNames: String,
): String = columnNames.firstOrNull { hasColumn(tableName, it) } ?: "NULL"

private fun SupportSQLiteDatabase.hasTable(tableName: String): Boolean {
    query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(tableName),
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
    query("PRAGMA table_info($tableName)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.columnIsNotNull(tableName: String, columnName: String): Boolean {
    query("PRAGMA table_info($tableName)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                return cursor.getInt(notNullIndex) == 1
            }
        }
    }
    return false
}
