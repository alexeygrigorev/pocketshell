package com.pocketshell.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Recovers the three distinct application-schema layouts that shipped with
 * `PRAGMA user_version = 1`, plus the exact data-free issue-261 marker
 * database. Classification is deliberately read-only and exact: an unknown v1
 * database fails closed before any DDL can mutate user data.
 */
val MIGRATION_1_8: Migration = object : Migration(1, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        when (db.classifyVersionOneSchema()) {
            VersionOneSchema.V0_2_0 -> normalizeLegacySchemaToVersionEight(db)
            VersionOneSchema.V0_2_9 -> normalizeLegacySchemaToVersionEight(
                db,
                forcePocketshellLastDetectedAtNull = true,
            )
            VersionOneSchema.V0_3_0 -> normalizeLegacySchemaToVersionEight(db)
            VersionOneSchema.ISSUE_261_MARKER -> replaceIssue261MarkerWithVersionEightSchema(db)
            null -> throw IllegalStateException(
                "Unsupported PocketShell schema v1 layout; refusing to modify or delete the database",
            )
        }
    }
}

private enum class VersionOneSchema {
    V0_2_0,
    V0_2_9,
    V0_3_0,
    ISSUE_261_MARKER,
}

private data class VersionOneColumn(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int,
    val defaultValue: String?,
    val hidden: Int,
)

private data class VersionOneIndexColumn(
    val name: String?,
    val descending: Boolean,
    val collation: String,
)

private data class VersionOneIndex(
    val name: String?,
    val unique: Boolean,
    val columns: List<VersionOneIndexColumn>,
    val origin: String,
    val partial: Boolean,
)

private data class VersionOneForeignKey(
    val referencedTable: String,
    val fromColumn: String,
    val toColumn: String,
    val onUpdate: String,
    val onDelete: String,
    val match: String,
)

private data class VersionOneTable(
    val columns: List<VersionOneColumn>,
    val indices: List<VersionOneIndex> = emptyList(),
    val foreignKeys: List<VersionOneForeignKey> = emptyList(),
    val autoIncrement: Boolean = false,
)

private val VERSION_ONE_INDEX_COMPARATOR =
    compareBy<VersionOneIndex>(
        { it.name ?: "" },
        { it.origin },
        { it.unique },
        { it.partial },
        {
            it.columns.joinToString("\u0000") { column ->
                "${column.name}\u0001${column.descending}\u0001${column.collation}"
            }
        },
    )

private fun SupportSQLiteDatabase.classifyVersionOneSchema(): VersionOneSchema? {
    // Keep every classifier read above the first possible DDL in MIGRATION_1_8.
    if (hasUnexpectedVersionOneObjects()) return null
    if (hasVersionOneSqlComments()) return null
    if (hasUnsupportedVersionOneTableSemantics()) return null
    val actual = readVersionOneApplicationSchema()
    return when (actual) {
        VERSION_ONE_V0_2_0_SCHEMA ->
            if (
                hasExactRoomMetadata(V0_2_0_IDENTITY_HASH) &&
                hasEmptyHistoricalStubTables()
            ) {
                VersionOneSchema.V0_2_0
            } else {
                null
            }
        VERSION_ONE_V0_2_9_SCHEMA ->
            if (
                hasExactRoomMetadata(V0_2_9_IDENTITY_HASH) &&
                hasEmptyHistoricalStubTables()
            ) {
                VersionOneSchema.V0_2_9
            } else {
                null
            }
        VERSION_ONE_V0_3_0_SCHEMA ->
            if (
                hasExactRoomMetadata(V0_3_0_IDENTITY_HASH) &&
                hasEmptyHistoricalStubTables()
            ) {
                VersionOneSchema.V0_3_0
            } else {
                null
            }
        VERSION_ONE_ISSUE_261_MARKER_SCHEMA ->
            if (hasExactIssue261MarkerMetadata()) VersionOneSchema.ISSUE_261_MARKER else null
        else -> null
    }
}

private fun SupportSQLiteDatabase.hasExactRoomMetadata(identityHash: String): Boolean {
    if (
        !hasVersionOneTable("room_master_table") ||
        VersionOneTable(
            columns = readVersionOneColumns("room_master_table"),
            indices = readVersionOneIndices("room_master_table"),
            foreignKeys = readVersionOneForeignKeys("room_master_table"),
            autoIncrement = readVersionOneAutoIncrement("room_master_table"),
        ) != VERSION_ONE_ROOM_MASTER_SCHEMA
    ) {
        return false
    }

    query("SELECT id, identity_hash FROM room_master_table ORDER BY id").use { cursor ->
        if (!cursor.moveToFirst()) return false
        if (cursor.getInt(0) != ROOM_MASTER_ID || cursor.getString(1) != identityHash) {
            return false
        }
        if (cursor.moveToNext()) return false
    }
    return true
}

private fun SupportSQLiteDatabase.hasExactIssue261MarkerMetadata(): Boolean {
    if (!hasExactRoomMetadata(ISSUE_261_IDENTITY_HASH)) return false
    query("SELECT COUNT(*) FROM stale_issue_261_marker").use { cursor ->
        return cursor.moveToFirst() && cursor.getLong(0) == 0L
    }
}

private fun SupportSQLiteDatabase.hasEmptyHistoricalStubTables(): Boolean =
    listOf("sessions", "agent_sessions").all { tableName ->
        query("SELECT 1 FROM ${quoteIdentifier(tableName)} LIMIT 1").use { cursor ->
            !cursor.moveToFirst()
        }
    }

private fun SupportSQLiteDatabase.hasVersionOneTable(tableName: String): Boolean {
    query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(tableName),
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

private fun SupportSQLiteDatabase.hasUnexpectedVersionOneObjects(): Boolean {
    query(
        """
        SELECT name
        FROM sqlite_master
        WHERE type IN ('view', 'trigger')
          AND name NOT LIKE 'sqlite_%'
        LIMIT 1
        """.trimIndent(),
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

/**
 * Room emitted no comments in any shipped v1 CREATE TABLE statement. Reject
 * comments before token-based semantic checks so comment text cannot spoof an
 * AUTOINCREMENT token or split an unsupported CHECK clause.
 * This deliberately fail-closed rule avoids pretending the classifier is a
 * permissive SQL parser.
 */
private fun SupportSQLiteDatabase.hasVersionOneSqlComments(): Boolean {
    query(
        """
        SELECT sql
        FROM sqlite_master
        WHERE type = 'table'
          AND name NOT LIKE 'sqlite_%'
          AND name != 'android_metadata'
        """.trimIndent(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.isNull(0)) return true
            if (SQL_COMMENT_TOKEN.containsMatchIn(cursor.getString(0))) return true
        }
    }
    return false
}

/**
 * The structural signature below intentionally ignores whitespace, quoting,
 * and declaration formatting, but it must not ignore table semantics that the
 * SQLite PRAGMAs do not expose. None of the three tagged schemas uses these
 * clauses. Rejecting them makes an unknown near-match fail closed instead of
 * treating CHECK/collation/conflict/rowid behavior as the shipped layout.
 *
 * The remaining accepted variation is syntax-only: table_xinfo covers visible
 * and hidden columns, index_list includes implicit sqlite_autoindex entries,
 * index_xinfo covers expressions/order/collation, foreign keys remain a list so
 * duplicates cannot collapse, and AUTOINCREMENT is compared separately.
 */
private fun SupportSQLiteDatabase.hasUnsupportedVersionOneTableSemantics(): Boolean {
    query(
        """
        SELECT sql
        FROM sqlite_master
        WHERE type = 'table'
          AND name NOT LIKE 'sqlite_%'
          AND name != 'android_metadata'
        """.trimIndent(),
    ).use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.isNull(0)) return true
            if (UNSUPPORTED_TABLE_SEMANTICS.containsMatchIn(cursor.getString(0))) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.readVersionOneApplicationSchema(): Map<String, VersionOneTable> {
    val tableNames = buildList {
        query(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT IN ('android_metadata', 'room_master_table')
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }
    return tableNames.associateWith { tableName ->
        VersionOneTable(
            columns = readVersionOneColumns(tableName),
            indices = readVersionOneIndices(tableName),
            foreignKeys = readVersionOneForeignKeys(tableName),
            autoIncrement = readVersionOneAutoIncrement(tableName),
        )
    }
}

private fun SupportSQLiteDatabase.readVersionOneColumns(tableName: String): List<VersionOneColumn> =
    buildList {
        query("PRAGMA table_xinfo(${quoteIdentifier(tableName)})").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val primaryKeyIndex = cursor.getColumnIndexOrThrow("pk")
            val defaultValueIndex = cursor.getColumnIndexOrThrow("dflt_value")
            val hiddenIndex = cursor.getColumnIndexOrThrow("hidden")
            while (cursor.moveToNext()) {
                add(
                    VersionOneColumn(
                        name = cursor.getString(nameIndex),
                        type = cursor.getString(typeIndex).uppercase(),
                        notNull = cursor.getInt(notNullIndex) == 1,
                        primaryKeyPosition = cursor.getInt(primaryKeyIndex),
                        defaultValue = if (cursor.isNull(defaultValueIndex)) {
                            null
                        } else {
                            cursor.getString(defaultValueIndex)
                        },
                        hidden = cursor.getInt(hiddenIndex),
                    ),
                )
            }
        }
    }

private fun SupportSQLiteDatabase.readVersionOneIndices(tableName: String): List<VersionOneIndex> =
    buildList {
        query("PRAGMA index_list(${quoteIdentifier(tableName)})").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            val originIndex = cursor.getColumnIndexOrThrow("origin")
            val partialIndex = cursor.getColumnIndexOrThrow("partial")
            while (cursor.moveToNext()) {
                val indexName = cursor.getString(nameIndex)
                val origin = cursor.getString(originIndex)
                add(
                    VersionOneIndex(
                        name = if (origin == "c") indexName else null,
                        unique = cursor.getInt(uniqueIndex) == 1,
                        columns = readVersionOneIndexColumns(indexName),
                        origin = origin,
                        partial = cursor.getInt(partialIndex) == 1,
                    ),
                )
            }
        }
    }.sortedWith(VERSION_ONE_INDEX_COMPARATOR)

private fun SupportSQLiteDatabase.readVersionOneIndexColumns(
    indexName: String,
): List<VersionOneIndexColumn> =
    buildList {
        query("PRAGMA index_xinfo(${quoteIdentifier(indexName)})").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val descendingIndex = cursor.getColumnIndexOrThrow("desc")
            val collationIndex = cursor.getColumnIndexOrThrow("coll")
            val keyIndex = cursor.getColumnIndexOrThrow("key")
            while (cursor.moveToNext()) {
                if (cursor.getInt(keyIndex) != 1) continue
                add(
                    VersionOneIndexColumn(
                        name = if (cursor.isNull(nameIndex)) null else cursor.getString(nameIndex),
                        descending = cursor.getInt(descendingIndex) == 1,
                        collation = if (cursor.isNull(collationIndex)) {
                            ""
                        } else {
                            cursor.getString(collationIndex).uppercase()
                        },
                    ),
                )
            }
        }
    }

private fun SupportSQLiteDatabase.readVersionOneForeignKeys(
    tableName: String,
): List<VersionOneForeignKey> = buildList {
    query("PRAGMA foreign_key_list(${quoteIdentifier(tableName)})").use { cursor ->
        val tableIndex = cursor.getColumnIndexOrThrow("table")
        val fromIndex = cursor.getColumnIndexOrThrow("from")
        val toIndex = cursor.getColumnIndexOrThrow("to")
        val onUpdateIndex = cursor.getColumnIndexOrThrow("on_update")
        val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
        val matchIndex = cursor.getColumnIndexOrThrow("match")
        while (cursor.moveToNext()) {
            add(
                VersionOneForeignKey(
                    referencedTable = cursor.getString(tableIndex),
                    fromColumn = cursor.getString(fromIndex),
                    toColumn = cursor.getString(toIndex),
                    onUpdate = cursor.getString(onUpdateIndex).uppercase(),
                    onDelete = cursor.getString(onDeleteIndex).uppercase(),
                    match = cursor.getString(matchIndex).uppercase(),
                ),
            )
        }
    }
}

private fun SupportSQLiteDatabase.readVersionOneAutoIncrement(tableName: String): Boolean {
    query(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(tableName),
    ).use { cursor ->
        return cursor.moveToFirst() &&
            !cursor.isNull(0) &&
            AUTOINCREMENT_TOKEN.containsMatchIn(cursor.getString(0))
    }
}

private fun quoteIdentifier(identifier: String): String =
    "`" + identifier.replace("`", "``") + "`"

private fun replaceIssue261MarkerWithVersionEightSchema(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE stale_issue_261_marker")
    createVersionEightBaseTables(db)
}

private fun createVersionEightBaseTables(db: SupportSQLiteDatabase) {
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
        "CREATE UNIQUE INDEX index_project_roots_hostId_path ON project_roots(hostId, path)",
    )
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

private fun column(
    name: String,
    type: String,
    notNull: Boolean,
    primaryKeyPosition: Int = 0,
): VersionOneColumn = VersionOneColumn(
    name = name,
    type = type,
    notNull = notNull,
    primaryKeyPosition = primaryKeyPosition,
    defaultValue = null,
    hidden = 0,
)

private fun index(name: String, unique: Boolean, vararg columns: String): VersionOneIndex =
    VersionOneIndex(
        name = name,
        unique = unique,
        columns = columns.map { VersionOneIndexColumn(it, false, "BINARY") },
        origin = "c",
        partial = false,
    )

private fun primaryKeyIndex(vararg columns: String): VersionOneIndex =
    VersionOneIndex(
        name = null,
        unique = true,
        columns = columns.map { VersionOneIndexColumn(it, false, "BINARY") },
        origin = "pk",
        partial = false,
    )

private fun indices(vararg values: VersionOneIndex): List<VersionOneIndex> =
    values.sortedWith(VERSION_ONE_INDEX_COMPARATOR)

private fun foreignKey(
    referencedTable: String,
    fromColumn: String,
    toColumn: String,
): VersionOneForeignKey = VersionOneForeignKey(
    referencedTable = referencedTable,
    fromColumn = fromColumn,
    toColumn = toColumn,
    onUpdate = "NO ACTION",
    onDelete = "CASCADE",
    match = "NONE",
)

private val BASE_VERSION_ONE_TABLES: Map<String, VersionOneTable> = mapOf(
    "ssh_keys" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("name", "TEXT", true),
            column("privateKeyPath", "TEXT", true),
            column("hasPassphrase", "INTEGER", true),
            column("createdAt", "INTEGER", true),
        ),
        autoIncrement = true,
    ),
    "port_remappings" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("hostId", "INTEGER", true),
            column("remotePort", "INTEGER", true),
            column("localPort", "INTEGER", true),
        ),
        indices = indices(
            index("index_port_remappings_hostId", false, "hostId"),
            index("index_port_remappings_hostId_remotePort", true, "hostId", "remotePort"),
        ),
        foreignKeys = listOf(foreignKey("hosts", "hostId", "id")),
        autoIncrement = true,
    ),
    "sessions" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("hostId", "INTEGER", true),
            column("name", "TEXT", true),
            column("lastSeenAt", "INTEGER", true),
            column("tags", "TEXT", false),
        ),
        indices = indices(
            index("index_sessions_hostId", false, "hostId"),
            index("index_sessions_hostId_name", true, "hostId", "name"),
        ),
        foreignKeys = listOf(foreignKey("hosts", "hostId", "id")),
        autoIncrement = true,
    ),
    "agent_sessions" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("paneRef", "TEXT", true),
            column("agent", "TEXT", true),
            column("jsonlPath", "TEXT", false),
            column("detectedAt", "INTEGER", true),
        ),
        indices = indices(index("index_agent_sessions_paneRef", true, "paneRef")),
        autoIncrement = true,
    ),
)

private val BASE_HOST_COLUMNS: List<VersionOneColumn> = listOf(
    column("id", "INTEGER", true, 1),
    column("name", "TEXT", true),
    column("hostname", "TEXT", true),
    column("port", "INTEGER", true),
    column("username", "TEXT", true),
    column("keyId", "INTEGER", true),
    column("maxAutoPort", "INTEGER", true),
    column("skipPortsBelow", "INTEGER", true),
    column("scanIntervalSec", "INTEGER", true),
    column("enabled", "INTEGER", true),
    column("createdAt", "INTEGER", true),
    column("lastConnectedAt", "INTEGER", false),
)

private val WRITER_BACKED_VERSION_ONE_TABLES: Map<String, VersionOneTable> = mapOf(
    "port_usage" to VersionOneTable(
        columns = listOf(
            column("hostId", "INTEGER", true, 1),
            column("remotePort", "INTEGER", true, 2),
            column("clickCount", "INTEGER", true),
            column("totalBytes", "INTEGER", true),
            column("lastUsedAt", "INTEGER", true),
        ),
        indices = indices(
            primaryKeyIndex("hostId", "remotePort"),
            index("index_port_usage_hostId", false, "hostId"),
        ),
        foreignKeys = listOf(foreignKey("hosts", "hostId", "id")),
    ),
    "project_roots" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("hostId", "INTEGER", true),
            column("label", "TEXT", true),
            column("path", "TEXT", true),
            column("createdAt", "INTEGER", true),
        ),
        indices = indices(
            index("index_project_roots_hostId", false, "hostId"),
            index("index_project_roots_hostId_path", true, "hostId", "path"),
        ),
        foreignKeys = listOf(foreignKey("hosts", "hostId", "id")),
        autoIncrement = true,
    ),
    "ai_api_call_log" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("timestampMillis", "INTEGER", true),
            column("provider", "TEXT", true),
            column("feature", "TEXT", true),
            column("inputUnits", "INTEGER", true),
            column("outputUnits", "INTEGER", true),
            column("unitCostUsdMillicents", "INTEGER", true),
            column("computedCostUsdMillicents", "INTEGER", true),
            column("metadataJson", "TEXT", false),
        ),
        indices = indices(
            index("index_ai_api_call_log_timestampMillis", false, "timestampMillis"),
            index("index_ai_api_call_log_provider_feature", false, "provider", "feature"),
        ),
        autoIncrement = true,
    ),
    "pending_transcriptions" to VersionOneTable(
        columns = listOf(
            column("id", "TEXT", true, 1),
            column("audioPath", "TEXT", true),
            column("recordingTimestampMs", "INTEGER", true),
            column("destinationContext", "TEXT", true),
            column("retryCount", "INTEGER", true),
            column("lastErrorMessage", "TEXT", false),
            column("audioByteSize", "INTEGER", true),
            column("createdAtMs", "INTEGER", true),
        ),
        indices = indices(
            primaryKeyIndex("id"),
            index(
                "index_pending_transcriptions_recordingTimestampMs",
                false,
                "recordingTimestampMs",
            ),
        ),
    ),
)

private fun hostsTable(columns: List<VersionOneColumn>): Pair<String, VersionOneTable> =
    "hosts" to VersionOneTable(
        columns = columns,
        indices = indices(index("index_hosts_keyId", false, "keyId")),
        foreignKeys = listOf(foreignKey("ssh_keys", "keyId", "id")),
        autoIncrement = true,
    )

private fun snippetsTable(labelNotNull: Boolean): Pair<String, VersionOneTable> =
    "snippets" to VersionOneTable(
        columns = listOf(
            column("id", "INTEGER", true, 1),
            column("hostId", "INTEGER", true),
            column("label", "TEXT", labelNotNull),
            column("body", "TEXT", true),
            column("kind", "TEXT", true),
        ),
        indices = indices(index("index_snippets_hostId", false, "hostId")),
        foreignKeys = listOf(foreignKey("hosts", "hostId", "id")),
        autoIncrement = true,
    )

private val VERSION_ONE_V0_2_0_SCHEMA: Map<String, VersionOneTable> =
    BASE_VERSION_ONE_TABLES + mapOf(
        hostsTable(BASE_HOST_COLUMNS),
        snippetsTable(labelNotNull = true),
    )

private val VERSION_ONE_V0_2_9_SCHEMA: Map<String, VersionOneTable> =
    BASE_VERSION_ONE_TABLES + WRITER_BACKED_VERSION_ONE_TABLES + mapOf(
        hostsTable(
            BASE_HOST_COLUMNS + listOf(
                column("tmuxInstalled", "INTEGER", false),
                column("lastBootstrapAt", "INTEGER", false),
                column("quseInstalled", "INTEGER", false),
                column("quseLastDetectedAt", "INTEGER", false),
                column("usageCommandOverride", "TEXT", false),
                column("pathOverride", "TEXT", false),
                column("pocketshellInstalled", "INTEGER", false),
            ),
        ),
        snippetsTable(labelNotNull = false),
    )

private val VERSION_ONE_V0_3_0_SCHEMA: Map<String, VersionOneTable> =
    BASE_VERSION_ONE_TABLES + WRITER_BACKED_VERSION_ONE_TABLES + mapOf(
        hostsTable(
            BASE_HOST_COLUMNS + listOf(
                column("tmuxInstalled", "INTEGER", false),
                column("lastBootstrapAt", "INTEGER", false),
                column("pocketshellInstalled", "INTEGER", false),
                column("pocketshellLastDetectedAt", "INTEGER", false),
                column("usageCommandOverride", "TEXT", false),
                column("pathOverride", "TEXT", false),
            ),
        ),
        snippetsTable(labelNotNull = false),
    )

private val VERSION_ONE_ISSUE_261_MARKER_SCHEMA: Map<String, VersionOneTable> = mapOf(
    "stale_issue_261_marker" to VersionOneTable(
        columns = listOf(column("id", "INTEGER", false, 1)),
    ),
)

private val VERSION_ONE_ROOM_MASTER_SCHEMA = VersionOneTable(
    columns = listOf(
        column("id", "INTEGER", false, 1),
        column("identity_hash", "TEXT", false),
    ),
)

private const val ROOM_MASTER_ID: Int = 42
private const val V0_2_0_IDENTITY_HASH: String = "4f9cdd46489198fdda4b34e6b682d56d"
private const val V0_2_9_IDENTITY_HASH: String = "4a479a15dfcab2d576e00c7ce10ac581"
private const val V0_3_0_IDENTITY_HASH: String = "5c2d470ba861de091b4dad454b282704"
private const val ISSUE_261_IDENTITY_HASH: String = "4a479a15dfcab2d576e00c7ce10ac581"

private val SQL_COMMENT_TOKEN = Regex("""--|/\*|\*/""")
private val AUTOINCREMENT_TOKEN = Regex("""\bAUTOINCREMENT\b""", RegexOption.IGNORE_CASE)

private val UNSUPPORTED_TABLE_SEMANTICS = Regex(
    pattern =
        """\b(?:CHECK\s*\(|COLLATE\b|GENERATED\b|ON\s+CONFLICT\b|DEFERRABLE\b|""" +
            """WITHOUT\s+ROWID\b|STRICT\b)""",
    option = RegexOption.IGNORE_CASE,
)
