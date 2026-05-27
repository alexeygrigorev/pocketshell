package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v8 → v9 (issue #180).
 *
 * Adds the new `pending_transcriptions` table — one row per queued
 * Whisper transcription that has not yet succeeded. Schema must match the
 * columns declared on
 * [com.pocketshell.core.storage.entity.PendingTranscriptionEntity]
 * exactly, so Room can open the database without reporting a mismatch.
 *
 * The migration is purely additive: no existing table is touched, no
 * existing column is renamed, and the v0.2.x install base picks up the
 * new table on first launch. Hosts, sessions, snippets, agent sessions,
 * project roots, and the ai-cost log continue to round-trip unchanged.
 *
 * One index matches the entity's `@Index` declaration on
 * `recordingTimestampMs` so the composer's "newest first" list query is
 * a range scan rather than a sort. The primary key is a TEXT UUID rather
 * than the usual `INTEGER ... AUTOINCREMENT` so the row id can be reused
 * as the basename of the persisted audio file at
 * `filesDir/voice-pending/<uuid>.wav` — see entity KDoc for the
 * rationale.
 */
public val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_transcriptions (
                id TEXT NOT NULL PRIMARY KEY,
                audioPath TEXT NOT NULL,
                recordingTimestampMs INTEGER NOT NULL,
                destinationContext TEXT NOT NULL,
                retryCount INTEGER NOT NULL,
                lastErrorMessage TEXT,
                audioByteSize INTEGER NOT NULL,
                createdAtMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_pending_transcriptions_recordingTimestampMs " +
                "ON pending_transcriptions (recordingTimestampMs)",
        )
    }
}
