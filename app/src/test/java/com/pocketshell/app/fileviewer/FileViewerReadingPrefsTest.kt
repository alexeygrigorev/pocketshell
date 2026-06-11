package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #696: the view-model seeds its reading-prefs state from the persisted
 * store and writes back through toggles, so the wrap + render-Markdown choice
 * survives navigation/restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerReadingPrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("file_viewer_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `view model seeds from persisted defaults`() {
        val vm = FileViewerViewModel(context, FileViewerPrefsStore(context))
        val prefs = vm.readingPrefs.value
        assertFalse(prefs.wordWrap)
        assertTrue(prefs.renderMarkdown)
    }

    @Test
    fun `toggles flip state and persist across view models`() {
        val store = FileViewerPrefsStore(context)
        val vm = FileViewerViewModel(context, store)

        vm.toggleWordWrap()
        assertTrue(vm.readingPrefs.value.wordWrap)
        vm.toggleRenderMarkdown()
        assertFalse(vm.readingPrefs.value.renderMarkdown)

        // A new view-model (e.g. after navigating back in) sees the same prefs.
        val vm2 = FileViewerViewModel(context, FileViewerPrefsStore(context))
        assertTrue(vm2.readingPrefs.value.wordWrap)
        assertFalse(vm2.readingPrefs.value.renderMarkdown)
    }
}
