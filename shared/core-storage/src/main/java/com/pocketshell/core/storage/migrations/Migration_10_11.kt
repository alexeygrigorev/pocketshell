package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v10 → v11 (issue #170, first PR).
 *
 * Adds a `pocketshellInstalled` column to the `hosts` table so the
 * bootstrap probe can cache whether the unified `pocketshell` CLI is
 * present on each remote. The column is `INTEGER` (nullable) following
 * the same convention as the sibling `tmuxInstalled` and `quseInstalled`
 * columns: `null` means "never probed", `1`/`0` is a verified
 * Installed/Missing reading.
 *
 * Per D22 (no backwards-compatibility, hard cuts only) we ship a real
 * migration here rather than carrying a code-level compatibility branch.
 * The brief on issue #170 explicitly accepts this — and explicitly
 * forbids removing the existing `quseInstalled` / `tmuxInstalled`
 * probes in this same PR (parallel detection, not legacy detection).
 * The legacy-probe removal is a separate follow-up issue.
 *
 * The migration is purely additive: no existing column or table is
 * renamed, dropped, or rewritten. Every other table — `ssh_keys`,
 * `port_remappings`, `port_usage`, `project_roots`, `sessions`,
 * `snippets`, `agent_sessions`, `ai_api_call_log`,
 * `pending_transcriptions` — round-trips unchanged.
 *
 * SQLite's `ALTER TABLE ... ADD COLUMN` is the canonical pattern for
 * nullable additions; it preserves all existing rows and indexes
 * without rebuilding the table.
 */
public val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE hosts ADD COLUMN pocketshellInstalled INTEGER",
        )
    }
}
