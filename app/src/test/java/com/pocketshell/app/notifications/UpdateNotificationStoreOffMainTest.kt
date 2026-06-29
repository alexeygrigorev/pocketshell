package com.pocketshell.app.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1087 freeze cause F6 (class sweep): building the
 * `update_notifications` SharedPreferences eagerly in
 * [UpdateNotificationStore]'s constructor ran a synchronous first-touch disk
 * read on the **Main** thread. This store is built during `App.onCreate` Hilt
 * injection (the `@Singleton` `UpdateNotifier` graph) — before
 * `StrictModeInstaller` arms, so it is not in the cold-launch StrictMode log,
 * but it is the identical Main-thread launch cost as the flagged
 * `LastSessionStore.<init>`.
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the load-bearing assertion
 * is that the prefs-file build runs on a thread OTHER than the constructing
 * (Main) thread. Pre-fix the read happened in `<init>` on the constructing
 * thread (RED); with the off-main eager-`async` build it runs on the IO
 * dispatcher (GREEN).
 *
 * Class coverage (G2): the default read is null and a write→fresh-instance read
 * ("process restart") returns the persisted tag and drives `shouldNotify`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateNotificationStoreOffMainTest {

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
     * LOAD-BEARING (#1087 F6): the `update_notifications` prefs file must NOT
     * be built on the thread that constructs the store (the Main thread during
     * `App.onCreate` Hilt injection in production).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = UpdateNotificationStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "update_notifications prefs must be built off the constructing " +
                "(Main) thread, not on it (#1087 F6). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: the default tag is null after the off-main build. */
    @Test
    fun default_last_notified_is_null_after_offmain_build() {
        val store = UpdateNotificationStore(context)
        assertNull(store.lastNotifiedTag())
        assertTrue(store.shouldNotify("v9.9.9"))
    }

    /** Write→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun last_notified_round_trips_and_survives_restart_after_offmain_build() {
        UpdateNotificationStore(context).markNotified("v1.2.3")

        val restarted = UpdateNotificationStore(context)
        assertEquals("v1.2.3", restarted.lastNotifiedTag())
        assertFalse(restarted.shouldNotify("v1.2.3"))
        assertTrue(restarted.shouldNotify("v1.2.4"))
    }

    private fun clearPrefs() {
        context.getSharedPreferences("update_notifications", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
