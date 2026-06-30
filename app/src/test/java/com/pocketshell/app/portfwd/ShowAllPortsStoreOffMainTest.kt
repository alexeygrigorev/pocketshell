package com.pocketshell.app.portfwd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1125: [ShowAllPortsStore] opened its
 * `port_forward_panel` SharedPreferences eagerly in a field initializer, so the
 * first-touch disk read blocked the Main thread at port-forward-panel-open Hilt
 * injection. The off-main [com.pocketshell.app.prefs.DeferredPrefs] build moves
 * it onto the IO dispatcher.
 *
 * LOAD-BEARING (#1125, #780 model, no self-skip): the prefs open must run on a
 * thread OTHER than the constructing (Main) thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShowAllPortsStoreOffMainTest {

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
    fun prefs_build_does_not_run_on_constructing_thread() {
        val constructing = Thread.currentThread().name
        val build = ShowAllPortsStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "port_forward_panel prefs must open off the constructing (Main) " +
                "thread (#1125). constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun show_all_round_trips_after_offmain_build() {
        ShowAllPortsStore(context).setShowAll(true)
        assertTrue(ShowAllPortsStore(context).isShowAll())
    }

    private fun clear() {
        context.getSharedPreferences("port_forward_panel", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
