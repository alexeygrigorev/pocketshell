package com.pocketshell.app.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

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
 *
 * ## Off-main construction (issue #1087, freeze cause F6 class sweep)
 *
 * This store is built during `App.onCreate` Hilt injection (the `@Singleton`
 * `UpdateNotifier` graph) — on the Main thread, before the first frame.
 * `getSharedPreferences(...)` does a synchronous first-touch disk read, so
 * building it eagerly in the constructor blocked Main during cold launch (the
 * identical class StrictMode flagged for `LastSessionStore.<init>`; built
 * before `StrictModeInstaller` arms so it is not in the log but is the same
 * Main-thread launch cost). The build is moved off-main onto [ioDispatcher]
 * (eager [async]); the getter warms-or-awaits. Reads run off-main from the
 * scheduler IO scope / the notifier, so the cache is warm before any read.
 * Hard-cut (D22): no synchronous on-Main fallback.
 */
class UpdateNotificationStore @JvmOverloads constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Volatile
    private var prefsBuildThreadName: String? = null

    private val warmUpScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val prefsDeferred: Deferred<SharedPreferences> = warmUpScope.async {
        prefsBuildThreadName = currentPhysicalThreadName()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
    }

    private val prefs: SharedPreferences
        get() = cachedPrefs ?: runBlocking { prefsDeferred.await() }

    /**
     * Test-only: block until the off-main build completes and return the name
     * of the thread it ran on (#1087). Proves the prefs-file build did NOT run
     * on the constructing/Main thread.
     */
    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String {
        runBlocking { prefsDeferred.await() }
        return prefsBuildThreadName
            ?: error("prefs build thread was not recorded")
    }

    // The build runs inside a coroutine, whose framework decorates the thread
    // name with a " @coroutine#N" suffix. Strip it so the recorded value is the
    // PHYSICAL thread name — otherwise an on-Main build (e.g. the un-fixed base)
    // would still differ from the captured constructing name by the suffix alone,
    // giving a false off-main pass (#1087 G6: keep the assertion load-bearing).
    private fun currentPhysicalThreadName(): String =
        Thread.currentThread().name.substringBefore(" @coroutine")

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
