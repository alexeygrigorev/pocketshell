package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v6 → v7 (issue #181).
 *
 * Adds the new `ai_api_call_log` table — one row per client-side AI API
 * call (Whisper today, future chat / TTS endpoints later). Schema must
 * match the columns declared on
 * [com.pocketshell.core.storage.entity.AiApiCallEntry] exactly, so Room
 * can open the database without reporting a mismatch.
 *
 * The migration is purely additive: no existing table is touched, no
 * existing column is renamed, and the v0.2.x install base picks up the
 * new table on first launch. The host-bootstrap, session, snippet,
 * agent-session, and project-root tables continue to round-trip unchanged.
 *
 * Two indices match the entity's `@Index` declarations:
 *
 * - On `timestampMillis` so the costs screen's "today / week / month"
 *   aggregates can range-scan instead of full-scanning.
 * - Joint on `(provider, feature)` so per-feature breakdowns (Whisper /
 *   future chat models) also stay cheap as the log grows.
 *
 * The autoincrement primary key matches the entity's
 * `@PrimaryKey(autoGenerate = true)` annotation — Room emits the same
 * `INTEGER PRIMARY KEY AUTOINCREMENT` shape when it would normally
 * `createTable` for a fresh install, so the migrated schema is identical
 * to a v7 fresh install's.
 */
public val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ai_api_call_log (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                timestampMillis INTEGER NOT NULL,
                provider TEXT NOT NULL,
                feature TEXT NOT NULL,
                inputUnits INTEGER NOT NULL,
                outputUnits INTEGER NOT NULL,
                unitCostUsdMillicents INTEGER NOT NULL,
                computedCostUsdMillicents INTEGER NOT NULL,
                metadataJson TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ai_api_call_log_timestampMillis " +
                "ON ai_api_call_log (timestampMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_ai_api_call_log_provider_feature " +
                "ON ai_api_call_log (provider, feature)",
        )
    }
}
