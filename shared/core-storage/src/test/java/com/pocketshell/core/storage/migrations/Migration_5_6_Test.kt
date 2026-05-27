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
 * Unit test for [MIGRATION_5_6] (issue #41): adds a nullable
 * `pathOverride TEXT` column to the `hosts` table so the host-bootstrap
 * probe can prepend a user-supplied PATH before running `command -v`.
 *
 * Verifies:
 * - Pre-existing rows are preserved (no data loss).
 * - The new column defaults to NULL for migrated rows.
 * - The unrelated bootstrap / usage cache columns continue to round-trip.
 * - Post-migration writes can populate the new column.
 * - Version range is (5, 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_5_6_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationAddsPathOverrideColumn() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-5to6.db")
            .callback(V5Callback())
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
                    " quseInstalled, quseLastDetectedAt, usageCommandOverride) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, 1, 12345, " +
                    " 1, 67890, 'mycorp-usage --json')",
            )

            MIGRATION_5_6.migrate(db)

            // Pre-existing row survives the migration with NULL for the
            // new column.
            db.query("SELECT pathOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }

            // The unrelated bootstrap + usage cache columns continue to
            // round-trip.
            db.query("SELECT tmuxInstalled, lastBootstrapAt, quseInstalled, quseLastDetectedAt, usageCommandOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(12345L, c.getLong(1))
                assertEquals(1, c.getInt(2))
                assertEquals(67890L, c.getLong(3))
                assertEquals("mycorp-usage --json", c.getString(4))
            }

            // Post-migration writes can populate the new column.
            db.execSQL(
                "UPDATE hosts SET pathOverride = '/home/u/git/quse/.venv/bin:/home/u/git/tmuxcli/.venv/bin' WHERE id = 1",
            )
            db.query("SELECT pathOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "/home/u/git/quse/.venv/bin:/home/u/git/tmuxcli/.venv/bin",
                    c.getString(0),
                )
            }

            // Inserts that omit the new column resolve to NULL (nullable
            // column with no DEFAULT — Room treats absent values as
            // NULL).
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt, " +
                    " quseInstalled, quseLastDetectedAt, usageCommandOverride) " +
                    "VALUES (2, 'h2', 'h2.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, NULL, NULL, " +
                    " NULL, NULL, NULL)",
            )
            db.query("SELECT pathOverride FROM hosts WHERE id = 2").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                @Suppress("UNUSED_EXPRESSION") assertNull(c.getString(0))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs5To6() {
        assertEquals(5, MIGRATION_5_6.startVersion)
        assertEquals(6, MIGRATION_5_6.endVersion)
    }

    /** V5 schema (after MIGRATION_1_2 + MIGRATION_2_3 + MIGRATION_3_4 + MIGRATION_4_5). */
    private class V5Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
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
