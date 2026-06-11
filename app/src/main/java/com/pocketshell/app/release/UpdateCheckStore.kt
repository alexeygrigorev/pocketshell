package com.pocketshell.app.release

import android.content.Context
import android.content.SharedPreferences

/**
 * Throttle ledger for the foreground update check (issue #698). Persists
 * the wall-clock millis of the most recent GitHub-Releases poll so the
 * resume-triggered + open-host-triggered checks don't hammer the GitHub
 * API on every app foreground / host tap.
 *
 * The maintainer almost never opens the host-list/home screen (they
 * deep-link straight into a host), so the old "check once in
 * `HostListViewModel.init`" trigger almost never fired and they missed
 * release prompts. Issue #698 moves the trigger to process foreground
 * resume + host open. Those events can fire many times per minute (quick
 * app-switches, tapping between hosts), so a persisted throttle window is
 * required to keep the call rate sane — and persistence means a cold
 * relaunch within the window also skips the redundant poll.
 *
 * Backed by a tiny dedicated [SharedPreferences] file rather than
 * [com.pocketshell.app.settings.AppSettings] because this is bookkeeping
 * state, not a user-facing preference. Mirrors the lightweight pref-store
 * pattern already used by [com.pocketshell.app.notifications.UpdateNotificationStore].
 */
class UpdateCheckStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The wall-clock millis of the last completed update check, or `0L`
     * if no check has ever run.
     */
    fun lastCheckedAtMillis(): Long =
        runCatching { prefs.getLong(KEY_LAST_CHECKED_AT, 0L) }.getOrDefault(0L)

    /** Record [nowMillis] as the most recent update-check time. */
    fun markCheckedAt(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECKED_AT, nowMillis).apply()
    }

    private companion object {
        const val PREFS_NAME = "update_check_throttle"
        const val KEY_LAST_CHECKED_AT = "last_checked_at_millis"
    }
}
