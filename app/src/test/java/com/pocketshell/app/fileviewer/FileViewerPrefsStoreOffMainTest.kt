package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression proof for issue #1125: [FileViewerPrefsStore] opened its
 * `file_viewer_prefs` SharedPreferences eagerly in a field initializer, so the
 * first-touch disk read blocked the Main thread at file-viewer-open Hilt
 * injection. The off-main [com.pocketshell.app.prefs.DeferredPrefs] build moves
 * it onto the IO dispatcher.
 *
 * LOAD-BEARING (#1125, #780 model, no self-skip): the prefs open must run on a
 * thread OTHER than the constructing (Main) thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerPrefsStoreOffMainTest {

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
        val build = FileViewerPrefsStore(context).awaitPrefsBuildThreadNameForTest()
        assertNotEquals(
            "file_viewer_prefs must open off the constructing (Main) thread " +
                "(#1125). constructing=$constructing build=$build",
            constructing,
            build,
        )
    }

    @Test
    fun reading_prefs_round_trip_after_offmain_build() {
        val store = FileViewerPrefsStore(context)
        store.setWordWrap(true)
        store.setRenderMarkdown(false)
        val restarted = FileViewerPrefsStore(context)
        assertTrue(restarted.isWordWrap())
        assertFalse(restarted.isRenderMarkdown())
    }

    private fun clear() {
        context.getSharedPreferences("file_viewer_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
