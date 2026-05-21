package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v1 → v2.
 *
 * Issue #49 added two nullable columns to the `hosts` table to cache the
 * tmux-bootstrap probe per host:
 *
 * - `tmuxInstalled INTEGER` (nullable `Boolean?`) — `1` / `0` / `NULL`.
 *   `NULL` means "never probed", `1` / `0` mean "verified at
 *   `lastBootstrapAt`".
 * - `lastBootstrapAt INTEGER` (nullable `Long?` epoch-millis) — timestamp
 *   of the last probe; bootstrap re-runs only after 24h or on explicit
 *   user trigger.
 *
 * Both columns default to `NULL`, so existing rows continue to behave as
 * "never probed" and the bootstrap flow will run on their next connect.
 *
 * Kept as a top-level `Migration` object (rather than Room's
 * `AutoMigration`) so a) the SQL is explicit and reviewable and b) we can
 * write a unit test against it via `MigrationTestHelper` without enabling
 * `exportSchema`.
 */
public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN tmuxInstalled INTEGER")
        db.execSQL("ALTER TABLE hosts ADD COLUMN lastBootstrapAt INTEGER")
    }
}
