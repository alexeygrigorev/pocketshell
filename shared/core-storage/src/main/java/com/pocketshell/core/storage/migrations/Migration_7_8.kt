package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v7 → v8 (issue #190).
 *
 * Relaxes the `snippets.label` column from `TEXT NOT NULL` to `TEXT`
 * (nullable). After this migration:
 *
 * - Existing rows keep their explicit `label` value verbatim — they were
 *   created back when the snippet editor required both fields, so the
 *   user's choice of label survives the upgrade.
 * - New rows can write `label = NULL` to signal "auto-derive the label
 *   from the first line of `body`". The UI layer (SnippetPickerSheet /
 *   SnippetsScreen) does the derivation; the schema only carries the
 *   override.
 *
 * SQLite has no `ALTER COLUMN` for relaxing a NOT NULL constraint on a
 * column that already exists, so the standard "create new table, copy
 * rows, drop old, rename" pattern is used. The new schema must match what
 * Room would emit for a fresh v8 install — same column order, same FK and
 * cascade behaviour, same companion index — otherwise Room will complain
 * about a schema mismatch on the next open.
 *
 * The copy step also preserves `id` so any in-memory references (e.g. the
 * snippet picker's `LazyColumn` keys, instrumentation tests holding ids)
 * stay valid across the upgrade.
 */
public val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the v8-shaped table under a temporary name.
        db.execSQL(
            """
            CREATE TABLE snippets_new (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                hostId INTEGER NOT NULL,
                label TEXT,
                body TEXT NOT NULL,
                kind TEXT NOT NULL,
                FOREIGN KEY (hostId) REFERENCES hosts (id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        // 2. Copy every row across. Pre-existing labels were NOT NULL so
        //    they round-trip into the relaxed column unchanged.
        db.execSQL(
            "INSERT INTO snippets_new (id, hostId, label, body, kind) " +
                "SELECT id, hostId, label, body, kind FROM snippets",
        )
        // 3. Drop the old table and rename the new one into place. The
        //    old `index_snippets_hostId` index follows the dropped table.
        db.execSQL("DROP TABLE snippets")
        db.execSQL("ALTER TABLE snippets_new RENAME TO snippets")
        // 4. Recreate the companion index Room declares on
        //    `SnippetEntity.indices = [Index("hostId")]` so the post-
        //    migration schema matches a fresh v8 install exactly.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_snippets_hostId ON snippets (hostId)")
    }
}
