package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v4 → v5.
 *
 * Issue #128 swapped the usage-tracking backend from the legacy `heru`
 * CLI to the new `quse` library (https://github.com/alexeygrigorev/terminal-usage-tracker).
 * The cached probe columns on the `hosts` table were renamed to match:
 *
 * - `heruInstalled` → `quseInstalled` (still nullable `INTEGER` mirroring
 *   `Boolean?`; `NULL` means "never probed").
 * - `heruLastDetectedAt` → `quseLastDetectedAt` (still nullable `INTEGER`
 *   epoch-millis).
 *
 * `usageCommandOverride` is unchanged — the column is generic enough that
 * the rename does not touch it. The default behaviour for `NULL` now
 * means "use `quse --json`" instead of the legacy `heru usage --json`,
 * but the column type and shape stay identical.
 *
 * SQLite 3.25+ supports `ALTER TABLE ... RENAME COLUMN`, which is what
 * Android ships on every API level Room supports. The rename preserves
 * pre-existing values so a host that previously had heru installed is
 * automatically treated as having quse installed; the next periodic
 * probe will overwrite the stale value if it turns out heru is present
 * but quse is not. This is a deliberate trade-off: forcing every host to
 * re-probe on first connect avoids a flicker for the common case where
 * both tools sit on the same `~/.local/bin` PATH.
 */
public val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts RENAME COLUMN heruInstalled TO quseInstalled")
        db.execSQL("ALTER TABLE hosts RENAME COLUMN heruLastDetectedAt TO quseLastDetectedAt")
    }
}
