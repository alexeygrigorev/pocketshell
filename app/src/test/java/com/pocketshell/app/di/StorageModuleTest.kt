package com.pocketshell.app.di

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.APP_DATABASE_SCHEMA_VERSION
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StorageModuleTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun provideAppDatabase_preservesTaggedVersion020Database() {
        assertTaggedVersionOneMigration(ShippedV1Layout.V0_2_0)
    }

    @Test
    fun provideAppDatabase_preservesTaggedVersion029Database() {
        assertTaggedVersionOneMigration(ShippedV1Layout.V0_2_9)
    }

    @Test
    fun provideAppDatabase_preservesTaggedVersion030Database() {
        assertTaggedVersionOneMigration(ShippedV1Layout.V0_3_0)
    }

    @Test
    fun provideAppDatabase_migratesExactIssue261MarkerDatabase() {
        seedStaleIdentityDatabase(version = ISSUE_261_STALE_SCHEMA_VERSION)
        assertEquals(
            LEGACY_CRASH_IDENTITY_HASH,
            queryString("SELECT identity_hash FROM room_master_table WHERE id = 42"),
        )
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM stale_issue_261_marker"))

        openProductionDatabase()

        assertCurrentSchemaVersion()
        assertTableExists("hosts")
        assertTableMissing("stale_issue_261_marker")
    }

    @Test
    fun provideAppDatabase_unknownVersionOneFailsClosedWithoutDeletingSentinel() {
        seedUnknownVersionOneDatabase()
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        assertTrue(databaseFile.exists())
        val beforeOpen = snapshotDatabaseBundle()

        assertProductionOpenFails()

        assertTrue(databaseFile.exists())
        assertDatabaseBundleUnchanged(beforeOpen)
        assertEquals(1, queryLong("PRAGMA user_version"))
        assertEquals("must-survive", queryString("SELECT payload FROM unknown_v1_data WHERE id = 1747"))
        assertTableExists("unknown_v1_data")
        assertTableMissing("hosts")
    }

    @Test
    fun provideAppDatabase_malformedTaggedVersionOneNearMatchesFailClosedByteForByte() {
        for (mutation in MalformedV1Mutation.entries) {
            VersionOneDatabaseFixtures.seed(
                context,
                DATABASE_NAME,
                ShippedV1Layout.V0_3_0,
                mutation,
            )
            val beforeOpen = snapshotDatabaseBundle()

            assertProductionOpenFails(mutation.toString())

            assertDatabaseBundleUnchanged(beforeOpen, mutation.toString())
            assertEquals(1, queryLong("PRAGMA user_version"))
            assertHistoricalWriterBackedSentinels(ShippedV1Layout.V0_3_0)
            if (
                mutation != MalformedV1Mutation.NON_EMPTY_SESSIONS &&
                mutation != MalformedV1Mutation.NON_EMPTY_AGENT_SESSIONS
            ) {
                assertEmptyHistoricalStubs()
            }
            assertMalformedMutationStillPresent(mutation)
        }
    }

    @Test
    fun provideAppDatabase_taggedMetadataNearMatchesFailClosedByteForByteForEveryArm() {
        for (layout in ShippedV1Layout.entries) {
            for (mutation in TaggedMetadataMutation.entries) {
                val label = "${layout.slug}-$mutation"
                VersionOneDatabaseFixtures.seed(
                    context = context,
                    databaseName = DATABASE_NAME,
                    layout = layout,
                    metadataMutation = mutation,
                )
                val beforeOpen = snapshotDatabaseBundle()

                assertProductionOpenFails(label)

                assertDatabaseBundleUnchanged(beforeOpen, label)
                assertEquals(1, queryLong("PRAGMA user_version"))
                assertHistoricalWriterBackedSentinels(layout)
                assertEmptyHistoricalStubs()
                assertTaggedMetadataMutationStillPresent(layout, mutation)
            }
        }
    }

    @Test
    fun provideAppDatabase_markerMetadataNearMatchesFailClosedByteForByte() {
        for (mutation in MarkerMetadataMutation.entries) {
            seedStaleIdentityDatabase(
                version = ISSUE_261_STALE_SCHEMA_VERSION,
                markerMutation = mutation,
            )
            val beforeOpen = snapshotDatabaseBundle()

            assertProductionOpenFails(mutation.toString())

            assertDatabaseBundleUnchanged(beforeOpen, mutation.toString())
            assertEquals(1, queryLong("PRAGMA user_version"))
            assertTableExists("stale_issue_261_marker")
            assertMarkerMutationStillPresent(mutation)
        }
    }

    @Test
    fun provideAppDatabase_doesNotDestructivelyRebuildMalformedVersionFiveDatabase() {
        seedStaleIdentityDatabase(version = LEGACY_026_SCHEMA_VERSION)

        val db = StorageModule.provideAppDatabase(context)
        assertThrows(RuntimeException::class.java) {
            try {
                db.openHelper.writableDatabase.query("SELECT 1").close()
            } finally {
                db.close()
            }
        }

        assertTableExists("stale_database_marker")
    }

    private fun assertTaggedVersionOneMigration(layout: ShippedV1Layout) {
        VersionOneDatabaseFixtures.seed(context, DATABASE_NAME, layout)
        assertEquals(
            "Raw fixture drifted from tagged ${layout.slug} application schema",
            VersionOneDatabaseFixtures.expectedApplicationColumns(layout),
            VersionOneDatabaseFixtures.readApplicationColumns(context, DATABASE_NAME),
        )
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM room_master_table"))
        assertEquals(
            layout.identityHash,
            queryString("SELECT identity_hash FROM room_master_table WHERE id = 42"),
        )
        assertHistoricalWriterBackedSentinels(layout)
        assertEmptyHistoricalStubs()

        openProductionDatabase()

        assertCurrentSchemaVersion()
        assertWriterBackedSentinels(layout)
        assertTableMissing("sessions")
        assertTableMissing("agent_sessions")
        assertColumnMissing("hosts", "pathOverride")
        assertForeignKeysValid()
    }

    private fun assertProductionOpenFails(label: String = "unknown v1") {
        val db = StorageModule.provideAppDatabase(context)
        assertThrows(label, RuntimeException::class.java) {
            try {
                db.openHelper.writableDatabase.query("SELECT 1").close()
            } finally {
                db.close()
            }
        }
    }

    private fun openProductionDatabase() {
        val db = StorageModule.provideAppDatabase(context)
        try {
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }
    }

    private fun assertWriterBackedSentinels(layout: ShippedV1Layout) {
        val ids = layout.sentinelIds
        assertRow(
            "SELECT id, name, privateKeyPath, hasPassphrase, createdAt, fingerprint " +
                "FROM ssh_keys WHERE id = ${ids.keyId}",
            ids.keyId,
            "${layout.slug}-key",
            "/keys/${layout.slug}",
            1L,
            ids.baseTime,
            "",
        )
        assertRow(
            """
            SELECT id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                   scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                   lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                   pocketshellCliVersion, pocketshellExpectedCliVersion,
                   pocketshellVersionCompatible, pocketshellDaemonRunning,
                   pocketshellDaemonEnabled, usageCommandOverride
            FROM hosts WHERE id = ${ids.hostId}
            """.trimIndent(),
            ids.hostId,
            "${layout.slug}-host",
            "${layout.slug}.example.com",
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 2222L
                ShippedV1Layout.V0_2_9 -> 2229L
                ShippedV1Layout.V0_3_0 -> 2230L
            },
            "alexey",
            ids.keyId,
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 12000L
                ShippedV1Layout.V0_2_9 -> 12900L
                ShippedV1Layout.V0_3_0 -> 13000L
            },
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 1200L
                ShippedV1Layout.V0_2_9 -> 1290L
                ShippedV1Layout.V0_3_0 -> 1300L
            },
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 7L
                ShippedV1Layout.V0_2_9 -> 9L
                ShippedV1Layout.V0_3_0 -> 10L
            },
            1L,
            ids.baseTime + 1,
            ids.baseTime + 2,
            if (layout == ShippedV1Layout.V0_2_0) null else 1L,
            if (layout == ShippedV1Layout.V0_2_0) null else ids.baseTime + 3,
            when (layout) {
                ShippedV1Layout.V0_2_0 -> null
                ShippedV1Layout.V0_2_9 -> 0L
                ShippedV1Layout.V0_3_0 -> 1L
            },
            if (layout == ShippedV1Layout.V0_3_0) ids.baseTime + 4 else null,
            null,
            null,
            null,
            null,
            null,
            when (layout) {
                ShippedV1Layout.V0_2_0 -> null
                ShippedV1Layout.V0_2_9 -> "legacy-usage-v029"
                ShippedV1Layout.V0_3_0 -> "legacy-usage-v030"
            },
        )
        assertRow(
            "SELECT id, hostId, remotePort, localPort FROM port_remappings " +
                "WHERE id = ${ids.baseId + 2}",
            ids.baseId + 2,
            ids.hostId,
            ids.remotePort.toLong(),
            ids.localPort.toLong(),
        )
        assertRow(
            "SELECT id, hostId, label, body, kind FROM snippets WHERE id = ${ids.baseId + 3}",
            ids.baseId + 3,
            ids.hostId,
            "${layout.slug}-snippet",
            "echo ${layout.slug}-preserved",
            "command",
        )

        when (layout) {
            ShippedV1Layout.V0_2_0 -> {
                assertNull(queryNullableLong("SELECT tmuxInstalled FROM hosts WHERE id = ${ids.hostId}"))
                assertNull(queryNullableLong("SELECT lastBootstrapAt FROM hosts WHERE id = ${ids.hostId}"))
                assertNull(queryNullableLong("SELECT pocketshellInstalled FROM hosts WHERE id = ${ids.hostId}"))
                assertNull(
                    queryNullableLong("SELECT pocketshellLastDetectedAt FROM hosts WHERE id = ${ids.hostId}"),
                )
            }
            ShippedV1Layout.V0_2_9 -> {
                assertEquals(0L, queryLong("SELECT pocketshellInstalled FROM hosts WHERE id = ${ids.hostId}"))
                assertNull(
                    queryNullableLong("SELECT pocketshellLastDetectedAt FROM hosts WHERE id = ${ids.hostId}"),
                )
                assertEquals(
                    "legacy-usage-v029",
                    queryString("SELECT usageCommandOverride FROM hosts WHERE id = ${ids.hostId}"),
                )
                assertExpandedWriterBackedSentinels(layout)
            }
            ShippedV1Layout.V0_3_0 -> {
                assertEquals(1L, queryLong("SELECT pocketshellInstalled FROM hosts WHERE id = ${ids.hostId}"))
                assertEquals(
                    ids.baseTime + 4,
                    queryLong("SELECT pocketshellLastDetectedAt FROM hosts WHERE id = ${ids.hostId}"),
                )
                assertEquals(
                    "legacy-usage-v030",
                    queryString("SELECT usageCommandOverride FROM hosts WHERE id = ${ids.hostId}"),
                )
                assertExpandedWriterBackedSentinels(layout)
            }
        }
    }

    private fun assertExpandedWriterBackedSentinels(layout: ShippedV1Layout) {
        val ids = layout.sentinelIds
        assertRow(
            "SELECT hostId, remotePort, clickCount, totalBytes, lastUsedAt FROM port_usage " +
                "WHERE hostId = ${ids.hostId} AND remotePort = ${ids.remotePort}",
            ids.hostId,
            ids.remotePort.toLong(),
            7L,
            1747L,
            ids.baseTime + 5,
        )
        assertRow(
            "SELECT id, hostId, label, path, createdAt FROM project_roots " +
                "WHERE id = ${ids.baseId + 4}",
            ids.baseId + 4,
            ids.hostId,
            "${layout.slug}-root",
            "/srv/${layout.slug}",
            ids.baseTime + 6,
        )
        assertRow(
            """
            SELECT id, timestampMillis, provider, feature, inputUnits, outputUnits,
                   unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
            FROM ai_api_call_log WHERE id = ${ids.baseId + 5}
            """.trimIndent(),
            ids.baseId + 5,
            ids.baseTime + 7,
            "openai",
            "whisper",
            17L,
            47L,
            10L,
            174L,
            """{"layout":"${layout.slug}"}""",
        )
        assertRow(
            """
            SELECT id, audioPath, recordingTimestampMs, destinationContext, retryCount,
                   lastErrorMessage, audioByteSize, createdAtMs
            FROM pending_transcriptions WHERE id = '${layout.slug}-pending'
            """.trimIndent(),
            "${layout.slug}-pending",
            "/audio/${layout.slug}.wav",
            ids.baseTime + 8,
            "composer",
            1L,
            "offline",
            1747L,
            ids.baseTime + 9,
        )
    }

    private fun assertHistoricalWriterBackedSentinels(layout: ShippedV1Layout) {
        val ids = layout.sentinelIds
        assertRow(
            "SELECT id, name, privateKeyPath, hasPassphrase, createdAt FROM ssh_keys WHERE id = ${ids.keyId}",
            ids.keyId,
            "${layout.slug}-key",
            "/keys/${layout.slug}",
            1L,
            ids.baseTime,
        )
        val hostSql = when (layout) {
            ShippedV1Layout.V0_2_0 ->
                """
                SELECT id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                       scanIntervalSec, enabled, createdAt, lastConnectedAt
                FROM hosts WHERE id = ${ids.hostId}
                """.trimIndent()
            ShippedV1Layout.V0_2_9 ->
                """
                SELECT id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                       scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                       lastBootstrapAt, quseInstalled, quseLastDetectedAt, usageCommandOverride,
                       pathOverride, pocketshellInstalled
                FROM hosts WHERE id = ${ids.hostId}
                """.trimIndent()
            ShippedV1Layout.V0_3_0 ->
                """
                SELECT id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                       scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                       lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                       usageCommandOverride, pathOverride
                FROM hosts WHERE id = ${ids.hostId}
                """.trimIndent()
        }
        val baseHost: List<Any?> = listOf(
            ids.hostId,
            "${layout.slug}-host",
            "${layout.slug}.example.com",
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 2222L
                ShippedV1Layout.V0_2_9 -> 2229L
                ShippedV1Layout.V0_3_0 -> 2230L
            },
            "alexey",
            ids.keyId,
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 12000L
                ShippedV1Layout.V0_2_9 -> 12900L
                ShippedV1Layout.V0_3_0 -> 13000L
            },
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 1200L
                ShippedV1Layout.V0_2_9 -> 1290L
                ShippedV1Layout.V0_3_0 -> 1300L
            },
            when (layout) {
                ShippedV1Layout.V0_2_0 -> 7L
                ShippedV1Layout.V0_2_9 -> 9L
                ShippedV1Layout.V0_3_0 -> 10L
            },
            1L,
            ids.baseTime + 1,
            ids.baseTime + 2,
        )
        val layoutHost: List<Any?> = when (layout) {
            ShippedV1Layout.V0_2_0 -> emptyList()
            ShippedV1Layout.V0_2_9 -> listOf(
                1L,
                ids.baseTime + 3,
                1L,
                ids.baseTime + 4,
                "legacy-usage-v029",
                "/opt/v029/bin",
                0L,
            )
            ShippedV1Layout.V0_3_0 -> listOf(
                1L,
                ids.baseTime + 3,
                1L,
                ids.baseTime + 4,
                "legacy-usage-v030",
                "/opt/v030/bin",
            )
        }
        assertRow(hostSql, *(baseHost + layoutHost).toTypedArray())
        assertRow(
            "SELECT id, hostId, remotePort, localPort FROM port_remappings WHERE id = ${ids.baseId + 2}",
            ids.baseId + 2,
            ids.hostId,
            ids.remotePort.toLong(),
            ids.localPort.toLong(),
        )
        assertRow(
            "SELECT id, hostId, label, body, kind FROM snippets WHERE id = ${ids.baseId + 3}",
            ids.baseId + 3,
            ids.hostId,
            "${layout.slug}-snippet",
            "echo ${layout.slug}-preserved",
            "command",
        )
        if (layout != ShippedV1Layout.V0_2_0) assertExpandedWriterBackedSentinels(layout)
    }

    private fun assertEmptyHistoricalStubs() {
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM sessions"))
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM agent_sessions"))
    }

    private fun assertMalformedMutationStillPresent(mutation: MalformedV1Mutation) {
        when (mutation) {
            MalformedV1Mutation.EXTRA_HOST_COLUMN ->
                assertTrue(tableColumnNames("hosts").contains("malformedExtra"))
            MalformedV1Mutation.MISSING_REQUIRED_COLUMN ->
                assertFalse(tableColumnNames("sessions").contains("tags"))
            MalformedV1Mutation.GENERATED_HOST_COLUMN ->
                assertTrue(tableColumnHidden("hosts", "malformedGenerated") != 0L)
            MalformedV1Mutation.HOSTNAME_AFFINITY ->
                assertEquals("BLOB", tableColumnType("hosts", "hostname"))
            MalformedV1Mutation.HOSTNAME_NULLABILITY ->
                assertEquals(0L, tableColumnNotNull("hosts", "hostname"))
            MalformedV1Mutation.HOSTNAME_CHECK_CONSTRAINT ->
                assertTrue(tableSql("hosts").contains("CHECK", ignoreCase = true))
            MalformedV1Mutation.HOSTNAME_BLOCK_COMMENT_CHECK_CONSTRAINT ->
                assertTrue(tableSql("hosts").contains("CHECK/**/(", ignoreCase = true))
            MalformedV1Mutation.HOSTNAME_LINE_COMMENT_CHECK_CONSTRAINT ->
                assertTrue(
                    tableSql("hosts").contains(
                        "CHECK-- comment-separated constraint",
                        ignoreCase = true,
                    ),
                )
            MalformedV1Mutation.HOSTNAME_COLLATION ->
                assertTrue(tableSql("hosts").contains("COLLATE NOCASE", ignoreCase = true))
            MalformedV1Mutation.HOST_INDEX_COLLATION -> {
                assertEquals("NOCASE", indexKeyCollation("index_hosts_keyId"))
                assertEquals(1L, indexKeyDescending("index_hosts_keyId"))
            }
            MalformedV1Mutation.EXTRA_HOST_UNIQUE_CONSTRAINT ->
                assertEquals(
                    1L,
                    queryLong(
                        "SELECT COUNT(*) FROM pragma_index_list('hosts') WHERE origin = 'u'",
                    ),
                )
            MalformedV1Mutation.MISSING_HOST_AUTOINCREMENT ->
                assertFalse(tableSql("hosts").contains("AUTOINCREMENT", ignoreCase = true))
            MalformedV1Mutation.BLOCK_COMMENT_SPOOFED_HOST_AUTOINCREMENT ->
                assertTrue(tableSql("hosts").contains("/* AUTOINCREMENT */", ignoreCase = true))
            MalformedV1Mutation.LINE_COMMENT_SPOOFED_HOST_AUTOINCREMENT ->
                assertTrue(tableSql("hosts").contains("-- AUTOINCREMENT", ignoreCase = true))
            MalformedV1Mutation.MISSING_HOST_INDEX ->
                assertFalse(indexNames("hosts").contains("index_hosts_keyId"))
            MalformedV1Mutation.MISSING_HOST_FOREIGN_KEY ->
                assertEquals(0L, queryLong("SELECT COUNT(*) FROM pragma_foreign_key_list('hosts')"))
            MalformedV1Mutation.NON_EMPTY_SESSIONS ->
                assertRow(
                    "SELECT id, hostId, name, lastSeenAt, tags FROM sessions",
                    1747L,
                    ShippedV1Layout.V0_3_0.sentinelIds.hostId,
                    "must-survive-session",
                    1747L,
                    "legacy",
                )
            MalformedV1Mutation.NON_EMPTY_AGENT_SESSIONS ->
                assertRow(
                    "SELECT id, paneRef, agent, jsonlPath, detectedAt FROM agent_sessions",
                    1747L,
                    "must-survive-pane",
                    "claude",
                    "/logs/must-survive.jsonl",
                    1747L,
                )
            MalformedV1Mutation.UNEXPECTED_VIEW ->
                assertSchemaObjectExists("view", "malformed_hosts_view")
            MalformedV1Mutation.UNEXPECTED_TRIGGER ->
                assertSchemaObjectExists("trigger", "malformed_hosts_trigger")
        }
    }

    private fun assertTaggedMetadataMutationStillPresent(
        layout: ShippedV1Layout,
        mutation: TaggedMetadataMutation,
    ) {
        when (mutation) {
            TaggedMetadataMutation.MISSING_ROOM_MASTER_TABLE ->
                assertTableMissing("room_master_table")
            TaggedMetadataMutation.EMPTY_ROOM_MASTER_TABLE ->
                assertEquals(0L, queryLong("SELECT COUNT(*) FROM room_master_table"))
            TaggedMetadataMutation.WRONG_IDENTITY_HASH ->
                assertEquals(
                    "not-the-${layout.slug}-identity",
                    queryString("SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
            TaggedMetadataMutation.EXTRA_ROOM_MASTER_COLUMN ->
                assertTrue(tableColumnNames("room_master_table").contains("unexpected_metadata"))
            TaggedMetadataMutation.EXTRA_ROOM_MASTER_ROW -> {
                assertEquals(2L, queryLong("SELECT COUNT(*) FROM room_master_table"))
                assertEquals(
                    "unexpected-extra-room-row",
                    queryString("SELECT identity_hash FROM room_master_table WHERE id = 43"),
                )
            }
        }
    }

    private fun assertMarkerMutationStillPresent(mutation: MarkerMetadataMutation) {
        when (mutation) {
            MarkerMetadataMutation.NON_EMPTY_MARKER ->
                assertEquals(1L, queryLong("SELECT COUNT(*) FROM stale_issue_261_marker"))
            MarkerMetadataMutation.WRONG_IDENTITY_HASH ->
                assertEquals(
                    "not-the-issue-261-identity",
                    queryString("SELECT identity_hash FROM room_master_table WHERE id = 42"),
                )
            MarkerMetadataMutation.EXTRA_ROOM_MASTER_COLUMN ->
                assertTrue(tableColumnNames("room_master_table").contains("unexpected_metadata"))
            MarkerMetadataMutation.MISSING_ROOM_MASTER_TABLE ->
                assertTableMissing("room_master_table")
            MarkerMetadataMutation.EXTRA_ROOM_MASTER_ROW -> {
                assertEquals(2L, queryLong("SELECT COUNT(*) FROM room_master_table"))
                assertEquals(
                    "unexpected-extra-room-row",
                    queryString("SELECT identity_hash FROM room_master_table WHERE id = 43"),
                )
            }
        }
    }

    private fun assertForeignKeysValid() {
        val sqlite = openReadOnlyDatabase()
        sqlite.use {
            it.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("Migrated database contains foreign-key violations", cursor.moveToFirst())
            }
        }
    }

    private fun seedStaleIdentityDatabase(
        version: Int,
        markerMutation: MarkerMetadataMutation? = null,
    ) {
        context.deleteDatabase(DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            it.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("wal", ignoreCase = true))
            }
            if (markerMutation != MarkerMetadataMutation.MISSING_ROOM_MASTER_TABLE) {
                val extraRoomMasterColumn =
                    if (markerMutation == MarkerMetadataMutation.EXTRA_ROOM_MASTER_COLUMN) {
                        ", unexpected_metadata TEXT"
                    } else {
                        ""
                    }
                it.execSQL(
                    "CREATE TABLE room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT$extraRoomMasterColumn)",
                )
                val identityHash =
                    if (markerMutation == MarkerMetadataMutation.WRONG_IDENTITY_HASH) {
                        "not-the-issue-261-identity"
                    } else {
                        LEGACY_CRASH_IDENTITY_HASH
                    }
                it.execSQL(
                    "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                    arrayOf(identityHash),
                )
                if (markerMutation == MarkerMetadataMutation.EXTRA_ROOM_MASTER_ROW) {
                    it.execSQL(
                        """
                        INSERT INTO room_master_table(id, identity_hash)
                        VALUES(43, 'unexpected-extra-room-row')
                        """.trimIndent(),
                    )
                }
            }
            val markerTable = if (version == ISSUE_261_STALE_SCHEMA_VERSION) {
                "stale_issue_261_marker"
            } else {
                "stale_database_marker"
            }
            it.execSQL("CREATE TABLE $markerTable (id INTEGER PRIMARY KEY)")
            if (markerMutation == MarkerMetadataMutation.NON_EMPTY_MARKER) {
                it.execSQL("INSERT INTO $markerTable(id) VALUES(1747)")
            }
            it.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun seedUnknownVersionOneDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use {
            it.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("wal", ignoreCase = true))
            }
            it.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            it.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(LEGACY_CRASH_IDENTITY_HASH),
            )
            it.execSQL("CREATE TABLE unknown_v1_data (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            it.execSQL("INSERT INTO unknown_v1_data(id, payload) VALUES(1747, 'must-survive')")
            it.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun assertCurrentSchemaVersion() {
        val sqlite = openReadOnlyDatabase()
        sqlite.use {
            it.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(APP_DATABASE_SCHEMA_VERSION, cursor.getInt(0))
            }
        }
    }

    private fun assertTableExists(tableName: String) {
        assertTrue(tableExists(tableName))
    }

    private fun assertTableMissing(tableName: String) {
        assertFalse(tableExists(tableName))
    }

    private fun assertColumnMissing(tableName: String, columnName: String) {
        val sqlite = openReadOnlyDatabase()
        sqlite.use {
            it.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    assertFalse(
                        "Column $tableName.$columnName should not exist",
                        cursor.getString(nameIndex) == columnName,
                    )
                }
            }
        }
    }

    private fun tableColumnNames(tableName: String): Set<String> {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            buildSet {
                it.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }
    }

    private fun tableColumnType(tableName: String, columnName: String): String {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            var result: String? = null
            it.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        result = cursor.getString(typeIndex).uppercase()
                        break
                    }
                }
            }
            requireNotNull(result) { "Missing column $tableName.$columnName" }
        }
    }

    private fun tableColumnNotNull(tableName: String, columnName: String): Long {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            var result: Long? = null
            it.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        result = cursor.getLong(notNullIndex)
                        break
                    }
                }
            }
            requireNotNull(result) { "Missing column $tableName.$columnName" }
        }
    }

    private fun tableColumnHidden(tableName: String, columnName: String): Long {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            var result: Long? = null
            it.rawQuery("PRAGMA table_xinfo(`$tableName`)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val hiddenIndex = cursor.getColumnIndexOrThrow("hidden")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        result = cursor.getLong(hiddenIndex)
                        break
                    }
                }
            }
            requireNotNull(result) { "Missing column $tableName.$columnName" }
        }
    }

    private fun tableSql(tableName: String): String =
        queryString("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$tableName'")

    private fun indexKeyCollation(indexName: String): String {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            it.rawQuery(
                "SELECT coll FROM pragma_index_xinfo('$indexName') WHERE key = 1",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }
    }

    private fun indexKeyDescending(indexName: String): Long =
        queryLong("SELECT desc FROM pragma_index_xinfo('$indexName') WHERE key = 1")

    private fun indexNames(tableName: String): Set<String> {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            buildSet {
                it.rawQuery("PRAGMA index_list(`$tableName`)", null).use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        }
    }

    private fun assertSchemaObjectExists(type: String, name: String) {
        val sqlite = openReadOnlyDatabase()
        sqlite.use {
            it.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?",
                arrayOf(type, name),
            ).use { cursor ->
                assertTrue("Expected $type $name to remain", cursor.moveToFirst())
            }
        }
    }

    private fun tableExists(tableName: String): Boolean {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName),
            ).use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    private fun queryString(sql: String): String {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            it.rawQuery(sql, null).use { cursor ->
                assertTrue("Expected one row for: $sql", cursor.moveToFirst())
                cursor.getString(0)
            }
        }
    }

    private fun queryLong(sql: String): Long =
        requireNotNull(queryNullableLong(sql)) { "Expected non-null value for: $sql" }

    private fun queryNullableLong(sql: String): Long? {
        val sqlite = openReadOnlyDatabase()
        return sqlite.use {
            it.rawQuery(sql, null).use { cursor ->
                assertTrue("Expected one row for: $sql", cursor.moveToFirst())
                if (cursor.isNull(0)) null else cursor.getLong(0)
            }
        }
    }

    private fun assertRow(sql: String, vararg expected: Any?) {
        val sqlite = openReadOnlyDatabase()
        sqlite.use {
            it.rawQuery(sql, null).use { cursor ->
                assertTrue("Expected exactly one row for: $sql", cursor.moveToFirst())
                assertEquals(
                    "Column count mismatch for: $sql",
                    expected.size,
                    cursor.columnCount,
                )
                val actual = List(cursor.columnCount) { columnIndex ->
                    when (cursor.getType(columnIndex)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(columnIndex)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(columnIndex)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(columnIndex)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(columnIndex)
                        else -> error("Unknown SQLite cursor type for column $columnIndex")
                    }
                }
                assertEquals("Row mismatch for: $sql", expected.toList(), actual)
                assertFalse("Expected exactly one row for: $sql", cursor.moveToNext())
            }
        }
    }

    private fun snapshotDatabaseBundle(): Map<String, ByteArray> {
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        return DATABASE_SUFFIXES.mapNotNull { suffix ->
            val file = java.io.File(databaseFile.path + suffix)
            if (file.exists()) suffix to file.readBytes() else null
        }.toMap()
    }

    private fun assertDatabaseBundleUnchanged(
        beforeOpen: Map<String, ByteArray>,
        label: String = "unknown v1",
    ) {
        val afterOpen = snapshotDatabaseBundle()
        assertEquals(
            "$label changed the SQLite bundle inventory",
            beforeOpen.keys,
            afterOpen.keys,
        )
        for ((suffix, expectedBytes) in beforeOpen) {
            assertArrayEquals(
                "$label changed pocketshell.db$suffix despite fail-closed classification",
                expectedBytes,
                afterOpen.getValue(suffix),
            )
        }
    }

    private fun openReadOnlyDatabase(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val ISSUE_261_STALE_SCHEMA_VERSION = 1
        const val LEGACY_026_SCHEMA_VERSION = 5
        const val LEGACY_CRASH_IDENTITY_HASH = "4a479a15dfcab2d576e00c7ce10ac581"
        val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }

    private enum class MarkerMetadataMutation {
        NON_EMPTY_MARKER,
        WRONG_IDENTITY_HASH,
        EXTRA_ROOM_MASTER_COLUMN,
        MISSING_ROOM_MASTER_TABLE,
        EXTRA_ROOM_MASTER_ROW,
    }
}
