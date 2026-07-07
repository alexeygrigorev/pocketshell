package com.pocketshell.app.session

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
 * Reproduce-first regression proof for issue #1292 (class coverage of #1291 /
 * D31 / D32-G2 / G10): a corrupt / unreadable `last_session` prefs file must NOT
 * crash-loop the app at launch on the process-death resume path.
 *
 * ## The bug (the #1291 class, second door)
 * v0.4.23 fixed the `app_settings` open (#1229/#1248), but [LastSessionStore]
 * shipped the same unguarded pattern: its off-main warm-up opened the prefs file
 * with `getSharedPreferences("last_session", ...)` — a synchronous first-touch
 * open + XML parse — with **no** guard around the open (individual key reads are
 * `safeXxx`-wrapped; the file open was not). `MainActivity.onCreate` reads the
 * persisted snapshot on the **Main** thread on the process-death resume path
 * (`lastSessionStore.read()`), whose `prefs` getter `runBlocking`-awaited the
 * warm-up `Deferred`. A power loss / disk-full event that mangles
 * `shared_prefs/last_session.xml` makes the open THROW; the exception latched in
 * the `Deferred` and rethrew on **Main** at `read()` — the identical total
 * cold-start outage as #1291, unrecoverable without clearing app data.
 *
 * ## The fixture that reproduces it (the non-happy state)
 * [CorruptLastSessionContext] throws on `getSharedPreferences("last_session",
 * ...)` exactly as a corrupt XML parse would, until the file is DELETED — a
 * happy fixture (a normal writable prefs file) can never enter the failing
 * state, so it would prove nothing (the v0.4.10/#847 lesson).
 *
 * RED on base: `store.read()` rethrows the latched exception on the reading
 * (Main-equivalent) thread → the test throws → RED.
 * GREEN with fix: the shared resilient helper catches the corrupt open,
 * best-effort deletes the file, re-opens a fresh handle, and `read()` returns
 * null (nothing to restore) without throwing → GREEN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LastSessionStoreCorruptPrefsTest {

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
     * LOAD-BEARING (#1292 acceptance 1): a corrupt/throwing `last_session`
     * source degrades to "nothing to restore" at launch instead of crashing the
     * process-death resume read. On base the unguarded open latches in the
     * warm-up Deferred and `read()` rethrows on the reading thread (RED); with
     * the shared resilient helper it returns null (GREEN).
     */
    @Test
    fun corrupt_last_session_prefs_does_not_crash_process_death_resume_read() {
        val corruptContext = CorruptLastSessionContext(realContext)

        // Constructing the store kicks off the off-main warm-up; `read()` is
        // exactly what `MainActivity.onCreate` does on the process-death resume
        // path — the crash site.
        val store = LastSessionStore(corruptContext)
        val restored = store.read()

        assertNull("corrupt last_session must degrade to no restore, not crash", restored)
        // The corrupt open was actually exercised (guards against a vacuous pass
        // where the fixture never threw — G3).
        assertTrue(
            "the corrupt-prefs open must have been attempted at least once",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
    }

    /**
     * #1292 acceptance 1 (durable self-recovery): after the first launch's
     * warm-up recovers (best-effort deleting the corrupt file), a SECOND
     * instance — a "relaunch" on the SAME app data, without the user clearing
     * data — opens cleanly, and a save→fresh-read round-trips. The fix's
     * `deleteSharedPreferences` clears the corrupt marker so the relaunch's open
     * succeeds and writes persist.
     */
    @Test
    fun recovery_is_durable_relaunch_after_corruption_round_trips() {
        val corruptContext = CorruptLastSessionContext(realContext)

        // First launch: recovers + best-effort clears the corrupt file.
        LastSessionStore(corruptContext).read()
        assertTrue(
            "first launch must have attempted the corrupt open",
            corruptContext.throwingOpenAttempts.get() >= 1,
        )
        assertTrue(
            "the fix must best-effort delete the corrupt prefs file so the " +
                "recovery is durable across relaunch",
            corruptContext.wasDeleted,
        )

        // Second launch on the SAME (now cleared) app data: a save persists and
        // a fresh instance reads it back (proves the fix re-seeded a usable,
        // writable prefs handle from the cleared file).
        val session = LastSessionStore.LastSession(
            hostId = 7L,
            hostName = "prod-box",
            hostname = "10.0.0.9",
            port = 2022,
            username = "alex",
            keyPath = "/data/keys/id_ed25519",
            sessionName = "claude-main",
            startDirectory = "/srv/app",
            tmuxSessionId = "\$3",
            sessionCreated = 1_700_000_000L,
            composerDraft = "deploy please",
            savedAtMillis = 1_000L,
        )
        LastSessionStore(corruptContext).save(session)
        val restored = LastSessionStore(corruptContext).read(
            nowMillis = 1_500L,
            maxAgeMillis = Long.MAX_VALUE,
        )
        assertEquals(session, restored)
    }

    private fun clearPrefs() {
        realContext.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /**
     * A [ContextWrapper] that simulates a corrupt `last_session` XML: it throws
     * on `getSharedPreferences("last_session", ...)` — mirroring the open/parse
     * failure — until the file is deleted, at which point (the durable recovery)
     * it opens cleanly against the real backing context. `getApplicationContext()`
     * returns `this` so the store's `context.applicationContext` is the throwing
     * wrapper.
     */
    private class CorruptLastSessionContext(base: Context) : ContextWrapper(base) {
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
        const val PREFS_NAME = "last_session"
    }
}
