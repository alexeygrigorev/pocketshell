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
 * Verifies [MIGRATION_1_2] adds the two new columns (`tmuxInstalled`,
 * `lastBootstrapAt`) without losing data in the `hosts` table.
 *
 * `:shared:core-storage` runs with `exportSchema = false`, so the
 * canonical Room `MigrationTestHelper` (which loads its v1 schema from
 * a committed JSON fixture) is not usable here. We instead stand up the
 * v1 `hosts` table by hand via `SupportSQLiteOpenHelperFactory`, write a
 * row, run the migration, and read the columns back.
 *
 * The v1 schema reproduced below is intentionally a verbatim copy of
 * Room's generated SQL for [com.pocketshell.core.storage.entity.HostEntity]
 * as of schema v1 — if Room changes the canonical SQL for the same
 * `@Entity`, this test must be updated to match (the migration SQL
 * itself doesn't depend on the v1 column order, only on the columns
 * being present).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_1_2_Test {

    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
        // Robolectric uses a per-test sandbox dir for SQLite files, so
        // no manual file cleanup is needed.
    }

    @Test
    fun migration_addsNullableColumns_andPreservesExistingRows() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-1to2.db")
            .callback(V1Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        // Stage 1: open v1, insert a host row.
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO ssh_keys (id, name, privateKeyPath, hasPassphrase, createdAt) " +
                    "VALUES (1, 'k', '/tmp/k', 0, 100)",
            )
            db.execSQL(
                "INSERT INTO hosts " +
                    "(id, name, hostname, port, username, keyId, maxAutoPort, skipPortsBelow, " +
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL)",
            )

            // Stage 2: run the migration directly. The Migration_1_2
            // declares only `ALTER TABLE`s, so it's safe to invoke
            // against an already-open writable database.
            MIGRATION_1_2.migrate(db)

            // Confirm the new columns exist by querying them.
            db.query("SELECT name, tmuxInstalled, lastBootstrapAt FROM hosts WHERE id = 1").use { c ->
                assertTrue("expected at least one row", c.moveToFirst())
                assertEquals("h", c.getString(0))
                // Both new columns default to NULL post-migration.
                assertTrue("tmuxInstalled should be null", c.isNull(1))
                assertTrue("lastBootstrapAt should be null", c.isNull(2))
            }

            // Confirm we can write the new columns and read them back.
            db.execSQL(
                "UPDATE hosts SET tmuxInstalled = 1, lastBootstrapAt = 12345 WHERE id = 1",
            )
            db.query("SELECT tmuxInstalled, lastBootstrapAt FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertEquals(12345L, c.getLong(1))
            }
        }
    }

    /**
     * V1 schema callback. The CREATE TABLE statements mirror the SQL
     * Room generates for the v1 entities — only `hosts` + `ssh_keys`
     * are needed for this test since the migration only touches `hosts`
     * and the `hosts` FK references `ssh_keys`.
     */
    private class V1Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Minimal v1 schema: just the two tables the migration touches.
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
        ) {
            // Not exercised in this test — we run the migration manually.
        }
    }

    /**
     * Sanity: the migration object reports the right version range. Cheap
     * test that catches accidental refactors that bump the wrong end of
     * the range.
     */
    @Test
    fun migration_versionRange_is_1_to_2() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }
}
