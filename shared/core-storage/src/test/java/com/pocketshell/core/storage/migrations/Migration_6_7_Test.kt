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
 * Unit test for [MIGRATION_6_7] (issue #181): introduces the
 * `ai_api_call_log` table for client-side AI API cost tracking.
 *
 * Verifies:
 * - Pre-existing rows on the other tables are preserved (no data loss).
 * - The new table exists, has the right columns, and round-trips a row.
 * - The companion indices are created.
 * - Version range is (6, 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_6_7_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationAddsAiApiCallLogTableAndPreservesExistingRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-6to7.db")
            .callback(V6Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            // Seed an unrelated row so we can prove the migration leaves
            // existing data untouched. The V6 schema includes the
            // `pathOverride` column introduced by issue #41.
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

            MIGRATION_6_7.migrate(db)

            // The new table accepts the expected column set.
            db.execSQL(
                "INSERT INTO ai_api_call_log " +
                    "(timestampMillis, provider, feature, inputUnits, outputUnits, " +
                    " unitCostUsdMillicents, computedCostUsdMillicents, metadataJson) " +
                    "VALUES (1000, 'openai', 'whisper', 12, 80, 10, 120, NULL)",
            )

            db.query(
                "SELECT timestampMillis, provider, feature, inputUnits, outputUnits, " +
                    "unitCostUsdMillicents, computedCostUsdMillicents, metadataJson " +
                    "FROM ai_api_call_log",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1_000L, c.getLong(0))
                assertEquals("openai", c.getString(1))
                assertEquals("whisper", c.getString(2))
                assertEquals(12L, c.getLong(3))
                assertEquals(80L, c.getLong(4))
                assertEquals(10L, c.getLong(5))
                assertEquals(120L, c.getLong(6))
                assertTrue(c.isNull(7))
            }

            // The companion indices are present after the migration.
            val indexNames = mutableSetOf<String>()
            db.query("PRAGMA index_list('ai_api_call_log')").use { c ->
                while (c.moveToNext()) {
                    indexNames += c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
            assertTrue(
                "expected timestampMillis index, got $indexNames",
                indexNames.contains("index_ai_api_call_log_timestampMillis"),
            )
            assertTrue(
                "expected provider/feature index, got $indexNames",
                indexNames.contains("index_ai_api_call_log_provider_feature"),
            )

            // Pre-existing rows on other tables are untouched (including
            // the v6 `pathOverride` column from issue #41).
            db.query(
                "SELECT name, hostname, tmuxInstalled, quseInstalled, pathOverride " +
                    "FROM hosts WHERE id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("h", c.getString(0))
                assertEquals("h.example", c.getString(1))
                assertEquals(1, c.getInt(2))
                assertEquals(1, c.getInt(3))
                assertEquals("/home/u/.venv/bin", c.getString(4))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs6To7() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)
    }

    /** V6 schema (after MIGRATION_1_2 + 2_3 + 3_4 + 4_5 + 5_6). The
     *  `hosts` table includes the `pathOverride` column added in v6 by
     *  issue #41. Only the tables this migration cares about; it does
     *  not exercise the others. */
    private class V6Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
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
        }

        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }
}
