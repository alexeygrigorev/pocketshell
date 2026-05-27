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
 * Unit test for [MIGRATION_7_8] (issue #190): relaxes the
 * `snippets.label` column from `TEXT NOT NULL` to `TEXT` so the snippet
 * editor can store a `null` label and let the UI derive one from the
 * body's first line at read time.
 *
 * Verifies:
 *  - Pre-existing rows with explicit labels survive the migration with
 *    their label intact (acceptance criterion: "Existing snippets with
 *    explicit labels keep them").
 *  - The post-migration table accepts `label = NULL` inserts.
 *  - The companion `index_snippets_hostId` index is recreated so the
 *    schema matches what Room emits for a fresh v8 install.
 *  - The version range advertised by the migration is (7, 8).
 *  - The FK ON DELETE CASCADE on hostId still fires after the table
 *    rebuild.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Migration_7_8_Test {
    private val factory = FrameworkSQLiteOpenHelperFactory()
    private var openHelper: androidx.sqlite.db.SupportSQLiteOpenHelper? = null

    @After
    fun tearDown() {
        openHelper?.close()
    }

    @Test
    fun migrationPreservesExistingLabelsAndAllowsNewNullLabels() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-7to8.db")
            .callback(V7Callback())
            .build()
        val helper = factory.create(config)
        openHelper = helper

        helper.writableDatabase.use { db ->
            // Enable FK enforcement explicitly — Room turns this on but
            // a raw helper does not.
            db.execSQL("PRAGMA foreign_keys = ON")

            // Seed: ssh key, host, and two pre-existing snippets that
            // exercise both kinds. These are the rows that the issue
            // promises will survive the upgrade with their explicit
            // labels intact.
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
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (10, 1, 'tail logs', 'kubectl logs -f deploy/api', 'command')",
            )
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (11, 1, 'summarise diff', " +
                    "'Please summarise the staged git diff.', 'prompt')",
            )

            MIGRATION_7_8.migrate(db)

            // Existing rows survived the rebuild with their label intact.
            db.query("SELECT id, hostId, label, body, kind FROM snippets ORDER BY id").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(10L, c.getLong(0))
                assertEquals(1L, c.getLong(1))
                assertEquals("tail logs", c.getString(2))
                assertEquals("kubectl logs -f deploy/api", c.getString(3))
                assertEquals("command", c.getString(4))

                assertTrue(c.moveToNext())
                assertEquals(11L, c.getLong(0))
                assertEquals("summarise diff", c.getString(2))
                assertEquals("prompt", c.getString(4))

                assertTrue("expected exactly two rows after migration", !c.moveToNext())
            }

            // The relaxed column now accepts NULL labels — this is the
            // whole point of issue #190.
            db.execSQL(
                "INSERT INTO snippets (id, hostId, label, body, kind) " +
                    "VALUES (12, 1, NULL, 'echo derived', 'command')",
            )
            db.query("SELECT label, body FROM snippets WHERE id = 12").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("expected NULL label, got '${c.getString(0)}'", c.isNull(0))
                assertEquals("echo derived", c.getString(1))
            }

            // The companion index Room declares on `@Index("hostId")` is
            // back on the rebuilt table so a fresh v8 install and an
            // upgraded one render schema-identical.
            val indexNames = mutableSetOf<String>()
            db.query("PRAGMA index_list('snippets')").use { c ->
                while (c.moveToNext()) {
                    indexNames += c.getString(c.getColumnIndexOrThrow("name"))
                }
            }
            assertTrue(
                "expected hostId index, got $indexNames",
                indexNames.contains("index_snippets_hostId"),
            )

            // FK ON DELETE CASCADE still fires on the rebuilt table — if
            // it didn't, deleting the host would leave orphaned
            // snippets.
            db.execSQL("DELETE FROM hosts WHERE id = 1")
            db.query("SELECT COUNT(*) FROM snippets").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrationVersionRangeIs7To8() {
        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)
    }

    @Test
    fun migrationPreservesUnrelatedTables() {
        // The migration only rebuilds `snippets` — any other table must
        // be untouched. Seed a row on `hosts` and check it round-trips.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-7to8-unrelated.db")
            .callback(V7Callback())
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
                    " scanIntervalSec, enabled, createdAt, lastConnectedAt, tmuxInstalled, lastBootstrapAt, " +
                    " quseInstalled, quseLastDetectedAt, usageCommandOverride, pathOverride) " +
                    "VALUES (1, 'h', 'h.example', 22, 'u', 1, 10000, 1000, 5, 0, 100, NULL, 1, 12345, " +
                    " 1, 67890, 'quse --json', '/home/u/.venv/bin')",
            )

            MIGRATION_7_8.migrate(db)

            db.query("SELECT name, pathOverride FROM hosts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("h", c.getString(0))
                assertEquals("/home/u/.venv/bin", c.getString(1))
            }
            db.query("SELECT name FROM ssh_keys WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("k", c.getString(0))
            }
        }
    }

    @Test
    fun migrationLeavesNullLabelOnNewInsertWithoutWriteFailure() {
        // Sanity-check that the new schema's `label` column truly is
        // nullable and not just nullable-by-default. Skipping the column
        // entirely on INSERT must succeed (legacy callers that omit the
        // label do not need to write `NULL` explicitly).
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name("migration-test-7to8-implicit-null.db")
            .callback(V7Callback())
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

            MIGRATION_7_8.migrate(db)

            db.execSQL(
                "INSERT INTO snippets (hostId, body, kind) VALUES (1, 'echo implicit', 'command')",
            )
            db.query("SELECT label FROM snippets WHERE body = 'echo implicit'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("expected implicit NULL label", c.isNull(0))
            }
        }
    }

    /**
     * V7 schema mirrors what an installed v0.2.x database looks like
     * after applying MIGRATION_1_2 through MIGRATION_6_7. Only the
     * tables this migration touches (or co-exists with) are declared.
     */
    private class V7Callback : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
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
            // V7-shaped snippets table: label is `TEXT NOT NULL` because
            // that is what the v1..v7 entity declared.
            db.execSQL(
                """
                CREATE TABLE snippets (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    hostId INTEGER NOT NULL,
                    label TEXT NOT NULL,
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
