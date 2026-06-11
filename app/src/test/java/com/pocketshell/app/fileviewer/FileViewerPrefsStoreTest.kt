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
 * Issue #696: pins the wrap + render-Markdown preference persistence against
 * Robolectric's in-memory SharedPreferences (mirrors LastSessionStoreTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerPrefsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("file_viewer_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `word wrap defaults off and persists`() {
        val store = FileViewerPrefsStore(context)
        assertFalse(store.isWordWrap())

        store.setWordWrap(true)
        assertTrue(store.isWordWrap())

        // A fresh instance reads the same persisted value.
        assertTrue(FileViewerPrefsStore(context).isWordWrap())
    }

    @Test
    fun `render markdown defaults on and persists`() {
        val store = FileViewerPrefsStore(context)
        assertTrue(store.isRenderMarkdown())

        store.setRenderMarkdown(false)
        assertFalse(store.isRenderMarkdown())
        assertFalse(FileViewerPrefsStore(context).isRenderMarkdown())
    }
}
