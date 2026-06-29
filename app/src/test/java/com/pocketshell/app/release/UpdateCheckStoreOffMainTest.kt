package com.pocketshell.app.release

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
 * Regression proof for issue #1087 freeze cause F6 (class sweep): building the
 * `update_check_throttle` SharedPreferences eagerly in [UpdateCheckStore]'s
 * constructor ran a synchronous first-touch disk read on the **Main** thread.
 * This store is built during `App.onCreate` Hilt injection (the `@Singleton`
 * `UpdateCheckScheduler` graph) — before `StrictModeInstaller` arms, so the
 * cold-launch StrictMode log does not flag it, but it is the identical
 * Main-thread launch cost as the flagged `LastSessionStore.<init>`.
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the load-bearing assertion
 * is that the prefs-file build runs on a thread OTHER than the constructing
 * (Main) thread. On the pre-fix code the read happened in `<init>` on the
 * constructing thread (RED); with the off-main eager-`async` build it runs on
 * the IO dispatcher (GREEN).
 *
 * Class coverage (G2): the default read is 0 and a write→fresh-instance read
 * ("process restart") returns the persisted value after the off-main build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateCheckStoreOffMainTest {

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
     * LOAD-BEARING (#1087 F6): the `update_check_throttle` prefs file must NOT
     * be built on the thread that constructs the store (the Main thread during
     * `App.onCreate` Hilt injection in production).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = UpdateCheckStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "update_check_throttle prefs must be built off the constructing " +
                "(Main) thread, not on it (#1087 F6). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: the default is 0 after the off-main build. */
    @Test
    fun default_last_checked_is_zero_after_offmain_build() {
        assertEquals(0L, UpdateCheckStore(context).lastCheckedAtMillis())
    }

    /** Write→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun last_checked_round_trips_and_survives_restart_after_offmain_build() {
        UpdateCheckStore(context).markCheckedAt(1_700_000_123L)

        val restarted = UpdateCheckStore(context)
        assertEquals(1_700_000_123L, restarted.lastCheckedAtMillis())
    }

    private fun clearPrefs() {
        context.getSharedPreferences("update_check_throttle", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
