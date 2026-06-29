package com.pocketshell.app.systemsurfaces

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

data class SessionWidgetState(
    val activeSessionCount: Int,
)

/**
 * Plaintext SharedPreferences store for cross-surface session state
 * (the active-session widget count).
 *
 * ## Off-main construction (issue #1086, freeze cause F5)
 *
 * `getSharedPreferences(...)` does a synchronous disk read the first time a
 * prefs file is touched in a process. Building it eagerly in the constructor
 * ran that read **on the Main thread** — StrictMode captured a ~648ms
 * `DiskReadViolation` in `SystemSurfaceStateStore.<init>` during cold-launch
 * composition (reached via
 * [com.pocketshell.app.sessions.SessionsDashboardViewModel.persistActiveSessionCount]).
 * It was previously masked behind the F1 keystore block (#1085); once F1 was
 * fixed this became the dominant remaining cold-launch stall.
 *
 * The fix mirrors F1's `AndroidKeystoreAssistantConfigStore`: the constructor
 * never reads the prefs file on the calling thread. It only *kicks off* the
 * build on [ioDispatcher] (an eager [async]) and returns immediately. The
 * build runs on a background thread, so `<init>` never blocks the constructing
 * (Main) thread. The first read warms-or-awaits that background result; the
 * [com.pocketshell.app.sessions.SessionsDashboardViewModel] persist path is
 * itself dispatched off-main so its first read also never lands on Main.
 * Hard-cut (D22): there is no synchronous on-Main fallback.
 *
 * @param context any Context; the application context is held internally so an
 *   Activity isn't pinned.
 * @param ioDispatcher dispatcher the prefs file is built on; defaults to
 *   [Dispatchers.IO]. Overridable for tests.
 */
class SystemSurfaceStateStore @JvmOverloads constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    // Once the background build completes, the result is cached here so reads
    // never re-enter runBlocking. @Volatile: written on [ioDispatcher], read
    // from any consumer thread.
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    // Test seam (issue #1086): records the name of the thread the prefs file
    // build actually ran on, so a regression test can hard-assert it is NOT
    // the constructing/Main thread. Written on [ioDispatcher].
    @Volatile
    private var prefsBuildThreadName: String? = null

    // Eager async: the build STARTS at construction but on a background thread.
    // `async` (DEFAULT start) dispatches immediately and returns without
    // blocking the caller.
    private val warmUpScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val prefsDeferred: Deferred<SharedPreferences> = warmUpScope.async {
        prefsBuildThreadName = Thread.currentThread().name
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
    }

    private val prefs: SharedPreferences
        get() = cachedPrefs ?: runBlocking { prefsDeferred.await() }

    /**
     * Test-only: block until the off-main build completes and return the name
     * of the thread it ran on. Lets a regression test prove the prefs-file
     * construction did NOT happen on the constructing/Main thread (#1086).
     */
    internal fun awaitPrefsBuildThreadNameForTest(): String {
        runBlocking { prefsDeferred.await() }
        return prefsBuildThreadName
            ?: error("prefs build thread was not recorded")
    }

    fun readSessionWidgetState(): SessionWidgetState =
        SessionWidgetState(
            activeSessionCount = prefs.safeInt(KEY_ACTIVE_SESSION_COUNT, 0).coerceAtLeast(0),
        )

    fun setActiveSessionCount(count: Int) {
        prefs.edit()
            .putInt(KEY_ACTIVE_SESSION_COUNT, count.coerceAtLeast(0))
            .apply()
    }

    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private companion object {
        const val PREFS_NAME = "system_surfaces"
        const val KEY_ACTIVE_SESSION_COUNT = "active_session_count"
    }
}

fun activeSessionCountText(count: Int): String =
    when (val safeCount = count.coerceAtLeast(0)) {
        1 -> "1 active session"
        else -> "$safeCount active sessions"
    }

internal const val SYSTEM_SURFACES_TAG: String = "PsSystemSurfaces"
