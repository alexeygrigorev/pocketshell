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
 * Unit test for [MIGRATION_10_11] (issue #170, first PR): adds the
 * `pocketshellInstalled` column to the `hosts` table so the bootstrap
 * probe can cache whether the unified `pocketshell` CLI is present.
 *
 * Verifies:
 *  - Pre-existing rows on `hosts` and unrelated tables are preserved
 *    (no data loss).
 *  - The new column exists with the right nullable INTEGER type and
 *    defaults to NULL on rows that pre-existed the migration.
 *  - Round-tripping a fresh insert with all three probe columns
 *    (`tmuxInstalled`, `quseInstalled`, `pocketshellInstalled`) populated
 *    keeps the values intact — parallel detection, not legacy detection.
 *  - The version range advertised by the migration is (10, 11).
 *
 * The starting schema is v10 (post-#203), so the V10Callback below
 * builds a database that already contains the `port_usage` table
 * MIGRATION_9_10 introduced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_10_11_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationAddsPocketshellInstalledColumnAndPreservesExistingRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-10to11.db")
            .callback(V10Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")

            // Seed a row that pre-exists the migration with all v10
            // columns explicitly populated, so we can assert the
            // migration leaves the original data alone and back-fills
            // the new column to NULL.
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
            // Pre-existing port_usage row (#203 v10 schema) — confirm
            // unrelated tables round-trip through MIGRATION_10_11.
            db.execSQL(
                "INSERT INTO port_usage (hostId, remotePort, clickCount, totalBytes, lastUsedAt) " +
                    "VALUES (1, 3000, 5, 12345, 1700000000000)",
            )

            MIGRATION_10_11.migrate(db)

            // The new column exists, has type INTEGER, allows NULL, and
            // back-filled the pre-existing row with NULL.
            db.query(
                "SELECT name, tmuxInstalled, quseInstalled, pocketshellInstalled FROM hosts WHERE id = 1",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("h", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals(1, c.getInt(2))
                assertTrue(
                    "pocketshellInstalled must default to NULL for pre-existing rows",
                    c.isNull(3),
                )
            }

            // Fresh inserts can populate the new column. All three probe
            // columns coexist — parallel detection, not a hard cut yet.
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (2, 'k2', '/tmp/k2', 0, 200)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, tmuxInstalled, quseInstalled, " +
                    " pocketshellInstalled) " +
                    "VALUES (2, 'h2', 'h2.example', 22, 'u2', 2, 10000, 1000, 5, 0, 200, " +
                    " 1, 0, 1)",
            )
            db.query(
                "SELECT tmuxInstalled, quseInstalled, pocketshellInstalled FROM hosts WHERE id = 2",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(0, c.getInt(1))
                assertEquals(1, c.getInt(2))
            }

            // FALSE / 0 round-trips as the explicit "probed and missing"
            // signal — distinct from NULL ("never probed").
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (3, 'k3', '/tmp/k3', 0, 300)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, pocketshellInstalled) " +
                    "VALUES (3, 'h3', 'h3.example', 22, 'u3', 3, 10000, 1000, 5, 0, 300, 0)",
            )
            db.query("SELECT pocketshellInstalled FROM hosts WHERE id = 3").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }

            // Pre-existing rows on unrelated tables are untouched.
            db.query("SELECT COUNT(*) FROM port_usage WHERE hostId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT remotePort, clickCount FROM port_usage WHERE hostId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3000, c.getInt(0))
                assertEquals(5, c.getInt(1))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs10To11() {
        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)
    }

    @Test
    fun newColumnIsNullableInteger() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-10to11-types.db")
            .callback(V10Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            MIGRATION_10_11.migrate(db)

            // PRAGMA table_info returns (cid, name, type, notnull, dflt_value, pk)
            // for each column. Find the new column and verify it is
            // declared INTEGER with no NOT NULL constraint.
            var found = false
            db.query("PRAGMA table_info('hosts')").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    if (name == "pocketshellInstalled") {
                        found = true
                        assertEquals(
                            "pocketshellInstalled must be INTEGER",
                            "INTEGER",
                            c.getString(c.getColumnIndexOrThrow("type")),
                        )
                        assertEquals(
                            "pocketshellInstalled must allow NULL",
                            0,
                            c.getInt(c.getColumnIndexOrThrow("notnull")),
                        )
                        val dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                        assertNull(
                            "pocketshellInstalled must have no default (so unset == never probed)",
                            dflt,
                        )
                    }
                }
            }
            assertTrue("pocketshellInstalled column must exist on `hosts` after migration", found)
        }
    }

    /**
     * V10 schema (after MIGRATION_1_2 .. MIGRATION_9_10). Mirrors the
     * production v10 layout for the tables this migration cares about.
     * `hosts` carries every column that landed by v10; `port_usage` is
     * present so the test can prove the migration leaves it alone.
     */
    private class V10Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
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
                CREATE TABLE port_usage (
                    hostId INTEGER NOT NULL,
                    remotePort INTEGER NOT NULL,
                    clickCount INTEGER NOT NULL DEFAULT 0,
                    totalBytes INTEGER NOT NULL DEFAULT 0,
                    lastUsedAt INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (hostId, remotePort),
                    FOREIGN KEY (hostId) REFERENCES hosts (id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX index_port_usage_hostId ON port_usage (hostId)")
        }

        override fun onUpgrade(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }
}
