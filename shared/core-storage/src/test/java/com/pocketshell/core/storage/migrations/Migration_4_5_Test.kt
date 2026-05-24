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
 * Unit test for [MIGRATION_4_5] (issue #128): renames the usage-tool cache
 * columns on `hosts` from `heruInstalled` / `heruLastDetectedAt` to
 * `quseInstalled` / `quseLastDetectedAt`.
 *
 * Verifies:
 * - Pre-existing rows are preserved (no data loss).
 * - Cached values survive the rename: `heruInstalled = 1` shows up as
 *   `quseInstalled = 1` after the migration.
 * - The legacy column names are no longer queryable.
 * - The `usageCommandOverride` column is untouched.
 * - Version range is (4, 5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_4_5_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationRenamesHeruColumnsToQuse() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-4to5.db")
            .callback(V4Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (1, 'k', '/tmp/k', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt, " +
                    " heruInstalled, heruLastDetectedAt, usageCommandOverride) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, 1, 12345, " +
                    " 1, 67890, 'mycorp-usage --json')",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt, " +
                    " heruInstalled, heruLastDetectedAt, usageCommandOverride) " +
                    "VALUES (2, 'never-probed', 'h2.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, NULL, NULL, " +
                    " NULL, NULL, NULL)",
            )

            MIGRATION_4_5.migrate(db)

            // Renamed columns carry the original values.
            db.query("SELECT quseInstalled, quseLastDetectedAt, usageCommandOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(67890L, c.getLong(1))
                assertEquals("mycorp-usage --json", c.getString(2))
            }

            // NULLs are preserved for never-probed rows.
            db.query("SELECT quseInstalled, quseLastDetectedAt FROM hosts WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
            }

            // The legacy column names are gone (SELECT on them now errors).
            val legacyError = runCatching {
                db.query("SELECT heruInstalled FROM hosts").use { it.moveToFirst() }
            }.exceptionOrNull()
            assertTrue(
                "expected legacy heruInstalled column to be gone, got $legacyError",
                legacyError != null,
            )

            // The unrelated bootstrap columns continue to round-trip.
            db.query("SELECT tmuxInstalled, lastBootstrapAt FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(12345L, c.getLong(1))
            }

            // Post-migration writes can populate the renamed columns.
            db.execSQL(
                "UPDATE hosts SET quseInstalled = 0, quseLastDetectedAt = 99999 WHERE id = 2",
            )
            db.query("SELECT quseInstalled, quseLastDetectedAt FROM hosts WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
                assertEquals(99999L, c.getLong(1))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs4To5() {
        assertEquals(4, MIGRATION_4_5.startVersion)
        assertEquals(5, MIGRATION_4_5.endVersion)
    }

    /** V4 schema (after MIGRATION_1_2 + MIGRATION_2_3 + MIGRATION_3_4). */
    private class V4Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
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
                    heruInstalled INTEGER,
                    heruLastDetectedAt INTEGER,
                    usageCommandOverride TEXT,
                    FOREIGN KEY (keyId) REFERENCES ssh_keys (id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_hosts_keyId ON hosts (keyId)")
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
        }

        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }
}
