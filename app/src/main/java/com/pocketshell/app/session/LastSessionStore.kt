package com.pocketshell.app.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.prefs.DeferredPrefs
import com.pocketshell.app.tmux.TmuxSessionGeneration
import com.pocketshell.app.tmux.tmuxSessionGenerationOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Issue #177: persists the user's last in-session view so returning to
 * the app after an app-switch (or a process death) restores the previous
 * `tmux -CC` session optimistically instead of dumping the user back on
 * the host list and making them re-tap their way in.
 *
 * ## Why SharedPreferences (not Room / DataStore)
 *
 * This is the same trade-off [com.pocketshell.app.settings.SettingsRepository]
 * already made: the payload is tiny (one host tuple + a session name +
 * a composer draft + a timestamp), write traffic is one-edit-per-app-stop,
 * and SharedPreferences is already on the classpath transitively. A Room
 * entity would force a schema bump and migration for state that does not need
 * relational queries. DataStore would add a version-catalog entry without
 * buying any feature we need. Future issues are free to migrate.
 *
 * ## Hard-cut (D22)
 *
 * There is no legacy shape to honour — this is a brand new store. A blob
 * written by a previous build that does not parse cleanly is simply
 * discarded ([read] returns null). We do not carry an `if (old shape)`
 * compatibility branch for this auxiliary preference blob.
 *
 * ## Foreground-only (D21)
 *
 * Nothing here runs while backgrounded. [save] is invoked from the
 * activity's `onStop`; [read] from `onCreate` / resume routing. The store
 * is pure on-disk state — it never holds a connection or schedules work.
 *
 * Singleton scope so the activity and any future consumer share one
 * instance over the same prefs file.
 *
 * ## Off-main construction (issue #1087, freeze cause F6)
 *
 * `getSharedPreferences(...)` does a synchronous disk read the first time a
 * prefs file is touched in a process. Building it eagerly in the constructor
 * ran that read **on the Main thread** — StrictMode captured a 69–117ms
 * `DiskReadViolation` in `LastSessionStore.<init>` during cold-launch Hilt
 * injection (`MainActivity.onCreate` → `injectMainActivity2`). It was the next
 * dominant cold-launch stall after the F1 keystore (#1085) and F5
 * `SystemSurfaceStateStore` (#1086) blocks were fixed.
 *
 * The fix mirrors F5's [com.pocketshell.app.systemsurfaces.SystemSurfaceStateStore]:
 * the constructor never reads the prefs file on the calling thread. It only
 * *kicks off* the build on [ioDispatcher] (an eager `async`) and returns
 * immediately, so `<init>` never blocks the constructing (Main) thread. The
 * first read warms-or-opens that background result; on a fresh cold launch
 * [read] is never called (only on the process-death resume path), so the
 * background build is virtually always warm before any read. Hard-cut (D22):
 * there is no legacy on-Main open branch.
 *
 * ## Resilient open + no runBlocking on Main (issue #1292)
 *
 * The prefs open routes through [com.pocketshell.app.prefs.DeferredPrefs], which
 * opens via [com.pocketshell.app.prefs.ResilientPrefs] — the ONE shared resilient
 * helper (best-effort delete + re-open on a corrupt file). A corrupt
 * `last_session.xml` previously made `getSharedPreferences(...)` THROW inside the
 * warm-up coroutine; `MainActivity.onCreate`'s process-death-resume
 * `lastSessionStore.read()` then rethrew it on **Main**, crash-looping launch
 * exactly like the #1291 `app_settings` outage (fixed for that one file in
 * #1229/#1248). Routing through [DeferredPrefs] closes this door of the same
 * class, and its [DeferredPrefs.get] opens synchronously on the cold path rather
 * than `runBlocking`-awaiting the off-main coroutine (#1249) — so [read] never
 * `runBlocking`s on Main.
 */
@Singleton
class LastSessionStore @VisibleForTesting internal constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val durableWriteWaitMillis: Long = DURABLE_WRITE_WAIT_MILLIS,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        Dispatchers.IO,
        DURABLE_WRITE_WAIT_MILLIS,
    )

    // The ONE shared resilient prefs helper (#1292): eager off-main open through
    // [com.pocketshell.app.prefs.ResilientPrefs] (corrupt-tolerant, self-healing),
    // and a cold-path synchronous open that never `runBlocking`s on Main (#1249).
    private val deferredPrefs = DeferredPrefs(context, PREFS_NAME, ioDispatcher)

    private val prefs: SharedPreferences
        get() = deferredPrefs.get()

    /**
     * Test-only: block until the off-main build completes and return the name
     * of the thread it ran on. Lets the regression test prove the prefs-file
     * construction did NOT happen on the constructing/Main thread (#1087).
     */
    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String =
        deferredPrefs.awaitBuildThreadNameForTest()

    /**
     * Issue #834: identity of the most recently killed session, remembered in
     * memory for this process. A session the user just deleted (tree/host-detail
     * Stop or in-session Stop, both confirmed via
     * [com.pocketshell.app.tmux.SessionLifecycleSignals.emitKilled]) must NEVER
     * be persisted as the "last active" view again — otherwise the next
     * foreground/process-death restore re-opens the dead session, which #818
     * lands on its Conversation tab (showing a deleted session is the #686
     * hazard).
     *
     * Clearing the on-disk record alone is not enough: the user may still be
     * sitting on the now-dead session screen when they background the app, and
     * `MainActivity.onStop` would re-`save()` that exact dead session, re-arming
     * the restore. So [onSessionKilled] both clears any matching persisted
     * record AND records this tombstone, and [save] refuses to persist a session
     * whose identity matches it.
     */
    @Volatile
    private var killedTombstone: SessionIdentity? = null

    /** Exact host-scoped tmux generation used for kill matching (#834). */
    private data class SessionIdentity(
        val hostId: Long,
        val generation: TmuxSessionGeneration,
    )

    /**
     * Persisted snapshot of the last active `tmux -CC` session view.
     *
     * Only the fields needed to rebuild an
     * [AppDestination.TmuxSession] plus the composer draft and the wall
     * clock the snapshot was taken at (so [read] can age it out). The
     * key passphrase is intentionally NOT stored — the reattach path
     * resolves the key from disk by path, same as a cold attach, so we
     * never write a secret into prefs.
     */
    data class LastSession(
        val hostId: Long,
        val hostName: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val sessionName: String,
        val startDirectory: String?,
        val tmuxSessionId: String? = null,
        val sessionCreated: Long? = null,
        val composerDraft: String,
        val savedAtMillis: Long,
    ) {
        /** The complete tmux identity, when this snapshot carried one. */
        val generation: TmuxSessionGeneration?
            get() = tmuxSessionGenerationOrNull(tmuxSessionId, sessionCreated)
    }

    /**
     * Persist [session] as the last active view. Called from
     * `MainActivity.onStop` when the current destination is a
     * [AppDestination.TmuxSession]. Returning from this lifecycle boundary
     * means the snapshot has been synchronously committed to disk: an external
     * force-stop may kill the process immediately afterwards (#2265).
     *
     * @return `true` only when the commit was acknowledged within the bounded
     *   wait; `false` means the snapshot is not durably acknowledged and the
     *   caller must not treat it as persisted.
     */
    fun save(session: LastSession): Boolean {
        // Issue #834: never persist a session the user just deleted. If the
        // user backgrounds the app while still on the now-dead session screen,
        // `onStop` would otherwise re-arm the restore for a session that no
        // longer exists, and the next foreground/process-death restore would
        // reopen it (→ #818 Conversation tab of a deleted session, the #686
        // hazard). Clear the on-disk record instead of writing the dead one.
        if (session.identity()?.let { it == killedTombstone } == true) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-save-suppressed trigger=onStop reason=killed " +
                    "hostId=${session.hostId} session=${session.sessionName}",
            )
            return clearDurably("save-suppressed-killed")
        }
        Log.i(
            LAST_SESSION_LOG_TAG,
            "last-session-save trigger=onStop hostId=${session.hostId} " +
                "host=${session.hostname} port=${session.port} user=${session.username} " +
                "session=${session.sessionName} startDirectory=${session.startDirectory}",
        )
        return persistDurably("save") {
            putLong(KEY_HOST_ID, session.hostId)
            putString(KEY_HOST_NAME, session.hostName)
            putString(KEY_HOSTNAME, session.hostname)
            putInt(KEY_PORT, session.port)
            putString(KEY_USERNAME, session.username)
            putString(KEY_KEY_PATH, session.keyPath)
            putString(KEY_SESSION_NAME, session.sessionName)
            putString(KEY_START_DIR, session.startDirectory)
            putString(KEY_TMUX_SESSION_ID, session.tmuxSessionId)
            putString(KEY_COMPOSER_DRAFT, session.composerDraft)
            putLong(KEY_SAVED_AT, session.savedAtMillis)
            if (session.sessionCreated != null) {
                putLong(KEY_SESSION_CREATED, session.sessionCreated)
            } else {
                remove(KEY_SESSION_CREATED)
            }
        }
    }

    /**
     * Read the last active session if one was persisted and it is fresh
     * enough to be worth restoring.
     *
     * Returns null when:
     *  - nothing was ever saved (cold install / user never opened a
     *    tmux session),
     *  - the blob is malformed (a previous build wrote a different shape;
     *    D22 — discard, do not migrate),
     *  - the snapshot is older than [maxAgeMillis] (stale; the user has
     *    moved on, restore would be surprising).
     *
     * @param nowMillis injectable clock for deterministic unit tests.
     */
    fun read(
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): LastSession? =
        parse(nowMillis, maxAgeMillis)?.also { session ->
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-restore trigger=cold-restore hostId=${session.hostId} " +
                    "host=${session.hostname} port=${session.port} user=${session.username} " +
                    "session=${session.sessionName} startDirectory=${session.startDirectory}",
            )
        }

    /**
     * Issue #1239: a non-logging read of the persisted last-session snapshot,
     * used by the host-card "Resume last session" affordance and the
     * Active-Sessions widget deep-link. Same recency + validity rules as
     * [read] (returns null for a stale, killed, or absent snapshot — so a gone
     * session simply hides the affordance and the user falls back to normal
     * navigation, no dead end), but WITHOUT the `trigger=cold-restore` log line:
     * this is peeked whenever the host list appears / a widget refreshes, not on
     * an actual process-death restore, so tagging it `cold-restore` would be
     * misleading log noise.
     */
    fun peek(
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): LastSession? = parse(nowMillis, maxAgeMillis)

    /**
     * Shared parse of the persisted snapshot into a [LastSession], applying the
     * recency cap + field-validity guards. Both [read] (which logs) and [peek]
     * (which does not) delegate here so the two never drift.
     */
    private fun parse(nowMillis: Long, maxAgeMillis: Long): LastSession? {
        val savedAt = prefs.safeLong(KEY_SAVED_AT, 0L) ?: return null
        if (savedAt <= 0L) return null
        if (nowMillis - savedAt > maxAgeMillis) return null
        val hostId = prefs.safeLong(KEY_HOST_ID, 0L) ?: return null
        val hostname = prefs.safeString(KEY_HOSTNAME, null) ?: return null
        val username = prefs.safeString(KEY_USERNAME, null) ?: return null
        val keyPath = prefs.safeString(KEY_KEY_PATH, null) ?: return null
        val sessionName = prefs.safeString(KEY_SESSION_NAME, null) ?: return null
        if (hostId <= 0L || hostname.isBlank() || keyPath.isBlank() || sessionName.isBlank()) {
            return null
        }
        return LastSession(
            hostId = hostId,
            hostName = prefs.safeString(KEY_HOST_NAME, hostname) ?: hostname,
            hostname = hostname,
            port = prefs.safeInt(KEY_PORT, DEFAULT_SSH_PORT) ?: DEFAULT_SSH_PORT,
            username = username,
            keyPath = keyPath,
            sessionName = sessionName,
            startDirectory = prefs.safeString(KEY_START_DIR, null),
            tmuxSessionId = prefs.safeString(KEY_TMUX_SESSION_ID, null)?.trim()?.ifBlank { null },
            sessionCreated = prefs.safeLong(KEY_SESSION_CREATED, 0L)?.takeIf { it > 0L },
            composerDraft = prefs.safeString(KEY_COMPOSER_DRAFT, "") ?: "",
            savedAtMillis = savedAt,
        )
    }

    private fun SharedPreferences.safeString(key: String, default: String?): String? =
        runCatching { getString(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeLong(key: String, default: Long): Long? =
        runCatching { getLong(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }

    private fun SharedPreferences.safeInt(key: String, default: Int): Int? =
        runCatching { getInt(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                null
            }

    /**
     * Clear the persisted snapshot. Called when the user explicitly walks
     * away from the session (Detach / back to the host list) so a later
     * resume does not silently re-route them into a session they left on
     * purpose.
     *
     * @return `true` only when the clear was acknowledged by `commit()` within
     *   the bounded wait; `false` means the old snapshot is not durably known
     *   to be gone.
     */
    fun clear(): Boolean {
        Log.i(LAST_SESSION_LOG_TAG, "last-session-clear trigger=onStop")
        return clearDurably("clear")
    }

    /**
     * Issue #834: a session was confirmed killed (tree/host-detail Stop or
     * in-session Stop). Drop it as a restore target so it is never re-opened:
     *
     *  1. If the persisted "last active" record points at this exact session,
     *     clear it — otherwise the next process-death resume restores a deleted
     *     session (→ #818 Conversation tab of a dead session, the #686 hazard).
     *  2. Remember the killed identity as a tombstone so a later `onStop`
     *     [save] for the SAME dead session (user still parked on the now-dead
     *     screen) is refused rather than re-arming the restore.
     *
     * Matching is on (hostId, exact tmux generation). The display name is only
     * diagnostic copy; it is never used to invalidate a snapshot or suppress a
     * save. A delayed predecessor event therefore cannot clear or suppress a
     * same-name successor.
     *
     * @return `true` when there was no matching persisted record or its clear
     *   was durably acknowledged; `false` when the matching clear failed.
     */
    fun onSessionKilled(
        hostId: Long,
        generation: TmuxSessionGeneration,
        lastKnownName: String,
    ): Boolean {
        val exact = tmuxSessionGenerationOrNull(
            generation.sessionId,
            generation.createdEpochSeconds,
        ) ?: return true
        val killed = SessionIdentity(hostId = hostId, generation = exact)
        killedTombstone = killed
        val stored = peek(maxAgeMillis = Long.MAX_VALUE)
        if (stored != null && stored.identity() == killed) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-clear trigger=killed hostId=$hostId " +
                    "generation=$exact session=${lastKnownName.trim()}",
            )
            return clearDurably("clear-killed")
        }
        return true
    }

    private fun clearDurably(operation: String): Boolean =
        persistDurably(operation) { clear() }

    /**
     * Issue #2265: one narrow, synchronous durability boundary for every
     * lifecycle save/clear of `last_session`.
     *
     * `Editor.apply()` acknowledges only the in-process map update; the #2264
     * two-process harness proved that an immediate process death can lose it.
     * `commit()` supplies the required disk acknowledgement, but doing the
     * prefs open/edit/commit on Main would reintroduce the StrictMode disk IO
     * fixed in #1087. Dispatch the entire operation to the existing IO
     * dispatcher and wait for that exact Future, so this is not a
     * fire-and-forget coroutine.
     *
     * The wait is capped well below Android's ANR interval. A wedged/saturated
     * storage path therefore cannot park `MainActivity.onStop` indefinitely;
     * timeout/commit failure is loud in logs and the task is cancelled. The
     * normal path returns only after `commit()` has acknowledged durable state.
     */
    private fun persistDurably(
        operation: String,
        edit: SharedPreferences.Editor.() -> Unit,
    ): Boolean {
        val task = FutureTask {
            prefs.edit().also(edit).commit()
        }
        try {
            ioDispatcher.dispatch(EmptyCoroutineContext, task)
        } catch (error: Throwable) {
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-dispatch-failed operation=$operation",
                error,
            )
            return false
        }

        val committed = try {
            task.get(durableWriteWaitMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(true)
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-timeout operation=$operation " +
                    "budgetMs=$durableWriteWaitMillis",
                error,
            )
            return false
        } catch (error: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-interrupted operation=$operation",
                error,
            )
            return false
        } catch (error: ExecutionException) {
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-failed operation=$operation",
                error.cause ?: error,
            )
            return false
        } catch (error: CancellationException) {
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-cancelled operation=$operation",
                error,
            )
            return false
        }
        if (!committed) {
            Log.e(
                LAST_SESSION_LOG_TAG,
                "last-session-durable-write-commit-false operation=$operation",
            )
        }
        return committed
    }

    /**
     * Issue #834: a session generation on [hostId] was legitimately
     * (re)opened. Clears the kill tombstone for that exact identity so a
     * recreated same-name generation is restorable again.
     *
     * tmux session names are user-chosen and habitually reused (`main`,
     * `work`, `claude-main`), so a kill tombstone must NOT outlive the
     * recreation of that identity — otherwise the next `onStop` [save] of the
     * recreated live session is wrongly suppressed and the #177 fast-resume
     * breaks for that name forever (the over-suppression the reviewer flagged).
     * Matching is on (hostId, exact tmux generation), identical to
     * [onSessionKilled], so opening a DIFFERENT generation never clears another
     * session's tombstone.
     */
    fun onSessionOpened(hostId: Long, generation: TmuxSessionGeneration) {
        val exact = tmuxSessionGenerationOrNull(
            generation.sessionId,
            generation.createdEpochSeconds,
        ) ?: return
        if (killedTombstone == SessionIdentity(hostId = hostId, generation = exact)) {
            Log.i(
                LAST_SESSION_LOG_TAG,
                "last-session-tombstone-clear trigger=opened hostId=$hostId " +
                    "generation=$exact",
            )
            killedTombstone = null
        }
    }

    private fun LastSession.identity(): SessionIdentity? =
        generation?.let { SessionIdentity(hostId = hostId, generation = it) }

    /**
     * Rebuild the navigation destination from a persisted [LastSession].
     * The passphrase is null — the reattach path reads the key from disk
     * by [LastSession.keyPath], identical to a cold attach.
     */
    fun LastSession.toDestination(): AppDestination.TmuxSession =
        AppDestination.TmuxSession(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = startDirectory,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
        )

    companion object {
        private const val PREFS_NAME = "last_session"
        private const val LAST_SESSION_LOG_TAG = "PsLastSession"
        private const val KEY_HOST_ID = "host_id"
        private const val KEY_HOST_NAME = "host_name"
        private const val KEY_HOSTNAME = "hostname"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_KEY_PATH = "key_path"
        private const val KEY_SESSION_NAME = "session_name"
        private const val KEY_START_DIR = "start_dir"
        private const val KEY_TMUX_SESSION_ID = "tmux_session_id"
        private const val KEY_SESSION_CREATED = "session_created"
        private const val KEY_COMPOSER_DRAFT = "composer_draft"
        private const val KEY_SAVED_AT = "saved_at"

        private const val DEFAULT_SSH_PORT = 22

        /** Main-thread lifecycle wait ceiling; disk work itself stays on IO. */
        @VisibleForTesting
        internal const val DURABLE_WRITE_WAIT_MILLIS: Long = 250L

        /**
         * Snapshots older than this are not restored — the user has moved
         * on and a surprise auto-route into a day-old session would be
         * worse than landing on the host list. Issue #177 acceptance:
         * "if the persisted state is recent (≤ 24h or configurable)".
         */
        const val DEFAULT_MAX_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L
    }
}
