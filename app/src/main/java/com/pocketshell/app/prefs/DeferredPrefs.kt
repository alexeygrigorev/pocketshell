package com.pocketshell.app.prefs

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
 * Issue #1125 / #1087 (freeze cause F6, class sweep): open a
 * [SharedPreferences] file OFF the Main thread.
 *
 * `Context.getSharedPreferences(...)` does a synchronous first-touch disk read.
 * Opening it eagerly in a store's field initializer blocks whatever thread
 * constructs the store. For screen/VM-scoped stores (composer draft + outbound
 * queue, port-forward panel, file viewer, messaging) that constructing thread
 * is **Main**, at first-screen-open — so each was a per-screen-open Main-thread
 * block (not on the cold-launch graph, so StrictMode flagged it only once #1089
 * lands, but the block is real on every screen open).
 *
 * This helper kicks the open + first-touch off onto [ioDispatcher] (an eager
 * [async]) at construction time and exposes the prefs via [get], which
 * warms-or-awaits. The build is started immediately, so by the time a consumer
 * actually reads (a draft load, a toggle read, a dedup check) the cache is
 * typically already warm; the worst case blocks the caller waiting for the IO
 * build, but the disk read itself never runs on the constructing/Main thread.
 * Hard-cut (D22): there is no synchronous on-Main open fallback.
 *
 * This is the reusable extraction of the inlined pattern that already lives in
 * [com.pocketshell.app.release.UpdateCheckStore],
 * [com.pocketshell.app.session.LastSessionStore], and siblings.
 */
internal class DeferredPrefs(
    private val opener: () -> SharedPreferences,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Open the named prefs file off-main. The opener captures [context]'s
     * application context so it does not retain an Activity/screen Context.
     */
    constructor(
        context: Context,
        prefsName: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        opener = {
            context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        },
        ioDispatcher = ioDispatcher,
    )

    // Written on [ioDispatcher] when the async build completes; read from any
    // thread via [get]. @Volatile so the warm value publishes across threads and
    // a later reader never re-enters runBlocking once the build is done.
    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    // The PHYSICAL thread name the open ran on, for the off-main regression
    // proof. Written on [ioDispatcher].
    @Volatile
    private var buildThreadName: String? = null

    private val warmUpScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val deferred: Deferred<SharedPreferences> = warmUpScope.async {
        buildThreadName = currentPhysicalThreadName()
        opener().also { cachedPrefs = it }
    }

    /** The opened prefs — warm if the off-main build finished, else await it. */
    fun get(): SharedPreferences = cachedPrefs ?: runBlocking { deferred.await() }

    /**
     * Test-only: block until the off-main open completes and return the name of
     * the thread it ran on (#1125 / #1087). Proves the prefs-file open did NOT
     * run on the constructing/Main thread.
     */
    @VisibleForTesting
    internal fun awaitBuildThreadNameForTest(): String {
        runBlocking { deferred.await() }
        return buildThreadName ?: error("prefs build thread was not recorded")
    }

    // The open runs inside a coroutine, whose framework decorates the thread
    // name with a " @coroutine#N" suffix. Strip it so the recorded value is the
    // PHYSICAL thread name — otherwise an on-Main open (the un-fixed base) would
    // still differ from the captured constructing name by the suffix alone,
    // giving a false off-main pass (#1087 G6: keep the assertion load-bearing).
    private fun currentPhysicalThreadName(): String =
        Thread.currentThread().name.substringBefore(" @coroutine")
}
