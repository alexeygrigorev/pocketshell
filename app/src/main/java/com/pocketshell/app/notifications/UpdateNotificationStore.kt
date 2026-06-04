package com.pocketshell.app.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * De-dupe ledger for the "new app version available" notification
 * (issue #502). Persists the tag of the most recently notified release
 * so the foreground [com.pocketshell.app.release.ReleaseChecker] can
 * post the notification exactly once per version rather than every time
 * it re-runs (it fires from `HostListViewModel.init {}` and from
 * pull-to-refresh).
 *
 * Backed by a tiny dedicated [SharedPreferences] file rather than
 * [com.pocketshell.app.settings.AppSettings] because this is bookkeeping
 * state, not a user-facing preference — surfacing a "last notified
 * version" toggle in Settings would be noise. Mirrors the lightweight
 * pref-store pattern used elsewhere in the app
 * (`SystemSurfaceStateStore`, `SettingsRepository`).
 *
 * Tag comparison is exact-string: the checker only ever returns a
 * release that is strictly newer than the installed version, so the last
 * notified tag changes monotonically and an exact match is sufficient to
 * mean "already notified this exact release".
 */
class UpdateNotificationStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The release tag last surfaced as a notification, or `null`. */
    fun lastNotifiedTag(): String? =
        runCatching { prefs.getString(KEY_LAST_NOTIFIED_TAG, null) }.getOrNull()

    /**
     * Returns `true` when [tagName] has NOT already been notified, i.e.
     * the caller should post a notification for it. A return of `false`
     * means the same version was already surfaced and must be suppressed.
     */
    fun shouldNotify(tagName: String): Boolean =
        lastNotifiedTag() != tagName

    /** Record [tagName] as the most recently notified release. */
    fun markNotified(tagName: String) {
        prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, tagName).apply()
    }

    private companion object {
        const val PREFS_NAME = "update_notifications"
        const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
    }
}
