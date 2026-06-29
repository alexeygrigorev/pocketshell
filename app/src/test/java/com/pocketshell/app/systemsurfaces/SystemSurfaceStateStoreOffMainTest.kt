package com.pocketshell.app.systemsurfaces

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1086 freeze cause F5: building the
 * `system_surfaces` SharedPreferences eagerly in
 * [SystemSurfaceStateStore]'s constructor ran a synchronous first-touch disk
 * read on the **Main** thread — StrictMode captured a ~648ms `DiskReadViolation`
 * in `SystemSurfaceStateStore.<init>` during cold-launch composition, reached
 * via
 * [com.pocketshell.app.sessions.SessionsDashboardViewModel.persistActiveSessionCount].
 * It was masked behind the F1 keystore block (#1085) until F1 was fixed.
 *
 * Reproduce-first (D33 / G10, #780 model — no self-skip): the load-bearing
 * assertion is that the prefs-file build runs on a thread OTHER than the
 * constructing (Main) thread. On the pre-fix code the
 * `getSharedPreferences(...)` read happened in `<init>` on the constructing
 * thread, so [prefs_build_does_not_run_on_constructing_thread] FAILS RED; with
 * the off-main eager-`async` build it runs on the IO dispatcher and PASSES
 * GREEN.
 *
 * Class coverage (G2): the remaining tests prove the off-main init does not
 * introduce an empty/racey first read — defaults, a write→fresh-instance read
 * ("process restart"), and the wrong-type fallback all return the correct
 * values after the build is awaited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SystemSurfaceStateStoreOffMainTest {

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
     * LOAD-BEARING (#1086 F5): the `system_surfaces` prefs file must NOT be
     * built on the thread that constructs the store (which, in production, is
     * the Main thread during the first Compose frame).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = SystemSurfaceStateStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "system_surfaces prefs must be built off the constructing (Main) " +
                "thread, not on it (#1086 F5). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: the default is 0 after the off-main build. */
    @Test
    fun default_count_is_zero_after_offmain_build() {
        val state = SystemSurfaceStateStore(context).readSessionWidgetState()
        assertEquals(SessionWidgetState(activeSessionCount = 0), state)
    }

    /** Write→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun count_round_trips_and_survives_restart_after_offmain_build() {
        SystemSurfaceStateStore(context).setActiveSessionCount(7)

        val restarted = SystemSurfaceStateStore(context)
        assertEquals(
            SessionWidgetState(activeSessionCount = 7),
            restarted.readSessionWidgetState(),
        )
    }

    /** The wrong-type fallback still applies after the off-main build. */
    @Test
    fun wrong_type_falls_back_to_zero_after_offmain_build() {
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit()
            .putString("active_session_count", "many")
            .commit()

        val store = SystemSurfaceStateStore(context)

        assertEquals(
            SessionWidgetState(activeSessionCount = 0),
            store.readSessionWidgetState(),
        )
    }

    private fun clearPrefs() {
        context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
