package com.pocketshell.app.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Raw on-disk reconstructions of the three distinct schema-v1 layouts shipped
 * in v0.2.0, v0.2.9, and v0.3.0.
 *
 * The tagged builds predate Room schema export, so these fixtures mirror the
 * entity DDL at those tags and the exact row-42 identity hash embedded in each
 * published APK.
 */
internal object VersionOneDatabaseFixtures {
    fun seed(
        context: Context,
        databaseName: String,
        layout: ShippedV1Layout,
        mutation: MalformedV1Mutation? = null,
        metadataMutation: TaggedMetadataMutation? = null,
    ) {
        context.deleteDatabase(databaseName)
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("wal", ignoreCase = true))
            }
            db.execSQL("PRAGMA foreign_keys=OFF")
            createRoomMasterTable(db, layout, metadataMutation)
            createSshKeys(db)
            createHosts(db, layout, mutation)
            createPortRemappings(db)
            createSessions(db, mutation)
            createSnippets(db, labelNotNull = layout == ShippedV1Layout.V0_2_0)
            createAgentSessions(db)
            if (layout != ShippedV1Layout.V0_2_0) {
                createPortUsage(db)
                createProjectRoots(db)
                createAiApiCallLog(db)
                createPendingTranscriptions(db)
            }
            insertWriterBackedSentinels(db, layout, mutation)
            when (mutation) {
                MalformedV1Mutation.NON_EMPTY_SESSIONS -> db.execSQL(
                    """
                    INSERT INTO sessions(id, hostId, name, lastSeenAt, tags)
                    VALUES(1747, ${layout.sentinelIds.hostId}, 'must-survive-session', 1747, 'legacy')
                    """.trimIndent(),
                )
                MalformedV1Mutation.NON_EMPTY_AGENT_SESSIONS -> db.execSQL(
                    """
                    INSERT INTO agent_sessions(id, paneRef, agent, jsonlPath, detectedAt)
                    VALUES(1747, 'must-survive-pane', 'claude', '/logs/must-survive.jsonl', 1747)
                    """.trimIndent(),
                )
                MalformedV1Mutation.UNEXPECTED_VIEW ->
                    db.execSQL("CREATE VIEW malformed_hosts_view AS SELECT id FROM hosts")
                MalformedV1Mutation.UNEXPECTED_TRIGGER -> db.execSQL(
                    """
                    CREATE TRIGGER malformed_hosts_trigger
                    AFTER UPDATE ON hosts
                    BEGIN
                        SELECT 1;
                    END
                    """.trimIndent(),
                )
                else -> Unit
            }
            db.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun createRoomMasterTable(
        db: SQLiteDatabase,
        layout: ShippedV1Layout,
        mutation: TaggedMetadataMutation?,
    ) {
        if (mutation == TaggedMetadataMutation.MISSING_ROOM_MASTER_TABLE) return
        val extraColumn = if (mutation == TaggedMetadataMutation.EXTRA_ROOM_MASTER_COLUMN) {
            ", unexpected_metadata TEXT"
        } else {
            ""
        }
        db.execSQL(
            "CREATE TABLE room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT$extraColumn)",
        )
        if (mutation == TaggedMetadataMutation.EMPTY_ROOM_MASTER_TABLE) return
        val identityHash = if (mutation == TaggedMetadataMutation.WRONG_IDENTITY_HASH) {
            "not-the-${layout.slug}-identity"
        } else {
            layout.identityHash
        }
        db.execSQL(
            "INSERT INTO room_master_table(id, identity_hash) VALUES(42, ?)",
            arrayOf(identityHash),
        )
        if (mutation == TaggedMetadataMutation.EXTRA_ROOM_MASTER_ROW) {
            db.execSQL(
                """
                INSERT INTO room_master_table(id, identity_hash)
                VALUES(43, 'unexpected-extra-room-row')
                """.trimIndent(),
            )
        }
    }

    fun expectedApplicationColumns(layout: ShippedV1Layout): Map<String, List<String>> =
        buildMap {
            put("agent_sessions", listOf("id", "paneRef", "agent", "jsonlPath", "detectedAt"))
            if (layout != ShippedV1Layout.V0_2_0) {
                put(
                    "ai_api_call_log",
                    listOf(
                        "id",
                        "timestampMillis",
                        "provider",
                        "feature",
                        "inputUnits",
                        "outputUnits",
                        "unitCostUsdMillicents",
                        "computedCostUsdMillicents",
                        "metadataJson",
                    ),
                )
            }
            put(
                "hosts",
                when (layout) {
                    ShippedV1Layout.V0_2_0 -> BASE_HOST_COLUMNS
                    ShippedV1Layout.V0_2_9 -> BASE_HOST_COLUMNS + listOf(
                        "tmuxInstalled",
                        "lastBootstrapAt",
                        "quseInstalled",
                        "quseLastDetectedAt",
                        "usageCommandOverride",
                        "pathOverride",
                        "pocketshellInstalled",
                    )
                    ShippedV1Layout.V0_3_0 -> BASE_HOST_COLUMNS + listOf(
                        "tmuxInstalled",
                        "lastBootstrapAt",
                        "pocketshellInstalled",
                        "pocketshellLastDetectedAt",
                        "usageCommandOverride",
                        "pathOverride",
                    )
                },
            )
            if (layout != ShippedV1Layout.V0_2_0) {
                put(
                    "pending_transcriptions",
                    listOf(
                        "id",
                        "audioPath",
                        "recordingTimestampMs",
                        "destinationContext",
                        "retryCount",
                        "lastErrorMessage",
                        "audioByteSize",
                        "createdAtMs",
                    ),
                )
            }
            put("port_remappings", listOf("id", "hostId", "remotePort", "localPort"))
            if (layout != ShippedV1Layout.V0_2_0) {
                put("port_usage", listOf("hostId", "remotePort", "clickCount", "totalBytes", "lastUsedAt"))
                put("project_roots", listOf("id", "hostId", "label", "path", "createdAt"))
            }
            put("sessions", listOf("id", "hostId", "name", "lastSeenAt", "tags"))
            put("snippets", listOf("id", "hostId", "label", "body", "kind"))
            put("ssh_keys", listOf("id", "name", "privateKeyPath", "hasPassphrase", "createdAt"))
        }.toSortedMap()

    fun readApplicationColumns(context: Context, databaseName: String): Map<String, List<String>> {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return db.use {
            val tableNames = buildList {
                it.rawQuery(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'table'
                      AND name NOT LIKE 'sqlite_%'
                      AND name NOT IN ('android_metadata', 'room_master_table')
                    ORDER BY name
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            tableNames.associateWith { tableName ->
                buildList {
                    it.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
            }
        }
    }

    private fun createSshKeys(db: SQLiteDatabase) {
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
    }

    private fun createHosts(
        db: SQLiteDatabase,
        layout: ShippedV1Layout,
        mutation: MalformedV1Mutation?,
    ) {
        val hostnameColumn = when (mutation) {
            MalformedV1Mutation.HOSTNAME_AFFINITY -> "hostname BLOB NOT NULL"
            MalformedV1Mutation.HOSTNAME_NULLABILITY -> "hostname TEXT"
            MalformedV1Mutation.HOSTNAME_CHECK_CONSTRAINT ->
                "hostname TEXT NOT NULL CHECK(length(hostname) > 0)"
            MalformedV1Mutation.HOSTNAME_BLOCK_COMMENT_CHECK_CONSTRAINT ->
                "hostname TEXT NOT NULL CHECK/**/(length(hostname) > 0)"
            MalformedV1Mutation.HOSTNAME_LINE_COMMENT_CHECK_CONSTRAINT ->
                "hostname TEXT NOT NULL CHECK-- comment-separated constraint\n" +
                    "(length(hostname) > 0)"
            MalformedV1Mutation.HOSTNAME_COLLATION -> "hostname TEXT NOT NULL COLLATE NOCASE"
            else -> "hostname TEXT NOT NULL"
        }
        val definitions = mutableListOf(
            when (mutation) {
                MalformedV1Mutation.MISSING_HOST_AUTOINCREMENT ->
                    "id INTEGER PRIMARY KEY NOT NULL"
                MalformedV1Mutation.BLOCK_COMMENT_SPOOFED_HOST_AUTOINCREMENT ->
                    "id INTEGER PRIMARY KEY NOT NULL /* AUTOINCREMENT */"
                MalformedV1Mutation.LINE_COMMENT_SPOOFED_HOST_AUTOINCREMENT ->
                    "id INTEGER PRIMARY KEY NOT NULL -- AUTOINCREMENT\n"
                else -> "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"
            },
            "name TEXT NOT NULL",
            hostnameColumn,
            "port INTEGER NOT NULL",
            "username TEXT NOT NULL",
            "keyId INTEGER NOT NULL",
            "maxAutoPort INTEGER NOT NULL",
            "skipPortsBelow INTEGER NOT NULL",
            "scanIntervalSec INTEGER NOT NULL",
            "enabled INTEGER NOT NULL",
            "createdAt INTEGER NOT NULL",
            "lastConnectedAt INTEGER",
        )
        definitions += when (layout) {
            ShippedV1Layout.V0_2_0 -> emptyList()
            ShippedV1Layout.V0_2_9 -> listOf(
                "tmuxInstalled INTEGER",
                "lastBootstrapAt INTEGER",
                "quseInstalled INTEGER",
                "quseLastDetectedAt INTEGER",
                "usageCommandOverride TEXT",
                "pathOverride TEXT",
                "pocketshellInstalled INTEGER",
            )
            ShippedV1Layout.V0_3_0 -> listOf(
                "tmuxInstalled INTEGER",
                "lastBootstrapAt INTEGER",
                "pocketshellInstalled INTEGER",
                "pocketshellLastDetectedAt INTEGER",
                "usageCommandOverride TEXT",
                "pathOverride TEXT",
            )
        }
        if (mutation == MalformedV1Mutation.EXTRA_HOST_COLUMN) {
            definitions += "malformedExtra TEXT"
        }
        if (mutation == MalformedV1Mutation.GENERATED_HOST_COLUMN) {
            // GENERATED is optional SQLite syntax. Omitting the keyword proves
            // the classifier uses table_xinfo.hidden rather than token matching.
            definitions += "malformedGenerated TEXT AS (name) STORED"
        }
        if (mutation == MalformedV1Mutation.EXTRA_HOST_UNIQUE_CONSTRAINT) {
            definitions += "UNIQUE(name)"
        }
        if (mutation != MalformedV1Mutation.MISSING_HOST_FOREIGN_KEY) {
            definitions +=
                "FOREIGN KEY(keyId) REFERENCES ssh_keys(id) ON UPDATE NO ACTION ON DELETE CASCADE"
        }
        db.execSQL(
            """
            CREATE TABLE hosts (
                ${definitions.joinToString(",\n                ")}
            )
            """.trimIndent(),
        )
        if (mutation != MalformedV1Mutation.MISSING_HOST_INDEX) {
            val indexColumn =
                if (mutation == MalformedV1Mutation.HOST_INDEX_COLLATION) {
                    "keyId COLLATE NOCASE DESC"
                } else {
                    "keyId"
                }
            db.execSQL("CREATE INDEX index_hosts_keyId ON hosts($indexColumn)")
        }
    }

    private fun createPortRemappings(db: SQLiteDatabase) {
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
                "ON port_remappings(hostId, remotePort)",
        )
    }

    private fun createSessions(db: SQLiteDatabase, mutation: MalformedV1Mutation?) {
        val tagsColumn = if (mutation == MalformedV1Mutation.MISSING_REQUIRED_COLUMN) {
            ""
        } else {
            ",\n                tags TEXT"
        }
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                name TEXT NOT NULL,
                lastSeenAt INTEGER NOT NULL$tagsColumn,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_sessions_hostId ON sessions(hostId)")
        db.execSQL("CREATE UNIQUE INDEX index_sessions_hostId_name ON sessions(hostId, name)")
    }

    private fun createSnippets(db: SQLiteDatabase, labelNotNull: Boolean) {
        val labelColumn = if (labelNotNull) "label TEXT NOT NULL" else "label TEXT"
        db.execSQL(
            """
            CREATE TABLE snippets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                $labelColumn,
                body TEXT NOT NULL,
                kind TEXT NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_snippets_hostId ON snippets(hostId)")
    }

    private fun createAgentSessions(db: SQLiteDatabase) {
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
    }

    private fun createPortUsage(db: SQLiteDatabase) {
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
    }

    private fun createProjectRoots(db: SQLiteDatabase) {
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
        db.execSQL("CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path)")
    }

    private fun createAiApiCallLog(db: SQLiteDatabase) {
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
            "CREATE INDEX index_ai_api_call_log_provider_feature ON ai_api_call_log(provider, feature)",
        )
    }

    private fun createPendingTranscriptions(db: SQLiteDatabase) {
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
                "ON pending_transcriptions(recordingTimestampMs)",
        )
    }

    private fun insertWriterBackedSentinels(
        db: SQLiteDatabase,
        layout: ShippedV1Layout,
        mutation: MalformedV1Mutation?,
    ) {
        val ids = layout.sentinelIds
        db.execSQL(
            """
            INSERT INTO ssh_keys(id, name, privateKeyPath, hasPassphrase, createdAt)
            VALUES(${ids.keyId}, '${layout.slug}-key', '/keys/${layout.slug}', 1, ${ids.baseTime})
            """.trimIndent(),
        )
        when (layout) {
            ShippedV1Layout.V0_2_0 -> db.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt
                ) VALUES(
                    ${ids.hostId}, '${layout.slug}-host', '${layout.slug}.example.com', 2222,
                    'alexey', ${ids.keyId}, 12000, 1200, 7, 1, ${ids.baseTime + 1}, ${ids.baseTime + 2}
                )
                """.trimIndent(),
            )
            ShippedV1Layout.V0_2_9 -> db.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, quseInstalled, quseLastDetectedAt, usageCommandOverride,
                    pathOverride, pocketshellInstalled
                ) VALUES(
                    ${ids.hostId}, '${layout.slug}-host', '${layout.slug}.example.com', 2229,
                    'alexey', ${ids.keyId}, 12900, 1290, 9, 1, ${ids.baseTime + 1},
                    ${ids.baseTime + 2}, 1, ${ids.baseTime + 3}, 1, ${ids.baseTime + 4},
                    'legacy-usage-v029', '/opt/v029/bin', 0
                )
                """.trimIndent(),
            )
            ShippedV1Layout.V0_3_0 -> db.execSQL(
                """
                INSERT INTO hosts(
                    id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow,
                    scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled,
                    lastBootstrapAt, pocketshellInstalled, pocketshellLastDetectedAt,
                    usageCommandOverride, pathOverride
                ) VALUES(
                    ${ids.hostId}, '${layout.slug}-host', '${layout.slug}.example.com', 2230,
                    'alexey', ${ids.keyId}, 13000, 1300, 10, 1, ${ids.baseTime + 1},
                    ${ids.baseTime + 2}, 1, ${ids.baseTime + 3}, 1, ${ids.baseTime + 4},
                    'legacy-usage-v030', '/opt/v030/bin'
                )
                """.trimIndent(),
            )
        }
        db.execSQL(
            """
            INSERT INTO port_remappings(id, hostId, remotePort, localPort)
            VALUES(${ids.baseId + 2}, ${ids.hostId}, ${ids.remotePort}, ${ids.localPort})
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO snippets(id, hostId, label, body, kind)
            VALUES(${ids.baseId + 3}, ${ids.hostId}, '${layout.slug}-snippet',
                   'echo ${layout.slug}-preserved', 'command')
            """.trimIndent(),
        )

        if (layout != ShippedV1Layout.V0_2_0) {
            db.execSQL(
                """
                INSERT INTO port_usage(hostId, remotePort, clickCount, totalBytes, lastUsedAt)
                VALUES(${ids.hostId}, ${ids.remotePort}, 7, 1747, ${ids.baseTime + 5})
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO project_roots(id, hostId, label, path, createdAt)
                VALUES(${ids.baseId + 4}, ${ids.hostId}, '${layout.slug}-root',
                       '/srv/${layout.slug}', ${ids.baseTime + 6})
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO ai_api_call_log(
                    id, timestampMillis, provider, feature, inputUnits, outputUnits,
                    unitCostUsdMillicents, computedCostUsdMillicents, metadataJson
                ) VALUES(
                    ${ids.baseId + 5}, ${ids.baseTime + 7}, 'openai', 'whisper',
                    17, 47, 10, 174, '{"layout":"${layout.slug}"}'
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO pending_transcriptions(
                    id, audioPath, recordingTimestampMs, destinationContext, retryCount,
                    lastErrorMessage, audioByteSize, createdAtMs
                ) VALUES(
                    '${layout.slug}-pending', '/audio/${layout.slug}.wav', ${ids.baseTime + 8},
                    'composer', 1, 'offline', 1747, ${ids.baseTime + 9}
                )
                """.trimIndent(),
            )
        }
    }

    private val BASE_HOST_COLUMNS = listOf(
        "id",
        "name",
        "hostname",
        "port",
        "username",
        "keyId",
        "maxAutoPort",
        "skipPortsBelow",
        "scanIntervalSec",
        "enabled",
        "createdAt",
        "lastConnectedAt",
    )
}

internal enum class ShippedV1Layout(
    val slug: String,
    val sentinelIds: SentinelIds,
    val identityHash: String,
) {
    V0_2_0(
        "v020",
        SentinelIds(baseId = 200, baseTime = 2_000),
        "4f9cdd46489198fdda4b34e6b682d56d",
    ),
    V0_2_9(
        "v029",
        SentinelIds(baseId = 290, baseTime = 2_900),
        "4a479a15dfcab2d576e00c7ce10ac581",
    ),
    V0_3_0(
        "v030",
        SentinelIds(baseId = 300, baseTime = 3_000),
        "5c2d470ba861de091b4dad454b282704",
    ),
}

internal enum class MalformedV1Mutation {
    EXTRA_HOST_COLUMN,
    MISSING_REQUIRED_COLUMN,
    GENERATED_HOST_COLUMN,
    HOSTNAME_AFFINITY,
    HOSTNAME_NULLABILITY,
    HOSTNAME_CHECK_CONSTRAINT,
    HOSTNAME_BLOCK_COMMENT_CHECK_CONSTRAINT,
    HOSTNAME_LINE_COMMENT_CHECK_CONSTRAINT,
    HOSTNAME_COLLATION,
    HOST_INDEX_COLLATION,
    EXTRA_HOST_UNIQUE_CONSTRAINT,
    MISSING_HOST_AUTOINCREMENT,
    BLOCK_COMMENT_SPOOFED_HOST_AUTOINCREMENT,
    LINE_COMMENT_SPOOFED_HOST_AUTOINCREMENT,
    MISSING_HOST_INDEX,
    MISSING_HOST_FOREIGN_KEY,
    NON_EMPTY_SESSIONS,
    NON_EMPTY_AGENT_SESSIONS,
    UNEXPECTED_VIEW,
    UNEXPECTED_TRIGGER,
}

internal enum class TaggedMetadataMutation {
    MISSING_ROOM_MASTER_TABLE,
    EMPTY_ROOM_MASTER_TABLE,
    WRONG_IDENTITY_HASH,
    EXTRA_ROOM_MASTER_COLUMN,
    EXTRA_ROOM_MASTER_ROW,
}

internal data class SentinelIds(
    val baseId: Long,
    val baseTime: Long,
) {
    val keyId: Long = baseId
    val hostId: Long = baseId + 1
    val remotePort: Int = baseId.toInt() + 4_000
    val localPort: Int = baseId.toInt() + 14_000
}
