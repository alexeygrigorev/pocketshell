package com.pocketshell.app.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.APP_DATABASE_SCHEMA_VERSION
import org.junit.Assert.assertThrows
import org.junit.After
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
    fun provideAppDatabase_doesNotRebuildSameVersionStaleIdentityDatabase() {
        seedStaleIdentityDatabase(version = APP_DATABASE_SCHEMA_VERSION)

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

    @Test
    fun provideAppDatabase_doesNotRebuildLegacyVersionFiveDatabase() {
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

    private fun assertTableExists(tableName: String) {
        val sqlite = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use {
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val LEGACY_026_SCHEMA_VERSION = 5
        const val LEGACY_CRASH_IDENTITY_HASH = "4a479a15dfcab2d576e00c7ce10ac581"
    }
}
