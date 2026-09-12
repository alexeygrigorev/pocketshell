package com.pocketshell.next.release

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny ledger for the foreground update check: last-poll time (6h throttle),
 * the release tag the user dismissed, and the release the last successful
 * check found — so an offer survives process death instead of silently
 * vanishing for the rest of the throttle window (issue #2552).
 *
 * SharedPreferences, like the rest of app2's small stores. Bookkeeping, not a
 * user-facing preference. `by lazy` keeps the first-touch disk read off
 * [com.pocketshell.next.App.onCreate] Hilt injection.
 */
@Singleton
class UpdateCheckStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun lastCheckedAtMillis(): Long =
        runCatching { prefs.getLong(KEY_LAST_CHECKED_AT, 0L) }.getOrDefault(0L)

    fun markCheckedAt(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECKED_AT, nowMillis).apply()
    }

    fun dismissedTag(): String? =
        runCatching { prefs.getString(KEY_DISMISSED_TAG, null) }.getOrNull()

    fun markDismissed(tagName: String) {
        prefs.edit().putString(KEY_DISMISSED_TAG, tagName).apply()
    }

    /** The release the last successful check offered, or null when none is stored. */
    fun foundRelease(): ReleaseInfo? {
        val tag = runCatching { prefs.getString(KEY_FOUND_TAG, null) }.getOrNull() ?: return null
        return ReleaseInfo(
            tagName = tag,
            htmlUrl = prefs.string(KEY_FOUND_HTML_URL),
            apkUrl = prefs.string(KEY_FOUND_APK_URL),
            publishedDateLabel = prefs.string(KEY_FOUND_DATE_LABEL),
        )
    }

    fun markFoundRelease(info: ReleaseInfo) {
        prefs.edit()
            .putString(KEY_FOUND_TAG, info.tagName)
            .putString(KEY_FOUND_HTML_URL, info.htmlUrl)
            .putString(KEY_FOUND_APK_URL, info.apkUrl)
            .putString(KEY_FOUND_DATE_LABEL, info.publishedDateLabel)
            .apply()
    }

    /** The stored offer is no longer an offer (the check said up to date). */
    fun clearFoundRelease() {
        prefs.edit()
            .remove(KEY_FOUND_TAG)
            .remove(KEY_FOUND_HTML_URL)
            .remove(KEY_FOUND_APK_URL)
            .remove(KEY_FOUND_DATE_LABEL)
            .apply()
    }

    private fun SharedPreferences.string(key: String): String =
        runCatching { getString(key, null) }.getOrNull().orEmpty()

    private companion object {
        const val PREFS_NAME = "update_check"
        const val KEY_LAST_CHECKED_AT = "last_checked_at_millis"
        const val KEY_DISMISSED_TAG = "dismissed_tag"
        const val KEY_FOUND_TAG = "found_tag"
        const val KEY_FOUND_HTML_URL = "found_html_url"
        const val KEY_FOUND_APK_URL = "found_apk_url"
        const val KEY_FOUND_DATE_LABEL = "found_date_label"
    }
}
