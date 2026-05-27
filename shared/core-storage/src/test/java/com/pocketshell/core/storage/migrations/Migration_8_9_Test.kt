package com.pocketshell.core.storage.migrations

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test for [MIGRATION_8_9] (issue #180): adds the
 * `pending_transcriptions` table for the offline / failure-retry queue
 * around the Whisper round-trip.
 *
 * Verifies:
 *  - The new table is created with the right columns and PK type.
 *  - The companion `index_pending_transcriptions_recordingTimestampMs`
 *    index is present so the composer's list query is a range scan.
 *  - Inserting a queue row round-trips through the new schema (including
 *    a `null` lastErrorMessage for offline-queued rows).
 *  - The migration is purely additive — pre-existing tables (`hosts`,
 *    `snippets`, etc.) are untouched.
 *  - The version range advertised by the migration is (8, 9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_8_9_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationCreatesPendingTranscriptionsTableAndAcceptsRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-8to9.db")
            .callback(V8Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            // Pre-migration: the new table must not exist yet.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='pending_transcriptions'",
            ).use { c ->
                assertTrue("table must not exist before migration", !c.moveToFirst())
            }

            MIGRATION_8_9.migrate(db)

            // Post-migration: insert two rows. The first is a "failed
            // Whisper" row (lastErrorMessage set, retryCount = 1). The
            // second is an "offline queue" row (null lastErrorMessage,
            // retryCount = 0).
            db.execSQL(
                "INSERT INTO pending_transcriptions " +
                    "(id, audioPath, recordingTimestampMs, destinationContext, " +
                    " retryCount, lastErrorMessage, audioByteSize, createdAtMs) " +
                    "VALUES ('uuid-1', '/data/files/voice-pending/uuid-1.wav', " +
                    " 1700000000000, 'composer', 1, 'Network error', 1900000, 1700000000500)",
            )
            db.execSQL(
                "INSERT INTO pending_transcriptions " +
                    "(id, audioPath, recordingTimestampMs, destinationContext, " +
                    " retryCount, lastErrorMessage, audioByteSize, createdAtMs) " +
                    "VALUES ('uuid-2', '/data/files/voice-pending/uuid-2.wav', " +
                    " 1700000001000, 'inline-dictation', 0, NULL, 800000, 1700000001500)",
            )

            db.query(
                "SELECT id, audioPath, recordingTimestampMs, destinationContext, " +
                    "retryCount, lastErrorMessage, audioByteSize, createdAtMs " +
                    "FROM pending_transcriptions ORDER BY recordingTimestampMs",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("uuid-1", c.getString(0))
                assertEquals("/data/files/voice-pending/uuid-1.wav", c.getString(1))
                assertEquals(1700000000000L, c.getLong(2))
                assertEquals("composer", c.getString(3))
                assertEquals(1, c.getInt(4))
                assertEquals("Network error", c.getString(5))
                assertEquals(1900000L, c.getLong(6))
                assertEquals(1700000000500L, c.getLong(7))

                assertTrue(c.moveToNext())
                assertEquals("uuid-2", c.getString(0))
                assertEquals("inline-dictation", c.getString(3))
                assertEquals(0, c.getInt(4))
                assertTrue("offline row must have NULL lastErrorMessage", c.isNull(5))

                assertTrue("expected exactly two rows", !c.moveToNext())
            }

            // The companion index is on `recordingTimestampMs` so
            // newest-first scans don't sort.
            val indexNames = mutableSetOf<String>()
            db.query("PRAGMA index_list('pending_transcriptions')").use { c ->
                while (c.moveToNext()) {
                    indexNames += c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
            assertTrue(
                "expected recordingTimestampMs index, got $indexNames",
                indexNames.contains("index_pending_transcriptions_recordingTimestampMs"),
            )
        }
    }

    @Test
    fun migrationVersionRangeIs8To9() {
        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)
    }

    @Test
    fun migrationLeavesUnrelatedTablesUntouched() {
        // Seed a row on `hosts` and `snippets` (v8 shape — nullable
        // label), run the additive migration, and confirm both rows
        // survive.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-8to9-unrelated.db")
            .callback(V8Callback())
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
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (10, 1, NULL, 'echo derived', 'command')",
            )

            MIGRATION_8_9.migrate(db)

            db.query("SELECT name FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("h", c.getString(0))
            }
            db.query("SELECT label, body FROM snippets WHERE id = 10").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("snippets.label must remain nullable + null", c.isNull(0))
                assertEquals("echo derived", c.getString(1))
            }
            db.query("SELECT COUNT(*) FROM pending_transcriptions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrationIsIdempotentOnReruns() {
        // If a partial-failure midway through `addMigrations` were to
        // replay this migration, the IF NOT EXISTS guards must keep it
        // safe to call twice without an error.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-8to9-idempotent.db")
            .callback(V8Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            MIGRATION_8_9.migrate(db)
            // Insert a sentinel row so the second migrate can be verified
            // to have not dropped+recreated.
            db.execSQL(
                "INSERT INTO pending_transcriptions " +
                    "(id, audioPath, recordingTimestampMs, destinationContext, " +
                    " retryCount, lastErrorMessage, audioByteSize, createdAtMs) " +
                    "VALUES ('sentinel', '/p', 1, 'composer', 0, NULL, 0, 1)",
            )
            MIGRATION_8_9.migrate(db)
            db.query("SELECT id, lastErrorMessage FROM pending_transcriptions WHERE id='sentinel'")
                .use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("sentinel", c.getString(0))
                    assertNull("nullable column round-trip", c.getString(1))
                }
        }
    }

    /**
     * V8 schema mirrors what an installed v0.2.x database looks like
     * after applying MIGRATION_1_2 through MIGRATION_7_8. Only the
     * tables this migration co-exists with are declared.
     */
    private class V8Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(8) {
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
            // V8-shaped snippets table: label is nullable per MIGRATION_7_8.
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
        }

        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }
}
