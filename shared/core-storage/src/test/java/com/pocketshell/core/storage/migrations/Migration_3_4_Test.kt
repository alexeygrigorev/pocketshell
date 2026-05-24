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
 * Unit test for [MIGRATION_3_4] (issue #117): adds three nullable
 * columns to the `hosts` table for usage-panel periodic polling.
 *
 * Verifies:
 * - Pre-existing rows are preserved (no data loss).
 * - The three new columns default to NULL for migrated rows.
 * - Post-migration writes can populate the new columns.
 * - Version range is (3, 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_3_4_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationAddsHeruAndUsageCommandColumns() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-3to4.db")
            .callback(V3Callback())
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
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, 1, 12345)",
            )

            MIGRATION_3_4.migrate(db)

            // Pre-existing row survives the migration with NULLs for the
            // new columns.
            db.query("SELECT heruInstalled, heruLastDetectedAt, usageCommandOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }

            // Post-migration writes can populate the new columns.
            db.execSQL(
                "UPDATE hosts SET heruInstalled = 1, heruLastDetectedAt = 99999, usageCommandOverride = 'mycorp-usage --json' WHERE id = 1",
            )
            db.query("SELECT heruInstalled, heruLastDetectedAt, usageCommandOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(99999L, c.getLong(1))
                assertEquals("mycorp-usage --json", c.getString(2))
            }

            // The existing bootstrap columns continue to round-trip.
            db.query("SELECT tmuxInstalled, lastBootstrapAt FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(12345L, c.getLong(1))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs3To4() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    /** V3 schema (after [MIGRATION_1_2] + [MIGRATION_2_3]). */
    private class V3Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
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
