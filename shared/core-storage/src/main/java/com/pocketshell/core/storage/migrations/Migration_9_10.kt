package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v9 → v10 (issue #203 expanded scope).
 *
 * Adds the new `port_usage` table — one row per (host, remote port) pair,
 * carrying the click count, accumulated bytes-in + bytes-out across every
 * forward instance, and the most recent use timestamp. The table backs the
 * "frequent ports" star indicator and the cumulative-traffic readout on the
 * Tunnels panel (both ported from `ssh-auto-forward-android`).
 *
 * Originally drafted as `MIGRATION_8_9`; the v8→v9 slot was claimed by
 * #180 (`pending_transcriptions`) which merged first, so this carve-out
 * was renumbered to 9→10 to maintain a strictly increasing version chain.
 *
 * The migration is purely additive: no existing table is touched, no
 * existing column is renamed, and the v0.2.x install base picks up the
 * new table on first launch. Every other table — `hosts`, `ssh_keys`,
 * `port_remappings`, `project_roots`, `sessions`, `snippets`,
 * `agent_sessions`, `ai_api_call_log`, `pending_transcriptions` — round-trips
 * unchanged.
 *
 * Schema notes:
 *
 * - Composite primary key on `(hostId, remotePort)` matches the
 *   declaration on
 *   [com.pocketshell.core.storage.entity.PortUsageEntity]. SQLite renders
 *   this as a constraint-only key (no rowid alias), so the columns can
 *   carry their natural types instead of being shoehorned into INTEGER.
 * - FK ON DELETE CASCADE matches the rest of the host-scoped tables:
 *   deleting a host wipes its usage rows. Schema-identical to a fresh
 *   v10 install so Room does not complain about a mismatch.
 * - The companion `index_port_usage_hostId` index supports the panel's
 *   `getByHostId` query without a table scan as the log grows.
 */
public val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS port_usage (
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_port_usage_hostId ON port_usage (hostId)",
        )
    }
}
