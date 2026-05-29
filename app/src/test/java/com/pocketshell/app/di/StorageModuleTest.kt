package com.pocketshell.app.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.APP_DATABASE_SCHEMA_VERSION
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun provideAppDatabase_rebuildsSameVersionStaleIdentityDatabase() {
        seedStaleIdentityDatabase(version = APP_DATABASE_SCHEMA_VERSION)

        val db = StorageModule.provideAppDatabase(context)
        try {
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }

        assertCurrentSchemaVersion()
    }

    @Test
    fun provideAppDatabase_rebuildsLegacyVersionFiveDatabase() {
        seedStaleIdentityDatabase(version = LEGACY_026_SCHEMA_VERSION)

        val db = StorageModule.provideAppDatabase(context)
        try {
            db.openHelper.writableDatabase.query("SELECT 1").close()
        } finally {
            db.close()
        }

        assertCurrentSchemaVersion()
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

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val LEGACY_026_SCHEMA_VERSION = 5
        const val LEGACY_CRASH_IDENTITY_HASH = "4a479a15dfcab2d576e00c7ce10ac581"
    }
}
