package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v3 → v4.
 *
 * Issue #117 (usage-panel Fix C) added three columns to the `hosts` table:
 *
 * - `heruInstalled INTEGER` (nullable `Boolean?`): cached result of the
 *   host-bootstrap probe for the `heru` CLI. `NULL` means "never probed",
 *   `1` / `0` mean "verified at [heruLastDetectedAt]". The periodic usage
 *   scheduler consumes this column to decide which hosts to poll.
 * - `heruLastDetectedAt INTEGER` (nullable `Long?` epoch-millis):
 *   timestamp of the last successful heru detection. Same 24h freshness
 *   convention as `lastBootstrapAt`.
 * - `usageCommandOverride TEXT` (nullable): optional per-host override
 *   for the usage command. Default `NULL` means "use
 *   `heru usage --json`". Power users can swap in a corporate wrapper
 *   that emits the same JSON shape without code changes.
 *
 * All three columns are nullable with a default of `NULL`, so existing
 * rows continue to behave as "never probed" / "use the default command"
 * and the next periodic refresh will pick them up naturally.
 */
public val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN heruInstalled INTEGER")
        db.execSQL("ALTER TABLE hosts ADD COLUMN heruLastDetectedAt INTEGER")
        db.execSQL("ALTER TABLE hosts ADD COLUMN usageCommandOverride TEXT")
    }
}
