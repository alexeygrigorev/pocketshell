package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_roots (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                hostId INTEGER NOT NULL,
                label TEXT NOT NULL,
                path TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_project_roots_hostId ON project_roots(hostId)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_project_roots_hostId_path ON project_roots(hostId, path)",
        )
    }
}
