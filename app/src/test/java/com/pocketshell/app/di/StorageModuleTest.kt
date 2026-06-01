package com.pocketshell.app.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.APP_DATABASE_SCHEMA_VERSION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StorageModuleTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun provideAppDatabase_rebuildsIssue261VersionOneStaleDatabase() {
        seedStaleIdentityDatabase(version = ISSUE_261_STALE_SCHEMA_VERSION)

        val db = StorageModule.provideAppDatabase(context)
        try {
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }

        assertCurrentSchemaVersion()
        assertTableExists("hosts")
        assertTableMissing("stale_database_marker")
    }

    @Test
    fun provideAppDatabase_doesNotRebuildUnsupportedLegacyVersionFiveDatabase() {
        seedStaleIdentityDatabase(version = LEGACY_026_SCHEMA_VERSION)

        val db = StorageModule.provideAppDatabase(context)
        assertThrows(IllegalStateException::class.java) {
            try {
                db.openHelper.writableDatabase.query("SELECT 1").close()
            } finally {
                db.close()
            }
        }

        assertTableExists("stale_database_marker")
    }

    private fun seedStaleIdentityDatabase(version: Int) {
        context.deleteDatabase(DATABASE_NAME)
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        val sqlite = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        sqlite.use {
            it.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            it.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(LEGACY_CRASH_IDENTITY_HASH),
            )
            it.execSQL("CREATE TABLE stale_database_marker (id INTEGER PRIMARY KEY)")
            it.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun assertCurrentSchemaVersion() {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use {
            it.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(APP_DATABASE_SCHEMA_VERSION, cursor.getInt(0))
            }
        }
    }

    private fun assertTableExists(tableName: String) {
        assertTrue(tableExists(tableName))
    }

    private fun assertTableMissing(tableName: String) {
        assertFalse(tableExists(tableName))
    }

    private fun tableExists(tableName: String): Boolean {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return sqlite.use {
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName),
            ).use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val ISSUE_261_STALE_SCHEMA_VERSION = 1
        const val LEGACY_026_SCHEMA_VERSION = 5
        const val LEGACY_CRASH_IDENTITY_HASH = "4a479a15dfcab2d576e00c7ce10ac581"
    }
}
