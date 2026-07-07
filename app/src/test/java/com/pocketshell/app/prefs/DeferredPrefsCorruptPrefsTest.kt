package com.pocketshell.app.prefs

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reproduce-first, CLASS-COVERING regression proof for issue #1292 (#1291 /
 * D31 / D32-G2 / G10): the 7 screen-scoped stores that route their prefs open
 * through [DeferredPrefs] (composer draft + outbound queue + attachment sidecar,
 * port-forward panel, file viewer, FCM token, push dedup) all share the same
 * unguarded-open crash class as `app_settings` (#1229) and `last_session`
 * (#1292): a corrupt prefs file makes `getSharedPreferences(...)` THROW, the
 * exception latches in the warm-up `Deferred`, and the first `get()` on the
 * **Main** thread (at screen-open) `runBlocking`-rethrows it — crashing the
 * screen.
 *
 * Rather than re-fixture all 7 stores, this exercises the SHARED node they all
 * funnel through — [DeferredPrefs] — so a fix there covers the whole class (G2).
 *
 * ## The fixture that reproduces it (the non-happy state)
 * [CorruptScreenPrefsContext] throws on `getSharedPreferences(PREFS_NAME, ...)`
 * exactly as a corrupt XML parse would, until the file is DELETED. A happy
 * fixture can never enter the failing state, so it would prove nothing.
 *
 * RED on base: `DeferredPrefs.get()` rethrows the latched exception → RED.
 * GREEN with fix: the shared resilient helper catches the corrupt open,
 * best-effort deletes the file, re-opens a fresh handle, and `get()` returns a
 * usable prefs instance without throwing → GREEN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeferredPrefsCorruptPrefsTest {

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

    /**
     * LOAD-BEARING (#1292 acceptance 2): a corrupt file in a [DeferredPrefs]-
     * backed screen store no longer crashes on Main at screen-open. On base
     * `get()` rethrows the latched corrupt-open exception (RED); with the shared
     * resilient helper it returns a usable prefs handle (GREEN).
     */
    @Test
    fun corrupt_screen_store_prefs_does_not_crash_on_get() {
        val corruptContext = CorruptScreenPrefsContext(realContext)

        val deferred = DeferredPrefs(corruptContext, PREFS_NAME)
        val prefs = deferred.get()

        // A usable handle came back (no crash) and reads a default cleanly.
        assertNull("recovered prefs must read a missing key as its default", prefs.getString("k", null))
        assertTrue(
            "the corrupt-prefs open must have been attempted at least once",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
    }

    /**
     * #1292 acceptance 2 (durable + writable recovery): after the corrupt open
     * recovers (best-effort delete + re-open), the returned handle is writable
     * and a fresh [DeferredPrefs] on the SAME cleared app data reads the value
     * back — proving the recovery is durable across screen re-open, not a
     * one-shot in-memory patch.
     */
    @Test
    fun recovery_is_durable_and_writable() {
        val corruptContext = CorruptScreenPrefsContext(realContext)

        val recovered = DeferredPrefs(corruptContext, PREFS_NAME).get()
        assertTrue(
            "the fix must best-effort delete the corrupt prefs file so recovery " +
                "is durable across screen re-open",
            corruptContext.wasDeleted,
        )
        recovered.edit().putString("k", "v").commit()

        val reopened = DeferredPrefs(corruptContext, PREFS_NAME).get()
        assertEquals("v", reopened.getString("k", null))
    }

    private fun clearPrefs() {
        realContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private class CorruptScreenPrefsContext(base: Context) : ContextWrapper(base) {
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
                throw RuntimeException(
                    "simulated corrupt $PREFS_NAME.xml: unexpected end of document",
                )
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
        const val PREFS_NAME = "file_viewer_prefs"
    }
}
