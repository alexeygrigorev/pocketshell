package com.pocketshell.app.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit proof for the ONE shared resilient prefs helper (issue #1292). This is
 * the node every cold-start / Main-path store funnels its open through, so its
 * corrupt-tolerance is the class-covering guarantee for the #1291 crash class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ResilientPrefsTest {

    private lateinit var realContext: Context

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    /** Happy path: a healthy prefs file opens and reads its persisted value. */
    @Test
    fun healthy_open_reads_persisted_value() {
        realContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("k", "persisted").commit()

        val prefs = ResilientPrefs.open(realContext, PREFS_NAME)

        assertEquals("persisted", prefs.getString("k", null))
    }

    /**
     * LOAD-BEARING: a corrupt open does NOT propagate — the helper best-effort
     * deletes the file, re-opens a fresh writable handle, and returns it.
     */
    @Test
    fun corrupt_open_self_heals_and_returns_writable_handle() {
        val corruptContext = CorruptContext(realContext)

        val prefs = ResilientPrefs.open(corruptContext, PREFS_NAME)

        assertTrue(
            "the corrupt open must have been attempted",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
        assertTrue(
            "the fix must best-effort delete the corrupt prefs file",
            corruptContext.wasDeleted,
        )
        // The returned handle is usable + writable.
        prefs.edit().putString("k", "fresh").commit()
        assertEquals("fresh", prefs.getString("k", null))
    }

    private fun clearPrefs() {
        realContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class CorruptContext(base: Context) : ContextWrapper(base) {
        @Volatile
        private var corrupt = true

        @Volatile
        var wasDeleted = false
            private set

        val throwingOpenAttempts = AtomicInteger(0)

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            if (name == PREFS_NAME && corrupt) {
                throwingOpenAttempts.incrementAndGet()
                throw RuntimeException("simulated corrupt $PREFS_NAME.xml")
            }
            return super.getSharedPreferences(name, mode)
        }

        override fun deleteSharedPreferences(name: String?): Boolean {
            if (name == PREFS_NAME) {
                corrupt = false
                wasDeleted = true
            }
            return super.deleteSharedPreferences(name)
        }
    }

    private companion object {
        const val PREFS_NAME = "resilient_prefs_probe"
    }
}
