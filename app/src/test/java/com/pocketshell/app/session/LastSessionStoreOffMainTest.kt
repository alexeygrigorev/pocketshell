package com.pocketshell.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1087 freeze cause F6: building the
 * `last_session` SharedPreferences eagerly in [LastSessionStore]'s
 * constructor ran a synchronous first-touch disk read on the **Main**
 * thread — StrictMode captured a 69–117ms `DiskReadViolation` in
 * `LastSessionStore.<init>` during cold-launch Hilt injection
 * (`MainActivity.onCreate` → `injectMainActivity2`). It was the next dominant
 * cold-launch stall after F1 (keystore, #1085) and F5
 * (`SystemSurfaceStateStore`, #1086) were fixed.
 *
 * Reproduce-first (D33 / G10, #780 model — no self-skip): the load-bearing
 * assertion is that the prefs-file build runs on a thread OTHER than the
 * constructing (Main) thread. On the pre-fix code the `getSharedPreferences(...)`
 * read happened in `<init>` on the constructing thread, so
 * [prefs_build_does_not_run_on_constructing_thread] FAILS RED; with the
 * off-main eager-`async` build it runs on the IO dispatcher and PASSES GREEN.
 *
 * Class coverage (G2): the remaining tests prove the off-main init does not
 * introduce an empty/racey first read — the default read returns null, and a
 * save→fresh-instance read ("process restart") returns the persisted value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LastSessionStoreOffMainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
    }

    /**
     * LOAD-BEARING (#1087 F6): the `last_session` prefs file must NOT be built
     * on the thread that constructs the store (which, in production, is the
     * Main thread during `MainActivity.onCreate` Hilt injection).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = LastSessionStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "last_session prefs must be built off the constructing (Main) " +
                "thread, not on it (#1087 F6). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: a never-saved store reads null after off-main build. */
    @Test
    fun default_read_is_null_after_offmain_build() {
        val read = LastSessionStore(context).read(nowMillis = 2_000L)
        assertNull(read)
    }

    /** Save→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun saved_session_round_trips_and_survives_restart_after_offmain_build() {
        val session = LastSessionStore.LastSession(
            hostId = 42L,
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
        LastSessionStore(context).save(session)

        // A brand-new instance reads its prefs off-main; the first read must
        // return the persisted snapshot, not an empty/default one.
        val restored = LastSessionStore(context).read(
            nowMillis = 1_500L,
            maxAgeMillis = Long.MAX_VALUE,
        )

        assertEquals(session, restored)
    }

    private fun clearPrefs() {
        context.getSharedPreferences("last_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
