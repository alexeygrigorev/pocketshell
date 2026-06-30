package com.pocketshell.app.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * Regression proof for issue #1125: [PushDedupStore] and [FcmTokenRegistrar]
 * opened their SharedPreferences eagerly in the `(context)` constructor, so the
 * first-touch disk read blocked the constructing thread. The push-token
 * registrar graph builds [FcmTokenRegistrar] on the Main thread when the usage
 * panel injects it, so that was a per-usage-panel-open Main-thread block; the
 * off-main [com.pocketshell.app.prefs.DeferredPrefs] build moves it onto the IO
 * dispatcher.
 *
 * LOAD-BEARING (#1125, #780 model, no self-skip): the prefs open run by the
 * `(context)` constructor must NOT run on the constructing thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MessagingStoresOffMainTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After
    fun tearDown() {
        clear()
    }

    @Test
    fun push_dedup_prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = PushDedupStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "push_dedup prefs must open off the constructing thread (#1125). " +
                "constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun fcm_registrar_prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = FcmTokenRegistrar(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "fcm prefs must open off the constructing thread (#1125). " +
                "constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun push_dedup_round_trips_after_offmain_build() {
        assertTrue(PushDedupStore(context).markNotifiedIfNew("codex|short_term|reset-A"))
        // A fresh instance (process restart) still sees the persisted key.
        assertTrue(PushDedupStore(context).hasNotified("codex|short_term|reset-A"))
    }

    @Test
    fun fcm_registrar_round_trips_after_offmain_build() {
        FcmTokenRegistrar(context).onTokenRefreshed("tok-123")
        assertEquals("tok-123", FcmTokenRegistrar(context).pendingToken())
    }

    private fun clear() {
        listOf(PushDedupStore.PREFS_NAME, FcmTokenRegistrar.PREFS_NAME).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
