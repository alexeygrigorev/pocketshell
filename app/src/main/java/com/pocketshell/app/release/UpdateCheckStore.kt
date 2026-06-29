package com.pocketshell.app.release

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
 *
 * ## Off-main construction (issue #1087, freeze cause F6 class sweep)
 *
 * This store is built during `App.onCreate` Hilt injection (the `@Singleton`
 * [UpdateCheckScheduler] graph) — on the Main thread, before the first frame.
 * `getSharedPreferences(...)` does a synchronous first-touch disk read, so
 * building it eagerly in the constructor blocked Main during cold launch (the
 * same class StrictMode flagged for `LastSessionStore.<init>`; this one is
 * built before `StrictModeInstaller` arms, so it is not in the log but is the
 * identical Main-thread launch cost). The build is moved off-main onto
 * [ioDispatcher] (eager [async]); the getter warms-or-awaits. Reads run on the
 * scheduler's `Dispatchers.IO` scope, so the cache is warm before any read.
 * Hard-cut (D22): no synchronous on-Main fallback.
 */
class UpdateCheckStore @JvmOverloads constructor(
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
