package com.pocketshell.app.usage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.usage.UsageThresholdState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1087 freeze cause F6 (class sweep): building the
 * `usage_notification_state` SharedPreferences eagerly in
 * [SharedPreferencesUsageNotificationStateStore]'s constructor ran a
 * synchronous first-touch disk read on the **Main** thread. This store is
 * built during `App.onCreate` Hilt injection (the `@Singleton` `UsageNotifier`
 * graph, via `UsageScheduler`) — before `StrictModeInstaller` arms, so it is
 * not in the cold-launch StrictMode log, but it is the identical Main-thread
 * launch cost as the flagged `LastSessionStore.<init>`.
 *
 * Reproduce-first (D33 / G10, #780 — no self-skip): the load-bearing assertion
 * is that the prefs-file build runs on a thread OTHER than the constructing
 * (Main) thread. Pre-fix the read happened in `<init>` on the constructing
 * thread (RED); with the off-main eager-`async` build it runs on the IO
 * dispatcher (GREEN).
 *
 * Class coverage (G2): the default set is empty and a write→fresh-instance read
 * ("process restart") returns the persisted notified keys after the off-main
 * build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageNotificationStateStoreOffMainTest {

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
     * LOAD-BEARING (#1087 F6): the `usage_notification_state` prefs file must
     * NOT be built on the thread that constructs the store (the Main thread
     * during `App.onCreate` Hilt injection in production).
     */
    @Test
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructingThread = Thread.currentThread().name

        val store = SharedPreferencesUsageNotificationStateStore(context)
        val buildThread = store.awaitPrefsBuildThreadNameForTest()

        assertNotEquals(
            "usage_notification_state prefs must be built off the constructing " +
                "(Main) thread, not on it (#1087 F6). " +
                "constructing=$constructingThread build=$buildThread",
            constructingThread,
            buildThread,
        )
    }

    /** No empty/racey first read: the default set is empty after the off-main build. */
    @Test
    fun default_notified_keys_empty_after_offmain_build() {
        val store = SharedPreferencesUsageNotificationStateStore(context)
        assertTrue(store.notifiedKeys().isEmpty())
    }

    /** Write→fresh-instance read survives the off-main build (process restart). */
    @Test
    fun notified_keys_round_trip_and_survive_restart_after_offmain_build() {
        val keys = setOf(
            UsageNotificationKey(
                hostId = 7L,
                provider = "anthropic",
                state = UsageThresholdState.Critical,
                windowName = "5h",
            ),
            UsageNotificationKey(
                hostId = 9L,
                provider = "openai",
                state = UsageThresholdState.Exceeded,
                windowName = null,
            ),
        )
        SharedPreferencesUsageNotificationStateStore(context).setNotifiedKeys(keys)

        val restarted = SharedPreferencesUsageNotificationStateStore(context)
        assertEquals(keys, restarted.notifiedKeys())
    }

    private fun clearPrefs() {
        context.getSharedPreferences("usage_notification_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
