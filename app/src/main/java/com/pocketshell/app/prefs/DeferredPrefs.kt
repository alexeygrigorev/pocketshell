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
 * [SharedPreferences] file OFF the Main thread, resiliently (issue #1292).
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
 * [async]) at construction time and exposes the prefs via [get]. The build is
 * started immediately, so by the time a consumer actually reads (a draft load, a
 * toggle read, a dedup check) the cache is typically already warm, and the disk
 * read is virtually never on the constructing/Main thread. Hard-cut (D22): there
 * is no legacy on-Main open branch.
 *
 * ## Resilient open + no runBlocking on Main (issue #1292)
 *
 * The file-backed constructor opens through [ResilientPrefs.open], which
 * tolerates a corrupt/unreadable prefs file (best-effort delete + re-open fresh)
 * instead of letting the open THROW — a corrupt file previously latched in the
 * warm-up `Deferred` and rethrew on **Main** at the first [get], crash-looping
 * the app/screen (the #1291 class; `app_settings` was fixed in #1229/#1248).
 *
 * [get] no longer `runBlocking`-awaits the off-main coroutine on a cache miss:
 * that parked Main on the whole contended IO-dispatcher queue drain during cold
 * start (the #1249 lesson), not merely on the small-file read. Instead the cold
 * path opens SYNCHRONOUSLY via the same resilient [opener] — a single bounded
 * small-file read that also caches — so no `runBlocking` runs on the Main /
 * cold-start path.
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
     * Open the named prefs file off-main, resiliently (#1292). The opener
     * captures [context]'s application context so it does not retain an
     * Activity/screen Context, and routes through [ResilientPrefs.open] so a
     * corrupt prefs file self-heals instead of crashing.
     */
    constructor(
        context: Context,
        prefsName: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        opener = { ResilientPrefs.open(context, prefsName) },
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

    // Single-flight the eager warm and cold get() opener paths. Without this,
    // both can observe a corrupt file and both can run recovery deletion; the
    // second delete may land after the first recovered handle writes (#1361).
    private val openLock = Any()
    private val warmUpScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val deferred: Deferred<SharedPreferences> = warmUpScope.async {
        buildThreadName = currentPhysicalThreadName()
        openOrGetCached()
    }

    /**
     * The opened prefs. Warm case: the eager off-main build already published
     * [cachedPrefs] — a plain volatile read, zero on-Main disk work. Cold /
     * contended case: open SYNCHRONOUSLY via the resilient [opener] (which caches
     * as a side effect), or share the eager warm if it is already inside the
     * opener, rather than `runBlocking`-awaiting the off-main coroutine — that
     * await parked Main on the whole contended IO-queue drain during cold start
     * (#1249), and this path must never `runBlocking` on Main (#1292).
     */
    fun get(): SharedPreferences = openOrGetCached()

    private fun openOrGetCached(): SharedPreferences =
        cachedPrefs ?: synchronized(openLock) {
            cachedPrefs ?: opener().also { cachedPrefs = it }
        }

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
