package com.pocketshell.app.fileviewer

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.prefs.DeferredPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the text file viewer's reading preferences (issue #696):
 *
 *  - **Word wrap** — when on, long lines wrap to the viewport width instead of
 *    scrolling horizontally. Critical for reading code/long lines on a phone.
 *  - **Render Markdown** — when on, a `.md`/`.markdown` file renders as
 *    formatted Markdown; when off, the raw source is shown. Default on so a
 *    Markdown file opens formatted (the maintainer's ask), with a per-session
 *    raw toggle in the viewer for devs who want the source.
 *
 * ## Why SharedPreferences (not Room / DataStore)
 *
 * Same trade-off as [com.pocketshell.app.portfwd.ShowAllPortsStore] and
 * `SettingsRepository`: the payload is two booleans, write traffic is one edit
 * per toggle, and SharedPreferences is already on the classpath. A Room entity
 * would force a schema bump for state that needs no relational queries;
 * DataStore would add a version-catalog entry without buying a feature we need.
 *
 * The flags are global viewing preferences (not per-file): a user who wants
 * wrap on for one code file wants it everywhere. Singleton scope so every
 * viewer composition shares one prefs file.
 */
@Singleton
class FileViewerPrefsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    // Issue #1125: open the prefs file off the Main thread (it is opened at
    // file-viewer-open Hilt injection on Main otherwise).
    private val deferredPrefs = DeferredPrefs(context, PREFS_NAME)
    private val prefs: SharedPreferences get() = deferredPrefs.get()

    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String =
        deferredPrefs.awaitBuildThreadNameForTest()

    /** True when long lines should wrap (default off → horizontal scroll). */
    fun isWordWrap(): Boolean =
        runCatching { prefs.getBoolean(KEY_WORD_WRAP, false) }.getOrDefault(false)

    /** Persist the word-wrap choice. A synchronous `apply()` suffices. */
    fun setWordWrap(wrap: Boolean) {
        prefs.edit().putBoolean(KEY_WORD_WRAP, wrap).apply()
    }

    /** True when Markdown files should render formatted (default on). */
    fun isRenderMarkdown(): Boolean =
        runCatching { prefs.getBoolean(KEY_RENDER_MD, true) }.getOrDefault(true)

    /** Persist the render-Markdown choice. */
    fun setRenderMarkdown(render: Boolean) {
        prefs.edit().putBoolean(KEY_RENDER_MD, render).apply()
    }

    companion object {
        private const val PREFS_NAME = "file_viewer_prefs"
        private const val KEY_WORD_WRAP = "word_wrap"
        private const val KEY_RENDER_MD = "render_markdown"
    }
}
