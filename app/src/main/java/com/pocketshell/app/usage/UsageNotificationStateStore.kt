package com.pocketshell.app.usage

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.core.usage.UsageThresholdState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Durable record of which usage warning crossings have already been
 * notified or explicitly dismissed.
 *
 * Issue #619: the notifier de-dupe used to live only in an in-memory
 * `Set<UsageNotificationKey>` on the `@Singleton` notifier. PocketShell is
 * foreground-only (decision D21): the OS kills the process in the background
 * and recreates it on next foreground, wiping that in-memory set. The next
 * usage sync then re-posts the same "quota exceeded" notification on every
 * cold launch.
 *
 * This store persists the notified-crossing set across process death so a
 * crossing fires exactly once until it re-arms (usage drops below threshold
 * or the window resets), and a user dismissal durably suppresses re-firing
 * until the crossing re-arms.
 *
 * The interface is deliberately tiny so JVM unit tests pass a fake instead
 * of touching a real Android preference store.
 */
public interface UsageNotificationStateStore {
    /** Keys for crossings that have already been notified (or dismissed). */
    public fun notifiedKeys(): Set<UsageNotificationKey>

    /**
     * Replace the persisted notified set with exactly [keys].
     *
     * Keys that warrant a warning this tick but are not yet persisted get
     * added; keys that no longer warrant a warning get pruned so the
     * crossing re-arms for a future genuine crossing. Dismissed keys that
     * still warrant a warning must be retained by the caller.
     */
    public fun setNotifiedKeys(keys: Set<UsageNotificationKey>)
}

/**
 * Stable, parseable string identity of a usage warning crossing.
 *
 * Encodes host id, provider, threshold state, and the constrained window
 * name so the persisted set can be reconstructed across process death and
 * a delete-intent broadcast can carry the key as a single extra.
 */
public data class UsageNotificationKey(
    val hostId: Long,
    val provider: String,
    val state: UsageThresholdState,
    val windowName: String?,
) {
    public fun encode(): String =
        listOf(
            hostId.toString(),
            provider,
            state.name,
            windowName ?: "",
        ).joinToString(FIELD_SEPARATOR)

    public companion object {
        // Unit Separator (0x1F) — never appears in provider/window names.
        private const val FIELD_SEPARATOR = "\u001F"

        public fun decode(raw: String): UsageNotificationKey? {
            val parts = raw.split(FIELD_SEPARATOR)
            if (parts.size != 4) return null
            val hostId = parts[0].toLongOrNull() ?: return null
            val state = runCatching { UsageThresholdState.valueOf(parts[2]) }.getOrNull()
                ?: return null
            val windowName = parts[3].takeIf { it.isNotEmpty() }
            return UsageNotificationKey(
                hostId = hostId,
                provider = parts[1],
                state = state,
                windowName = windowName,
            )
        }
    }
}

/**
 * [UsageNotificationStateStore] backed by a small dedicated
 * [android.content.SharedPreferences] file. Deliberately separate from
 * user-facing settings (`app_settings`) so it never appears in the Settings
 * UI or backup/restore of user preferences.
 *
 * ## Off-main construction (issue #1087, freeze cause F6 class sweep)
 *
 * This store is built during `App.onCreate` Hilt injection (the `@Singleton`
 * `UsageNotifier` graph, via `UsageScheduler`) — on the Main thread, before
 * the first frame. `getSharedPreferences(...)` does a synchronous first-touch
 * disk read, so building it eagerly in the constructor blocked Main during
 * cold launch (the identical class StrictMode flagged for
 * `LastSessionStore.<init>`; built before `StrictModeInstaller` arms so it is
 * not in the log but is the same Main-thread launch cost). The build is moved
 * off-main onto [ioDispatcher] (eager [async]); the getter warms-or-awaits.
 * Reads run off-main from the usage scheduler IO loop, so the cache is warm
 * before any read. Hard-cut (D22): no synchronous on-Main fallback.
 */
public class SharedPreferencesUsageNotificationStateStore @JvmOverloads constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UsageNotificationStateStore {

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

    override fun notifiedKeys(): Set<UsageNotificationKey> =
        prefs.getStringSet(KEY_NOTIFIED, emptySet())
            .orEmpty()
            .mapNotNull(UsageNotificationKey::decode)
            .toSet()

    override fun setNotifiedKeys(keys: Set<UsageNotificationKey>) {
        prefs.edit()
            .putStringSet(KEY_NOTIFIED, keys.map { it.encode() }.toSet())
            .apply()
    }

    public companion object {
        public const val PREFS_NAME: String = "usage_notification_state"
        private const val KEY_NOTIFIED = "notified_keys"
    }
}
