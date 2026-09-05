package com.pocketshell.next.release

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny ledger for the foreground update check: last-poll time (6h throttle)
 * and the release tag the user dismissed, so that tag is not re-nagged.
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

    private companion object {
        const val PREFS_NAME = "update_check"
        const val KEY_LAST_CHECKED_AT = "last_checked_at_millis"
        const val KEY_DISMISSED_TAG = "dismissed_tag"
    }
}
