package com.pocketshell.core.storage.migrations

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test for [MIGRATION_9_10] (issue #203 expanded scope): introduces
 * the `port_usage` table for per-(host, remote port) usage counters
 * ported from `ssh-auto-forward-android`.
 *
 * Verifies:
 *  - Pre-existing rows on the other tables are preserved (no data loss).
 *  - The new table exists, has the right columns, and round-trips a row.
 *  - The composite primary key on `(hostId, remotePort)` is enforced.
 *  - The companion `index_port_usage_hostId` index is created.
 *  - The version range advertised by the migration is (9, 10).
 *  - FK ON DELETE CASCADE on hostId fires (deleting a host wipes its
 *    usage rows).
 *
 * The starting schema is v9 (post-#180), so the V9Callback below builds
 * a database that already contains the `pending_transcriptions` table
 * MIGRATION_8_9 introduced. This migration runs strictly on top of that
 * baseline; renaming from the original `Migration_8_9` slot to `9_10`
 * reflects #180 landing the v8→v9 number first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_9_10_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationAddsPortUsageTableAndPreservesExistingRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-9to10.db")
            .callback(V9Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")

            // Seed unrelated rows so we can prove the migration leaves
            // existing data untouched.
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (1, 'k', '/tmp/k', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt, " +
                    " quseInstalled, quseLastDetectedAt, usageCommandOverride, pathOverride) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, 1, 12345, " +
                    " 1, 67890, 'quse --json', '/home/u/.venv/bin')",
            )
            // Pre-existing snippet rows on the v9 schema (nullable label).
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (10, 1, 'tail logs', 'kubectl logs -f deploy/api', 'command')",
            )
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (11, 1, NULL, 'echo derived', 'command')",
            )
            // Pre-existing pending_transcriptions row (#180 v9 schema).
            db.execSQL(
                "INSERT INTO pending_transcriptions " +
                    "(id, audioPath, recordingTimestampMs, destinationContext, " +
                    " retryCount, lastErrorMessage, audioByteSize, createdAtMs) " +
                    "VALUES ('pending-uuid', '/data/voice-pending/pending-uuid.wav', " +
                    " 1700000000000, 'composer', 0, NULL, 256000, 1700000000500)",
            )

            MIGRATION_9_10.migrate(db)

            // The new table accepts the expected column set + composite
            // primary key on (hostId, remotePort).
            db.execSQL(
                "INSERT INTO port_usage " +
                    "(hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 3000, 5, 12345, 1700000000000)",
            )
            db.execSQL(
                "INSERT INTO port_usage " +
                    "(hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 8080, 0, 0, 0)",
            )

            db.query(
                "SELECT hostId, remotePort, clickCount, totalBytes, lastUsedAt " +
                    "FROM port_usage ORDER BY remotePort",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(3000, c.getInt(1))
                assertEquals(5, c.getInt(2))
                assertEquals(12_345L, c.getLong(3))
                assertEquals(1_700_000_000_000L, c.getLong(4))
                assertTrue(c.moveToNext())
                assertEquals(1L, c.getLong(0))
                assertEquals(8080, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0L, c.getLong(3))
                assertEquals(0L, c.getLong(4))
                assertTrue("expected exactly two rows", !c.moveToNext())
            }

            // The companion `hostId` index is present after the
            // migration so the panel's getByHostId query can range-scan
            // cheaply as the log grows.
            val indexNames = mutableSetOf<String>()
            db.query("PRAGMA index_list('port_usage')").use { c ->
                while (c.moveToNext()) {
                    indexNames += c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
            assertTrue(
                "expected hostId index, got $indexNames",
                indexNames.contains("index_port_usage_hostId"),
            )

            // Pre-existing rows on other tables are untouched.
            db.query("SELECT name, hostname, pathOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("h", c.getString(0))
                assertEquals("h.example", c.getString(1))
                assertEquals("/home/u/.venv/bin", c.getString(2))
            }
            db.query("SELECT COUNT(*) FROM snippets WHERE hostId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2, c.getInt(0))
            }
            // #180's pending_transcriptions row must survive the v9→v10 step.
            db.query(
                "SELECT id, destinationContext FROM pending_transcriptions WHERE id = 'pending-uuid'",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("pending-uuid", c.getString(0))
                assertEquals("composer", c.getString(1))
            }
        }
    }

    @Test
    fun migrationEnforcesCompositePrimaryKey() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-9to10-pk.db")
            .callback(V9Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (1, 'k', '/tmp/k', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100)",
            )

            MIGRATION_9_10.migrate(db)

            db.execSQL(
                "INSERT INTO port_usage (hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 3000, 1, 0, 0)",
            )
            // Re-inserting the same (hostId, remotePort) tuple must fail —
            // this is the primary-key constraint that lets `insertIfMissing`
            // be a safe upsert via OnConflictStrategy.IGNORE.
            val threw = runCatching {
                db.execSQL(
                    "INSERT INTO port_usage (hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                        "VALUES (1, 3000, 999, 0, 0)",
                )
            }.isFailure
            assertTrue("PK constraint on (hostId, remotePort) must reject duplicates", threw)
        }
    }

    @Test
    fun migrationFkCascadeWipesUsageWhenHostDeleted() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-9to10-cascade.db")
            .callback(V9Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (1, 'k', '/tmp/k', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100)",
            )

            MIGRATION_9_10.migrate(db)

            db.execSQL(
                "INSERT INTO port_usage (hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 3000, 5, 100, 100)",
            )
            db.execSQL(
                "INSERT INTO port_usage (hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 8080, 1, 50, 100)",
            )

            // Deleting the host must cascade and wipe both usage rows.
            db.execSQL("DELETE FROM hosts WHERE id = 1")

            db.query("SELECT COUNT(*) FROM port_usage").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("FK cascade should have wiped usage rows", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs9To10() {
        assertEquals(9, MIGRATION_9_10.startVersion)
        assertEquals(10, MIGRATION_9_10.endVersion)
    }

    /**
     * V9 schema (after MIGRATION_1_2 .. MIGRATION_8_9). Only the tables
     * this migration cares about are declared. The `snippets.label`
     * column is `TEXT` (nullable) per the v7 → v8 rebuild, and the
     * `pending_transcriptions` table from #180's MIGRATION_8_9 is
     * present so this migration can be tested in the proper baseline.
     */
    private class V9Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(9) {
        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE ssh_keys (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    privateKeyPath TEXT NOT NULL,
                    hasPassphrase INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE hosts (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    hostname TEXT NOT NULL,
                    port INTEGER NOT NULL DEFAULT 22,
                    username TEXT NOT NULL,
                    keyId INTEGER NOT NULL,
                    maxAutoPort INTEGER NOT NULL DEFAULT 10000,
                    skipPortsBelow INTEGER NOT NULL DEFAULT 1000,
                    scanIntervalSec INTEGER NOT NULL DEFAULT 5,
                    enabled INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    lastConnectedAt INTEGER,
                    tmuxInstalled INTEGER,
                    lastBootstrapAt INTEGER,
                    quseInstalled INTEGER,
                    quseLastDetectedAt INTEGER,
                    usageCommandOverride TEXT,
                    pathOverride TEXT,
                    FOREIGN KEY (keyId) REFERENCES ssh_keys (id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_hosts_keyId ON hosts (keyId)")
            db.execSQL(
                """
                CREATE TABLE snippets (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    hostId INTEGER NOT NULL,
                    label TEXT,
                    body TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    FOREIGN KEY (hostId) REFERENCES hosts (id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_snippets_hostId ON snippets (hostId)")
            // #180's v8 → v9 addition: pending_transcriptions table.
            db.execSQL(
                """
                CREATE TABLE pending_transcriptions (
                    id TEXT NOT NULL PRIMARY KEY,
                    audioPath TEXT NOT NULL,
                    recordingTimestampMs INTEGER NOT NULL,
                    destinationContext TEXT NOT NULL,
                    retryCount INTEGER NOT NULL,
                    lastErrorMessage TEXT,
                    audioByteSize INTEGER NOT NULL,
                    createdAtMs INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX index_pending_transcriptions_recordingTimestampMs " +
                    "ON pending_transcriptions (recordingTimestampMs)",
            )
        }

        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }
}
