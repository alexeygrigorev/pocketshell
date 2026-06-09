package com.pocketshell.core.storage

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.entity.AgentSessionEntity
import com.pocketshell.core.storage.entity.AiApiCallEntry
import com.pocketshell.core.storage.entity.CommandTemplateEntity
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SessionEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises one round-trip per DAO against an in-memory Room database.
 *
 * Robolectric provides the Android Context that Room needs to instantiate
 * its SupportSQLiteOpenHelper; the database itself lives entirely in
 * memory so the test is hermetic.
 *
 * `RobolectricTestRunner` is used (not `AndroidJUnit4` directly) because
 * `:test` runs on the host JVM; `AndroidJUnit4` would dispatch to either
 * Robolectric or instrumentation depending on classpath. Being explicit
 * avoids confusion when this same pattern gets copied elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppDatabaseTest {

    private lateinit var db: AppDatabase
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sshKey_insert_then_read_by_id() = runTest {
        val id = db.sshKeyDao().insert(
            SshKeyEntity(
                name = "my-key",
                privateKeyPath = "/tmp/id_ed25519",
                fingerprint = "sha256:test",
            ),
        )
        val read = db.sshKeyDao().getById(id)
        assertNotNull(read)
        assertEquals("my-key", read!!.name)
        assertEquals("/tmp/id_ed25519", read.privateKeyPath)
        assertEquals("sha256:test", read.fingerprint)
        assertEquals(id, db.sshKeyDao().getByFingerprint("sha256:test")!!.id)
    }

    @Test
    fun host_insert_with_fk_to_sshKey_then_read() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "prod",
                hostname = "example.com",
                username = "alexey",
                keyId = keyId,
            ),
        )
        val read = db.hostDao().getById(hostId)
        assertNotNull(read)
        assertEquals("prod", read!!.name)
        assertEquals(22, read.port)
        assertEquals(keyId, read.keyId)
    }

    @Test
    fun portRemapping_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.portRemappingDao().insert(
            PortRemappingEntity(hostId = hostId, remotePort = 5432, localPort = 15432),
        )
        val remappings = db.portRemappingDao().getByHostId(hostId).first()
        assertEquals(1, remappings.size)
        assertEquals(5432, remappings[0].remotePort)
        assertEquals(15432, remappings[0].localPort)
    }

    @Test
    fun session_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.sessionDao().insert(
            SessionEntity(hostId = hostId, name = "main", lastSeenAt = 1_000L, tags = "work"),
        )
        val sessions = db.sessionDao().getByHostId(hostId).first()
        assertEquals(1, sessions.size)
        assertEquals("main", sessions[0].name)
        assertEquals("work", sessions[0].tags)
    }

    @Test
    fun snippet_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        // Issue #190: insert both an explicit-label row and a derived-
        // label row (label = null) so the schema round-trip covers both
        // paths.
        db.snippetDao().insert(
            SnippetEntity(hostId = hostId, label = "ls", body = "ls -la", kind = "command"),
        )
        db.snippetDao().insert(
            SnippetEntity(hostId = hostId, label = null, body = "echo derived", kind = "command"),
        )
        val snippets = db.snippetDao().getByHostId(hostId).first()
        assertEquals(2, snippets.size)
        // The DAO's `ORDER BY label` puts NULLs first under SQLite's
        // default sort; the explicit-label row comes second.
        val derived = snippets.first { it.label == null }
        assertEquals("echo derived", derived.body)
        val explicit = snippets.first { it.label == "ls" }
        assertEquals("ls -la", explicit.body)
        assertEquals("command", explicit.kind)
    }

    @Test
    fun commandTemplate_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.commandTemplateDao().insert(
            CommandTemplateEntity(
                hostId = hostId,
                label = "Git commit push",
                commands = "git add .\ngit commit -m '{{message}}'\ngit push",
            ),
        )

        val templates = db.commandTemplateDao().getByHostId(hostId).first()
        assertEquals(1, templates.size)
        assertEquals("Git commit push", templates[0].label)
        assertEquals("git add .\ngit commit -m '{{message}}'\ngit push", templates[0].commands)
    }

    @Test
    fun projectRoot_insert_then_read_by_host() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "work", path = "~/work"),
        )

        val roots = db.projectRootDao().getByHostId(hostId).first()
        assertEquals(1, roots.size)
        assertEquals("work", roots[0].label)
        assertEquals("~/work", roots[0].path)
    }

    @Test
    fun projectRoot_orderPrefixesPersistAndControlReadOrder() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "h", hostname = "h", username = "u", keyId = keyId),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "[01] git", path = "~/git"),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "[00] tmp", path = "~/tmp"),
        )

        val roots = db.projectRootDao().getByHostId(hostId).first()

        assertEquals(listOf("~/tmp", "~/git"), roots.map { it.path })
        assertEquals(listOf("[00] tmp", "[01] git"), roots.map { it.label })
    }

    @Test
    fun aiApiCallLog_insert_then_streams_all() = runTest {
        val dao = db.aiApiCallLogDao()
        val firstId = dao.insert(
            AiApiCallEntry(
                timestampMillis = 1_000L,
                provider = "openai",
                feature = "whisper",
                inputUnits = 12,
                outputUnits = 84,
                unitCostUsdMillicents = 10,
                computedCostUsdMillicents = 120,
                metadataJson = null,
            ),
        )
        val secondId = dao.insert(
            AiApiCallEntry(
                timestampMillis = 5_000L,
                provider = "openai",
                feature = "whisper",
                inputUnits = 3,
                outputUnits = 20,
                unitCostUsdMillicents = 10,
                computedCostUsdMillicents = 30,
                metadataJson = """{"requestId":"abc"}""",
            ),
        )

        val all = dao.getAll().first()
        assertEquals(2, all.size)
        // Newest first.
        assertEquals(secondId, all[0].id)
        assertEquals(firstId, all[1].id)
        assertEquals(120L, all[1].computedCostUsdMillicents)
        assertEquals("""{"requestId":"abc"}""", all[0].metadataJson)

        // Range query: only the second row falls inside (timestamp >= 2_000).
        val recent = dao.getSince(2_000L).first()
        assertEquals(1, recent.size)
        assertEquals(secondId, recent[0].id)

        dao.deleteAll()
        assertEquals(0, dao.getAll().first().size)
    }

    @Test
    fun agentSession_upsert_then_read_by_paneRef() = runTest {
        val paneRef = "host1:main:0:0"
        db.agentSessionDao().upsert(
            AgentSessionEntity(
                paneRef = paneRef,
                agent = "claude",
                jsonlPath = "/home/alexey/.claude/projects/foo/abc.jsonl",
                detectedAt = 42L,
            ),
        )
        val read = db.agentSessionDao().getByPaneRef(paneRef)
        assertNotNull(read)
        assertEquals("claude", read!!.agent)
        assertEquals(42L, read.detectedAt)

        // Re-upsert with new state should replace, not duplicate.
        db.agentSessionDao().upsert(
            AgentSessionEntity(
                id = read.id,
                paneRef = paneRef,
                agent = "codex",
                jsonlPath = null,
                detectedAt = 100L,
            ),
        )
        val updated = db.agentSessionDao().getByPaneRef(paneRef)
        assertEquals("codex", updated!!.agent)
        assertNull(updated.jsonlPath)
    }

    @Test
    fun staleIdentityHashAtSameVersion_reproducesRoomLaunchCrash() {
        val databaseName = "stale-same-version-${System.nanoTime()}.db"
        seedStaleIdentityDatabase(databaseName, version = currentRoomSchemaVersion())

        val thrown = assertThrows(IllegalStateException::class.java) {
            val staleDb = openOnDiskDatabase(databaseName)
            try {
                staleDb.openHelper.writableDatabase.query("SELECT 1").close()
            } finally {
                staleDb.close()
            }
        }

        assertTrue(thrown.message.orEmpty().contains("Room cannot verify the data integrity"))
        assertTrue(thrown.message.orEmpty().contains(LEGACY_CRASH_IDENTITY_HASH))
        context.deleteDatabase(databaseName)
    }

    @Test
    fun missingMigrationFailsWithoutDestroyingLegacyRows() {
        val databaseName = "missing-migration-${System.nanoTime()}.db"
        seedStaleIdentityDatabase(databaseName, version = LEGACY_CRASH_SCHEMA_VERSION)

        assertThrows(IllegalStateException::class.java) {
            val staleDb = openOnDiskDatabase(databaseName)
            try {
                staleDb.openHelper.writableDatabase.query("SELECT 1").close()
            } finally {
                staleDb.close()
            }
        }

        assertTableExists(databaseName, "stale_issue_261_marker")
        context.deleteDatabase(databaseName)
    }

    @Test
    fun destructiveFallbackVersionsDoNotOverlapSupportedMigrationStarts() {
        val supportedMigrationStarts = APP_DATABASE_MIGRATIONS.map { it.startVersion }.toSet()

        assertTrue(
            APP_DATABASE_UNSUPPORTED_STALE_SCHEMA_VERSIONS.none {
                it in supportedMigrationStarts
            },
        )
    }

    @Test
    fun migrationFromVersionEightToCurrent_preservesUserRowsAndDropsPathOverride() = runTest {
        val databaseName = "v8-to-current-${System.nanoTime()}.db"
        seedVersionEightDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            assertEquals(1, hosts.size)
            assertEquals("prod-v8", hosts[0].name)
            assertEquals("v8.example.com", hosts[0].hostname)
            assertEquals(true, hosts[0].enabled)
            assertEquals("pocketshell usage --json", hosts[0].usageCommandOverride)
            assertNull(hosts[0].pocketshellCliVersion)
            assertNull(hosts[0].pocketshellExpectedCliVersion)
            assertNull(hosts[0].pocketshellVersionCompatible)
            assertNull(hosts[0].pocketshellDaemonRunning)
            assertNull(hosts[0].pocketshellDaemonEnabled)

            val key = migratedDb.sshKeyDao().getById(1)
            assertNotNull(key)
            assertEquals("deploy-key-v8", key!!.name)
            assertEquals("/keys/deploy-v8", key.privateKeyPath)
            assertEquals("", key.fingerprint)

            val roots = migratedDb.projectRootDao().getByHostId(1).first()
            assertEquals(listOf("~/git/pocketshell-v8"), roots.map { it.path })

            val sessions = migratedDb.sessionDao().getByHostId(1).first()
            assertEquals(listOf("main-v8"), sessions.map { it.name })

            val snippets = migratedDb.snippetDao().getByHostId(1).first()
            assertEquals(listOf("echo preserved from v8"), snippets.map { it.body })

            val costs = migratedDb.aiApiCallLogDao().getAll().first()
            assertEquals(1, costs.size)
            assertEquals(456L, costs[0].computedCostUsdMillicents)

            val pending = migratedDb.pendingTranscriptionDao().getAllOnce()
            assertEquals(1, pending.size)
            assertEquals("pending-v8", pending[0].id)

            val remappings = migratedDb.portRemappingDao().getByHostId(1).first()
            assertEquals(1, remappings.size)
            assertEquals(4000, remappings[0].remotePort)

            val usage = migratedDb.portUsageDao().getByHostId(1).first()
            assertEquals(1, usage.size)
            assertEquals(84L, usage[0].totalBytes)

            assertColumnMissing(databaseName, "hosts", "pathOverride")
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionElevenToCurrent_preservesUserRows() = runTest {
        val databaseName = "v11-to-current-${System.nanoTime()}.db"
        seedVersionElevenDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            assertEquals(1, hosts.size)
            assertEquals("prod", hosts[0].name)
            assertEquals("example.com", hosts[0].hostname)
            assertEquals(true, hosts[0].enabled)
            assertNull(hosts[0].pocketshellDaemonRunning)
            assertNull(hosts[0].pocketshellDaemonEnabled)
            assertNull(hosts[0].claudeProfilesJson)

            val key = migratedDb.sshKeyDao().getById(1)
            assertNotNull(key)
            assertEquals("deploy-key", key!!.name)
            assertEquals("sha256:v11", key.fingerprint)

            val roots = migratedDb.projectRootDao().getByHostId(1).first()
            assertEquals(listOf("~/git/pocketshell"), roots.map { it.path })

            val sessions = migratedDb.sessionDao().getByHostId(1).first()
            assertEquals(listOf("main"), sessions.map { it.name })

            val snippets = migratedDb.snippetDao().getByHostId(1).first()
            assertEquals(listOf("echo preserved"), snippets.map { it.body })

            val costs = migratedDb.aiApiCallLogDao().getAll().first()
            assertEquals(1, costs.size)
            assertEquals(123L, costs[0].computedCostUsdMillicents)

            val pending = migratedDb.pendingTranscriptionDao().getAllOnce()
            assertEquals(1, pending.size)
            assertEquals("pending-v11", pending[0].id)

            val remappings = migratedDb.portRemappingDao().getByHostId(1).first()
            assertEquals(1, remappings.size)
            assertEquals(3000, remappings[0].remotePort)

            val usage = migratedDb.portUsageDao().getByHostId(1).first()
            assertEquals(1, usage.size)
            assertEquals(42L, usage[0].totalBytes)
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionThirteen_preservesUserRowsAndAddsClaudeProfilesJson() = runTest {
        val databaseName = "v13-to-current-${System.nanoTime()}.db"
        seedVersionThirteenDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            assertEquals(1, hosts.size)
            assertEquals("prod", hosts[0].name)
            // The new column should be null after migration (no profiles).
            assertNull(hosts[0].claudeProfilesJson)
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionThirteen_claudeProfilesJsonRoundTrips() = runTest {
        val databaseName = "v13-roundtrip-${System.nanoTime()}.db"
        seedVersionThirteenDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            val host = hosts[0]
            // Update with profiles JSON.
            val profilesJson = """[{"name":"work","configDir":"/home/.claude-work"}]"""
            migratedDb.hostDao().update(
                host.copy(claudeProfilesJson = profilesJson),
            )
            val updated = migratedDb.hostDao().getById(host.id)
            assertEquals(profilesJson, updated!!.claudeProfilesJson)
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    // --- Issue #631: v14 -> v15 migration (codexProfilesJson) ---

    @Test
    fun migrationFromVersionFourteen_preservesUserRowsAndAddsCodexProfilesJson() = runTest {
        val databaseName = "v14-to-current-${System.nanoTime()}.db"
        seedVersionFourteenDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            assertEquals(1, hosts.size)
            assertEquals("prod", hosts[0].name)
            // claudeProfilesJson preserved from v14.
            assertNotNull(hosts[0].claudeProfilesJson)
            // The new column should be null after migration (no Codex profiles).
            assertNull(hosts[0].codexProfilesJson)
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFromVersionFourteen_codexProfilesJsonRoundTrips() = runTest {
        val databaseName = "v14-roundtrip-${System.nanoTime()}.db"
        seedVersionFourteenDatabaseWithUserRows(databaseName)

        val migratedDb = openOnDiskDatabase(databaseName)
        try {
            migratedDb.openHelper.writableDatabase.query("SELECT 1").close()

            val hosts = migratedDb.hostDao().getAll().first()
            val host = hosts[0]
            // Update with Codex profiles JSON.
            val profilesJson = """[{"name":"work","configDir":"/home/.codex-work"}]"""
            migratedDb.hostDao().update(
                host.copy(codexProfilesJson = profilesJson),
            )
            val updated = migratedDb.hostDao().getById(host.id)
            assertEquals(profilesJson, updated!!.codexProfilesJson)
        } finally {
            migratedDb.close()
        }
        context.deleteDatabase(databaseName)
    }

    private fun openOnDiskDatabase(databaseName: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .addMigrations(*APP_DATABASE_MIGRATIONS)
            .build()

    private fun seedStaleIdentityDatabase(databaseName: String, version: Int) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            it.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            it.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(LEGACY_CRASH_IDENTITY_HASH),
            )
            it.execSQL("CREATE TABLE stale_issue_261_marker (id INTEGER PRIMARY KEY)")
            it.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun assertTableExists(databaseName: String, tableName: String) {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use {
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    private fun assertColumnMissing(databaseName: String, tableName: String, columnName: String) {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use {
            it.rawQuery("PRAGMA table_info($tableName)", emptyArray()).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        throw AssertionError("Unexpected column $tableName.$columnName")
                    }
                }
            }
        }
    }

    private fun seedVersionEightDatabaseWithUserRows(databaseName: String) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            createVersionEightSchema(it)
            it.execSQL(
                """
                INSERT INTO ssh_keys(id, name, privateKeyPath, hasPassphrase, createdAt)
                VALUES(1, 'deploy-key-v8', '/keys/deploy-v8', 1, 100)
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                    usageCommandOverride, pathOverride
                ) VALUES(
                    1, 'prod-v8', 'v8.example.com', 2222, 'alexey', 1, 10000, 1000,
                    5, 1, 101, 102, 1, 103, 1, 104,
                    'pocketshell usage --json', '~/git/pocketshell/.venv/bin'
                )
                """.trimIndent(),
            )
            it.execSQL(
                "INSERT INTO project_roots(id, hostId, label, path, createdAt) " +
                    "VALUES(1, 1, 'repo', '~/git/pocketshell-v8', 110)"
            )
            it.execSQL(
                "INSERT INTO sessions(id, hostId, name, lastSeenAt, tags) " +
                    "VALUES(1, 1, 'main-v8', 120, 'work')"
            )
            it.execSQL(
                "INSERT INTO snippets(id, hostId, label, body, kind) " +
                    "VALUES(1, 1, 'preserve', 'echo preserved from v8', 'command')"
            )
            it.execSQL(
                "INSERT INTO port_remappings(id, hostId, remotePort, localPort) " +
                    "VALUES(1, 1, 4000, 14000)"
            )
            it.execSQL(
                "INSERT INTO port_usage(hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES(1, 4000, 3, 84, 130)"
            )
            it.execSQL(
                "INSERT INTO agent_sessions(id, paneRef, agent, jsonlPath, detectedAt) " +
                    "VALUES(1, 'prod-v8:main:0:0', 'codex', '/logs/codex-v8.jsonl', 140)"
            )
            it.execSQL(
                """
                INSERT INTO ai_api_call_log(
                    id, timestampMillis, provider, feature, inputUnits, outputUnits,
                    unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
                ) VALUES(1, 150, 'openai', 'whisper', 12, 34, 10, 456, '{"requestId":"v8"}')
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO pending_transcriptions(
                    id, audioPath, recordingTimestampMs, destinationContext, retryCount,
                    lastErrorMessage, audioByteSize, createdAtMs
                ) VALUES(
                    'pending-v8', '/audio/pending-v8.wav', 160,
                    '${PendingTranscriptionEntity.DESTINATION_COMPOSER}', 1,
                    'offline', 2048, 161
                )
                """.trimIndent(),
            )
            it.execSQL("PRAGMA user_version = 8")
        }
    }

    private fun seedVersionElevenDatabaseWithUserRows(databaseName: String) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            createVersionElevenSchema(it)
            it.execSQL(
                """
                INSERT INTO ssh_keys(id, name, privateKeyPath, fingerprint, hasPassphrase, createdAt)
                VALUES(1, 'deploy-key', '/keys/deploy', 'sha256:v11', 1, 100)
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                    pocketshellCliVersion, pocketshellExpectedCliVersion,
                    pocketshellVersionCompatible, usageCommandOverride
                ) VALUES(
                    1, 'prod', 'example.com', 2222, 'alexey', 1, 10000, 1000,
                    5, 1, 101, 102, 1, 103, 1, 104, '0.3.14', '0.3.14', 1,
                    'pocketshell usage --json'
                )
                """.trimIndent(),
            )
            it.execSQL(
                "INSERT INTO project_roots(id, hostId, label, path, createdAt) " +
                    "VALUES(1, 1, 'repo', '~/git/pocketshell', 110)"
            )
            it.execSQL(
                "INSERT INTO sessions(id, hostId, name, lastSeenAt, tags) " +
                    "VALUES(1, 1, 'main', 120, 'work')"
            )
            it.execSQL(
                "INSERT INTO snippets(id, hostId, label, body, kind) " +
                    "VALUES(1, 1, 'preserve', 'echo preserved', 'command')"
            )
            it.execSQL(
                "INSERT INTO port_remappings(id, hostId, remotePort, localPort) " +
                    "VALUES(1, 1, 3000, 13000)"
            )
            it.execSQL(
                "INSERT INTO port_usage(hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES(1, 3000, 2, 42, 130)"
            )
            it.execSQL(
                "INSERT INTO agent_sessions(id, paneRef, agent, jsonlPath, detectedAt) " +
                    "VALUES(1, 'prod:main:0:0', 'codex', '/logs/codex.jsonl', 140)"
            )
            it.execSQL(
                """
                INSERT INTO ai_api_call_log(
                    id, timestampMillis, provider, feature, inputUnits, outputUnits,
                    unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
                ) VALUES(1, 150, 'openai', 'whisper', 12, 34, 10, 123, '{"requestId":"v11"}')
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO pending_transcriptions(
                    id, audioPath, recordingTimestampMs, destinationContext, retryCount,
                    lastErrorMessage, audioByteSize, createdAtMs
                ) VALUES(
                    'pending-v11', '/audio/pending-v11.wav', 160,
                    '${PendingTranscriptionEntity.DESTINATION_COMPOSER}', 1,
                    'offline', 2048, 161
                )
                """.trimIndent(),
            )
            it.execSQL("PRAGMA user_version = 11")
        }
    }

    private fun seedVersionThirteenDatabaseWithUserRows(databaseName: String) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            createVersionThirteenSchema(it)
            it.execSQL(
                """
                INSERT INTO ssh_keys(id, name, privateKeyPath, fingerprint, hasPassphrase, createdAt)
                VALUES(1, 'deploy-key', '/keys/deploy', 'sha256:v13', 1, 100)
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                    pocketshellCliVersion, pocketshellExpectedCliVersion,
                    pocketshellVersionCompatible, pocketshellDaemonRunning,
                    pocketshellDaemonEnabled, usageCommandOverride
                ) VALUES(
                    1, 'prod', 'example.com', 2222, 'alexey', 1, 10000, 1000,
                    5, 1, 101, 102, 1, 103, 1, 104, '0.3.14', '0.3.14', 1, 1, 1,
                    'pocketshell usage --json'
                )
                """.trimIndent(),
            )
            it.execSQL("PRAGMA user_version = 13")
        }
    }

    private fun createVersionThirteenSchema(db: SQLiteDatabase) {
        // Start from the v11 schema (which includes ssh_keys, hosts with
        // daemon columns, and all host-scoped tables).
        createVersionElevenSchema(db)
        // Add the command_templates table from migration 12->13.
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
        // Add the daemon columns from migration 11->12 (v11 schema doesn't have them).
        // Actually createVersionElevenSchema does NOT have daemonRunning/Enabled,
        // but the v11 schema's hosts table also doesn't have them. They're added
        // by MIGRATION_11_12. For a v13 seed, the hosts table must include them.
        // Re-create hosts with all v13 columns.
        db.execSQL("DROP TABLE IF EXISTS hosts_v13")
        db.execSQL(
            """
            CREATE TABLE hosts_v13 (
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
            INSERT INTO hosts_v13 SELECT
                id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                pocketshellCliVersion, pocketshellExpectedCliVersion,
                pocketshellVersionCompatible, NULL, NULL, usageCommandOverride
            FROM hosts
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE hosts")
        db.execSQL("ALTER TABLE hosts_v13 RENAME TO hosts")
        db.execSQL("CREATE INDEX index_hosts_keyId ON hosts(keyId)")
    }

    // --- Issue #631: v14 seed helpers ---

    private fun seedVersionFourteenDatabaseWithUserRows(databaseName: String) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            createVersionFourteenSchema(it)
            it.execSQL(
                """
                INSERT INTO ssh_keys(id, name, privateKeyPath, fingerprint, hasPassphrase, createdAt)
                VALUES(1, 'deploy-key', '/keys/deploy', 'sha256:v14', 1, 100)
                """.trimIndent(),
            )
            it.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                    pocketshellCliVersion, pocketshellExpectedCliVersion,
                    pocketshellVersionCompatible, pocketshellDaemonRunning,
                    pocketshellDaemonEnabled, usageCommandOverride, claudeProfilesJson
                ) VALUES(
                    1, 'prod', 'example.com', 2222, 'alexey', 1, 10000, 1000,
                    5, 1, 101, 102, 1, 103, 1, 104, '0.3.14', '0.3.14', 1, 1, 1,
                    'pocketshell usage --json',
                    '[{"name":"work","configDir":"/home/.claude-work"}]'
                )
                """.trimIndent(),
            )
            it.execSQL("PRAGMA user_version = 14")
        }
    }

    private fun createVersionFourteenSchema(db: SQLiteDatabase) {
        // v14 = v13 schema + claudeProfilesJson column on hosts.
        createVersionThirteenSchema(db)
        db.execSQL("ALTER TABLE hosts ADD COLUMN claudeProfilesJson TEXT")
    }

    private fun createVersionEightSchema(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.execSQL(
            """
            CREATE TABLE ssh_keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                privateKeyPath TEXT NOT NULL,
                hasPassphrase INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE hosts (
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
        db.execSQL("CREATE INDEX index_hosts_keyId ON hosts(keyId)")
        createHostScopedVersionEightTables(db)
    }

    private fun createHostScopedVersionEightTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE port_remappings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                remotePort INTEGER NOT NULL,
                localPort INTEGER NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_port_remappings_hostId ON port_remappings(hostId)")
        db.execSQL(
            "CREATE UNIQUE INDEX index_port_remappings_hostId_remotePort " +
                "ON port_remappings(hostId, remotePort)"
        )
        db.execSQL(
            """
            CREATE TABLE port_usage (
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
        db.execSQL("CREATE INDEX index_port_usage_hostId ON port_usage(hostId)")
        db.execSQL(
            """
            CREATE TABLE project_roots (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT NOT NULL,
                path TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_project_roots_hostId ON project_roots(hostId)")
        db.execSQL(
            "CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path)"
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                name TEXT NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                tags TEXT,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_sessions_hostId ON sessions(hostId)")
        db.execSQL("CREATE UNIQUE INDEX index_sessions_hostId_name ON sessions(hostId, name)")
        db.execSQL(
            """
            CREATE TABLE snippets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT,
                body TEXT NOT NULL,
                kind TEXT NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_snippets_hostId ON snippets(hostId)")
        db.execSQL(
            """
            CREATE TABLE agent_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                paneRef TEXT NOT NULL,
                agent TEXT NOT NULL,
                jsonlPath TEXT,
                detectedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_agent_sessions_paneRef ON agent_sessions(paneRef)")
        db.execSQL(
            """
            CREATE TABLE ai_api_call_log (
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
        db.execSQL("CREATE INDEX index_ai_api_call_log_timestampMillis ON ai_api_call_log(timestampMillis)")
        db.execSQL(
            "CREATE INDEX index_ai_api_call_log_provider_feature ON ai_api_call_log(provider, feature)"
        )
        db.execSQL(
            """
            CREATE TABLE pending_transcriptions (
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
            "CREATE INDEX index_pending_transcriptions_recordingTimestampMs " +
                "ON pending_transcriptions(recordingTimestampMs)"
        )
    }

    private fun createVersionElevenSchema(db: SQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.execSQL(
            """
            CREATE TABLE ssh_keys (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                privateKeyPath TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                hasPassphrase INTEGER NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE hosts (
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
                usageCommandOverride TEXT,
                FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_hosts_keyId ON hosts(keyId)")
        db.execSQL(
            """
            CREATE TABLE port_remappings (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                remotePort INTEGER NOT NULL,
                localPort INTEGER NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_port_remappings_hostId ON port_remappings(hostId)")
        db.execSQL(
            "CREATE UNIQUE INDEX index_port_remappings_hostId_remotePort " +
                "ON port_remappings(hostId, remotePort)"
        )
        db.execSQL(
            """
            CREATE TABLE port_usage (
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
        db.execSQL("CREATE INDEX index_port_usage_hostId ON port_usage(hostId)")
        db.execSQL(
            """
            CREATE TABLE project_roots (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT NOT NULL,
                path TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_project_roots_hostId ON project_roots(hostId)")
        db.execSQL(
            "CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path)"
        )
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                name TEXT NOT NULL,
                lastSeenAt INTEGER NOT NULL,
                tags TEXT,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_sessions_hostId ON sessions(hostId)")
        db.execSQL("CREATE UNIQUE INDEX index_sessions_hostId_name ON sessions(hostId, name)")
        db.execSQL(
            """
            CREATE TABLE snippets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT,
                body TEXT NOT NULL,
                kind TEXT NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_snippets_hostId ON snippets(hostId)")
        db.execSQL(
            """
            CREATE TABLE agent_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                paneRef TEXT NOT NULL,
                agent TEXT NOT NULL,
                jsonlPath TEXT,
                detectedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_agent_sessions_paneRef ON agent_sessions(paneRef)")
        db.execSQL(
            """
            CREATE TABLE ai_api_call_log (
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
        db.execSQL("CREATE INDEX index_ai_api_call_log_timestampMillis ON ai_api_call_log(timestampMillis)")
        db.execSQL(
            "CREATE INDEX index_ai_api_call_log_provider_feature ON ai_api_call_log(provider, feature)"
        )
        db.execSQL(
            """
            CREATE TABLE pending_transcriptions (
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
            "CREATE INDEX index_pending_transcriptions_recordingTimestampMs " +
                "ON pending_transcriptions(recordingTimestampMs)"
        )
    }

    private fun currentRoomSchemaVersion(): Int =
        APP_DATABASE_SCHEMA_VERSION

    private companion object {
        const val LEGACY_CRASH_SCHEMA_VERSION = 1
        const val LEGACY_CRASH_IDENTITY_HASH = "4a479a15dfcab2d576e00c7ce10ac581"
    }
}
